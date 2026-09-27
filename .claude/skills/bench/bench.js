#!/usr/bin/env node
/**
 * LLM 網關壓測：TTFT 分位數、tokens/s、以及虛擬執行緒與平台執行緒的對照
 *
 * 與一般 QPS 壓測的差別：
 *   LLM 網關的每個請求都是長連線、長時間、低 CPU。「每秒完成幾個請求」不是重點，
 *   **「同時能維持幾條串流」與「第一個字多久出現」**才是使用者真正感受到的東西。
 *   所以這裡量的是 TTFT 分位數與並行串流下的穩定度，不是吞吐量。
 *
 * 這支腳本的每一個設計都是被某次量錯逼出來的：
 *
 *   1. **每一條串流都要驗證**（狀態碼 200、真的收到 token、以 [DONE] 收尾）。
 *      開發過程中假 Provider 中途整組消失，而當時的探測腳本只看「有沒有收到位元組」，
 *      於是把 25 條全部失敗的請求報成「成功 25，失敗 0」，還從中得出了錯誤的結論。
 *      不驗證回應的壓測，數字越漂亮越危險。
 *
 *   2. **暖機是必須的，而且各組要一致**。冷啟動的 JVM 在同樣負載下 TTFT 是 1188ms，
 *      暖機後是 175ms——差了近 7 倍。沒暖機的對照組量到的是 JIT，不是架構。
 *
 *   3. **要有基準線（直接打上游，不經網關）**。沒有它就無法分辨
 *      「網關慢」與「假 Provider 慢」或「壓測端自己是瓶頸」。
 *
 *   4. **量測端的 event loop 抖動要一起量，而且它就是量測解析度**。
 *      小於抖動的差距不能宣稱測得出來。門檻要用「佔訊號的比例」而不是絕對值——
 *      第一版用固定 50ms，結果把表現最好的那一組判成無效，因為虛擬執行緒讓
 *      100 條串流幾乎同時開始吐 token，壓測端瞬間收到爆量；平台執行緒因為排隊
 *      反而讓抵達變平順、抖動更低。固定門檻等於獎勵「上游被卡住」的那一組。
 *
 *   5. **Windows 上 child.kill() 殺不乾淨 JVM**。殘留的舊行程會佔住連接埠，
 *      下一組啟動失敗卻靜靜地退出，於是負載全部打到「上一組」——變成自己跟自己比。
 *      所以用 taskkill /T /F，並在啟動後確認行程還活著、連接埠真的換人了。
 *
 * 用法：
 *   node .claude/skills/bench/bench.js
 *   node .claude/skills/bench/bench.js --concurrency 100 --rounds 3
 *   node .claude/skills/bench/bench.js --arms direct,virtual
 */

'use strict';

const { spawn, spawnSync, fork } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { gatewayAuthHeaders, ensureLocalGatewayKey } = require('../../../tools/gateway-auth');

const REPO_ROOT = path.resolve(__dirname, '../../..');
const JAR = path.join(REPO_ROOT, 'gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar');
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8090);
const FAKE_PORTS = [9101, 9102, 9103];

// ------------------------------------------------------------ 參數

function arg(name, fallback) {
    const index = process.argv.indexOf(name);
    return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const OPTIONS = {
    concurrency: Number(arg('--concurrency', 100)),
    rounds: Number(arg('--rounds', 3)),
    warmupRequests: Number(arg('--warmup', 80)),
    workers: Number(arg('--workers', Math.min(4, Math.max(2, os.cpus().length - 2)))),
    /**
     * 上游首字延遲刻意設得長：請求執行緒在 ChatController 裡會阻塞到第一個 token
     * （見 CLAUDE.md 技術決定 6），這段時間正是平台執行緒池被佔住的時間。
     * 設太短的話兩組看起來一樣，不是因為沒有差別，而是因為差別還沒被逼出來。
     */
    upstreamTtftMs: Number(arg('--upstream-ttft', 800)),
    tokens: Number(arg('--tokens', 20)),
    tokenDelayMs: Number(arg('--token-delay', 10)),
    /** 平台執行緒組的 Tomcat 執行緒上限。要明顯小於並行數，天花板才看得見 */
    platformThreads: Number(arg('--platform-threads', 20)),
    arms: arg('--arms', 'direct,virtual,platform').split(','),
    /**
     * 量測端 event loop 抖動超過該輪 TTFT 的這個比例，就宣告該輪無效。
     *
     * 為什麼用比例而不是絕對值：第一版用固定 50ms，結果**把表現最好的那一組判成無效**。
     * 原因是抖動本身就是被測特性造成的——虛擬執行緒讓 100 條串流幾乎同時開始吐 token，
     * 壓測端瞬間收到爆量；平台執行緒因為排隊反而讓抵達變平順、抖動更低。
     * 用固定門檻等於獎勵「上游被卡住」的那一組。
     *
     * 抖動真正的意義是**量測解析度**：小於抖動的差距不能宣稱測得出來。
     * 只有當抖動大到與訊號同一個數量級時，那一輪才是真的不成立。
     */
    maxLagRatio: Number(arg('--max-lag-ratio', 0.25)),
};

// ------------------------------------------------------------ 壓測 worker

/**
 * 每個 worker 開 N 條並行串流，逐條記錄 TTFT 與結果，並回報自己的 event loop 延遲。
 *
 * 用多程序而不是單程序開更多連線：單一 Node 程序的 event loop 一旦塞住，
 * 量到的 TTFT 就變成自己的排隊延遲。多程序不能消除這個問題，但能把它推遠，
 * 而回報的 lag 讓我們知道有沒有推得夠遠。
 */
function runWorker() {
    const config = JSON.parse(process.argv[process.argv.indexOf('--config') + 1]);
    const records = [];

    let maxLag = 0;
    let lastTick = Date.now();
    const lagTimer = setInterval(() => {
        const now = Date.now();
        maxLag = Math.max(maxLag, now - lastTick - 20);
        lastTick = now;
    }, 20);

    /** 一條串流。回傳的是「這條到底成不成立」，不是「有沒有收到位元組」 */
    function openStream() {
        return new Promise((resolve) => {
            const payload = JSON.stringify({
                model: 'fake-chat',
                messages: [{ role: 'user', content: 'benchmark request' }],
                max_tokens: config.tokens,
                stream: true,
            });
            const startedAt = Date.now();
            let ttft = -1;
            let tokenCount = 0;
            let sawDone = false;
            let buffer = '';

            const request = http.request({
                host: 'localhost',
                port: config.port,
                path: '/v1/chat/completions',
                method: 'POST',
                // fork 出來的負載產生器繼承 process.env，key 由主行程的 ensureLocalGatewayKey() 設好
                headers: Object.assign(gatewayAuthHeaders(),
                    { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }),
                timeout: config.timeoutMs,
            }, (response) => {
                response.on('data', (chunk) => {
                    buffer += chunk;
                    let index;
                    while ((index = buffer.indexOf('\n\n')) >= 0) {
                        const raw = buffer.slice(0, index).trim();
                        buffer = buffer.slice(index + 2);
                        if (!raw.startsWith('data:')) continue;
                        const data = raw.slice(5).trim();
                        if (data === '[DONE]') { sawDone = true; continue; }
                        if (ttft < 0) ttft = Date.now() - startedAt;
                        try {
                            if (JSON.parse(data)?.choices?.[0]?.delta?.content) tokenCount += 1;
                        } catch { /* 錯誤事件之類的非 chunk 內容 */ }
                    }
                });
                response.on('end', () => {
                    // 三個條件都要成立才算成功。少驗任何一個，失敗都會被算成成功
                    const ok = response.statusCode === 200 && tokenCount > 0 && sawDone;
                    resolve({
                        ok,
                        ttft,
                        tokenCount,
                        totalMs: Date.now() - startedAt,
                        reason: ok ? null : `status=${response.statusCode} tokens=${tokenCount} done=${sawDone}`,
                    });
                });
            });

            request.on('timeout', () => {
                request.destroy();
                resolve({ ok: false, ttft: -1, tokenCount, totalMs: Date.now() - startedAt, reason: '逾時' });
            });
            request.on('error', (error) => {
                resolve({ ok: false, ttft: -1, tokenCount, totalMs: Date.now() - startedAt, reason: error.code || 'error' });
            });
            request.end(payload);
        });
    }

    (async () => {
        const started = Date.now();
        const batch = await Promise.all(Array.from({ length: config.streams }, () => openStream()));
        records.push(...batch);
        clearInterval(lagTimer);
        process.send({ records, maxLag, wallMs: Date.now() - started });
        process.exit(0);
    })();
}

if (process.argv.includes('--worker')) {
    runWorker();
    return;
}

// ------------------------------------------------------------ 基礎工具

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function json(port, method, urlPath, body) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = gatewayAuthHeaders();
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        const request = http.request({ host: 'localhost', port, path: urlPath, method, headers, timeout: 15000 }, (response) => {
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

function percentile(values, fraction) {
    if (!values.length) return -1;
    const sorted = [...values].sort((a, b) => a - b);
    return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))];
}

const median = (values) => percentile(values, 0.5);

// ------------------------------------------------------------ 被測系統的生命週期

/**
 * Windows 上 child.kill() 殺不乾淨 JVM。
 *
 * 殘留行程會佔住 8090，下一組啟動時 bind 失敗、靜靜退出，
 * 於是負載全部打到「上一組」還活著的那個 JVM——兩組數字看起來都很正常，
 * 實際上是同一個東西跟自己比。這是最難察覺的一種量測錯誤。
 */
function killLingeringJvms() {
    if (process.platform === 'win32') {
        const list = spawnSync('powershell', ['-NoProfile', '-Command',
            "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*gateway-server*' } | ForEach-Object { $_.ProcessId }"],
            { encoding: 'utf8' });
        const pids = String(list.stdout || '').split(/\s+/).filter(Boolean);
        for (const pid of pids) spawnSync('taskkill', ['/PID', pid, '/T', '/F'], { stdio: 'ignore' });
        return pids.length;
    }
    spawnSync('pkill', ['-f', 'gateway-server'], { stdio: 'ignore' });
    return 0;
}

async function portAnswers(port) {
    try {
        const health = await json(port, 'GET', '/actuator/health');
        return health.status === 200;
    } catch {
        return false;
    }
}

let gatewayChild = null;

async function startGateway(environment, label) {
    killLingeringJvms();

    // 確認連接埠真的空出來了，否則接下來會測到殘留的那一個
    for (let i = 0; i < 40 && await portAnswers(GATEWAY_PORT); i += 1) await sleep(250);
    if (await portAnswers(GATEWAY_PORT)) {
        throw new Error(`連接埠 ${GATEWAY_PORT} 仍被佔用，無法確保測到的是新啟動的網關`);
    }

    gatewayChild = spawn('java', ['-jar', JAR], {
        env: Object.assign({}, process.env, environment),
        stdio: 'ignore',
    });

    for (let i = 0; i < 120; i += 1) {
        // 啟動失敗時 JVM 會直接退出。少了這個判斷，接下來會一路等到逾時，
        // 或者更糟——連到別的行程上
        if (gatewayChild.exitCode !== null) {
            throw new Error(`網關（${label}）啟動後隨即退出，exit code ${gatewayChild.exitCode}`);
        }
        if (await portAnswers(GATEWAY_PORT)) return;
        await sleep(500);
    }
    throw new Error(`網關（${label}）在 60 秒內未就緒`);
}

function stopGateway() {
    if (gatewayChild) {
        try { spawnSync('taskkill', ['/PID', String(gatewayChild.pid), '/T', '/F'], { stdio: 'ignore' }); } catch { /* 已結束 */ }
        gatewayChild = null;
    }
    killLingeringJvms();
}

/**
 * 網關的堆積用量，取自 **JVM 自己的指標**而不是作業系統的常駐頁數。
 *
 * 第一版用 Windows 的 `WorkingSet64`，量到 11 MB——對一個 Spring Boot 應用顯然不可能，
 * 而且兩組數字一模一樣。原因是 Windows 會主動修剪閒置行程的 working set，
 * 而量測點正好在負載結束之後，拿到的是修剪後的殘值。
 *
 * `/actuator/prometheus` 的 `jvm_memory_used_bytes{area="heap"}` 是 JVM 自己回報的，
 * 不受作業系統的頁面調度影響。取不到就回 -1，不要為了湊數字而猜。
 */
function jvmHeapMb() {
    return new Promise((resolve) => {
        const request = http.get({
            host: 'localhost', port: GATEWAY_PORT, path: '/actuator/prometheus', timeout: 5000,
        }, (response) => {
            if (response.statusCode !== 200) { response.resume(); resolve(-1); return; }
            let text = '';
            response.on('data', (chunk) => { text += chunk; });
            response.on('end', () => {
                let bytes = 0;
                for (const line of text.split('\n')) {
                    if (!line.startsWith('jvm_memory_used_bytes') || !line.includes('area="heap"')) continue;
                    const value = Number(line.slice(line.lastIndexOf(' ') + 1));
                    if (Number.isFinite(value)) bytes += value;
                }
                resolve(bytes > 0 ? Math.round(bytes / 1024 / 1024) : -1);
            });
        });
        request.on('timeout', () => { request.destroy(); resolve(-1); });
        request.on('error', () => resolve(-1));
    });
}

// ------------------------------------------------------------ 上游設定與健康檢查

async function configureFakes() {
    for (const port of FAKE_PORTS) {
        await json(port, 'POST', '/_fake/reset');
        await json(port, 'PUT', '/_fake/config', {
            errorRate: 0,
            ttftMs: OPTIONS.upstreamTtftMs,
            tokenDelayMs: OPTIONS.tokenDelayMs,
            tokens: Math.max(OPTIONS.tokens, 40),
        });
    }
}

/**
 * 上游還活著嗎。
 *
 * 每一輪前後都要確認：開發過程中假 Provider 曾經整組消失，
 * 而當時沒有這個檢查，於是量到的全是失敗，報告卻顯示一切正常。
 */
async function fakesHealthy() {
    for (const port of FAKE_PORTS) {
        try {
            const health = await json(port, 'GET', '/health');
            if (health.status !== 200) return false;
        } catch {
            return false;
        }
    }
    return true;
}

/**
 * 暖機。冷啟動的 JVM 在同樣負載下 TTFT 差了近 7 倍（1188ms vs 175ms），
 * 沒暖機的對照組量到的是 JIT 而不是架構。
 *
 * 小批並行而不是完全序列：JIT 看的是請求**次數**，而序列跑 80 次
 * 每次都要等滿一個上游延遲，光暖機就要一分多鐘。並行也更接近待測時的條件。
 */
async function warmup(port) {
    const batchSize = 8;
    for (let done = 0; done < OPTIONS.warmupRequests; done += batchSize) {
        const size = Math.min(batchSize, OPTIONS.warmupRequests - done);
        await Promise.all(Array.from({ length: size }, () =>
            json(port, 'POST', '/v1/chat/completions', {
                model: 'fake-chat', messages: [{ role: 'user', content: 'warmup' }], max_tokens: 3,
            }).catch(() => null)));  // 暖機期間的失敗不計入結果
    }
}

// ------------------------------------------------------------ 執行一輪

function runLoad(port) {
    const perWorker = Math.ceil(OPTIONS.concurrency / OPTIONS.workers);
    const config = {
        port,
        streams: perWorker,
        tokens: OPTIONS.tokens,
        // 逾時要寬鬆到不會把「正在排隊」誤判成失敗，但仍能抓到真的卡死
        timeoutMs: Math.max(30000, OPTIONS.upstreamTtftMs * 10),
    };

    const children = Array.from({ length: OPTIONS.workers }, () =>
        fork(__filename, ['--worker', '--config', JSON.stringify(config)], { stdio: 'ignore' }));

    return Promise.all(children.map((child) => new Promise((resolve) => {
        let payload = null;
        child.on('message', (message) => { payload = message; });
        child.on('exit', () => resolve(payload || { records: [], maxLag: 0, wallMs: 0 }));
    })));
}

async function measureArm(name, port) {
    const records = [];
    const rounds = [];
    const heapSamples = [];
    let invalidRounds = 0;

    for (let round = 1; round <= OPTIONS.rounds; round += 1) {
        if (!await fakesHealthy()) throw new Error('上游假 Provider 不可用，這一輪的數字不成立');

        const results = await runLoad(port);
        const roundRecords = results.flatMap((result) => result.records);
        const maxLag = Math.max(...results.map((result) => result.maxLag), 0);
        const wallMs = Math.max(...results.map((result) => result.wallMs), 0);

        if (!await fakesHealthy()) throw new Error('上游假 Provider 在這一輪中途失效，數字不成立');

        const ok = roundRecords.filter((record) => record.ok);
        const ttfts = ok.map((record) => record.ttft);
        const roundP50 = percentile(ttfts, 0.5);
        // 抖動與訊號同一個數量級時，這一輪量到的主要是壓測端自己
        const valid = roundP50 > 0 && maxLag <= roundP50 * OPTIONS.maxLagRatio;
        if (!valid) invalidRounds += 1;

        rounds.push({
            round,
            valid,
            maxLag,
            wallMs,
            total: roundRecords.length,
            ok: ok.length,
            p50: percentile(ttfts, 0.5),
            p95: percentile(ttfts, 0.95),
            p99: percentile(ttfts, 0.99),
            tokensPerSecond: wallMs > 0 ? Math.round(ok.reduce((sum, r) => sum + r.tokenCount, 0) / (wallMs / 1000)) : 0,
        });

        console.log(`    第 ${round} 輪：成功 ${ok.length}/${roundRecords.length}，`
            + `TTFT p50 ${roundP50}ms / p95 ${percentile(ttfts, 0.95)}ms，`
            + `量測解析度 ±${maxLag}ms${valid ? '' : '（與訊號同數量級，本輪無效）'}`);

        if (ok.length < roundRecords.length) {
            const reasons = new Map();
            roundRecords.filter((record) => !record.ok)
                .forEach((record) => reasons.set(record.reason, (reasons.get(record.reason) || 0) + 1));
            console.log(`      失敗原因：${[...reasons].map(([reason, count]) => `${reason} ×${count}`).join('，')}`);
        }

        records.push(...roundRecords);
        // 負載剛結束時取樣，這時堆積用量最接近尖峰
        if (name !== 'direct') heapSamples.push(await jvmHeapMb());
        await sleep(1000);
    }

    const validRounds = rounds.filter((round) => round.valid);
    const source = validRounds.length ? validRounds : rounds;

    return {
        name,
        rounds,
        invalidRounds,
        // 小於這個值的差距不能宣稱測得出來
        resolutionMs: Math.max(...source.map((round) => round.maxLag), 0),
        heapMb: heapSamples.filter((value) => value > 0).length
            ? Math.max(...heapSamples.filter((value) => value > 0)) : -1,
        successRate: records.length ? records.filter((r) => r.ok).length / records.length : 0,
        // 取各輪的中位數而不是把所有樣本混在一起：後者會讓某一輪的異常淹沒其他輪
        p50: median(source.map((round) => round.p50)),
        p95: median(source.map((round) => round.p95)),
        p99: median(source.map((round) => round.p99)),
        tokensPerSecond: median(source.map((round) => round.tokensPerSecond)),
        p95Range: [Math.min(...source.map((r) => r.p95)), Math.max(...source.map((r) => r.p95))],
    };
}

// ------------------------------------------------------------ 報告

function environmentInfo() {
    const jdk = spawnSync('java', ['-version'], { encoding: 'utf8' });
    const version = String(jdk.stderr || jdk.stdout || '').split('\n')[0].trim();
    return {
        cpu: `${os.cpus()[0]?.model?.trim()} × ${os.cpus().length}`,
        memoryGb: Math.round(os.totalmem() / 1024 / 1024 / 1024),
        platform: `${os.platform()} ${os.release()}`,
        jdk: version,
        node: process.version,
    };
}

function writeReport(arms, environment) {
    const directory = path.join(REPO_ROOT, 'docs/benchmark');
    fs.mkdirSync(directory, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const file = path.join(directory, `${stamp}.md`);

    const baseline = arms.find((arm) => arm.name === 'direct');
    const lines = [];

    lines.push(`# 網關壓測報告　${new Date().toISOString()}`, '');
    lines.push('## 執行環境', '');
    lines.push('| 項目 | 值 |', '| --- | --- |');
    lines.push(`| CPU | ${environment.cpu} |`);
    lines.push(`| 記憶體 | ${environment.memoryGb} GB |`);
    lines.push(`| 作業系統 | ${environment.platform} |`);
    lines.push(`| JDK | ${environment.jdk} |`);
    lines.push(`| Node | ${environment.node} |`, '');

    lines.push('## 負載參數', '');
    lines.push('| 參數 | 值 |', '| --- | --- |');
    lines.push(`| 並行串流數 | ${OPTIONS.concurrency}（${OPTIONS.workers} 個壓測程序）|`);
    lines.push(`| 輪數 | ${OPTIONS.rounds} |`);
    lines.push(`| 每輪前暖機 | ${OPTIONS.warmupRequests} 次請求 |`);
    lines.push(`| 上游首字延遲 | ${OPTIONS.upstreamTtftMs} ms |`);
    lines.push(`| 每次輸出 token | ${OPTIONS.tokens}（間隔 ${OPTIONS.tokenDelayMs} ms）|`);
    lines.push(`| 平台執行緒組的池上限 | ${OPTIONS.platformThreads} |`, '');

    lines.push('## 結果', '');
    lines.push('| 組別 | TTFT p50 | TTFT p95 | TTFT p99 | p95 區間 | tokens/s | 成功率 | 堆積尖峰 |');
    lines.push('| --- | --- | --- | --- | --- | --- | --- | --- |');
    for (const arm of arms) {
        lines.push(`| ${arm.name} | ${arm.p50}ms | ${arm.p95}ms | ${arm.p99}ms | `
            + `${arm.p95Range[0]}~${arm.p95Range[1]}ms | ${arm.tokensPerSecond} | `
            + `${(arm.successRate * 100).toFixed(1)}% | ${arm.heapMb > 0 ? `${arm.heapMb} MB` : '—'} |`);
    }
    lines.push('');

    if (baseline) {
        lines.push('## 歸因', '');
        lines.push('基準線（`direct`）是**不經過網關**直接打上游，它包含了壓測端與假 Provider 自身的延遲。');
        lines.push('網關造成的額外延遲要用各組減去它才有意義。', '');
        lines.push('每一組的**量測解析度**是該組觀察到的壓測端抖動：小於它的差距不能宣稱測得出來。', '');
        for (const arm of arms.filter((item) => item.name !== 'direct')) {
            const delta = arm.p95 - baseline.p95;
            const resolution = Math.max(arm.resolutionMs, baseline.resolutionMs);
            const resolvable = Math.abs(delta) > resolution;
            lines.push(`- \`${arm.name}\`：p95 ${arm.p95}ms − 基準線 ${baseline.p95}ms = **${delta >= 0 ? '+' : ''}${delta}ms**`
                + `（解析度 ±${resolution}ms）`);
            lines.push(resolvable
                ? `  → 差距大於解析度，是可以宣稱的結果。`
                : `  → **差距小於解析度，不能宣稱網關有可測得的額外延遲**，只能說「在這個負載下量不出差別」。`);
        }
        lines.push('');
    }

    lines.push('## 每一輪的原始數字', '');
    for (const arm of arms) {
        lines.push(`### ${arm.name}`, '');
        lines.push('| 輪 | 成功／總數 | p50 | p95 | p99 | tokens/s | 量測端延遲 | 有效 |');
        lines.push('| --- | --- | --- | --- | --- | --- | --- | --- |');
        for (const round of arm.rounds) {
            lines.push(`| ${round.round} | ${round.ok}/${round.total} | ${round.p50}ms | ${round.p95}ms | `
                + `${round.p99}ms | ${round.tokensPerSecond} | ${round.maxLag}ms | ${round.valid ? '是' : '否'} |`);
        }
        lines.push('');
    }

    lines.push('## 這份報告的有效性條件', '');
    lines.push('- 三組在**同一次執行、同一台機器**上取得，暖機條件相同。');
    lines.push('- 每一條串流都經過驗證（HTTP 200、確實收到 token、以 `[DONE]` 收尾）；');
    lines.push('  只要有一項不成立就計為失敗，不會被算進 TTFT 分位數。');
    lines.push(`- 量測端抖動超過該輪 TTFT ${Math.round(OPTIONS.maxLagRatio * 100)}% 的輪次標記為無效——`);
    lines.push('  抖動與訊號同數量級時，量到的主要是壓測端自己的排隊延遲。');
    lines.push('- 抖動本身即為**量測解析度**：小於它的差距不做宣稱。');
    lines.push('- 每一輪前後都確認上游假 Provider 仍然存活。');
    lines.push('- 分位數取各輪的**中位數**並附上區間，不是把所有樣本混在一起。');
    lines.push('- 堆積用量取自 JVM 自己的 `jvm_memory_used_bytes{area="heap"}`，'
        + '而非作業系統的常駐頁數——後者會被 Windows 主動修剪，量到的是殘值。');
    lines.push('');
    lines.push('> 重現方式：');
    lines.push('> ```bash');
    lines.push(`> node .claude/skills/bench/bench.js --concurrency ${OPTIONS.concurrency} `
        + `--rounds ${OPTIONS.rounds} --upstream-ttft ${OPTIONS.upstreamTtftMs} `
        + `--platform-threads ${OPTIONS.platformThreads}`);
    lines.push('> ```');

    fs.writeFileSync(file, lines.join('\n'), 'utf8');
    return file;
}

// ------------------------------------------------------------ 主流程

(async () => {
    ensureLocalGatewayKey();
    if (!fs.existsSync(JAR)) {
        console.error(`✗ 找不到 ${path.relative(REPO_ROOT, JAR)}，先執行 mvn clean install -DskipTests`);
        process.exit(2);
    }
    if (!await fakesHealthy()) {
        console.error('✗ 假 Provider 不可用，先執行 node .claude/skills/dev-stack/up.js');
        process.exit(2);
    }

    console.log('網關壓測');
    console.log(`並行 ${OPTIONS.concurrency} 條串流 × ${OPTIONS.rounds} 輪，`
        + `上游首字延遲 ${OPTIONS.upstreamTtftMs}ms，${OPTIONS.workers} 個壓測程序\n`);

    await configureFakes();
    const environment = environmentInfo();
    const arms = [];

    try {
        if (OPTIONS.arms.includes('direct')) {
            console.log('  [基準線] 直接打上游，不經過網關');
            await warmup(FAKE_PORTS[0]);
            arms.push(await measureArm('direct', FAKE_PORTS[0]));
        }

        if (OPTIONS.arms.includes('virtual')) {
            console.log('\n  [虛擬執行緒] 網關預設組態');
            await startGateway({ SPRING_THREADS_VIRTUAL_ENABLED: 'true' }, 'virtual');
            await warmup(GATEWAY_PORT);
            arms.push(await measureArm('virtual', GATEWAY_PORT));
        }

        if (OPTIONS.arms.includes('platform')) {
            console.log(`\n  [平台執行緒] Tomcat 執行緒上限 ${OPTIONS.platformThreads}`);
            await startGateway({
                SPRING_THREADS_VIRTUAL_ENABLED: 'false',
                SERVER_TOMCAT_THREADS_MAX: String(OPTIONS.platformThreads),
            }, 'platform');
            await warmup(GATEWAY_PORT);
            arms.push(await measureArm('platform', GATEWAY_PORT));
        }
    } catch (error) {
        console.error(`\n✗ 壓測中止：${error.message}`);
        stopGateway();
        process.exit(1);
    } finally {
        stopGateway();
    }

    const file = writeReport(arms, environment);

    console.log(`\n${'='.repeat(66)}`);
    console.log('組別        TTFT p50   TTFT p95   tokens/s   成功率');
    for (const arm of arms) {
        console.log(`${arm.name.padEnd(11)} ${String(arm.p50).padStart(6)}ms   ${String(arm.p95).padStart(6)}ms   `
            + `${String(arm.tokensPerSecond).padStart(8)}   ${(arm.successRate * 100).toFixed(1)}%`);
    }

    const baseline = arms.find((arm) => arm.name === 'direct');
    if (baseline) {
        console.log('');
        for (const arm of arms.filter((item) => item.name !== 'direct')) {
            const delta = arm.p95 - baseline.p95;
            const resolution = Math.max(arm.resolutionMs, baseline.resolutionMs);
            console.log(Math.abs(delta) > resolution
                ? `${arm.name} 相對基準線 ${delta >= 0 ? '+' : ''}${delta}ms（解析度 ±${resolution}ms，可宣稱）`
                : `${arm.name} 相對基準線 ${delta >= 0 ? '+' : ''}${delta}ms，小於解析度 ±${resolution}ms —— 在這個負載下量不出差別`);
        }
    }

    const invalid = arms.reduce((sum, arm) => sum + arm.invalidRounds, 0);
    if (invalid > 0) {
        console.log(`\n⚠ 有 ${invalid} 輪的抖動與訊號同數量級而標記為無效。`
            + '降低 --concurrency 或增加 --workers 可改善。');
    }
    const failed = arms.filter((arm) => arm.successRate < 0.99);
    if (failed.length) {
        console.log(`\n⚠ 以下組別有失敗的串流：${failed.map((arm) => `${arm.name}（${(arm.successRate * 100).toFixed(1)}%）`).join('、')}`);
    }

    console.log(`\n報告：${path.relative(REPO_ROOT, file)}`);
})();
