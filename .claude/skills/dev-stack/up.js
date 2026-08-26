#!/usr/bin/env node
/**
 * 啟動本機基礎設施並等到「真的可用」
 *
 * 為什麼不是一行 docker compose up：
 *   - 容器 Up 不等於服務可用。PostgreSQL 要跑初始化、Qdrant 要載入 collection，
 *     這段期間打過去是連線被拒，很容易被誤判成設定寫錯而開始亂改。
 *   - 這台機器上其他專案已占用 6379／9090／3000~3002，docker compose 的錯誤訊息
 *     只會說 bind 失敗，不會告訴你是誰佔的。
 *
 * 用法：
 *   node .claude/skills/dev-stack/up.js
 *   node .claude/skills/dev-stack/up.js --status
 *   node .claude/skills/dev-stack/up.js --down
 */

const { spawnSync } = require('node:child_process');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '../../..');
const COMPOSE_FILE = path.join(REPO_ROOT, 'deploy/docker-compose.yml');

const args = process.argv.slice(2);
const statusOnly = args.includes('--status');
const down = args.includes('--down');

const PORTS = {
    redis: Number(process.env.REDIS_PORT || 6380),
    postgres: Number(process.env.POSTGRES_PORT || 5433),
    qdrant: Number(process.env.QDRANT_PORT || 6333),
    qdrantGrpc: Number(process.env.QDRANT_GRPC_PORT || 6334),
    fakeFast: Number(process.env.FAKE_FAST_PORT || 9101),
    fakeSlow: Number(process.env.FAKE_SLOW_PORT || 9102),
    fakeFlaky: Number(process.env.FAKE_FLAKY_PORT || 9103),
    prometheus: Number(process.env.PROMETHEUS_PORT || 9091),
    grafana: Number(process.env.GRAFANA_PORT || 3003),
};

/**
 * 就緒探測一律用 Node 內建的 http，不外呼 curl：
 * Windows 的 curl.exe 不認得 `-o /dev/null`，用 curl 會在 Windows 上一律探測失敗——
 * 容器明明 healthy，腳本卻回報不可用。
 */
function httpOk(url, expectStatusBelow = 400) {
    return new Promise((resolve) => {
        const request = http.get(url, { timeout: 3000 }, (response) => {
            response.resume();
            resolve(response.statusCode > 0 && response.statusCode < expectStatusBelow);
        });
        request.on('timeout', () => { request.destroy(); resolve(false); });
        request.on('error', () => resolve(false));
    });
}

function execOk(command, commandArgs) {
    const result = spawnSync(command, commandArgs, { encoding: 'utf8' });
    return result.status === 0;
}

const SERVICES = [
    {
        name: 'Redis',
        probe: () => execOk('docker', ['exec', 'aigw_redis', 'redis-cli', 'ping']),
        url: () => `redis://localhost:${PORTS.redis}`,
        timeoutMs: 30_000,
    },
    {
        name: 'PostgreSQL',
        probe: () => execOk('docker', ['exec', 'aigw_postgres', 'pg_isready', '-U', 'aigateway', '-d', 'aigateway']),
        url: () => `postgres://aigateway:aigateway@localhost:${PORTS.postgres}/aigateway`,
        timeoutMs: 60_000,
    },
    {
        name: 'Qdrant',
        probe: () => httpOk(`http://localhost:${PORTS.qdrant}/readyz`),
        url: () => `http://localhost:${PORTS.qdrant}/dashboard`,
        timeoutMs: 60_000,
    },
    {
        name: 'fake-fast',
        probe: () => httpOk(`http://localhost:${PORTS.fakeFast}/health`),
        url: () => `http://localhost:${PORTS.fakeFast}（TTFT 80ms）`,
        timeoutMs: 30_000,
    },
    {
        name: 'fake-slow',
        probe: () => httpOk(`http://localhost:${PORTS.fakeSlow}/health`),
        url: () => `http://localhost:${PORTS.fakeSlow}（TTFT 1500ms）`,
        timeoutMs: 30_000,
    },
    {
        name: 'fake-flaky',
        probe: () => httpOk(`http://localhost:${PORTS.fakeFlaky}/health`),
        url: () => `http://localhost:${PORTS.fakeFlaky}（30% 機率回 429）`,
        timeoutMs: 30_000,
    },
    {
        name: 'Prometheus',
        probe: () => httpOk(`http://localhost:${PORTS.prometheus}/-/ready`),
        url: () => `http://localhost:${PORTS.prometheus}`,
        timeoutMs: 60_000,
    },
    {
        name: 'Grafana',
        probe: () => httpOk(`http://localhost:${PORTS.grafana}/api/health`),
        url: () => `http://localhost:${PORTS.grafana}（admin/admin）`,
        timeoutMs: 90_000,
    },
];

function compose(composeArgs) {
    return spawnSync('docker', ['compose', '-f', COMPOSE_FILE, ...composeArgs], {
        encoding: 'utf8',
        stdio: 'inherit',
    });
}

function portInUse(port) {
    return new Promise((resolve) => {
        const server = net.createServer();
        server.once('error', (error) => resolve(error.code === 'EADDRINUSE'));
        server.once('listening', () => server.close(() => resolve(false)));
        server.listen(port, '0.0.0.0');
    });
}

/** 找出佔用該連接埠的容器，讓使用者知道要停哪個而不是盲目試 */
function containerOnPort(port) {
    const result = spawnSync('docker', ['ps', '--format', '{{.Names}}\t{{.Ports}}'], { encoding: 'utf8' });
    if (result.status !== 0) return null;
    const line = result.stdout.split('\n').find((row) => row.includes(`:${port}->`));
    return line ? line.split('\t')[0] : null;
}

async function checkPorts() {
    const conflicts = [];
    for (const [service, port] of Object.entries(PORTS)) {
        const owner = containerOnPort(port);
        // 兩種偵測都要做：Docker Desktop（Windows/WSL2）發布的連接埠，
        // 從宿主機 bind 同一個埠有時仍會成功，只靠 bind 測試會漏掉別的容器佔用的情況
        const taken = owner !== null || await portInUse(port);
        const ours = owner && owner.startsWith('aigw_');
        if (taken && !ours) conflicts.push({ service, port, owner });
    }
    return conflicts;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(service) {
    const started = Date.now();
    process.stdout.write(`  ${service.name.padEnd(11)} `);

    while (Date.now() - started < service.timeoutMs) {
        if (await service.probe()) {
            console.log(`✓ 就緒（${((Date.now() - started) / 1000).toFixed(1)}s）`);
            return true;
        }
        process.stdout.write('.');
        await sleep(2000);
    }

    console.log(`✗ 逾時（${service.timeoutMs / 1000}s）`);
    return false;
}

(async () => {
    if (down) {
        compose(['down']);
        process.exit(0);
    }

    if (!statusOnly) {
        console.log('檢查連接埠占用...');
        const conflicts = await checkPorts();
        if (conflicts.length > 0) {
            console.error('\n✗ 以下連接埠已被占用：');
            for (const { service, port, owner } of conflicts) {
                console.error(`  ${service} (${port})${owner ? ` — 由容器 ${owner} 使用` : ' — 由非 Docker 的程序使用'}`);
            }
            console.error('\n處理方式：停掉佔用的服務，或以環境變數換一個連接埠再啟動，例如：');
            console.error('  GRAFANA_PORT=3004 node .claude/skills/dev-stack/up.js');
            process.exit(1);
        }
        console.log('  ✓ 無衝突\n');

        console.log('啟動容器...');
        if (compose(['up', '-d']).status !== 0) {
            console.error('✗ docker compose up 失敗');
            process.exit(1);
        }
        console.log('');
    }

    const failures = [];
    if (statusOnly) {
        // --status 是「現在到底通不通」，探一次就回答；等待是啟動流程才需要做的事
        console.log('目前狀態：');
        for (const service of SERVICES) {
            const ok = await service.probe();
            console.log(`  ${service.name.padEnd(11)} ${ok ? '✓ 可用' : '✗ 不可用'}`);
            if (!ok) failures.push(service.name);
        }
    } else {
        console.log('等待服務就緒（容器 Up 不等於服務可用）...');
        for (const service of SERVICES) {
            if (!await waitFor(service)) failures.push(service.name);
        }
    }

    console.log('\n服務位址：');
    for (const service of SERVICES) {
        console.log(`  ${service.name.padEnd(11)} ${service.url()}`);
    }

    if (failures.length > 0) {
        console.error(`\n✗ 未就緒：${failures.join('、')}`);
        console.error('  查看日誌：docker compose -f deploy/docker-compose.yml logs <service>');
        process.exit(1);
    }

    console.log('\n✓ 基礎設施已就緒');
})();
