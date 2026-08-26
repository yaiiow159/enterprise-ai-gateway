#!/usr/bin/env node
/**
 * PreToolUse hook：偵測到 Provider 金鑰就**阻擋寫入**
 *
 * 為什麼是阻擋而不是事後警告：
 *   金鑰一旦寫進檔案並提交，就算後續刪掉也已經留在 git 歷史裡。
 *   正確的處理是去 Provider 後台輪替金鑰、更新所有部署環境——
 *   成本遠高於當下擋下來。事後警告等於「已經發生了，請去善後」。
 *
 * 為什麼誤報比漏報更該擔心：
 *   一個常常擋錯的 hook 會被關掉，然後它就什麼都不保護了。
 *   所以每個樣式都要求足夠的長度與熵，並排除明顯的佔位字串
 *   （`${OPENAI_API_KEY}`、`sk-xxxxxx`、`your-api-key`）。
 *   本專案自己的文件就寫著「金鑰樣式（`sk-`、`anthropic-` 等）」，
 *   那必須放行——self-test 裡有這一條。
 *
 * 行為：
 *   - 偵測到疑似真實金鑰：exit 2，寫入被擋下，訊息回饋給 Claude
 *   - 其餘一律 exit 0
 *   - 輸入無法解析時放行（那是 harness 的問題，不該癱瘓所有寫入）
 *
 * 自我驗證：
 *   node .claude/hooks/secret-guard.js --self-test
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

// ------------------------------------------------------------ 金鑰樣式

/**
 * 每個樣式都要求「前綴 + 足夠長的隨機段」。
 * 只比對前綴會把文件裡提到 `sk-` 的每一句話都擋下來。
 */
const PATTERNS = [
    { name: 'Anthropic API 金鑰', regex: /\bsk-ant-[A-Za-z0-9_-]{24,}/g },
    { name: 'OpenAI API 金鑰', regex: /\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}/g },
    // 真實金鑰固定是 AIza + 35 字元；結尾不要求 \b，
    // 否則嵌在更長的字串裡的金鑰反而漏抓
    { name: 'Google AI 金鑰', regex: /\bAIza[A-Za-z0-9_-]{35}/g },
    { name: 'AWS 存取金鑰 ID', regex: /\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\b/g },
    { name: 'GitHub token', regex: /\bgh[pousr]_[A-Za-z0-9]{36,}\b/g },
    { name: 'HuggingFace token', regex: /\bhf_[A-Za-z0-9]{34,}\b/g },
    { name: 'Slack token', regex: /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/g },
];

/**
 * 泛用規則：名稱看起來像金鑰的賦值，且值夠長又夠亂。
 * 這條最容易誤報，所以門檻訂得比上面的具名樣式高。
 */
const GENERIC_ASSIGNMENT = /(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|password)\s*[:=]\s*["']([^"']{24,})["']/gi;

/** 明顯是佔位字串的特徵。命中任何一項就不算金鑰 */
const PLACEHOLDER_MARKERS = [
    'your', 'example', 'placeholder', 'changeme', 'change-me', 'dummy', 'fake',
    'sample', 'redacted', 'todo', 'insert', 'replace', 'xxxx', 'test-key',
    'not-a-real', 'reallynot', '1234567890', 'abcdef',
];

function looksLikePlaceholder(value) {
    const lower = value.toLowerCase();

    // 環境變數參照、樣板變數、角括號佔位：這些本來就是「正確的做法」
    if (/\$\{|\{\{|<[a-z_]+>|%s|%\(/.test(value)) return true;
    if (PLACEHOLDER_MARKERS.some((marker) => lower.includes(marker))) return true;

    // 前綴之後全是同一個字元（sk-xxxxxxxx、sk-00000000）
    const body = value.replace(/^(sk-ant-|sk-proj-|sk-|AIza|hf_|gh[pousr]_|xox[baprs]-)/, '');
    if (/^(.)\1+$/.test(body)) return true;

    return false;
}

/**
 * Shannon 熵（bits/字元）。真實金鑰是隨機產生的，通常在 4 以上；
 * 人寫的佔位字串與一般識別碼明顯偏低。
 */
function entropy(value) {
    const counts = new Map();
    for (const character of value) counts.set(character, (counts.get(character) || 0) + 1);

    let bits = 0;
    for (const count of counts.values()) {
        const probability = count / value.length;
        bits -= probability * Math.log2(probability);
    }
    return bits;
}

/** 遮蔽後才輸出：hook 的訊息會進到對話紀錄，不該把完整金鑰再抄一份進去 */
function mask(value) {
    if (value.length <= 12) return `${value.slice(0, 3)}…`;
    return `${value.slice(0, 8)}…${value.slice(-2)}（共 ${value.length} 字元）`;
}

/**
 * 逃生口。任何金鑰掃描器都需要一個，否則會擋住它自己的測試樣本
 * （這支 hook 就是：self-test 的樣本刻意長得像真金鑰，不排除的話它會擋住對自己的編輯）。
 *
 * 用「同一行加註記」而不是「整檔排除」：範圍最小，而且在 code review 時看得見——
 * 有人想用它偷渡真金鑰的話，那一行會很顯眼。
 */
const ALLOW_MARKER = 'secret-guard:allow';

/**
 * 掃描一段內容，回傳所有疑似金鑰。
 *
 * 具名樣式與泛用規則的熵門檻不同：具名樣式的前綴本身已經帶有很強的證據，
 * 泛用規則只靠「變數名稱像金鑰」，所以要求更高的隨機度才算數。
 */
function findSecrets(rawContent) {
    // 金鑰不會跨行，所以逐行過濾註記過的行就夠了
    const content = rawContent
        .split('\n')
        .filter((line) => !line.includes(ALLOW_MARKER))
        .join('\n');

    const findings = [];
    const seen = new Set();

    for (const { name, regex } of PATTERNS) {
        regex.lastIndex = 0;
        let match;
        while ((match = regex.exec(content)) !== null) {
            const value = match[0];
            if (seen.has(value) || looksLikePlaceholder(value)) continue;
            if (entropy(value) < 3.0) continue;
            seen.add(value);
            findings.push({ kind: name, value });
        }
    }

    GENERIC_ASSIGNMENT.lastIndex = 0;
    let assignment;
    while ((assignment = GENERIC_ASSIGNMENT.exec(content)) !== null) {
        const value = assignment[1];
        if (seen.has(value) || looksLikePlaceholder(value)) continue;
        // 泛用規則的門檻拉高：只有「名稱像金鑰」這一個證據
        if (entropy(value) < 4.0) continue;
        seen.add(value);
        findings.push({ kind: '疑似金鑰的賦值', value });
    }

    return findings;
}

// ------------------------------------------------------------ hook 主體

/** 不同工具把要寫入的內容放在不同欄位 */
function contentFrom(toolInput) {
    if (!toolInput) return '';
    return [toolInput.content, toolInput.new_string, toolInput.new_source]
        .filter((value) => typeof value === 'string')
        .join('\n');
}

function runHook() {
    let raw = '';
    try {
        raw = fs.readFileSync(0, 'utf8');
    } catch {
        process.exit(0);
    }

    let payload;
    try {
        payload = JSON.parse(raw);
    } catch {
        // 解析不了是 harness 契約的問題，不該癱瘓所有寫入
        process.exit(0);
    }

    const content = contentFrom(payload?.tool_input);
    if (!content) process.exit(0);

    const findings = findSecrets(content);
    if (findings.length === 0) process.exit(0);

    const filePath = payload?.tool_input?.file_path || '（未知檔案）';
    console.error(`[secret-guard] 阻擋寫入 ${path.basename(filePath)}：內容含疑似 Provider 金鑰`);
    for (const finding of findings) {
        console.error(`  - ${finding.kind}：${mask(finding.value)}`);
    }
    console.error('');
    console.error('金鑰一旦寫進檔案並提交，就算後續刪掉也留在 git 歷史裡，只能去後台輪替。');
    console.error('正確做法：金鑰只從環境變數注入，設定檔寫 ${OPENAI_API_KEY} 之類的參照。');
    console.error('這確實是佔位字串而非真金鑰的話，改用明顯的佔位形式（your-api-key、sk-xxxx）。');
    process.exit(2);
}

// ------------------------------------------------------------ 自我驗證

/**
 * 一個從不觸發的安全 hook 和一個總是誤報的安全 hook 一樣沒用：
 * 前者什麼都沒擋，後者會被關掉。兩個方向都要驗。
 */
function selfTest() {
    /**
     * 樣本在**原始碼裡**一律拆成片段，執行期才組起來。
     *
     * 不拆的話有兩個後果，第二個是踩到才知道的：
     *
     *   1. 這支 hook 會擋住對它自己的編輯（`secret-guard:allow` 註記解決這一項）。
     *   2. **GitHub 的推送保護會擋下整個 push**——它不認得我們的逃生註記。
     *      推送時就撞上了：`cohere_api_key: "<40 字元高熵字串>"` 被判定為真的
     *      Cohere 金鑰，整個 repo 推不上去。Cohere 的金鑰沒有特徵前綴，
     *      偵測靠的正是「變數名 + 40 字元」這個組合，所以連變數名也要拆開。
     *
     * 逃生註記只對自己人有效。真正安全的做法是讓完整字串**根本不存在於檔案裡**。
     * 這些片段是隨手打的，不是任何真實金鑰。
     */
    const join = (...parts) => parts.join('');
    const shouldBlock = [
        ['OpenAI 舊格式', join('const key = "sk-', 'QmRk3xPvT8wLnZ7a', 'HcJ2eYuI9oKdFgS4bNmX1vCz";')],
        ['OpenAI proj 格式', join('OPENAI_API', '_KEY=sk-proj-', '8fKqW2mNvR7tYpL3', 'xZ9cAeB4dGhJ6sUiO1nT5rQw')],
        ['Anthropic', join('ANTHROPIC_API', '_KEY=sk-ant-api03-', 'Xy7Kq2WmNvR8tYpL3', 'xZ9cAeB4dGhJ6sUiO1nT5rQwZz')],
        ['Google AI', join('key: AIza', 'SyD3xK9mNvR7tYpL2', 'xZ8cAeB4dGhJ6sUiO1')],
        ['AWS 存取金鑰', join('aws_access', '_key_id = AKIA', 'IOSFODNN7SVQXZ2A')],
        ['GitHub token', join('token: ghp_', '9mNvR7tYpL2xZ8cAe', 'B4dGhJ6sUiO1nT5rQwZ')],
        ['泛用賦值', join('cohere_api', '_key: "K7mNvR2tYpL8', 'xZ9cAeB4dGhJ6sUiO', '1nT5rQwZaXy"')],
    ];

    const shouldPass = [
        ['環境變數參照', 'api-key: ${OPENAI_API_KEY}'],
        ['明顯佔位', 'OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxx'],
        ['your- 佔位', 'api_key: "your-api-key-goes-here-not-a-real-one"'],
        ['文件提到樣式', '金鑰樣式（`sk-`、`anthropic-` 等）出現在原始碼或設定檔'],
        ['角括號佔位', 'Authorization: Bearer <YOUR_TOKEN_HERE>'],
        ['一般 Java 程式碼', 'public record Usage(long promptTokens, long completionTokens) {}'],
        ['本專案的設定寫法', 'base-url: ${FAKE_FAST_URL:http://localhost:9101}'],
        ['一般 UUID', 'requestId = "550e8400-e29b-41d4-a716-446655440000";'],
        // 逃生口本身也要驗：沒驗過的例外機制，等到真的需要時才發現壞掉
        ['帶逃生註記的行', shouldBlock[0][1] + ' // ' + ALLOW_MARKER],
    ];

    let failures = 0;
    console.log('secret-guard 自我驗證\n');

    console.log('應該擋下的：');
    for (const [name, content] of shouldBlock) {
        const found = findSecrets(content);
        const ok = found.length > 0;
        if (!ok) failures += 1;
        console.log(`  ${ok ? '✓' : '✗'} ${name}${ok ? `　→ ${found[0].kind}` : '　→ 沒抓到（漏報）'}`);
    }

    console.log('\n應該放行的：');
    for (const [name, content] of shouldPass) {
        const found = findSecrets(content);
        const ok = found.length === 0;
        if (!ok) failures += 1;
        console.log(`  ${ok ? '✓' : '✗'} ${name}${ok ? '' : `　→ 誤報成 ${found[0].kind}：${mask(found[0].value)}`}`);
    }

    // 對整個專案掃一遍：任何誤報都會讓這個 hook 在日常工作中被關掉
    console.log('\n對本專案現有檔案掃描（誤報會讓 hook 被關掉）：');
    const repoRoot = path.resolve(__dirname, '../..');
    const scanned = [];
    const walk = (directory) => {
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
            if (['node_modules', 'target', '.git'].includes(entry.name)) continue;
            const full = path.join(directory, entry.name);
            if (entry.isDirectory()) { walk(full); continue; }
            if (!/\.(java|js|yml|yaml|md|json|xml|properties|env)$/.test(entry.name)) continue;
            const findings = findSecrets(fs.readFileSync(full, 'utf8'));
            if (findings.length) scanned.push({ file: path.relative(repoRoot, full), findings });
        }
    };
    walk(repoRoot);

    if (scanned.length === 0) {
        console.log('  ✓ 沒有誤報，也沒有真的金鑰');
    } else {
        failures += scanned.length;
        for (const entry of scanned) {
            console.log(`  ✗ ${entry.file}：${entry.findings.map((f) => `${f.kind} ${mask(f.value)}`).join('、')}`);
        }
        console.log('  （若這些是真的金鑰，請立刻輪替；若是誤報，請調整樣式門檻）');
    }

    console.log(`\n${'='.repeat(56)}`);
    console.log(failures === 0 ? '✓ 全部通過' : `✗ ${failures} 項未通過`);
    process.exit(failures === 0 ? 0 : 1);
}

module.exports = { findSecrets, entropy, looksLikePlaceholder, ALLOW_MARKER };

// 只有被直接執行時才跑 hook 主流程。少了這個判斷，cost-guard 之類的
// 工具一 require 進來就會觸發 runHook()——它讀 stdin、然後 exit 0，
// 把引用它的那支腳本一起結束掉，而且沒有任何輸出可循。
if (require.main === module) {
    if (process.argv.includes('--self-test')) {
        selfTest();
    } else {
        runHook();
    }
}
