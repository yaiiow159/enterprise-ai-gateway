#!/usr/bin/env node
/**
 * 假 Provider 的自我驗證
 *
 * 這支測的是「測試替身本身可不可信」。假 Provider 若行為不符預期，
 * 後面所有以它為基礎的路由／對沖／串流驗證都會得到錯誤的結論——
 * 而且是那種看起來全綠、實際上什麼都沒驗到的錯誤結論。
 *
 * 用法：node tools/fake-provider/smoke.js [--port=9101]
 */

const http = require('node:http');

const arg = (name, fallback) => {
    const found = process.argv.find((value) => value.startsWith(`--${name}=`));
    return found ? found.split('=')[1] : fallback;
};

const PORT = Number(arg('port', 9101));
const results = [];

function request({ method = 'GET', path: urlPath, body, headers = {}, onChunk, abortAfterMs }) {
    return new Promise((resolve, reject) => {
        // 從「送出請求」起算而非「標頭抵達」起算：後者量到的是 token 間隔，
        // 不是使用者真正感受到的首字延遲
        const startedAt = Date.now();
        const payload = body ? JSON.stringify(body) : null;
        const req = http.request({
            host: 'localhost',
            port: PORT,
            path: urlPath,
            method,
            headers: {
                ...headers,
                ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } : {}),
            },
            timeout: 20_000,
        }, (response) => {
            let data = '';
            response.on('data', (chunk) => {
                data += chunk;
                if (onChunk) onChunk(chunk.toString(), Date.now() - startedAt);
            });
            response.on('end', () => resolve({ status: response.statusCode, body: data }));
            response.on('close', () => resolve({ status: response.statusCode, body: data, closed: true }));
        });

        if (abortAfterMs) {
            setTimeout(() => { req.destroy(); resolve({ aborted: true }); }, abortAfterMs);
        }
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        req.on('error', (error) => {
            if (abortAfterMs) resolve({ aborted: true });
            else reject(error);
        });
        if (payload) req.write(payload);
        req.end();
    });
}

async function check(name, fn) {
    try {
        await fn();
        results.push({ name, ok: true });
        console.log(`  ✓ ${name}`);
    } catch (error) {
        results.push({ name, ok: false, message: error.message });
        console.log(`  ✗ ${name}`);
        console.log(`      ${error.message}`);
    }
}

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

/** 解析 SSE 串流，記錄每個 chunk 的抵達時間 */
async function streamChat(body, headers = {}, abortAfterMs) {
    const arrivals = [];
    let buffer = '';
    const events = [];

    const result = await request({
        method: 'POST',
        path: '/v1/chat/completions',
        body: { messages: [{ role: 'user', content: 'hello' }], stream: true, ...body },
        headers,
        abortAfterMs,
        onChunk: (chunk, elapsedMs) => {
            buffer += chunk;
            let index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                const raw = buffer.slice(0, index).trim();
                buffer = buffer.slice(index + 2);
                if (!raw.startsWith('data:')) continue;
                const payload = raw.slice(5).trim();
                arrivals.push(elapsedMs);
                events.push(payload);
            }
        },
    });

    return { ...result, arrivals, events };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

(async () => {
    console.log(`假 Provider 自我驗證（port ${PORT}）`);

    await request({ method: 'POST', path: '/_fake/reset' });
    const config = JSON.parse((await request({ path: '/_fake/config' })).body);
    console.log(`  設定：ttft=${config.ttftMs}ms tokenDelay=${config.tokenDelayMs}ms\n`);

    await check('串流回傳 OpenAI 格式的 chunk 並以 [DONE] 結尾', async () => {
        const { events } = await streamChat({ max_tokens: 5 });
        assert(events.length >= 6, `事件數過少：${events.length}`);
        assert(events[events.length - 1] === '[DONE]', '最後一個事件不是 [DONE]');
        const first = JSON.parse(events[0]);
        assert(first.object === 'chat.completion.chunk', `object 欄位不對：${first.object}`);
        assert(typeof first.choices[0].delta.content === 'string', 'delta.content 缺失');
    });

    await check('token 是逐段抵達，不是整段生成完才一次吐出', async () => {
        const { arrivals } = await streamChat({ max_tokens: 8 }, { 'x-fake-token-delay-ms': '60' });
        assert(arrivals.length >= 8, `chunk 數過少：${arrivals.length}`);
        const spread = arrivals[arrivals.length - 1] - arrivals[0];
        // 8 個 token × 60ms 間隔；若中間有人做了緩衝，所有 chunk 會擠在同一毫秒抵達
        assert(spread > 200, `chunk 幾乎同時抵達（間距 ${spread}ms），代表有緩衝`);
    });

    await check('TTFT 由設定決定，且與 token 總數無關', async () => {
        const fast = await streamChat({ max_tokens: 3 }, { 'x-fake-ttft-ms': '100', 'x-fake-token-delay-ms': '1' });
        const slow = await streamChat({ max_tokens: 3 }, { 'x-fake-ttft-ms': '600', 'x-fake-token-delay-ms': '1' });
        assert(fast.arrivals[0] < 400, `快的 TTFT 異常：${fast.arrivals[0]}ms`);
        assert(slow.arrivals[0] > 500, `慢的 TTFT 異常：${slow.arrivals[0]}ms`);
    });

    await check('每個 token 帶序號，可據以驗證遺漏或重複', async () => {
        const { events } = await streamChat({ max_tokens: 6 });
        const content = events
            .filter((event) => event !== '[DONE]')
            .map((event) => JSON.parse(event).choices[0].delta.content || '')
            .join('');
        for (let i = 0; i < 6; i += 1) {
            assert(content.includes(`#${i}`), `缺少第 ${i} 個 token`);
        }
    });

    await check('串流可中途中斷（不送 [DONE]）', async () => {
        const { events } = await streamChat({ max_tokens: 20 }, { 'x-fake-abort-after-tokens': '3' });
        assert(!events.includes('[DONE]'), '中斷的串流不該送出 [DONE]');
        assert(events.length >= 1 && events.length <= 5, `中斷點不符預期：${events.length} 個事件`);
    });

    await check('可注入錯誤狀態碼', async () => {
        const response = await request({
            method: 'POST',
            path: '/v1/chat/completions',
            body: { messages: [{ role: 'user', content: 'x' }] },
            headers: { 'x-fake-error': '1', 'x-fake-error-status': '429' },
        });
        assert(response.status === 429, `預期 429，實際 ${response.status}`);
        const payload = JSON.parse(response.body);
        assert(payload.error.type === 'rate_limit_exceeded', `錯誤型別不對：${payload.error.type}`);
    });

    // 這一條是整組裡最重要的：網關有沒有把「客戶端斷線」往上游傳，
    // 決定了使用者關掉頁面之後我們還會不會繼續付錢
    await check('客戶端斷線會被記錄為取消', async () => {
        const before = JSON.parse((await request({ path: '/_fake/stats' })).body).cancelled;
        await streamChat({ max_tokens: 50 }, { 'x-fake-token-delay-ms': '50' }, 300);
        await sleep(300);
        const after = JSON.parse((await request({ path: '/_fake/stats' })).body).cancelled;
        assert(after === before + 1, `取消計數未增加：${before} → ${after}`);
    });

    await check('遵守 max_tokens，不會超量產生', async () => {
        await request({ method: 'POST', path: '/_fake/reset' });
        await streamChat({ max_tokens: 4 });
        const stats = JSON.parse((await request({ path: '/_fake/stats' })).body);
        assert(stats.completionTokens === 4, `產生了 ${stats.completionTokens} 個 token，預期 4`);
    });

    await check('執行期可改變行為（PUT /_fake/config）', async () => {
        await request({ method: 'PUT', path: '/_fake/config', body: { ttftMs: 800 } });
        const { arrivals } = await streamChat({ max_tokens: 2 });
        await request({ method: 'PUT', path: '/_fake/config', body: { ttftMs: config.ttftMs } });
        assert(arrivals[0] > 700, `設定未生效，TTFT 為 ${arrivals[0]}ms`);
    });

    // ---- 嵌入 ----
    // 這幾項守的是「替身要忠實」：不忠實的替身讓測試看起來有跑，實際上什麼都沒驗。
    // 開發時就踩到——handleEmbeddings 一開始忽略 body.model，於是
    //「用 v1 建索引、用 v2 查詢」的測試兩邊其實是同一個模型，相似度當然一樣高。

    const embed = async (input, model) => {
        const response = await request({
            method: 'POST', path: '/v1/embeddings', body: { model, input },
        });
        const payload = JSON.parse(response.body);
        return { model: payload.model, vectors: payload.data.map((item) => item.embedding), usage: payload.usage };
    };
    const cosine = (a, b) => a.reduce((sum, value, index) => sum + value * b[index], 0);

    const DOCUMENT = 'rate limit uses a token bucket refilled at a fixed rate';
    const RELATED = 'token bucket rate limit';
    const UNRELATED = 'virtual thread blocks without consuming a platform pool thread';

    await check('/v1/embeddings 回傳 OpenAI 格式與正確維度', async () => {
        const { vectors, usage } = await embed([DOCUMENT, RELATED]);
        assert(vectors.length === 2, `回傳 ${vectors.length} 筆，預期 2`);
        assert(vectors[0].length === config.embeddingDimension,
            `維度 ${vectors[0].length}，預期 ${config.embeddingDimension}`);
        assert(usage && usage.prompt_tokens > 0, 'usage 缺少 prompt_tokens');
    });

    await check('相關文字的相似度明顯高於無關文字（recall 才有意義）', async () => {
        const { vectors } = await embed([DOCUMENT, RELATED, UNRELATED]);
        const near = cosine(vectors[0], vectors[1]);
        const far = cosine(vectors[0], vectors[2]);
        assert(near > far + 0.3, `相關 ${near.toFixed(3)} 與無關 ${far.toFixed(3)} 區分度不足`);
    });

    await check('body.model 決定用哪個模型，換模型會換掉整個向量空間', async () => {
        const first = await embed([DOCUMENT], 'fake-embed-v1');
        const same = await embed([RELATED], 'fake-embed-v1');
        const other = await embed([RELATED], 'fake-embed-v2');

        assert(other.model === 'fake-embed-v2', `回應回報的模型是 ${other.model}`);
        const consistent = cosine(first.vectors[0], same.vectors[0]);
        const mismatched = cosine(first.vectors[0], other.vectors[0]);
        assert(consistent > 0.7, `同模型相似度只有 ${consistent.toFixed(3)}`);
        assert(mismatched < consistent / 2,
            `換模型後相似度仍有 ${mismatched.toFixed(3)}（同模型 ${consistent.toFixed(3)}）——`
            + 'body.model 可能沒有生效');
    });

    await check('嵌入也會回報成本（真 Provider 不回時要能靠 omitUsage 模擬）', async () => {
        const withUsage = await embed([DOCUMENT]);
        assert(withUsage.usage.estimated_cost_cents > 0, '嵌入沒有回報成本');

        await request({ method: 'PUT', path: '/_fake/config', body: { omitUsage: true } });
        const without = await embed([DOCUMENT]);
        await request({ method: 'PUT', path: '/_fake/config', body: { omitUsage: false } });
        assert(without.usage === undefined, 'omitUsage 時 usage 欄位仍然存在');
    });

    await request({ method: 'POST', path: '/_fake/reset' });

    const failed = results.filter((entry) => !entry.ok).length;
    console.log(`\n=== ${results.length} 項檢查，通過 ${results.length - failed}，失敗 ${failed} ===`);
    process.exit(failed > 0 ? 1 : 0);
})().catch((error) => {
    console.error('✗ 執行失敗：', error.message);
    process.exit(1);
});
