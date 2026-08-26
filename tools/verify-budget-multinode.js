#!/usr/bin/env node
/**
 * 多節點成本上限驗證。
 *
 * 這是 #8 的核心主張：兩個節點共用同一份帳，總支出不該是「節點數 × 上限」。
 * 用 memory 後端跑同一個情境作為對照——它應該會超支，那正是要修掉的問題。
 */
'use strict';
const { spawn, spawnSync } = require('node:child_process');
const http = require('node:http');

const path = require('node:path');
const REPO_ROOT = path.resolve(__dirname, '..');
const JAR = path.join(REPO_ROOT, 'gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar');
const PORTS = [8090, 8091];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const children = [];

function json(port, method, path, body) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined ? null : JSON.stringify(body);
        const headers = {};
        if (payload) { headers['Content-Type'] = 'application/json'; headers['Content-Length'] = Buffer.byteLength(payload); }
        const req = http.request({ host: 'localhost', port, path, method, headers, timeout: 20000 }, res => {
            let d = ''; res.on('data', c => { d += c; });
            res.on('end', () => { let p = null; try { p = JSON.parse(d); } catch {} resolve({ status: res.statusCode, body: p }); });
        });
        req.on('timeout', () => { req.destroy(); resolve({ status: 0 }); });
        req.on('error', () => resolve({ status: 0 }));
        if (payload) req.write(payload); req.end();
    });
}

function killAll() {
    spawnSync('powershell', ['-NoProfile', '-Command',
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*gateway-server*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"],
        { stdio: 'ignore' });
    children.length = 0;
}

async function startNodes(backend, limitUsd) {
    killAll();
    await sleep(2000);
    for (const port of PORTS) {
        children.push(spawn('java', ['-jar', JAR], {
            stdio: 'ignore',
            env: Object.assign({}, process.env, {
                SERVER_PORT: String(port),
                BUDGET_BACKEND: backend,
                GATEWAY_BUDGET_DAILYLIMITUSD: String(limitUsd),
                GATEWAY_BUDGET_PERTENANTDAILYLIMITUSD: '0',
            }),
        }));
    }
    for (const port of PORTS) {
        let up = false;
        for (let i = 0; i < 120; i++) {
            const health = await json(port, 'GET', '/actuator/health');
            if (health.status === 200) { up = true; break; }
            await sleep(500);
        }
        if (!up) throw new Error(`節點 ${port} 未就緒`);
    }
}

/** 直接問網關花了多少，不要用假設的單筆成本回推——那只會量到我自己的假設 */
function gauge(port, name) {
    return new Promise((resolve) => {
        const req = http.get({ host: 'localhost', port, path: '/actuator/prometheus', timeout: 5000 }, res => {
            let t = ''; res.on('data', c => { t += c; });
            res.on('end', () => {
                const line = t.split('\n').find(row => row.startsWith(name));
                resolve(line ? Number(line.slice(line.lastIndexOf(' ') + 1)) : 0);
            });
        });
        req.on('timeout', () => { req.destroy(); resolve(0); });
        req.on('error', () => resolve(0));
    });
}

async function drive(backend, attempts) {
    let accepted = 0;
    for (let i = 0; i < attempts; i++) {
        const port = PORTS[i % PORTS.length];
        const r = await json(port, 'POST', '/v1/chat/completions',
            { model: 'any', messages: [{ role: 'user', content: 'hi' }], max_tokens: 10 });
        if (r.status === 200) accepted += 1;
    }

    const perNode = [];
    for (const port of PORTS) perNode.push(await gauge(port, 'gateway_budget_spent_microcents'));

    // memory 後端每個節點各記各的，總支出要加總；
    // redis 後端兩個節點看到的是同一份帳，加總會重複計算
    const spent = backend === 'memory' ? perNode.reduce((a, b) => a + b, 0) : perNode[0];
    return { attempts, accepted, spent, perNode };
}

(async () => {
    for (const p of [9101, 9102, 9103]) {
        await json(p, 'POST', '/_fake/reset');
        await json(p, 'PUT', '/_fake/config', { errorRate: 0, ttftMs: 30, tokenDelayMs: 1, tokens: 40 });
    }

    const LIMIT_MICRO = 17500;
    const LIMIT_USD = LIMIT_MICRO / 100 / 1e6;
    const ATTEMPTS = 30;                        // 遠超過上限所能容納的筆數

    const results = [];
    try {
        for (const backend of ['memory', 'redis']) {
            console.log(`\n=== ${backend} 後端：兩個節點，共用上限 ${LIMIT_MICRO} 微美分 ===`);
            // Redis 上一輪的帳要清掉，否則第二輪一開始就滿了
            spawnSync('docker', ['exec', 'aigw_redis', 'redis-cli', 'FLUSHALL'], { stdio: 'ignore' });
            await startNodes(backend, LIMIT_USD);
            const r = await drive(backend, ATTEMPTS);
            const ratio = r.spent / LIMIT_MICRO;
            console.log(`  送出 ${r.attempts} 筆，接受 ${r.accepted} 筆`);
            console.log(`  各節點回報支出：${r.perNode.join('、')} 微美分`);
            console.log(`  實際總支出 ${r.spent} 微美分 = 上限的 ${ratio.toFixed(2)} 倍`);
            results.push({ backend, ...r, ratio });
        }
    } finally {
        killAll();
    }

    const memory = results.find(r => r.backend === 'memory');
    const redis = results.find(r => r.backend === 'redis');

    console.log(`\n${'='.repeat(60)}`);
    console.log(`  memory：${memory.ratio.toFixed(2)} 倍上限　（兩個節點各記各的，預期約 2 倍）`);
    console.log(`  redis： ${redis.ratio.toFixed(2)} 倍上限　（共用一份帳，預期約 1 倍）`);

    const ok = redis.ratio <= 1.2 && memory.ratio > 1.5;
    console.log(ok
        ? '\n✓ Redis 後端確實把多節點的總支出收斂到單一上限'
        : '\n✗ 結果不符預期');
    process.exit(ok ? 0 : 1);
})();
