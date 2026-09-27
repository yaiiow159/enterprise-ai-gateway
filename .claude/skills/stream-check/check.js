#!/usr/bin/env node
/**
 * SSE 串流轉發的端到端驗證
 *
 * 為什麼需要專門的工具：
 *   串流的錯法幾乎都是「看起來正常」的。內容最後都會出現、狀態碼是 200、
 *   瀏覽器裡手動點一次也看不出問題——差別只在使用者要多等幾秒，
 *   或是我們多付了幾秒的錢。一般的 HTTP 測試工具只看最終結果，
 *   而串流的錯誤全部藏在「過程」裡：什麼時候到、分幾次到、中斷時怎麼表達。
 *
 * 設計上的一個選擇：
 *   每個情境都把三家假 Provider 設成**相同行為**，斷言因此不依賴「誰是主要 Provider」。
 *   路由順序會被前一次請求的延遲樣本與失敗紀錄改變，若斷言的前提是
 *   「fake-fast 會服務這次請求」，那前提隨時可能不成立，測出來的紅綠都沒有意義。
 *   需要統計數字時一律跨三家加總，同樣不假設是誰。
 *
 * 用法：
 *   node .claude/skills/stream-check/check.js
 *   node .claude/skills/stream-check/check.js --verbose
 *
 * 前置條件：dev-stack 已啟動（三個假 Provider），且網關正在 8090 埠執行。
 */

'use strict';

const http = require('node:http');
const { gatewayAuthHeaders } = require('../../../tools/gateway-auth');

const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8090);
const FAKE_PORTS = [
    Number(process.env.FAKE_FAST_PORT || 9101),
    Number(process.env.FAKE_SLOW_PORT || 9102),
    Number(process.env.FAKE_FLAKY_PORT || 9103),
];

const verbose = process.argv.includes('--verbose');
const results = [];

// ---------------------------------------------------------------- 基礎工具

/**
 * 一律用 Node 內建的 http，不外呼 curl：
 * Windows 的 curl.exe 不認得 `-o /dev/null` 這類寫法，會讓探測在 Windows 上一律失敗。
 */
function json(port, method, path, body) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = gatewayAuthHeaders();
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const request = http.request({ host: 'localhost', port, path, method, headers, timeout: 15000 }, (response) => {
            let data = '';
            response.on('data', (chunk) => { data += chunk; });
            response.on('end', () => {
                let parsed = null;
                try { parsed = JSON.parse(data); } catch { /* 非 JSON 保留原文 */ }
                resolve({ status: response.statusCode, body: parsed, raw: data });
            });
        });
        request.on('timeout', () => { request.destroy(new Error('逾時')); });
        request.on('error', reject);
        if (payload) request.write(payload);
        request.end();
    });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 把三家假 Provider 設成同一種行為。
 *
 * 順序不能反：`/_fake/reset` 會把設定一併還原成環境變數的預設值，
 * 先設定再 reset 等於什麼都沒設。
 */
async function configureAll(settings) {
    for (const port of FAKE_PORTS) {
        await json(port, 'POST', '/_fake/reset');
        await json(port, 'PUT', '/_fake/config', Object.assign({ errorRate: 0 }, settings));
    }
}

/** 跨三家加總，因為我們刻意不假設是誰在服務這次請求 */
async function totalStats() {
    const total = { requests: 0, cancelled: 0, completedStreams: 0, completionTokens: 0, errors: 0 };
    for (const port of FAKE_PORTS) {
        const stats = (await json(port, 'GET', '/_fake/stats')).body;
        for (const key of Object.keys(total)) total[key] += stats[key] || 0;
    }
    return total;
}

/**
 * 送出一次串流請求，記錄每一個事件的抵達時刻。
 *
 * 三個時間點分開記，因為它們對應三種不同的錯法：
 *   headersAt  —— 標頭被壓到第一個 token 才送（客戶端無法分辨「正在生成」與「還沒連上」）
 *   第一個事件 —— TTFT，被整段緩衝的話這個值會等於總時長
 *   最後一個事件 —— 與第一個的差距就是「有沒有逐段抵達」
 */
function streamRequest({ maxTokens = 8, abortAfterChunks = 0 } = {}) {
    return new Promise((resolve, reject) => {
        const payload = JSON.stringify({
            model: 'fake-chat',
            messages: [{ role: 'user', content: 'hello' }],
            max_tokens: maxTokens,
            stream: true,
        });
        const startedAt = Date.now();
        const arrivals = [];
        let buffer = '';
        let headersAt = -1;
        let aborted = false;

        const request = http.request({
            host: 'localhost',
            port: GATEWAY_PORT,
            path: '/v1/chat/completions',
            method: 'POST',
            headers: Object.assign(gatewayAuthHeaders(),
                { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }),
            timeout: 30000,
        }, (response) => {
            headersAt = Date.now() - startedAt;

            response.on('data', (chunk) => {
                buffer += chunk;
                let index;
                while ((index = buffer.indexOf('\n\n')) >= 0) {
                    const rawEvent = buffer.slice(0, index).trim();
                    buffer = buffer.slice(index + 2);
                    if (!rawEvent.startsWith('data:')) continue;

                    const data = rawEvent.slice(5).trim();
                    arrivals.push({ at: Date.now() - startedAt, data });

                    if (abortAfterChunks > 0 && arrivals.length === abortAfterChunks) {
                        aborted = true;
                        request.destroy();
                        resolve({ status: response.statusCode, headers: response.headers, headersAt, arrivals, aborted, totalMs: Date.now() - startedAt });
                        return;
                    }
                }
            });

            response.on('end', () => {
                if (aborted) return;
                resolve({ status: response.statusCode, headers: response.headers, headersAt, arrivals, aborted, totalMs: Date.now() - startedAt });
            });
        });

        request.on('timeout', () => { request.destroy(new Error('串流逾時')); });
        request.on('error', (error) => { if (!aborted) reject(error); });
        request.write(payload);
        request.end();
    });
}

/** 把所有 delta 串起來，還原客戶端實際看到的內容 */
function contentOf(arrivals) {
    return arrivals
        .filter((event) => event.data !== '[DONE]')
        .map((event) => {
            try { return JSON.parse(event.data)?.choices?.[0]?.delta?.content || ''; } catch { return ''; }
        })
        .join('');
}

function parsedEvents(arrivals) {
    return arrivals
        .filter((event) => event.data !== '[DONE]')
        .map((event) => { try { return JSON.parse(event.data); } catch { return null; } })
        .filter(Boolean);
}

function check(name, passed, detail) {
    results.push({ name, passed, detail });
    console.log(`  ${passed ? '✓' : '✗'} ${name}`);
    if (detail) console.log(`      ${detail}`);
}

// ---------------------------------------------------------------- 檢查項目

/**
 * 回應標頭不能等到整段生成完才送。
 *
 * 這裡刻意**不**斷言「標頭早於第一個 token 抵達」，雖然那對客戶端更友善
 * （能分辨「已連上、模型正在想」與「請求還沒被接受」）。原因是它與兩個更重要的性質衝突：
 *
 *   1. 標頭一送出，HTTP 狀態碼就定案了。上游若在那之後才失敗，就只能回 200 加一個
 *      錯誤事件——只看狀態碼的客戶端與重試中介層會把失敗當成成功（見檢查 8）。
 *   2. 對沖依定義要以首個 token 決勝負（CLAUDE.md 技術決定 4），所以在第一個 token
 *      之前，網關其實還不知道最後會由誰服務、甚至不知道有沒有人能服務。
 *
 * 誠實的狀態碼比提早幾百毫秒的標頭重要，因此標頭與第一個 token 綁在一起送。
 * 這一項要守住的是**剩下那個真正的錯法**：標頭被拖到整段生成完才送，
 * 那代表中間有人把整個串流緩衝起來了。
 *
 * 副作用要留意：TTFT 很長時，中間的反向代理可能因為遲遲沒有位元組而先逾時。
 * 真的遇到再考慮送 SSE 註解行（`: ping`）保活，但那等於提前承諾狀態碼，要一起評估。
 */
async function checkHeadersNotBuffered() {
    console.log('\n[1] 回應標頭不應等到整段生成完才送');
    // 首字 300ms、之後每個 token 150ms：整段約 300 + 7×150 = 1350ms
    await configureAll({ ttftMs: 300, tokenDelayMs: 150, tokens: 40 });
    const result = await streamRequest({ maxTokens: 8 });
    const firstEventAt = result.arrivals.length ? result.arrivals[0].at : -1;

    check('標頭遠早於整段完成',
        result.headersAt >= 0 && result.headersAt < result.totalMs * 0.6,
        `標頭 ${result.headersAt}ms，整段 ${result.totalMs}ms`);
    check('標頭與第一個 token 同時送出（設計上綁在一起）',
        result.headersAt >= 0 && Math.abs(result.headersAt - firstEventAt) < 200,
        `標頭 ${result.headersAt}ms，第一個事件 ${firstEventAt}ms`);
    check('content-type 為 text/event-stream',
        String(result.headers['content-type'] || '').includes('text/event-stream'),
        `實際：${result.headers['content-type']}`);
}

/**
 * TTFT 要反映上游的首字延遲，而不是整段生成時間。
 *
 * 任何一層（網關自己、反向代理、緩衝的 writer）把 SSE 緩衝起來，
 * 症狀都是「內容最後都會出現，只是要等整段生成完」——狀態碼與內容都正確，
 * 只有時間軸看得出來。
 */
async function checkNotBuffered() {
    console.log('\n[2] TTFT 應反映上游首字延遲，而非整段生成時間');
    // 首字 200ms、之後每個 token 150ms：整段約 200 + 7×150 = 1250ms，
    // 兩者差距夠大，被緩衝的話一眼就看得出來
    await configureAll({ ttftMs: 200, tokenDelayMs: 150, tokens: 40 });
    const result = await streamRequest({ maxTokens: 8 });
    const ttft = result.arrivals.length ? result.arrivals[0].at : -1;

    check('首個事件遠早於整段完成',
        ttft > 0 && ttft < result.totalMs * 0.5,
        `TTFT ${ttft}ms，整段 ${result.totalMs}ms（被緩衝的話兩者會幾乎相等）`);
    check('TTFT 接近上游設定值',
        ttft > 0 && ttft < 1000,
        `TTFT ${ttft}ms，上游設定 200ms（差額為網關與路由開銷）`);
}

/** chunk 要逐段抵達。一次到齊代表中間有人把整段收完才轉發 */
async function checkIncrementalDelivery() {
    console.log('\n[3] chunk 應逐段抵達，而非一次到齊');
    await configureAll({ ttftMs: 100, tokenDelayMs: 120, tokens: 40 });
    const result = await streamRequest({ maxTokens: 8 });
    const arrivals = result.arrivals;
    const spread = arrivals.length > 1 ? arrivals[arrivals.length - 1].at - arrivals[0].at : 0;
    // 8 個 token、每個間隔 120ms，理論分佈約 840ms。取一半當門檻容忍抖動
    const expected = 120 * 7;

    check('事件分佈在時間軸上',
        spread > expected * 0.5,
        `分佈區間 ${spread}ms，理論值約 ${expected}ms`);
    check('抵達時刻是遞增的',
        arrivals.every((event, index) => index === 0 || event.at >= arrivals[index - 1].at),
        `共 ${arrivals.length} 個事件`);
}

/**
 * token 不遺漏、不重複、順序正確。
 *
 * 假 Provider 的每個 token 都帶序號（`the#0 quick#1 …`），
 * 就是為了讓「轉發時的邊界處理」錯誤現形——SSE 以空行分隔事件，
 * 跨 TCP 封包切斷時若邊界處理錯了，症狀是少字或重複，而內容看起來仍然通順。
 */
async function checkNoTokenLoss() {
    console.log('\n[4] token 不遺漏、不重複、順序正確');
    await configureAll({ ttftMs: 50, tokenDelayMs: 5, tokens: 60 });
    const wanted = 30;
    const result = await streamRequest({ maxTokens: wanted });
    const content = contentOf(result.arrivals);
    const sequence = [...content.matchAll(/#(\d+)/g)].map((match) => Number(match[1]));
    const expected = Array.from({ length: wanted }, (unused, index) => index);

    check('序號完整且不重複',
        sequence.length === wanted && sequence.every((value, index) => value === expected[index]),
        `期望 0~${wanted - 1} 共 ${wanted} 個，實際收到 ${sequence.length} 個` +
        (verbose ? `\n      內容：${content}` : ''));
    check('沒有超出 max_tokens',
        sequence.length <= wanted,
        `max_tokens=${wanted}，實際 ${sequence.length} 個 token`);
}

/** 正常結束要有明確的收尾，客戶端才知道「就到這裡了」 */
async function checkNormalTermination() {
    console.log('\n[5] 正常結束應有明確收尾');
    await configureAll({ ttftMs: 50, tokenDelayMs: 5, tokens: 40 });
    const result = await streamRequest({ maxTokens: 6 });
    const last = result.arrivals[result.arrivals.length - 1];
    const finishReasons = parsedEvents(result.arrivals)
        .map((event) => event?.choices?.[0]?.finish_reason)
        .filter((reason) => reason);

    check('以 [DONE] 結束',
        last && last.data === '[DONE]',
        `最後一個事件：${last ? last.data.slice(0, 60) : '（無）'}`);
    check('帶有 finish_reason',
        finishReasons.includes('stop'),
        `收到的 finish_reason：${finishReasons.join('、') || '（無）'}`);
}

/**
 * 客戶端斷線後，上游請求必須被取消。
 *
 * 這是整份檢查裡唯一會直接造成金錢損失的項目，而且沒有任何錯誤徵兆：
 * 使用者關掉頁面之後，我們還在為那些沒人看的 token 付錢。
 */
async function checkCancellationPropagates() {
    console.log('\n[6] 客戶端斷線後，上游請求應被取消');
    await configureAll({ ttftMs: 50, tokenDelayMs: 80, tokens: 200 });
    const budget = 100;

    await streamRequest({ maxTokens: budget, abortAfterChunks: 3 });
    // 取消要往上游傳需要一點時間，等它落地再讀統計
    await sleep(2000);
    const stats = await totalStats();

    check('上游收到取消',
        stats.cancelled >= 1,
        `cancelled=${stats.cancelled}`);
    check('取消後不再產生 token',
        stats.completionTokens < budget * 0.5,
        `上限 ${budget} 個 token，實際只產生 ${stats.completionTokens} 個`);
    check('串流未被當成正常完成',
        stats.completedStreams === 0,
        `completedStreams=${stats.completedStreams}`);
}

/**
 * 上游在中途斷線時，客戶端要能分辨「正常結束」與「被截斷」。
 *
 * 已經送出的 SSE 收不回來，所以唯一能做的是明確告知。
 * 最糟的情況是網關安靜地送出 [DONE]——客戶端會把半截答案當成完整答案，
 * 使用者看到的是一段莫名其妙就停住、但沒有任何錯誤提示的回覆。
 */
async function checkMidStreamAbort() {
    console.log('\n[7] 上游中途斷線時，客戶端要能分辨截斷與正常結束');
    await configureAll({ ttftMs: 50, tokenDelayMs: 10, tokens: 60, abortAfterTokens: 4 });
    const result = await streamRequest({ maxTokens: 20 });

    const last = result.arrivals[result.arrivals.length - 1];
    const events = parsedEvents(result.arrivals);
    const hasErrorEvent = events.some((event) => event && event.error);
    const abortedFinish = events.some((event) => {
        const reason = event?.choices?.[0]?.finish_reason;
        return reason && reason !== 'stop';
    });

    check('沒有偽裝成正常結束',
        !(last && last.data === '[DONE]'),
        `最後一個事件：${last ? last.data.slice(0, 80) : '（無）'}`);
    check('有明確的中斷訊號（錯誤事件或非 stop 的 finish_reason）',
        hasErrorEvent || abortedFinish,
        hasErrorEvent ? '收到錯誤事件' : (abortedFinish ? '收到非 stop 的 finish_reason' : '兩者皆無'));
    check('截斷前已收到的內容仍然完整',
        contentOf(result.arrivals).includes('#0'),
        `已收到 ${result.arrivals.length} 個事件`);
}

/**
 * 一個 token 都還沒送出就失敗時，狀態碼要能反映失敗。
 *
 * 這個情境和中途斷線不同：什麼都還沒送出去，回應也還沒有承諾任何內容，
 * 所以應該用 HTTP 狀態碼誠實表達。若一律先回 200 再補一個錯誤事件，
 * 只檢查狀態碼的客戶端（以及大部分的監控與重試中介層）會把失敗當成成功。
 */
async function checkUpstreamFailureBeforeFirstToken() {
    console.log('\n[8] 尚未送出任何 token 就失敗時，狀態碼應反映失敗');
    await configureAll({ ttftMs: 50, tokenDelayMs: 5, tokens: 40, errorRate: 1, errorStatus: 503 });
    const result = await streamRequest({ maxTokens: 5 });
    const events = parsedEvents(result.arrivals);
    const hasErrorEvent = events.some((event) => event && event.error);

    check('客戶端能得知失敗（狀態碼或錯誤事件）',
        result.status >= 400 || hasErrorEvent,
        `HTTP ${result.status}，錯誤事件：${hasErrorEvent ? '有' : '無'}`);
    check('狀態碼不是 200',
        result.status !== 200,
        `HTTP ${result.status}` +
        (result.status === 200 ? ' —— 只看狀態碼的客戶端會把這次失敗當成成功' : ''));
}

// ---------------------------------------------------------------- 主流程

async function ensureReachable() {
    try {
        const health = await json(GATEWAY_PORT, 'GET', '/actuator/health');
        if (health.status !== 200) throw new Error(`健康檢查回傳 ${health.status}`);
    } catch (error) {
        console.error(`✗ 網關（localhost:${GATEWAY_PORT}）無法連線：${error.message}\n`);
        console.error('  先啟動：');
        console.error('    node .claude/skills/dev-stack/up.js');
        console.error('    mvn -q clean install -DskipTests');
        console.error('    java -jar gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar');
        process.exit(2);
    }

    for (const port of FAKE_PORTS) {
        try {
            await json(port, 'GET', '/health');
        } catch {
            console.error(`✗ 假 Provider（localhost:${port}）無法連線，先跑 dev-stack`);
            process.exit(2);
        }
    }
}

(async () => {
    console.log('SSE 串流轉發驗證');
    console.log(`網關 localhost:${GATEWAY_PORT}，假 Provider ${FAKE_PORTS.join('、')}`);
    await ensureReachable();

    try {
        await checkHeadersNotBuffered();
        await checkNotBuffered();
        await checkIncrementalDelivery();
        await checkNoTokenLoss();
        await checkNormalTermination();
        await checkCancellationPropagates();
        await checkMidStreamAbort();
        await checkUpstreamFailureBeforeFirstToken();
    } catch (error) {
        console.error(`\n驗證中斷：${error.message}`);
        results.push({ name: '驗證流程本身', passed: false, detail: error.message });
    } finally {
        // 別把假 Provider 留在「必定回錯誤」的狀態，否則下一個人跑任何東西都會失敗
        await configureAll({ ttftMs: 100, tokenDelayMs: 8, tokens: 40, abortAfterTokens: 0 }).catch(() => {});
    }

    const failed = results.filter((result) => !result.passed);
    console.log(`\n${'='.repeat(62)}`);
    console.log(`共 ${results.length} 項檢查，通過 ${results.length - failed.length}，失敗 ${failed.length}`);
    if (failed.length) {
        console.log('\n失敗項目：');
        failed.forEach((result) => console.log(`  ✗ ${result.name}\n      ${result.detail || ''}`));
    }
    process.exit(failed.length ? 1 : 0);
})();
