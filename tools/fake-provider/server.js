#!/usr/bin/env node
/**
 * 假的 LLM Provider（OpenAI 相容，含 SSE 串流）
 *
 * 這是整個專案第一個要有的東西。網關的核心功能——依延遲路由、對沖取最快、
 * 失敗切換、熔斷、串流轉發——**只有在下游行為可控時才驗證得了**：
 * 用真的 Provider 測，每跑一次測試付一次錢，而且延遲每次都不同，
 * 「有沒有選到最快的那個」根本無從斷言。
 *
 * 用 Node 而不是 Java 實作是刻意的：這是測試替身，要能秒起、零建置、
 * 改行為不用重編譯。它不會進到正式產物裡。
 *
 * 行為的三層控制（後者覆蓋前者）：
 *   1. 環境變數      —— 這個實例的預設個性（compose 起三個不同個性的實例）
 *   2. PUT /_fake/config —— 執行期改變行為，用於「跑到一半讓 Provider 開始故障」
 *   3. 請求標頭      —— 單一請求的覆寫，用於單元／整合測試
 *
 * 端點：
 *   POST /v1/chat/completions   OpenAI 相容，支援 stream: true
 *   GET  /v1/models
 *   GET  /_fake/config          目前設定
 *   PUT  /_fake/config          修改設定（JSON body）
 *   GET  /_fake/stats           請求數、取消數、產出 token 數
 *   POST /_fake/reset           清空統計並還原設定
 */

const http = require('node:http');

const PORT = Number(process.env.FAKE_PORT || 9101);

const DEFAULTS = {
    name: process.env.FAKE_NAME || 'fake-provider',
    /** 首個 token 的延遲。對沖與路由的勝負就是由這個值決定 */
    ttftMs: Number(process.env.FAKE_TTFT_MS || 120),
    /** 每個後續 token 的間隔 */
    tokenDelayMs: Number(process.env.FAKE_TOKEN_DELAY_MS || 8),
    /** 預設產生幾個 token */
    tokens: Number(process.env.FAKE_TOKENS || 40),
    /** 回傳錯誤的機率（0~1）*/
    errorRate: Number(process.env.FAKE_ERROR_RATE || 0),
    /** 錯誤時回傳的狀態碼 */
    errorStatus: Number(process.env.FAKE_ERROR_STATUS || 500),
    /** 串流到第 N 個 token 後直接中斷連線；0 表示不中斷 */
    abortAfterTokens: Number(process.env.FAKE_ABORT_AFTER_TOKENS || 0),
    /**
     * 不回報 usage，模擬真實 Provider 的預設行為。
     *
     * OpenAI 的串流**預設不回** usage，要另外送 stream_options.include_usage 才有。
     * 這個替身原本一律回報，比真貨慷慨——於是網關理所當然地依賴了那份慷慨，
     * 接上真 Provider 後每一筆成本都會記成 0。
     * 替身該模擬的是真貨的**限制**，不只是它的能力。
     */
    omitUsage: String(process.env.FAKE_OMIT_USAGE || 'false') === 'true',
    /** 嵌入模型名稱。換名字就等於換模型：維度相同、空間不同 */
    embeddingModel: process.env.FAKE_EMBEDDING_MODEL || 'fake-embed-v1',
    /** 嵌入維度。可以刻意設成與 collection 不符來驗證啟動檢查 */
    embeddingDimension: Number(process.env.FAKE_EMBEDDING_DIM || 32),
    /** 嵌入呼叫的延遲。檢索讓 TTFT 增加多少，靠它才量得出來 */
    embeddingLatencyMs: Number(process.env.FAKE_EMBEDDING_LATENCY_MS || 20),
    /** 模擬價格，用於驗證成本歸戶（單位：每百萬 token 的美分）*/
    inputCentsPerMillion: Number(process.env.FAKE_INPUT_CENTS || 50),
    outputCentsPerMillion: Number(process.env.FAKE_OUTPUT_CENTS || 150),
};

let config = { ...DEFAULTS };

const stats = {
    requests: 0,
    streamingRequests: 0,
    embeddingRequests: 0,
    errors: 0,
    /** 客戶端在串流結束前斷線的次數。網關有沒有把取消往上游傳，就看這個數字 */
    cancelled: 0,
    completedStreams: 0,
    promptTokens: 0,
    completionTokens: 0,
};

const WORDS = ('the quick brown fox jumps over a lazy dog while parsing tokens '
    + 'and streaming them back through the gateway one chunk at a time').split(' ');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 粗略估算 prompt token 數。假 Provider 不需要真的 tokenizer，但要回報「像樣」的數字 */
function estimateTokens(messages) {
    const text = (messages || []).map((m) => String(m.content ?? '')).join(' ');
    return Math.max(1, Math.ceil(text.length / 4));
}

function readBody(request) {
    return new Promise((resolve) => {
        let data = '';
        request.on('data', (chunk) => { data += chunk; });
        request.on('end', () => {
            try {
                resolve(data ? JSON.parse(data) : {});
            } catch (_) {
                resolve({});
            }
        });
    });
}

/** 標頭覆寫：X-Fake-Ttft-Ms 之類，只影響這一個請求 */
function effectiveConfig(request) {
    const header = (name, fallback) => {
        const raw = request.headers[`x-fake-${name}`];
        return raw === undefined ? fallback : Number(raw);
    };
    return {
        ...config,
        ttftMs: header('ttft-ms', config.ttftMs),
        tokenDelayMs: header('token-delay-ms', config.tokenDelayMs),
        tokens: header('tokens', config.tokens),
        errorStatus: header('error-status', config.errorStatus),
        errorRate: request.headers['x-fake-error'] !== undefined ? 1 : config.errorRate,
        abortAfterTokens: header('abort-after-tokens', config.abortAfterTokens),
        omitUsage: request.headers['x-fake-omit-usage'] !== undefined || config.omitUsage,
        embeddingModel: request.headers['x-fake-embedding-model'] || config.embeddingModel,
        embeddingDimension: header('embedding-dim', config.embeddingDimension),
        embeddingLatencyMs: header('embedding-latency-ms', config.embeddingLatencyMs),
    };
}

function sendJson(response, status, payload) {
    const body = JSON.stringify(payload);
    response.writeHead(status, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
    });
    response.end(body);
}

function errorPayload(status) {
    // 對齊 OpenAI 的錯誤格式，網關的錯誤對映才有得測
    const type = status === 429 ? 'rate_limit_exceeded' : 'server_error';
    return { error: { message: `fake provider error (${status})`, type, code: null } };
}

async function handleChatCompletion(request, response) {
    const body = await readBody(request);
    const settings = effectiveConfig(request);
    const streaming = body.stream === true;

    stats.requests += 1;
    if (streaming) stats.streamingRequests += 1;

    if (settings.errorRate > 0 && Math.random() < settings.errorRate) {
        stats.errors += 1;
        sendJson(response, settings.errorStatus, errorPayload(settings.errorStatus));
        return;
    }

    const promptTokens = estimateTokens(body.messages);
    // max_tokens 要被遵守：網關若忘了設，這裡就會產生一大串 token，
    // 成本測試才看得出差別
    const requested = Number(body.max_tokens) > 0 ? Number(body.max_tokens) : settings.tokens;
    const totalTokens = Math.min(requested, settings.tokens);
    stats.promptTokens += promptTokens;

    const id = `chatcmpl-fake-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
    const model = body.model || settings.name;

    if (!streaming) {
        await sleep(settings.ttftMs + settings.tokenDelayMs * totalTokens);
        const content = buildContent(totalTokens);
        stats.completionTokens += totalTokens;
        sendJson(response, 200, {
            id,
            object: 'chat.completion',
            created: Math.floor(Date.now() / 1000),
            model,
            provider: settings.name,
            choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
            ...(settings.omitUsage ? {} : { usage: usage(promptTokens, totalTokens, settings) }),
        });
        return;
    }

    response.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
        // 這一行不能少：中間若有 nginx 之類的代理，沒有它會把 SSE 緩衝起來，
        // 症狀是「內容最後都會出現，但要等整段生成完」——正是 stream-check 要抓的
        'X-Accel-Buffering': 'no',
    });
    // 必須明確 flush：Node 預設會把標頭壓到第一次 write() 才一起送出，
    // 於是客戶端要等到第一個 token 才收到回應標頭，量到的 TTFT 永遠是 0，
    // 也分不出「已連上正在生成」與「還沒連上」。真實 Provider 是立刻回標頭的。
    response.flushHeaders();

    let cancelled = false;
    // 客戶端斷線。網關有沒有把取消往上游傳，就靠這個計數驗證——
    // 沒傳的話就是「使用者早就離開了，我們還在付錢」。
    // 要監聽 response 的 close 並比對 writableFinished：IncomingMessage 的 close
    // 在請求主體讀完時就會觸發，用它判斷會漏掉真正的中途斷線。
    response.on('close', () => {
        if (!response.writableFinished) {
            cancelled = true;
            stats.cancelled += 1;
        }
    });

    await sleep(settings.ttftMs);

    for (let index = 0; index < totalTokens; index += 1) {
        if (cancelled) return;

        if (settings.abortAfterTokens > 0 && index >= settings.abortAfterTokens) {
            // 串流到一半直接斷線，不送 [DONE]。網關要能把這個情況表達給它的客戶端
            response.destroy();
            return;
        }

        const chunk = {
            id,
            object: 'chat.completion.chunk',
            created: Math.floor(Date.now() / 1000),
            model,
            provider: settings.name,
            choices: [{ index: 0, delta: { content: token(index) }, finish_reason: null }],
        };
        response.write(`data: ${JSON.stringify(chunk)}\n\n`);
        stats.completionTokens += 1;

        if (index < totalTokens - 1) await sleep(settings.tokenDelayMs);
    }

    if (cancelled) return;

    response.write(`data: ${JSON.stringify({
        id,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model,
        provider: settings.name,
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
        // omitUsage 時整個欄位不出現，模擬真實 Provider 的預設行為
        ...(settings.omitUsage ? {} : { usage: usage(promptTokens, totalTokens, settings) }),
    })}\n\n`);
    response.write('data: [DONE]\n\n');
    response.end();
    stats.completedStreams += 1;
}

/** 每個 token 都帶序號，串流時才驗得出有沒有遺漏或重複 */
function token(index) {
    return `${index === 0 ? '' : ' '}${WORDS[index % WORDS.length]}#${index}`;
}

function buildContent(count) {
    let text = '';
    for (let i = 0; i < count; i += 1) text += token(i);
    return text;
}

function usage(promptTokens, completionTokens, settings) {
    return {
        prompt_tokens: promptTokens,
        completion_tokens: completionTokens,
        total_tokens: promptTokens + completionTokens,
        // 非 OpenAI 標準欄位，但網關要驗證成本歸戶就需要一個可預期的價格來源
        estimated_cost_cents:
            (promptTokens * settings.inputCentsPerMillion
                + completionTokens * settings.outputCentsPerMillion) / 1_000_000,
    };
}

// ---------------------------------------------------------------- 嵌入

/**
 * 詞袋嵌入。**這不是真的模型**，是一個行為可預測的替身。
 *
 * 關鍵是它要有真實模型的一個性質：**共用詞越多越相似**。
 * 用隨機或雜湊向量的話 recall@k 會是隨機值，那樣就無法斷言檢索有沒有壞掉——
 * 而「檢索悄悄壞掉但照樣回結果」正是 RAG 最典型的失效方式。
 *
 * `embeddingModel` 決定「詞 → 維度」的映射。換一個名字就等於換一個模型：
 * **維度完全相同、只是空間不同**。這正是最危險的那種不一致——維度對得上所以不報錯，
 * 查詢照常回傳最近鄰，只是全部不相關。
 */
const VOCABULARY = [
    'retry', 'timeout', 'circuit', 'breaker', 'rate', 'limit', 'token', 'bucket',
    'stream', 'cancel', 'upstream', 'provider', 'hedge', 'latency', 'cost', 'budget',
    'embedding', 'vector', 'index', 'recall', 'cache', 'semantic', 'prompt', 'model',
    'virtual', 'thread', 'block', 'pool', 'router', 'failover', 'metric', 'quantile',
];

function vocabularyMapping(model) {
    let seed = 0;
    for (const character of String(model)) seed = (seed * 31 + character.charCodeAt(0)) >>> 0;

    const indices = VOCABULARY.map((unused, index) => index);
    // 以模型名稱為種子的 Fisher-Yates；不用 Math.random 才能重現
    for (let i = indices.length - 1; i > 0; i -= 1) {
        seed = (seed * 1103515245 + 12345) >>> 0;
        const j = seed % (i + 1);
        [indices[i], indices[j]] = [indices[j], indices[i]];
    }
    return new Map(VOCABULARY.map((word, index) => [word, indices[index]]));
}

function embed(text, settings) {
    const mapping = vocabularyMapping(settings.embeddingModel);
    const vector = new Array(settings.embeddingDimension).fill(0);

    for (const word of String(text).toLowerCase().split(/[^a-z]+/)) {
        const index = mapping.get(word);
        // 維度被設得比詞彙表小時，落在範圍外的詞就丟掉——
        // 這也是刻意的：可以用它模擬「維度設錯」的情況
        if (index !== undefined && index < vector.length) vector[index] += 1;
    }

    const norm = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0));
    return norm > 0 ? vector.map((value) => value / norm) : vector;
}

async function handleEmbeddings(request, response) {
    const body = await readBody(request);
    const settings = effectiveConfig(request);

    stats.requests += 1;
    stats.embeddingRequests += 1;

    if (settings.errorRate > 0 && Math.random() < settings.errorRate) {
        stats.errors += 1;
        sendJson(response, settings.errorStatus, errorPayload(settings.errorStatus));
        return;
    }

    const inputs = Array.isArray(body.input) ? body.input : [body.input ?? ''];

    // 以請求指定的模型為準，設定值只是預設——真 Provider 就是靠 body.model 選模型的。
    // 一開始漏了這一行，結果「用 v1 建索引、用 v2 查詢」的測試看起來有跑，
    // 實際上兩邊都用同一個模型，相似度當然一樣高。**替身不忠實，測試就是假的。**
    const model = body.model || settings.embeddingModel;
    const effective = { ...settings, embeddingModel: model };

    // 嵌入也要花時間。沒有延遲的話「檢索讓 TTFT 變慢多少」就量不出來
    await sleep(settings.embeddingLatencyMs);

    const promptTokens = inputs.reduce((sum, text) => sum + Math.max(1, Math.ceil(String(text).length / 4)), 0);
    stats.promptTokens += promptTokens;

    sendJson(response, 200, {
        object: 'list',
        model,
        data: inputs.map((text, index) => ({
            object: 'embedding',
            index,
            embedding: embed(text, effective),
        })),
        // 與 chat 一致：真 Provider 不回 estimated_cost_cents，omitUsage 時整個 usage 都不出現
        ...(settings.omitUsage ? {} : {
            usage: {
                prompt_tokens: promptTokens,
                total_tokens: promptTokens,
                estimated_cost_cents: promptTokens * settings.inputCentsPerMillion / 1_000_000,
            },
        }),
    });
}

const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, `http://localhost:${PORT}`);

    try {
        if (request.method === 'POST' && url.pathname === '/v1/chat/completions') {
            await handleChatCompletion(request, response);
            return;
        }

        if (request.method === 'POST' && url.pathname === '/v1/embeddings') {
            await handleEmbeddings(request, response);
            return;
        }

        if (request.method === 'GET' && url.pathname === '/v1/models') {
            sendJson(response, 200, {
                object: 'list',
                data: [{ id: config.name, object: 'model', owned_by: 'fake', created: 0 }],
            });
            return;
        }

        if (url.pathname === '/_fake/config') {
            if (request.method === 'GET') {
                sendJson(response, 200, config);
                return;
            }
            if (request.method === 'PUT') {
                const patch = await readBody(request);
                config = { ...config, ...patch };
                sendJson(response, 200, config);
                return;
            }
        }

        if (request.method === 'GET' && url.pathname === '/_fake/stats') {
            sendJson(response, 200, { name: config.name, ...stats });
            return;
        }

        if (request.method === 'POST' && url.pathname === '/_fake/reset') {
            config = { ...DEFAULTS };
            Object.keys(stats).forEach((key) => { stats[key] = 0; });
            sendJson(response, 200, { reset: true, config });
            return;
        }

        if (request.method === 'GET' && url.pathname === '/health') {
            sendJson(response, 200, { status: 'UP', name: config.name });
            return;
        }

        sendJson(response, 404, { error: { message: `no route for ${request.method} ${url.pathname}` } });
    } catch (error) {
        sendJson(response, 500, { error: { message: error.message } });
    }
});

server.listen(PORT, () => {
    console.log(`[fake-provider] ${config.name} listening on ${PORT} `
        + `(ttft=${config.ttftMs}ms, tokenDelay=${config.tokenDelayMs}ms, errorRate=${config.errorRate})`);
});
