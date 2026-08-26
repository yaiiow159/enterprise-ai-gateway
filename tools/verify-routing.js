#!/usr/bin/env node
/**
 * 網關路由／對沖／取消的端到端驗證。
 *
 * 每個情境都在「重啟後的乾淨統計」上跑，否則前一個情境留下的延遲樣本與失敗紀錄
 * 會改變路由順序，讓斷言看起來過了或沒過都不可信。
 */
'use strict';

const { spawn, execFileSync } = require('node:child_process');
const http = require('node:http');
const path = require('node:path');

const REPO = process.env.REPO || path.resolve(__dirname, '..');
const JAR = path.join(REPO, 'gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar');
const GATEWAY = 8090;
const FAKES = { 'fake-fast': 9101, 'fake-slow': 9102, 'fake-flaky': 9103 };

let child = null;
const results = [];

// ---------- 基礎工具 ----------

function request(port, method, urlPath, body, extraHeaders) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = Object.assign({}, extraHeaders);
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const req = http.request({ host: 'localhost', port, path: urlPath, method, headers }, res => {
            let data = '';
            res.on('data', chunk => { data += chunk; });
            res.on('end', () => {
                let parsed = null;
                try { parsed = JSON.parse(data); } catch { /* 非 JSON 就保留原文 */ }
                resolve({ status: res.statusCode, body: parsed, raw: data });
            });
        });
        req.on('error', reject);
        if (payload) req.write(payload);
        req.end();
    });
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function resetFakes(config) {
    for (const port of Object.values(FAKES)) {
        // 順序不能反：reset 會把設定一併還原成環境變數的預設值
        await request(port, 'POST', '/_fake/reset');
        await request(port, 'PUT', '/_fake/config', config[port] || { errorRate: 0, ttftMs: 100 });
    }
}

async function fakeStats() {
    const out = {};
    for (const [name, port] of Object.entries(FAKES)) {
        out[name] = (await request(port, 'GET', '/_fake/stats')).body;
    }
    return out;
}

function totalRequests(stats) {
    return Object.values(stats).reduce((sum, s) => sum + s.requests, 0);
}

function killGateway() {
    if (!child) return;
    try { execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' }); } catch { /* 已結束 */ }
    child = null;
}

async function restartGateway() {
    killGateway();
    await sleep(1500);
    child = spawn('java', ['-jar', JAR], { stdio: 'ignore', detached: false });
    for (let i = 0; i < 90; i++) {
        try {
            const health = await request(GATEWAY, 'GET', '/actuator/health');
            if (health.status === 200) return;
        } catch { /* 還沒起來 */ }
        await sleep(500);
    }
    throw new Error('網關在 45 秒內沒有就緒');
}

function chat(overrides) {
    return request(GATEWAY, 'POST', '/v1/chat/completions', Object.assign({
        model: 'any',
        messages: [{ role: 'user', content: 'hi' }],
        max_tokens: 5,
    }, overrides));
}

function check(name, passed, detail) {
    results.push({ name, passed, detail });
    console.log(`  ${passed ? 'PASS' : 'FAIL'}  ${name}`);
    if (detail) console.log(`        ${detail}`);
}

// ---------- 情境 ----------

async function scenarioRoutingOrder() {
    console.log('\n[1] 冷啟動時的主要 Provider 應等於設定檔的第一家');
    await restartGateway();
    await resetFakes({});
    const response = await chat();
    const provider = response.body?.x_gateway?.provider;
    check('冷啟動路由順序 = 註冊順序',
        provider === 'fake-fast',
        `實際主要 Provider: ${provider}`);
}

async function scenarioFailover() {
    console.log('\n[2] 主要 Provider 快速失敗（429）應切換到健康的備援');
    await restartGateway();
    await resetFakes({ 9101: { errorRate: 1, errorStatus: 429, ttftMs: 80 } });
    const response = await chat();
    const stats = await fakeStats();
    check('429 之後改由備援服務',
        response.status === 200 && response.body?.x_gateway?.provider === 'fake-slow',
        `HTTP ${response.status}，服務者 ${response.body?.x_gateway?.provider}`);
    check('失敗切換不會多打用不到的第三家',
        stats['fake-flaky'].requests === 0,
        `fake-flaky 收到 ${stats['fake-flaky'].requests} 次請求`);
}

async function scenarioNonRetryable() {
    console.log('\n[3] 請求本身有問題（400）不該換一家重試');
    await restartGateway();
    const all400 = { errorRate: 1, errorStatus: 400, ttftMs: 80 };
    await resetFakes({ 9101: all400, 9102: all400, 9103: all400 });
    const response = await chat();
    const stats = await fakeStats();
    check('400 直接回傳，不做失敗切換',
        response.status === 400 && totalRequests(stats) === 1,
        `HTTP ${response.status}，全體共收到 ${totalRequests(stats)} 次請求（期望 1）`);
}

async function scenarioAllDown() {
    console.log('\n[4] 全部 Provider 都掛（503）應試完所有候選才放棄');
    await restartGateway();
    const all503 = { errorRate: 1, errorStatus: 503, ttftMs: 50 };
    await resetFakes({ 9101: all503, 9102: all503, 9103: all503 });
    const response = await chat();
    const stats = await fakeStats();
    check('三家都嘗試過才回傳失敗',
        response.status >= 500 && totalRequests(stats) === 3,
        `HTTP ${response.status}，全體共收到 ${totalRequests(stats)} 次請求（期望 3）`);
}

async function scenarioHedging() {
    console.log('\n[5] 主要 Provider 很慢時，延遲對沖應補發並由較快者勝出');
    await restartGateway();
    await resetFakes({ 9101: { errorRate: 0, ttftMs: 3000 } });
    const startedAt = Date.now();
    const response = await chat();
    const elapsed = Date.now() - startedAt;
    const stats = await fakeStats();
    check('較快的備援勝出',
        response.body?.x_gateway?.provider === 'fake-slow',
        `服務者 ${response.body?.x_gateway?.provider}，耗時 ${elapsed}ms`);
    check('慢的主要 Provider 被實際中止（不再產生 token）',
        stats['fake-fast'].cancelled === 1 && stats['fake-fast'].completedStreams === 0,
        `fake-fast 取消 ${stats['fake-fast'].cancelled}，完成 ${stats['fake-fast'].completedStreams}`);
    check('對沖比等主要 Provider 更快返回',
        elapsed < 3000,
        `耗時 ${elapsed}ms，主要 Provider 的 TTFT 是 3000ms`);
}

async function scenarioLoserNotPenalised() {
    console.log('\n[6] 對沖落敗者不該被記成故障（落敗只代表這次比較慢）');
    await restartGateway();
    // 第一次：fake-fast 很慢，會落敗並被中止
    await resetFakes({ 9101: { errorRate: 0, ttftMs: 3000 } });
    await chat();
    // 第二次：三家一樣快。若落敗被記成故障，fake-fast 會被排到後面
    await resetFakes({});
    const response = await chat();
    const provider = response.body?.x_gateway?.provider;
    check('落敗後仍能回到主要位置',
        provider === 'fake-fast',
        `第二次由 ${provider} 服務（期望 fake-fast）`);
}

async function scenarioCancellation() {
    console.log('\n[7] 客戶端中途斷線，上游請求應被取消');
    await restartGateway();
    await resetFakes({ 9101: { errorRate: 0, ttftMs: 50, tokenDelayMs: 60 } });

    await new Promise(resolve => {
        const payload = JSON.stringify({
            model: 'any', messages: [{ role: 'user', content: 'hi' }], max_tokens: 100, stream: true,
        });
        const req = http.request({
            host: 'localhost', port: GATEWAY, path: '/v1/chat/completions', method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
        }, res => {
            let chunks = 0;
            res.on('data', () => { if (++chunks === 3) { req.destroy(); resolve(); } });
        });
        req.on('error', () => resolve());
        req.end(payload);
    });

    await sleep(2000);
    const stats = await fakeStats();
    const fast = stats['fake-fast'];
    check('上游收到取消',
        fast.cancelled === 1 && fast.completedStreams === 0,
        `取消 ${fast.cancelled}，完成 ${fast.completedStreams}`);
    check('取消後停止產生 token',
        fast.completionTokens < 100,
        `上限 100 個 token，實際只產生 ${fast.completionTokens} 個`);
}

async function scenarioStreamingIncremental() {
    console.log('\n[8] SSE 應逐段抵達，而非整段生成完才一次吐出');
    await restartGateway();
    await resetFakes({ 9101: { errorRate: 0, ttftMs: 100, tokenDelayMs: 40 } });

    const timings = await new Promise((resolve, reject) => {
        const payload = JSON.stringify({
            model: 'any', messages: [{ role: 'user', content: 'hi' }], max_tokens: 6, stream: true,
        });
        const startedAt = Date.now();
        const arrivals = [];
        let buffer = '';
        const req = http.request({
            host: 'localhost', port: GATEWAY, path: '/v1/chat/completions', method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
        }, res => {
            res.on('data', chunk => {
                buffer += chunk;
                let index;
                while ((index = buffer.indexOf('\n\n')) >= 0) {
                    const raw = buffer.slice(0, index).trim();
                    buffer = buffer.slice(index + 2);
                    if (raw.startsWith('data:')) arrivals.push({ at: Date.now() - startedAt, data: raw.slice(5).trim() });
                }
            });
            res.on('end', () => resolve({ arrivals, contentType: res.headers['content-type'] }));
        });
        req.on('error', reject);
        req.end(payload);
    });

    const { arrivals, contentType } = timings;
    const ttft = arrivals.length ? arrivals[0].at : -1;
    const last = arrivals.length ? arrivals[arrivals.length - 1].at : -1;
    check('content-type 是 text/event-stream',
        String(contentType).includes('text/event-stream'),
        `實際: ${contentType}`);
    check('串流以 [DONE] 結束',
        arrivals.length > 0 && arrivals[arrivals.length - 1].data === '[DONE]',
        `最後一個事件: ${arrivals.length ? arrivals[arrivals.length - 1].data : '（無）'}`);
    check('chunk 是逐段抵達而非一次到齊',
        last - ttft > 50,
        `首個事件 ${ttft}ms，最後一個 ${last}ms，分佈區間 ${last - ttft}ms`);
}

// ---------- 主流程 ----------

(async () => {
    try {
        await scenarioRoutingOrder();
        await scenarioFailover();
        await scenarioNonRetryable();
        await scenarioAllDown();
        await scenarioHedging();
        await scenarioLoserNotPenalised();
        await scenarioCancellation();
        await scenarioStreamingIncremental();
    } catch (error) {
        console.error('\n驗證中斷:', error.message);
        results.push({ name: '驗證流程本身', passed: false, detail: error.message });
    } finally {
        killGateway();
    }

    const failed = results.filter(r => !r.passed);
    console.log(`\n${'='.repeat(60)}`);
    console.log(`共 ${results.length} 項檢查，通過 ${results.length - failed.length}，失敗 ${failed.length}`);
    if (failed.length) {
        console.log('\n失敗項目：');
        failed.forEach(r => console.log(`  - ${r.name}：${r.detail || ''}`));
    }
    process.exit(failed.length ? 1 : 0);
})();
