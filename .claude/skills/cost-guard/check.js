#!/usr/bin/env node
/**
 * 成本風險把關
 *
 * 為什麼這個專案需要它：
 *   一般後端的 bug 是服務壞掉——會有人尖叫，會有告警。
 *   這裡的 bug 是**帳單**，而帳單要到月底才會尖叫。
 *   無條件對沖、失控重試、沒有 max_tokens 的請求、忘了取消的串流，
 *   任何一個都足以在無人注意的情況下持續燒錢，而且系統看起來完全正常。
 *
 * 兩層檢查：
 *   靜態掃描（秒級，不需要環境）：讀原始碼與設定，找出結構性的成本風險
 *   執行期量測（需要網關與假 Provider）：實際跑流量，量「一次客戶端請求會變成幾次上游請求」
 *
 * 用法：
 *   node .claude/skills/cost-guard/check.js            靜態 + 執行期（網關沒起來就只跑靜態）
 *   node .claude/skills/cost-guard/check.js --static    只做靜態掃描
 *   node .claude/skills/cost-guard/check.js --runtime   只做執行期量測
 *   node .claude/skills/cost-guard/check.js --requests-per-day 50000
 */

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { gatewayAuthHeaders } = require('../../../tools/gateway-auth');

const { findSecrets } = require('../../hooks/secret-guard.js');

const REPO_ROOT = path.resolve(__dirname, '../../..');
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8090);
const FAKE_PORTS = [
    Number(process.env.FAKE_FAST_PORT || 9101),
    Number(process.env.FAKE_SLOW_PORT || 9102),
    Number(process.env.FAKE_FLAKY_PORT || 9103),
];

const args = process.argv.slice(2);
const staticOnly = args.includes('--static');
const runtimeOnly = args.includes('--runtime');

/**
 * hook 模式：只做靜態掃描，且**只有阻擋級的發現才出聲**。
 *
 * 警告級的項目（例如「還沒有每日成本上限」）是已知且已記錄在文件裡的缺口，
 * 每次存檔都刷一遍只會訓練大家忽略這個 hook。一個被忽略的告警等於沒有告警。
 */
const hookMode = args.includes('--hook');

function numericArg(name, fallback) {
    const index = args.indexOf(name);
    return index >= 0 && args[index + 1] ? Number(args[index + 1]) : fallback;
}

/**
 * 換算金額用的假設。這些是**假設**不是量測值，報告裡一定要一起印出來——
 * 沒有附上單價與流量假設的「每天會花 X 元」是不可重現的，也就沒有意義。
 */
const ASSUMPTIONS = {
    requestsPerDay: numericArg('--requests-per-day', 10000),
    inputUsdPerMillion: numericArg('--price-input', 2.5),
    outputUsdPerMillion: numericArg('--price-output', 10),
};

const findings = [];

/** severity：`阻擋` 會讓整支腳本以非 0 結束；`警告` 只出現在報告裡 */
function report(severity, title, detail, remedy) {
    findings.push({ severity, title, detail, remedy });
    if (hookMode && severity !== '阻擋') return;

    const mark = severity === '阻擋' ? '✗' : '⚠';
    const prefix = hookMode ? '[cost-guard] ' : `  ${mark} [${severity}] `;
    console.log(`${prefix}${title}`);
    if (detail) console.log(`      ${detail}`);
    if (remedy) console.log(`      → ${remedy}`);
}

function pass(title, detail) {
    if (hookMode) return;
    console.log(`  ✓ ${title}`);
    if (detail) console.log(`      ${detail}`);
}

/** hook 模式下把章節標題也吞掉，沒有發現時應該完全安靜 */
function section(title) {
    if (!hookMode) console.log(title);
}

// ---------------------------------------------------------------- 靜態掃描

function sourceFiles(extensions) {
    const collected = [];
    const walk = (directory) => {
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
            if (['node_modules', 'target', '.git'].includes(entry.name)) continue;
            const full = path.join(directory, entry.name);
            if (entry.isDirectory()) { walk(full); continue; }
            if (extensions.some((extension) => entry.name.endsWith(extension))) collected.push(full);
        }
    };
    walk(REPO_ROOT);
    return collected;
}

const relative = (file) => path.relative(REPO_ROOT, file).replace(/\\/g, '/');

/**
 * 金鑰字面值。
 *
 * 與 PreToolUse hook 共用同一組樣式：hook 擋的是「正在寫入的內容」，
 * 這裡掃的是「已經在檔案裡的內容」——hook 裝上之前寫進去的，只有這裡抓得到。
 */
function scanSecrets() {
    section('\n[靜態 1] 原始碼與設定檔中的金鑰字面值');
    const files = sourceFiles(['.java', '.js', '.yml', '.yaml', '.json', '.xml', '.properties', '.env', '.md']);
    const leaks = [];

    for (const file of files) {
        const found = findSecrets(fs.readFileSync(file, 'utf8'));
        if (found.length) leaks.push({ file: relative(file), found });
    }

    if (leaks.length === 0) {
        pass('沒有金鑰字面值', `掃描 ${files.length} 個檔案`);
        return;
    }
    for (const leak of leaks) {
        report('阻擋', `${leak.file} 含疑似金鑰`,
            leak.found.map((item) => item.kind).join('、'),
            '立刻去 Provider 後台輪替該金鑰——它已經在檔案裡，很可能也已經在 git 歷史裡');
    }
}

/**
 * 送給 Provider 的請求一定要帶輸出上限。
 *
 * 沒有上限的請求會一直生成到模型自己停下來，可能是幾千個 token。
 * 單看一次請求不痛不癢，乘上日流量就是另一個數量級的帳單。
 */
function scanMaxTokens() {
    section('\n[靜態 2] Provider 請求是否一定帶輸出上限');
    const files = sourceFiles(['.java']);
    const builders = files.filter((file) => /"messages"/.test(fs.readFileSync(file, 'utf8')));

    if (builders.length === 0) {
        report('警告', '找不到組裝 Provider 請求的程式碼',
            '掃描的判準是原始碼中出現 "messages" 字面值',
            '若請求組裝方式改變，這條檢查要跟著更新，否則它會靜默地什麼都不驗');
        return;
    }

    for (const file of builders) {
        const source = fs.readFileSync(file, 'utf8');
        if (/max_tokens/.test(source)) {
            pass(`${relative(file)} 有帶 max_tokens`);
        } else {
            report('阻擋', `${relative(file)} 組裝請求但沒有 max_tokens`,
                '沒有輸出上限的請求會生成到模型自己停下來',
                '在請求 payload 中固定帶上 max_tokens');
        }
    }

    // 型別層面的防線比字串掃描可靠得多，有的話要確認它還在
    const chatRequest = path.join(REPO_ROOT, 'gateway-core/src/main/java/io/aigateway/core/ChatRequest.java');
    if (fs.existsSync(chatRequest) && /maxTokens == null \|\| maxTokens <= 0/.test(fs.readFileSync(chatRequest, 'utf8'))) {
        pass('ChatRequest 在建構時就拒絕未設上限的請求', '型別層面的防線，比字串掃描可靠');
    } else {
        report('警告', 'ChatRequest 不再於建構時強制 maxTokens',
            '原本這裡有一道「忘記設上限就建不出請求」的防線',
            '恢復建構子檢查，讓漏設上限在最早的時間點就失敗');
    }
}

/**
 * 對沖策略。
 *
 * `immediate` 會讓每個請求同時打 N 家，成本直接乘上 N。
 * 它有正當用途（極端要求延遲的少數端點），但必須有成本上限作為防線。
 */
function scanHedgePolicy() {
    section('\n[靜態 3] 對沖策略是否為無條件觸發');
    const configs = sourceFiles(['.yml', '.yaml']).filter((file) => !relative(file).startsWith('deploy/'));
    let flagged = false;

    for (const file of configs) {
        const source = fs.readFileSync(file, 'utf8');
        const match = source.match(/policy:\s*(\w+)/);
        if (!match) continue;
        if (match[1] === 'immediate') {
            flagged = true;
            const maxAttempts = (source.match(/max-attempts:\s*(\d+)/) || [])[1] || '?';
            report('阻擋', `${relative(file)} 使用 immediate 對沖`,
                `每個請求都會同時打 ${maxAttempts} 家 Provider，成本約為單一 Provider 的 ${maxAttempts} 倍`,
                '改用 deferred（只有主要 Provider 超過 p95 才補發），或先確認每日成本上限已生效');
        } else {
            pass(`${relative(file)} 使用 ${match[1]} 對沖`, 'deferred 依定義只有約 5% 的請求會觸發');
        }
    }
    if (!flagged && configs.length === 0) {
        report('警告', '找不到對沖設定', '', '確認設定鍵名是否改變');
    }
}

/**
 * 每日成本上限。
 *
 * 這是最後一道防線：上面所有檢查都可能被繞過（新加的端點、改壞的設定、
 * 上游計價變動），但一個真的會攔截的每日上限能把損失限制在一天之內。
 */
function scanBudgetCeiling() {
    section('\n[靜態 4] 是否有會實際攔截的每日成本上限');
    const files = sourceFiles(['.java']);
    const enforcing = files.filter((file) => {
        const source = fs.readFileSync(file, 'utf8');
        // 要的是「超過就擋下來」，不是只有一個設定欄位或一句註解
        return /budget|dailyLimit|spendLimit|costCeiling/i.test(source)
            && /throw|reject|deny|block/i.test(source);
    });

    if (enforcing.length > 0) {
        pass('有成本上限的攔截邏輯', enforcing.map(relative).join('、'));
        return;
    }

    // 程式碼裡有沒有「假設它存在」的地方？有的話落差更值得指出來
    const assuming = files.filter((file) => /成本上限|cost ceiling|budget/i.test(fs.readFileSync(file, 'utf8')));
    report('警告', '沒有任何每日成本上限的攔截邏輯',
        assuming.length
            ? `而且有 ${assuming.length} 處註解假設它存在：${assuming.map(relative).join('、')}`
            : '',
        '上線前必須補上。在那之前，immediate 對沖與任何自動重試都沒有防線兜底');
}

/**
 * 成本歸戶是否只依賴上游回報。
 *
 * 只讀上游回傳的成本欄位是個陷阱：假 Provider 會回，真 Provider 不會。
 * 症狀是接上真 Provider 之後所有請求的成本都記成 0——不會報錯，
 * 只是帳目上永遠看起來免費，直到對帳的時候。
 */
function scanCostAttribution() {
    section('\n[靜態 5] 成本歸戶是否會在真 Provider 上靜默記成 0');
    const provider = path.join(REPO_ROOT, 'gateway-providers/src/main/java/io/aigateway/providers/OpenAiCompatibleProvider.java');
    if (!fs.existsSync(provider)) {
        report('警告', '找不到 Provider 實作', '', '掃描路徑可能需要更新');
        return;
    }

    const source = fs.readFileSync(provider, 'utf8');
    const readsUpstreamCost = /estimated_cost_cents/.test(source);
    const hasFallback = /estimatedCostMicroCents|inputMicroCentsPerToken/.test(source);

    if (readsUpstreamCost && !hasFallback) {
        report('警告', '成本只從上游回傳的欄位取值，沒有本地單價作為後備',
            'estimated_cost_cents 是假 Provider 才有的欄位；真 Provider 不回這個欄位時成本會記成 0',
            '在 usage 缺少成本欄位時，改用 ModelCapability 的單價自行推算');
    } else if (hasFallback) {
        pass('有本地單價作為後備', '上游不回成本欄位時仍能歸戶');
    } else {
        pass('沒有依賴上游的成本欄位');
    }
}

/** 同一家 Provider 的重試若沒有退避與次數上限，一次上游故障會被放大成一場自我 DDoS */
function scanRetryDiscipline() {
    section('\n[靜態 6] 重試是否有退避與次數上限');
    const files = sourceFiles(['.java']);
    const suspects = files.filter((file) => {
        const source = fs.readFileSync(file, 'utf8');
        return /(while|for)\s*\([^)]*\)\s*\{[^}]*(retry|attempt)/is.test(source)
            && !/sleep|backoff|delay|Duration\.of/i.test(source);
    });

    if (suspects.length === 0) {
        pass('沒有無退避的重試迴圈');
        return;
    }
    for (const file of suspects) {
        report('警告', `${relative(file)} 疑似有無退避的重試`,
            '上游故障時，無退避重試會把一次故障放大成持續打擊，且每一次都計費',
            '加上指數退避與明確的次數上限');
    }
}

// ---------------------------------------------------------------- 執行期量測

function json(port, method, urlPath, body) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = gatewayAuthHeaders();
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const request = http.request({ host: 'localhost', port, path: urlPath, method, headers, timeout: 30000 }, (response) => {
            let data = '';
            response.on('data', (chunk) => { data += chunk; });
            response.on('end', () => {
                let parsed = null;
                try { parsed = JSON.parse(data); } catch { /* 保留原文 */ }
                resolve({ status: response.statusCode, body: parsed });
            });
        });
        request.on('timeout', () => request.destroy(new Error('逾時')));
        request.on('error', reject);
        if (payload) request.write(payload);
        request.end();
    });
}

async function configureAll(settings) {
    for (const port of FAKE_PORTS) {
        await json(port, 'POST', '/_fake/reset');
        await json(port, 'PUT', '/_fake/config', Object.assign({ errorRate: 0 }, settings));
    }
}

async function totalStats() {
    const total = { requests: 0, completionTokens: 0, promptTokens: 0, cancelled: 0 };
    for (const port of FAKE_PORTS) {
        const stats = (await json(port, 'GET', '/_fake/stats')).body;
        for (const key of Object.keys(total)) total[key] += stats[key] || 0;
    }
    return total;
}

const usd = (micro) => micro / 1_000_000 / 100;

/**
 * 成本倍率：一次客戶端請求變成幾次上游請求。
 *
 * 這是這支腳本最重要的數字。對沖、失敗切換、重試全都會推高它，
 * 而它直接就是帳單的乘數——1.0 表示沒有浪費，3.0 表示帳單是三倍。
 */
async function measureCostMultiplier() {
    console.log('\n[執行期 1] 成本倍率（一次客戶端請求 → 幾次上游請求）');
    const sampleSize = 12;
    await configureAll({ ttftMs: 60, tokenDelayMs: 3, tokens: 40 });

    for (let index = 0; index < sampleSize; index += 1) {
        await json(GATEWAY_PORT, 'POST', '/v1/chat/completions', {
            model: 'fake-chat',
            messages: [{ role: 'user', content: '這是一個典型長度的提問，用來估算輸入 token。' }],
            max_tokens: 20,
        });
    }

    const stats = await totalStats();
    const multiplier = stats.requests / sampleSize;

    if (multiplier <= 1.2) {
        pass(`成本倍率 ${multiplier.toFixed(2)}×`,
            `${sampleSize} 次客戶端請求 → ${stats.requests} 次上游請求（延遲對沖下應接近 1.0）`);
    } else {
        report(multiplier >= 2 ? '阻擋' : '警告', `成本倍率 ${multiplier.toFixed(2)}×`,
            `${sampleSize} 次客戶端請求變成 ${stats.requests} 次上游請求`,
            '確認對沖策略是 deferred、且沒有非預期的重試');
    }
    return { multiplier, stats, sampleSize };
}

/**
 * max_tokens 有沒有真的傳到上游。
 *
 * 網關若在轉換請求時漏掉這個欄位，上游會用自己的預設值（通常大得多），
 * 而回應看起來完全正常——只是每次都比預期長，帳單也是。
 */
async function measureMaxTokensHonoured() {
    console.log('\n[執行期 2] max_tokens 是否真的傳到上游');
    const budget = 5;
    // 上游被允許產生 200 個 token：網關若漏掉 max_tokens，這裡就會看到遠超過 5 個
    await configureAll({ ttftMs: 40, tokenDelayMs: 2, tokens: 200 });
    await json(GATEWAY_PORT, 'POST', '/v1/chat/completions', {
        model: 'fake-chat',
        messages: [{ role: 'user', content: 'hi' }],
        max_tokens: budget,
    });

    const stats = await totalStats();
    if (stats.completionTokens <= budget) {
        pass(`上游只產生 ${stats.completionTokens} 個 token`, `客戶端要求上限 ${budget}，上游本身允許 200`);
    } else {
        report('阻擋', `上游產生了 ${stats.completionTokens} 個 token，超過客戶端要求的 ${budget}`,
            '表示 max_tokens 沒有被轉發到上游請求',
            '檢查請求組裝是否漏掉 max_tokens');
    }
}

/** 取消到底省下多少：把「有沒有中止上游」換算成 token 數 */
async function measureCancellationSaving() {
    console.log('\n[執行期 3] 客戶端斷線實際省下多少 token');
    const budget = 120;
    await configureAll({ ttftMs: 40, tokenDelayMs: 25, tokens: 200 });

    await new Promise((resolve) => {
        const payload = JSON.stringify({
            model: 'fake-chat', messages: [{ role: 'user', content: 'hi' }], max_tokens: budget, stream: true,
        });
        const request = http.request({
            host: 'localhost', port: GATEWAY_PORT, path: '/v1/chat/completions', method: 'POST',
            headers: Object.assign(gatewayAuthHeaders(),
                { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }),
        }, (response) => {
            let chunks = 0;
            response.on('data', () => { if (++chunks === 3) { request.destroy(); resolve(); } });
        });
        request.on('error', () => resolve());
        request.end(payload);
    });

    await new Promise((resolve) => setTimeout(resolve, 2500));
    const stats = await totalStats();
    const saved = budget - stats.completionTokens;

    if (stats.completionTokens < budget * 0.3) {
        pass(`省下 ${saved} / ${budget} 個 token`, `斷線後上游只再產生到第 ${stats.completionTokens} 個就停住`);
    } else {
        report('阻擋', `斷線後上游仍產生了 ${stats.completionTokens} / ${budget} 個 token`,
            '使用者已經離開，這些 token 仍然計費',
            '確認 SseEmitter 的 onError／onCompletion 有關閉上游串流');
    }
}

/** 從 /actuator/prometheus 取一個 gauge 的值。取不到回 null，不猜 */
function gauge(name) {
    return new Promise((resolve) => {
        const request = http.get({
            host: 'localhost', port: GATEWAY_PORT, path: '/actuator/prometheus', timeout: 5000,
        }, (response) => {
            if (response.statusCode !== 200) { response.resume(); resolve(null); return; }
            let text = '';
            response.on('data', (chunk) => { text += chunk; });
            response.on('end', () => {
                const line = text.split('\n').find((row) => row.startsWith(name));
                resolve(line ? Number(line.slice(line.lastIndexOf(' ') + 1)) : null);
            });
        });
        request.on('timeout', () => { request.destroy(); resolve(null); });
        request.on('error', () => resolve(null));
    });
}

/**
 * 每日成本上限有沒有真的在記帳。
 *
 * <p>靜態掃描只能確認「有攔截邏輯存在」，不能確認它接上了。
 * 一個設定了上限卻沒接進請求路徑的帳本，跟沒有上限完全一樣——
 * 而且更糟，因為儀表板上會顯示一個永遠是 0 的支出數字，看起來像一切正常。
 */
async function measureBudgetEnforcement() {
    console.log('\n[執行期 4] 每日成本上限是否真的在記帳');

    const limit = await gauge('gateway_budget_limit_microcents');
    if (limit === null) {
        report('警告', '找不到成本上限的指標',
            'gateway_budget_limit_microcents 沒有出現在 /actuator/prometheus',
            '確認 BudgetLedger 的 MeterBinder 有註冊；看不見的上限只有一半用處');
        return;
    }
    if (limit === 0) {
        report('阻擋', '成本上限設為 0（不限制）',
            '任何失控的迴圈或誤設都不會被擋下來',
            '設定 gateway.budget.daily-limit-usd');
        return;
    }

    const before = await gauge('gateway_budget_spent_microcents');
    await json(GATEWAY_PORT, 'POST', '/v1/chat/completions', {
        model: 'fake-chat', messages: [{ role: 'user', content: '確認帳本有在記帳' }], max_tokens: 10,
    });
    const after = await gauge('gateway_budget_spent_microcents');

    if (after !== null && before !== null && after > before) {
        pass('帳本確實接在請求路徑上',
            `一次請求後支出從 ${before} 增加到 ${after} 微美分，上限 ${limit}`);
    } else {
        report('阻擋', '請求沒有計入成本帳本',
            `支出前後都是 ${before}——帳本存在但沒接上請求路徑`,
            '一個永遠是 0 的支出數字比沒有數字更危險，因為它看起來像一切正常');
    }
}

/**
 * 把量到的 token 用量換算成金額。
 *
 * 這一段是**推估**不是量測：token 數是量到的，單價與日流量是假設。
 * 所以假設一定要跟著數字一起印出來，否則這個金額不可重現、也無法被檢驗。
 */
function projectDailyCost(sample) {
    console.log('\n[執行期 5] 換算成每日成本（推估）');
    const { stats, sampleSize } = sample;
    const promptPerRequest = stats.promptTokens / sampleSize;
    const completionPerRequest = stats.completionTokens / sampleSize;

    const daily = ASSUMPTIONS.requestsPerDay * (
        promptPerRequest * ASSUMPTIONS.inputUsdPerMillion / 1_000_000
        + completionPerRequest * ASSUMPTIONS.outputUsdPerMillion / 1_000_000
    );

    console.log(`      量測值：平均每請求 輸入 ${promptPerRequest.toFixed(1)} token、輸出 ${completionPerRequest.toFixed(1)} token`);
    console.log(`      假設值：每日 ${ASSUMPTIONS.requestsPerDay.toLocaleString()} 次請求、`
        + `輸入 $${ASSUMPTIONS.inputUsdPerMillion}/M、輸出 $${ASSUMPTIONS.outputUsdPerMillion}/M`);
    console.log(`      → 推估每日 $${daily.toFixed(2)}，每月 $${(daily * 30).toFixed(2)}`);
    console.log(`      → 若成本倍率從 ${sample.multiplier.toFixed(2)}× 變成 3×（無條件對沖），`
        + `每月變成 $${(daily * 30 * 3 / sample.multiplier).toFixed(2)}`);
    console.log('      （token 數是量測值；單價與流量是假設，換算結果僅供比例參考）');
}

// ---------------------------------------------------------------- 主流程

async function gatewayReachable() {
    try {
        const health = await json(GATEWAY_PORT, 'GET', '/actuator/health');
        return health.status === 200;
    } catch {
        return false;
    }
}

(async () => {
    if (!hookMode) console.log('成本風險把關');

    if (!runtimeOnly) {
        section(`\n${'─'.repeat(62)}\n靜態掃描（不需要執行環境）`);
        scanSecrets();
        scanMaxTokens();
        scanHedgePolicy();
        scanBudgetCeiling();
        scanCostAttribution();
        scanRetryDiscipline();
    }

    if (!staticOnly && !hookMode) {
        console.log(`\n${'─'.repeat(62)}\n執行期量測`);
        if (!await gatewayReachable()) {
            console.log(`\n  略過：網關（localhost:${GATEWAY_PORT}）未執行`);
            console.log('  先啟動 dev-stack 與網關後再跑一次，才能量到成本倍率');
        } else {
            try {
                const sample = await measureCostMultiplier();
                await measureMaxTokensHonoured();
                await measureCancellationSaving();
                await measureBudgetEnforcement();
                projectDailyCost(sample);
            } finally {
                await configureAll({ ttftMs: 100, tokenDelayMs: 8, tokens: 40 }).catch(() => {});
            }
        }
    }

    const blocking = findings.filter((finding) => finding.severity === '阻擋');
    const warnings = findings.filter((finding) => finding.severity === '警告');

    if (hookMode) {
        // 不阻擋：成本風險常常需要人判斷（immediate 對沖有正當用途），
        // 機械式地擋住寫入只會讓這個 hook 被關掉。金鑰外洩才用阻擋，
        // 因為那個沒有「其實沒關係」的情況。
        if (blocking.length) console.log('[cost-guard] 詳細檢查：node .claude/skills/cost-guard/check.js');
        process.exit(0);
    }

    console.log(`\n${'='.repeat(62)}`);
    console.log(`阻擋 ${blocking.length} 項，警告 ${warnings.length} 項`);
    if (blocking.length) {
        console.log('\n阻擋項目：');
        blocking.forEach((finding) => console.log(`  ✗ ${finding.title}`));
    }
    if (warnings.length) {
        console.log('\n警告項目（不阻擋，但上線前要處理）：');
        warnings.forEach((finding) => console.log(`  ⚠ ${finding.title}`));
    }
    process.exit(blocking.length ? 1 : 0);
})();
