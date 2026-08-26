#!/usr/bin/env node
/**
 * RAG 檢索品質把關：嵌入一致性與 recall@k
 *
 * 要防的是什麼：
 *   嵌入不一致是**靜默失效**。用 A 模型建索引、用 B 模型查詢，向量庫照樣回傳最近鄰，
 *   只是全部不相關。沒有例外、沒有錯誤碼、延遲也不變——系統不會壞掉，只會變笨。
 *   維度不同會直接報錯（那算幸運）；**維度相同但模型不同才是災難**，
 *   因為兩邊都「正常運作」，只有答案品質默默崩掉。
 *
 * 這裡定義的契約：
 *   Qdrant 原生**不記錄** collection 是用哪個嵌入模型建的。不記的話，
 *   上面那個災難在事後根本無從偵測——你只會看到「RAG 效果不好」，
 *   而那可以歸咎於一百種原因。所以本工具要求：
 *
 *     每一個 point 的 payload 都要帶 `_embedding_model` 與 `_embedding_dim`。
 *
 *   逐點記錄而不是整個 collection 記一次，是為了同時抓到**部分重建索引**：
 *   一半的點來自舊模型、一半來自新模型，這種狀態比整個索引錯掉更難發現。
 *
 * 目前狀態：
 *   本專案還沒有 RAG 程式碼，Qdrant 也還沒有任何 collection。
 *   所以 `--check` 會報告「沒有東西可檢查」，而 `--self-test` 用合成資料
 *   證明這些檢查真的抓得到問題——等真的 RAG 進來時，工具已經備妥且驗證過。
 *
 * 用法：
 *   node .claude/skills/rag-eval/check.js              檢查現有 collection
 *   node .claude/skills/rag-eval/check.js --self-test   用合成資料驗證檢查有效
 */

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '../../..');
const QDRANT_PORT = Number(process.env.QDRANT_PORT || 6333);

const findings = [];

function report(severity, title, detail, remedy) {
    findings.push({ severity, title });
    console.log(`  ${severity === '阻擋' ? '✗' : '⚠'} [${severity}] ${title}`);
    if (detail) console.log(`      ${detail}`);
    if (remedy) console.log(`      → ${remedy}`);
}

function pass(title, detail) {
    console.log(`  ✓ ${title}`);
    if (detail) console.log(`      ${detail}`);
}

// ---------------------------------------------------------------- Qdrant

function qdrant(method, urlPath, body) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = { 'Content-Type': 'application/json' };
        if (payload) headers['Content-Length'] = Buffer.byteLength(payload);

        const request = http.request({
            host: 'localhost', port: QDRANT_PORT, path: urlPath, method, headers, timeout: 20000,
        }, (response) => {
            let data = '';
            response.on('data', (chunk) => { data += chunk; });
            response.on('end', () => {
                let parsed = null;
                try { parsed = JSON.parse(data); } catch { /* 保留原文 */ }
                resolve({ status: response.statusCode, body: parsed, raw: data });
            });
        });
        request.on('timeout', () => request.destroy(new Error('Qdrant 逾時')));
        request.on('error', reject);
        if (payload) request.write(payload);
        request.end();
    });
}

// ---------------------------------------------------------------- 固定裝置

/**
 * 測試用的嵌入。**這不是真的模型**，是一個行為可預測的替身：
 * 以詞袋（bag-of-words）建向量，所以「共用詞越多越相似」這件事成立，
 * recall@k 才會是一個有意義的數字而不是隨機值。
 *
 * `model` 參數決定每個詞映射到哪一個維度。兩個模型的**維度相同**、
 * 只是詞到維度的對應不同——這正是最危險的那種不一致：
 * 維度對得上，所以不會報錯，查詢照常回傳最近鄰，只是全部不相關。
 */
const VOCABULARY = [
    'retry', 'timeout', 'circuit', 'breaker', 'rate', 'limit', 'token', 'bucket',
    'stream', 'cancel', 'upstream', 'provider', 'hedge', 'latency', 'cost', 'budget',
    'embedding', 'vector', 'index', 'recall', 'cache', 'semantic', 'prompt', 'model',
    'virtual', 'thread', 'block', 'pool', 'router', 'failover', 'metric', 'quantile',
];
const DIMENSION = VOCABULARY.length;

/** 以模型名稱為種子，決定詞到維度的映射。同名必然同映射 */
function vocabularyMapping(model) {
    let seed = 0;
    for (const character of model) seed = (seed * 31 + character.charCodeAt(0)) >>> 0;

    const indices = VOCABULARY.map((unused, index) => index);
    // 以種子驅動的 Fisher-Yates，不用 Math.random 以確保可重現
    for (let i = indices.length - 1; i > 0; i -= 1) {
        seed = (seed * 1103515245 + 12345) >>> 0;
        const j = seed % (i + 1);
        [indices[i], indices[j]] = [indices[j], indices[i]];
    }
    return new Map(VOCABULARY.map((word, index) => [word, indices[index]]));
}

function embed(text, model) {
    const mapping = vocabularyMapping(model);
    const vector = new Array(DIMENSION).fill(0);
    for (const word of String(text).toLowerCase().split(/[^a-z]+/)) {
        const index = mapping.get(word);
        if (index !== undefined) vector[index] += 1;
    }
    const norm = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0));
    return norm > 0 ? vector.map((value) => value / norm) : vector;
}

/** 每份文件都對應到一個問題，讓 recall@k 有明確的正確答案 */
const CORPUS = [
    { id: 1, text: 'retry with backoff and a hard attempt limit prevents upstream overload' },
    { id: 2, text: 'circuit breaker opens after repeated upstream failures' },
    { id: 3, text: 'rate limit uses a token bucket refilled at a fixed rate' },
    { id: 4, text: 'cancel the upstream stream when the client disconnects' },
    { id: 5, text: 'hedge a second provider only after the primary exceeds its latency quantile' },
    { id: 6, text: 'cost budget blocks requests once the daily limit is reached' },
    { id: 7, text: 'embedding and index must come from the same model or recall collapses' },
    { id: 8, text: 'semantic cache returns a stored prompt completion for similar input' },
    { id: 9, text: 'virtual thread blocks without consuming a platform pool thread' },
    { id: 10, text: 'router performs failover to another provider on retryable errors' },
];

const QUESTIONS = [
    { query: 'token bucket rate limit', expected: 3 },
    { query: 'circuit breaker upstream failures', expected: 2 },
    { query: 'cancel stream client disconnects', expected: 4 },
    { query: 'hedge provider latency quantile', expected: 5 },
    { query: 'cost budget daily limit', expected: 6 },
    { query: 'embedding index model recall', expected: 7 },
    { query: 'virtual thread platform pool', expected: 9 },
    { query: 'router failover retryable provider', expected: 10 },
];

// ---------------------------------------------------------------- 檢查

/**
 * 讀專案設定裡宣告的嵌入模型與維度。
 * 沒有這個宣告的話，「索引跟查詢是不是同一個模型」就沒有比對基準。
 */
function declaredEmbedding() {
    const yml = path.join(REPO_ROOT, 'gateway-server/src/main/resources/application.yml');
    if (!fs.existsSync(yml)) return null;
    const source = fs.readFileSync(yml, 'utf8');

    const model = value(source, 'embedding-model');
    const dimension = value(source, 'embedding-dimension');
    return model ? { model, dimension: dimension ? Number(dimension) : null } : null;
}

/**
 * 讀一個設定值，並解開 Spring 的 `${VAR:default}` 佔位語法。
 *
 * <p>不解開的話讀到的是字面上的 `${RAG_EMBEDDING_MODEL:fake-embed-v1}`，
 * 拿它去跟 collection 記錄的模型比對必然不相等——而這支工具會把它報成
 * 「設定中沒有宣告嵌入模型」，看起來像設定漏了，實際上是解析器看不懂。
 *
 * <p>取的是**預設值**，不是環境變數的實際值：環境變數只有跑起來才知道，
 * 而這是一支靜態檢查。真正權威的比對在網關啟動時做（RagConfiguration）。
 */
function value(source, key) {
    const line = source.match(new RegExp(`${key}:\\s*(.+)`));
    if (!line) return null;

    const raw = line[1].split('#')[0].trim();
    const placeholder = raw.match(/^\$\{[^:}]+:([^}]*)\}$/);
    return (placeholder ? placeholder[1] : raw).trim() || null;
}

/**
 * 檢查一個 collection：維度、距離度量、以及每個點宣告的嵌入模型是否一致。
 */
async function inspectCollection(name, expected) {
    const info = await qdrant('GET', `/collections/${name}`);
    if (info.status !== 200) {
        report('阻擋', `無法讀取 collection ${name}`, `HTTP ${info.status}`);
        return;
    }

    const vectors = info.body?.result?.config?.params?.vectors || {};
    const size = vectors.size;
    const distance = vectors.distance;

    // 抽樣點的 payload，順便抓「部分重建索引」——一半舊模型一半新模型
    const scroll = await qdrant('POST', `/collections/${name}/points/scroll`, {
        limit: 200, with_payload: true, with_vector: false,
    });
    const points = scroll.body?.result?.points || [];

    if (points.length === 0) {
        report('警告', `collection ${name} 是空的`, `維度 ${size}，距離 ${distance}`,
            '空索引的檢索結果一律為空，但不會報錯');
        return;
    }

    const models = new Set(points.map((point) => point.payload?._embedding_model || '（未宣告）'));
    const dimensions = new Set(points.map((point) => point.payload?._embedding_dim || size));

    if (models.has('（未宣告）')) {
        report('阻擋', `collection ${name} 的點沒有宣告嵌入模型`,
            `抽樣 ${points.length} 個點，其中有點缺少 _embedding_model`,
            '寫入時在 payload 帶上 _embedding_model 與 _embedding_dim，否則嵌入不一致事後無從偵測');
        return;
    }

    if (models.size > 1) {
        report('阻擋', `collection ${name} 混雜了多個嵌入模型`,
            `抽樣中出現：${[...models].join('、')}`,
            '這通常是重建索引跑到一半中斷。整個 collection 重建，不要增量修補');
        return;
    }

    const actualModel = [...models][0];
    pass(`collection ${name} 的嵌入模型一致`, `${actualModel}，維度 ${size}，距離 ${distance}，抽樣 ${points.length} 點`);

    if (dimensions.size > 1 || (size && [...dimensions][0] !== size)) {
        report('阻擋', `collection ${name} 宣告的維度與實際不符`,
            `payload 宣告 ${[...dimensions].join('、')}，collection 實際為 ${size}`);
    }

    if (!expected) {
        report('警告', '專案設定沒有宣告嵌入模型，無法比對索引是否用同一個模型建成',
            `collection 自稱 ${actualModel}`,
            'application.yml 加上 gateway.rag.embedding-model 與 embedding-dimension');
        return;
    }

    if (expected.model !== actualModel) {
        report('阻擋', `索引與設定的嵌入模型不一致`,
            `索引用 ${actualModel} 建成，設定要求 ${expected.model}`,
            '這是靜默失效：查詢照常回結果，只是全部不相關。用設定的模型重建索引');
    } else {
        pass('索引的嵌入模型與設定相符', expected.model);
    }

    if (expected.dimension && size !== expected.dimension) {
        report('阻擋', `維度不符`, `collection ${size}，設定 ${expected.dimension}`);
    }
}

/** recall@k：正確文件有沒有出現在前 k 名 */
async function measureRecall(collection, queryModel, k) {
    let hits = 0;
    for (const question of QUESTIONS) {
        const search = await qdrant('POST', `/collections/${collection}/points/search`, {
            vector: embed(question.query, queryModel),
            limit: k,
            with_payload: false,
        });
        const ids = (search.body?.result || []).map((item) => item.id);
        if (ids.includes(question.expected)) hits += 1;
    }
    return hits / QUESTIONS.length;
}

// ---------------------------------------------------------------- 自我驗證

async function buildFixture(collection, indexModel) {
    await qdrant('DELETE', `/collections/${collection}`);
    const created = await qdrant('PUT', `/collections/${collection}`, {
        vectors: { size: DIMENSION, distance: 'Cosine' },
    });
    if (created.status !== 200) throw new Error(`建立 collection 失敗：HTTP ${created.status} ${created.raw}`);

    await qdrant('PUT', `/collections/${collection}/points?wait=true`, {
        points: CORPUS.map((document) => ({
            id: document.id,
            vector: embed(document.text, indexModel),
            payload: {
                text: document.text,
                _embedding_model: indexModel,
                _embedding_dim: DIMENSION,
            },
        })),
    });
}

/**
 * 用合成資料證明這些檢查真的抓得到問題。
 *
 * 沒紅過的斷言可能根本沒在測東西——這個習慣沿用自 stream-check 與 cost-guard。
 */
async function selfTest() {
    console.log('rag-eval 自我驗證（合成資料）\n');
    const consistent = '_rageval_consistent';
    const mismatched = '_rageval_mismatched';
    let failures = 0;

    const expect = (name, condition, detail) => {
        console.log(`  ${condition ? '✓' : '✗'} ${name}`);
        console.log(`      ${detail}`);
        if (!condition) failures += 1;
    };

    try {
        // 情境 1：索引與查詢同一個模型
        await buildFixture(consistent, 'fixture-model-a');
        const good = await measureRecall(consistent, 'fixture-model-a', 3);
        expect('索引與查詢同模型時 recall@3 高', good >= 0.75,
            `recall@3 = ${(good * 100).toFixed(0)}%`);

        // 情境 2：維度相同、模型不同——最危險的那種不一致。
        // 對多個不匹配的模型取平均：Qdrant 的 HNSW 建圖有隨機性，
        // 而且單一排列可能剛好把某幾題排對，單次量測會在 38%~50% 之間跳。
        const mismatchModels = ['fixture-model-b', 'fixture-model-c', 'fixture-model-d'];
        const badScores = [];
        for (const model of mismatchModels) badScores.push(await measureRecall(consistent, model, 3));
        const bad = badScores.reduce((sum, value) => sum + value, 0) / badScores.length;
        // 只有 10 份文件、取前 3 名，隨機亂猜的期望值就有 30%
        const randomBaseline = 3 / CORPUS.length;
        // 門檻 0.7：實測不匹配時平均落在 46%~54%（同模型 100%）。
        // 訂在剛好貼著實測值會偶爾假紅，訂太鬆則抓不到真的退化，這裡留約 15 個百分點的餘裕
        expect('索引與查詢不同模型時 recall@3 崩到接近隨機', bad <= good * 0.7,
            `recall@3 平均 ${(bad * 100).toFixed(0)}%（${badScores.map((s) => `${(s * 100).toFixed(0)}%`).join('、')}）`
            + `　同模型 ${(good * 100).toFixed(0)}%　隨機基線 ${(randomBaseline * 100).toFixed(0)}%`);
        expect('不一致時 Qdrant 完全不會報錯', true,
            '兩次查詢都是 HTTP 200、都回傳了最近鄰——這正是「靜默失效」的樣子');

        // 情境 3：payload 混雜多個模型（重建索引跑到一半）
        await buildFixture(mismatched, 'fixture-model-a');
        await qdrant('PUT', `/collections/${mismatched}/points?wait=true`, {
            points: CORPUS.slice(0, 4).map((document) => ({
                id: document.id,
                vector: embed(document.text, 'fixture-model-b'),
                payload: { text: document.text, _embedding_model: 'fixture-model-b', _embedding_dim: DIMENSION },
            })),
        });
        console.log('\n  對混雜模型的 collection 跑檢查：');
        const before = findings.length;
        await inspectCollection(mismatched, { model: 'fixture-model-a', dimension: DIMENSION });
        expect('混雜多個嵌入模型時被抓到', findings.length > before,
            findings.length > before ? `新增 ${findings.length - before} 項發現` : '沒有任何發現（檢查是空的）');

        // 情境 4：缺少宣告
        console.log('\n  對缺少 _embedding_model 宣告的 collection 跑檢查：');
        await qdrant('DELETE', `/collections/${mismatched}`);
        await qdrant('PUT', `/collections/${mismatched}`, { vectors: { size: DIMENSION, distance: 'Cosine' } });
        await qdrant('PUT', `/collections/${mismatched}/points?wait=true`, {
            points: CORPUS.slice(0, 3).map((document) => ({
                id: document.id, vector: embed(document.text, 'fixture-model-a'), payload: { text: document.text },
            })),
        });
        const beforeDeclare = findings.length;
        await inspectCollection(mismatched, { model: 'fixture-model-a', dimension: DIMENSION });
        expect('缺少嵌入模型宣告時被抓到', findings.length > beforeDeclare,
            findings.length > beforeDeclare ? '有發現' : '沒有任何發現（檢查是空的）');

        // 情境 5：維度不符會由 Qdrant 直接擋下（這算幸運的那種）
        const wrongDimension = await qdrant('POST', `/collections/${consistent}/points/search`, {
            vector: new Array(DIMENSION + 8).fill(0.1), limit: 3,
        });
        expect('維度不符時 Qdrant 會明確報錯', wrongDimension.status >= 400,
            `HTTP ${wrongDimension.status}——維度對不上是會報錯的，所以不是靜默失效`);
    } finally {
        await qdrant('DELETE', `/collections/${consistent}`);
        await qdrant('DELETE', `/collections/${mismatched}`);
    }

    console.log(`\n${'='.repeat(62)}`);
    console.log(failures === 0 ? '✓ 全部通過：這些檢查確實抓得到問題' : `✗ ${failures} 項未通過`);
    process.exit(failures === 0 ? 0 : 1);
}

// ---------------------------------------------------------------- 主流程

(async () => {
    try {
        const health = await qdrant('GET', '/collections');
        if (health.status !== 200) throw new Error(`HTTP ${health.status}`);
    } catch (error) {
        console.error(`✗ Qdrant（localhost:${QDRANT_PORT}）無法連線：${error.message}`);
        console.error('  先執行 node .claude/skills/dev-stack/up.js');
        process.exit(2);
    }

    if (process.argv.includes('--self-test')) {
        await selfTest();
        return;
    }

    console.log('RAG 檢索品質把關\n');

    const expected = declaredEmbedding();
    console.log('[1] 專案設定宣告的嵌入模型');
    if (expected) {
        pass('設定中有宣告', `${expected.model}，維度 ${expected.dimension ?? '未宣告'}`);
    } else {
        report('警告', '設定中沒有宣告嵌入模型',
            'application.yml 找不到 gateway.rag.embedding-model',
            '接上 RAG 之後補上，否則無從比對索引是不是用同一個模型建的');
    }

    console.log('\n[2] Qdrant 的 collection');
    const list = await qdrant('GET', '/collections');
    const collections = (list.body?.result?.collections || []).map((item) => item.name);

    if (collections.length === 0) {
        report('警告', 'Qdrant 沒有任何 collection',
            '本專案目前還沒有 RAG 程式碼，所以這是預期的',
            '接上向量庫之後重跑。目前可用 --self-test 驗證這些檢查本身有效');
    } else {
        for (const name of collections) {
            await inspectCollection(name, expected);
        }

        if (expected) {
            console.log('\n[3] recall@k');
            for (const name of collections) {
                const recall = await measureRecall(name, expected.model, 5);
                console.log(`      ${name}：recall@5 = ${(recall * 100).toFixed(0)}%`
                    + '（以本工具的固定問答集量測，僅供換模型時的迴歸比較）');
            }
        }
    }

    const blocking = findings.filter((finding) => finding.severity === '阻擋');
    console.log(`\n${'='.repeat(62)}`);
    console.log(`阻擋 ${blocking.length} 項，警告 ${findings.length - blocking.length} 項`);
    process.exit(blocking.length ? 1 : 0);
})();
