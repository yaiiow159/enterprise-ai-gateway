#!/usr/bin/env node
/**
 * 建立向量索引。
 *
 * 為什麼是獨立的 CLI 而不是網關的一個端點：
 *   建索引是完全不同的生命週期——批次、慢、要決定分塊策略、要能重跑。
 *   放進網關會讓網關變成資料管線，而且索引任務的失敗會影響線上請求。
 *
 * **這支腳本負責維護 `rag-eval` 定義的契約**：每個 point 的 payload 必須帶
 * `_embedding_model` 與 `_embedding_dim`。Qdrant 原生不記錄索引是用哪個模型建的，
 * 不記的話，「用 A 模型建索引、用 B 模型查詢」這個災難事後根本無從偵測——
 * 檢索照常回傳最近鄰，只是全部不相關。
 *
 * 用法：
 *   node tools/rag-index.js --input docs/kb.jsonl
 *   node tools/rag-index.js --input docs/kb.jsonl --collection docs --model fake-embed-v1
 *   node tools/rag-index.js --collection docs --drop        只刪除，不重建
 *
 * 輸入格式（JSONL，每行一筆）：
 *   {"id": 1, "text": "..."}
 */

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

function arg(name, fallback) {
    const index = process.argv.indexOf(name);
    return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const OPTIONS = {
    input: arg('--input', null),
    collection: arg('--collection', 'docs'),
    model: arg('--model', process.env.RAG_EMBEDDING_MODEL || 'fake-embed-v1'),
    embeddingUrl: arg('--embedding-url', process.env.RAG_EMBEDDING_URL || 'http://localhost:9101'),
    qdrantUrl: arg('--qdrant-url', process.env.QDRANT_URL || 'http://localhost:6333'),
    distance: arg('--distance', 'Cosine'),
    batchSize: Number(arg('--batch-size', 32)),
    drop: process.argv.includes('--drop'),
};

function request(base, method, urlPath, body) {
    return new Promise((resolve, reject) => {
        const url = new URL(urlPath, base);
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = {};
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const req = http.request({
            host: url.hostname, port: url.port, path: url.pathname + url.search, method, headers, timeout: 60000,
        }, (response) => {
            let data = '';
            response.on('data', (chunk) => { data += chunk; });
            response.on('end', () => {
                let parsed = null;
                try { parsed = JSON.parse(data); } catch { /* 保留原文 */ }
                resolve({ status: response.statusCode, body: parsed, raw: data });
            });
        });
        req.on('timeout', () => { req.destroy(); reject(new Error(`逾時：${method} ${urlPath}`)); });
        req.on('error', reject);
        if (payload) req.write(payload);
        req.end();
    });
}

async function embed(texts) {
    const response = await request(OPTIONS.embeddingUrl, 'POST', '/v1/embeddings',
        { model: OPTIONS.model, input: texts });
    if (response.status !== 200) {
        throw new Error(`嵌入呼叫失敗 HTTP ${response.status}：${response.raw.slice(0, 200)}`);
    }
    // 以回應回報的模型為準，不要用送出去的那個——真 Provider 可能做了對映或降級，
    // 而寫進 payload 的必須是**實際用來產生這些向量的模型**
    return { model: response.body.model || OPTIONS.model, vectors: response.body.data.map((item) => item.embedding) };
}

function readDocuments(file) {
    const absolute = path.resolve(file);
    if (!fs.existsSync(absolute)) throw new Error(`找不到輸入檔 ${absolute}`);

    const documents = [];
    fs.readFileSync(absolute, 'utf8').split('\n').forEach((line, index) => {
        const trimmed = line.trim();
        if (!trimmed) return;
        let parsed;
        try {
            parsed = JSON.parse(trimmed);
        } catch {
            throw new Error(`第 ${index + 1} 行不是合法的 JSON`);
        }
        if (!parsed.text) throw new Error(`第 ${index + 1} 行缺少 text 欄位`);
        documents.push({ id: parsed.id ?? documents.length + 1, text: String(parsed.text), meta: parsed.meta });
    });
    return documents;
}

(async () => {
    if (OPTIONS.drop) {
        await request(OPTIONS.qdrantUrl, 'DELETE', `/collections/${OPTIONS.collection}`);
        console.log(`✓ 已刪除 collection「${OPTIONS.collection}」`);
        if (!OPTIONS.input) return;
    }
    if (!OPTIONS.input) {
        console.error('✗ 需要 --input <jsonl 檔案>');
        process.exit(2);
    }

    const documents = readDocuments(OPTIONS.input);
    console.log(`讀入 ${documents.length} 筆文件，嵌入模型 ${OPTIONS.model}`);

    // 先嵌一筆問出實際維度，不要用設定值去建 collection——
    // 設定值寫錯的話會建出一個維度不符的 collection，而錯誤要到查詢時才出現
    const probe = await embed([documents[0].text]);
    const dimension = probe.vectors[0].length;
    console.log(`實際維度 ${dimension}（由上游回應決定，非設定值）`);

    // **整個重建，不做增量修補。** 增量的話舊模型的點會留下來，
    // 變成「一半舊模型、一半新模型」——那種狀態比整個索引錯掉更難發現，
    // 因為檢索結果會「有時候對、有時候不對」
    await request(OPTIONS.qdrantUrl, 'DELETE', `/collections/${OPTIONS.collection}`);
    const created = await request(OPTIONS.qdrantUrl, 'PUT', `/collections/${OPTIONS.collection}`,
        { vectors: { size: dimension, distance: OPTIONS.distance } });
    if (created.status !== 200) {
        throw new Error(`建立 collection 失敗 HTTP ${created.status}：${created.raw.slice(0, 200)}`);
    }

    let indexed = 0;
    for (let offset = 0; offset < documents.length; offset += OPTIONS.batchSize) {
        const batch = documents.slice(offset, offset + OPTIONS.batchSize);
        const { model, vectors } = await embed(batch.map((document) => document.text));

        const upsert = await request(OPTIONS.qdrantUrl, 'PUT',
            `/collections/${OPTIONS.collection}/points?wait=true`, {
                points: batch.map((document, index) => ({
                    id: document.id,
                    vector: vectors[index],
                    payload: {
                        text: document.text,
                        ...(document.meta ? { meta: document.meta } : {}),
                        // 契約：沒有這兩個欄位，嵌入不一致就無從偵測
                        _embedding_model: model,
                        _embedding_dim: vectors[index].length,
                    },
                })),
            });
        if (upsert.status !== 200) {
            throw new Error(`寫入失敗 HTTP ${upsert.status}：${upsert.raw.slice(0, 200)}`);
        }
        indexed += batch.length;
        process.stdout.write(`\r  已索引 ${indexed}/${documents.length}`);
    }

    console.log(`\n✓ collection「${OPTIONS.collection}」建立完成`);
    console.log(`  模型 ${probe.model}，維度 ${dimension}，距離 ${OPTIONS.distance}，${indexed} 筆`);
    console.log('\n下一步：確認網關的設定與這個索引一致，否則啟動時會被擋下');
    console.log(`  gateway.rag.embedding-model: ${probe.model}`);
    console.log(`  gateway.rag.embedding-dimension: ${dimension}`);
    console.log(`  gateway.rag.collection: ${OPTIONS.collection}`);
})().catch((error) => {
    console.error(`\n✗ ${error.message}`);
    process.exit(1);
});
