#!/usr/bin/env node
/**
 * RAG 的端到端驗證。
 *
 * 三個最重要的性質，每一個對應一種「不驗就不會發現」的失效：
 *
 *   1. **注入的 context 有被算進成本。** 檢索會往 prompt 塞進成千個 token。
 *      如果檢索發生在成本預扣之後，那些 token 完全不會被計入——
 *      成本上限會系統性低估，而且低估的比例正好隨檢索量成長。
 *      帳面上一切正常，防線卻是漏的。
 *
 *   2. **嵌入不一致時拒絕啟動。** 用 A 模型建索引、用 B 模型查詢，
 *      向量庫照樣回傳最近鄰，只是全部不相關。沒有例外、沒有錯誤碼、延遲也不變。
 *
 *   3. **檢索失敗預設是失敗，不是靜默降級。** 降級成「不帶 context 的普通問答」
 *      會回傳 200 和一段通順但毫無依據的答案，使用者無從察覺。
 *
 * 用法：
 *   node tools/verify-rag.js
 */

'use strict';

const { spawn, spawnSync } = require('node:child_process');
const http = require('node:http');
const path = require('node:path');
const { gatewayAuthHeaders, ensureLocalGatewayKey } = require('./gateway-auth');

const REPO_ROOT = path.resolve(__dirname, '..');
const JAR = path.join(REPO_ROOT, 'gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar');
const GATEWAY_PORT = 8090;
const QDRANT = 'http://localhost:6333';
const SAMPLE = path.join(REPO_ROOT, 'docs/rag-sample-kb.jsonl');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const results = [];

function check(name, passed, detail) {
    results.push({ name, passed });
    console.log(`  ${passed ? '✓' : '✗'} ${name}`);
    if (detail) console.log(`      ${detail}`);
}

function request(port, method, urlPath, body) {
    return new Promise((resolve) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = port === GATEWAY_PORT ? gatewayAuthHeaders() : {};
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const req = http.request({ host: 'localhost', port, path: urlPath, method, headers, timeout: 30000 },
            (response) => {
                let data = '';
                response.on('data', (chunk) => { data += chunk; });
                response.on('end', () => {
                    let parsed = null;
                    try { parsed = JSON.parse(data); } catch { /* 保留原文 */ }
                    resolve({ status: response.statusCode, body: parsed, raw: data });
                });
            });
        req.on('timeout', () => { req.destroy(); resolve({ status: 0, raw: '逾時' }); });
        req.on('error', (error) => resolve({ status: 0, raw: error.message }));
        if (payload) req.write(payload);
        req.end();
    });
}

function gauge(name) {
    return new Promise((resolve) => {
        const req = http.get({ host: 'localhost', port: GATEWAY_PORT, path: '/actuator/prometheus', timeout: 5000 },
            (response) => {
                let text = '';
                response.on('data', (chunk) => { text += chunk; });
                response.on('end', () => {
                    const line = text.split('\n').find((row) => row.startsWith(name));
                    resolve(line ? Number(line.slice(line.lastIndexOf(' ') + 1)) : 0);
                });
            });
        req.on('timeout', () => { req.destroy(); resolve(0); });
        req.on('error', () => resolve(0));
    });
}

// ---------------------------------------------------------------- 生命週期

let gateway = null;

function killGateway() {
    spawnSync('powershell', ['-NoProfile', '-Command',
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*gateway-server*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"],
        { stdio: 'ignore' });
    gateway = null;
}

/**
 * @returns 就緒回傳 true；**啟動失敗回傳 false 而不是拋錯**——
 *          有些情境正是要驗「它應該啟動失敗」
 */
async function startGateway(environment) {
    killGateway();

    // 等到連接埠真的沒人回應才啟動。強制砍掉行程之後，作業系統釋放連接埠要一點時間，
    // 固定 sleep 猜不準——猜短了新的 JVM 會 bind 失敗然後**靜靜退出**，
    // 看起來就像「啟動逾時」，而真正的原因完全看不出來。
    // bench 也踩過同一個坑，那裡的教訓是：不要猜，去確認。
    for (let i = 0; i < 40; i++) {
        const health = await request(GATEWAY_PORT, 'GET', '/actuator/health');
        if (health.status === 0) break;
        await sleep(250);
    }

    gateway = spawn('java', ['-jar', JAR], {
        cwd: REPO_ROOT,
        stdio: 'ignore',
        env: Object.assign({}, process.env, { RAG_ENABLED: 'true' }, environment || {}),
    });

    for (let i = 0; i < 60; i++) {
        if (gateway.exitCode !== null) return false;
        const health = await request(GATEWAY_PORT, 'GET', '/actuator/health');
        if (health.status === 200) return true;
        await sleep(500);
    }
    return false;
}

function buildIndex(model) {
    const result = spawnSync('node', [
        path.join(REPO_ROOT, 'tools/rag-index.js'),
        '--input', SAMPLE, '--collection', 'docs', '--model', model,
    ], { cwd: REPO_ROOT, encoding: 'utf8' });
    if (result.status !== 0) throw new Error(`建索引失敗：${result.stdout}${result.stderr}`);
}

const ask = (extra) => request(GATEWAY_PORT, 'POST', '/v1/chat/completions', Object.assign({
    model: 'fake-chat',
    messages: [{ role: 'user', content: 'token bucket rate limit' }],
    max_tokens: 10,
}, extra));

// ---------------------------------------------------------------- 情境

async function scenarioRetrieval() {
    console.log('\n[1] 索引一致時能啟動並檢索到正確的文件');
    buildIndex('fake-embed-v1');
    const up = await startGateway({ RAG_EMBEDDING_MODEL: 'fake-embed-v1', RAG_EMBEDDING_DIM: '32' });
    check('一致時可以啟動', up, up ? '' : '網關未就緒');
    if (!up) return;

    const response = await ask({ x_rag: { collection: 'docs', top_k: 3 } });
    const rag = response.body?.x_gateway?.rag;
    // 查詢是 "token bucket rate limit"，第 3 筆文件正是講 token bucket 的。
    // id 要用 Number() 轉換再比：Qdrant 的 id 可能是 uint64 也可能是 UUID，
    // 所以 RetrievedDocument 用 String 保存，嚴格比較會永遠不相等
    const top = rag?.documents?.[0];
    check('檢索到最相關的那一筆', Number(top?.id) === 3 && top?.score > 0.7,
        `第一名 id=${top?.id} score=${top?.score?.toFixed(3)}`);
    check('嵌入成本非零（不能靜默記成 0）', rag?.embedding_cost_micro_cents > 0,
        `${rag?.embedding_cost_micro_cents} 微美分`);
    check('沒有降級', rag?.degraded === false, `degraded=${rag?.degraded}`);
}

async function scenarioCostIncludesContext() {
    console.log('\n[2] 注入的 context 有被算進成本（檢索必須在預扣之前）');
    // 同一個問題問兩次：一次不檢索、一次檢索。檢索那次的 prompt 會長很多，
    // 成本也該高很多。若檢索發生在預扣之後，兩者會一樣
    const before = await gauge('gateway_budget_spent_microcents');
    await ask({});
    const afterPlain = await gauge('gateway_budget_spent_microcents');

    await ask({ x_rag: { collection: 'docs', top_k: 3 } });
    const afterRag = await gauge('gateway_budget_spent_microcents');

    const plainCost = afterPlain - before;
    const ragCost = afterRag - afterPlain;
    check('帶檢索的請求成本明顯較高', ragCost > plainCost * 1.5,
        `不檢索 ${plainCost} 微美分，檢索 ${ragCost} 微美分`
        + `（若相等代表注入的 context 沒有被計入）`);
}

async function scenarioModelMismatch() {
    console.log('\n[3] 嵌入模型不符時應拒絕啟動（維度相同，所以不會有任何錯誤）');
    buildIndex('fake-embed-v1');
    const up = await startGateway({ RAG_EMBEDDING_MODEL: 'fake-embed-v2', RAG_EMBEDDING_DIM: '32' });
    check('模型不符時拒絕啟動', !up,
        up ? '竟然啟動成功了——這正是最危險的情況：檢索會回結果，只是全部不相關'
           : '啟動被擋下');
}

async function scenarioDimensionMismatch() {
    console.log('\n[4] 維度不符時應拒絕啟動');
    const up = await startGateway({ RAG_EMBEDDING_MODEL: 'fake-embed-v1', RAG_EMBEDDING_DIM: '64' });
    check('維度不符時拒絕啟動', !up, up ? '竟然啟動成功了' : '啟動被擋下');
}

async function scenarioMissingCollection() {
    console.log('\n[5] collection 不存在時應拒絕啟動');
    const up = await startGateway({ RAG_COLLECTION: '_does_not_exist_' });
    check('collection 不存在時拒絕啟動', !up, up ? '竟然啟動成功了' : '啟動被擋下');
}

async function scenarioFailureSemantics() {
    console.log('\n[6] 檢索失敗：預設失敗，降級要明確 opt-in');
    buildIndex('fake-embed-v1');
    // 嵌入服務指向沒有人聽的埠來模擬檢索失敗。啟動檢查只看向量庫，所以網關起得來，
    // 失敗發生在請求當下。不能再用不存在的 collection：那會先被允許清單擋下，走不到檢索
    const up = await startGateway({
        RAG_EMBEDDING_MODEL: 'fake-embed-v1', RAG_EMBEDDING_DIM: '32', RAG_EMBEDDING_URL: 'http://localhost:9199',
    });
    if (!up) { check('前置：網關就緒', false, '未就緒'); return; }

    const failing = { collection: 'docs', top_k: 3 };

    const strict = await ask({ x_rag: failing });
    check('預設不降級，直接失敗', strict.status >= 400,
        `HTTP ${strict.status}`);

    const lenient = await ask({ x_rag: Object.assign({ degrade_on_failure: true }, failing) });
    const rag = lenient.body?.x_gateway?.rag;
    check('明確要求降級時可以繼續，但要標記出來',
        lenient.status === 200 && rag?.degraded === true,
        `HTTP ${lenient.status}，degraded=${rag?.degraded}`);
    check('降級時回報原因', Boolean(rag?.note), `note=${rag?.note?.slice(0, 60)}`);
}

async function scenarioCollectionNotAllowed() {
    console.log('\n[8] 不在允許清單的 collection 一律拒絕，即使要求降級');
    buildIndex('fake-embed-v1');
    const up = await startGateway({ RAG_EMBEDDING_MODEL: 'fake-embed-v1', RAG_EMBEDDING_DIM: '32' });
    if (!up) { check('前置：網關就緒', false, '未就緒'); return; }

    // 允許任意 collection 等於繞過啟動時的嵌入一致性檢查，也能讀別人的知識庫
    const response = await ask({ x_rag: { collection: '_someone_else_', top_k: 3, degrade_on_failure: true } });
    check('回 400 而不是降級成沒有依據的答案', response.status === 400, `HTTP ${response.status}`);
}

async function scenarioNotEnabled() {
    console.log('\n[7] 沒啟用檢索卻收到 x_rag，要明確報錯而不是靜靜忽略');
    const up = await startGateway({ RAG_ENABLED: 'false' });
    if (!up) { check('前置：網關就緒', false, '未就緒'); return; }

    const response = await ask({ x_rag: { collection: 'docs', top_k: 3 } });
    check('明確拒絕', response.status >= 400,
        `HTTP ${response.status}——靜靜忽略會讓呼叫端以為答案有依據`);
}

// ---------------------------------------------------------------- 主流程

(async () => {
    ensureLocalGatewayKey();
    console.log('RAG 端到端驗證');

    const qdrant = await request(6333, 'GET', '/collections');
    if (qdrant.status !== 200) {
        console.error('✗ Qdrant 不可用，先執行 node .claude/skills/dev-stack/up.js');
        process.exit(2);
    }

    try {
        await scenarioRetrieval();
        await scenarioCostIncludesContext();
        await scenarioModelMismatch();
        await scenarioDimensionMismatch();
        await scenarioMissingCollection();
        await scenarioFailureSemantics();
        await scenarioCollectionNotAllowed();
        await scenarioNotEnabled();
    } catch (error) {
        console.error(`\n驗證中斷：${error.message}`);
        results.push({ name: '驗證流程本身', passed: false });
    } finally {
        killGateway();
        // 把索引還原成一致的狀態，不要留下一個壞掉的 collection 給下一個人
        try { buildIndex('fake-embed-v1'); } catch { /* 盡力而為 */ }
    }

    const failed = results.filter((result) => !result.passed);
    console.log(`\n${'='.repeat(60)}`);
    console.log(`共 ${results.length} 項檢查，通過 ${results.length - failed.length}，失敗 ${failed.length}`);
    failed.forEach((result) => console.log(`  ✗ ${result.name}`));
    process.exit(failed.length ? 1 : 0);
})();
