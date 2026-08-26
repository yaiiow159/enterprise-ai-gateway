#!/usr/bin/env node
/**
 * 發版把關：一次跑完所有檢查並產出報告
 *
 * 這支腳本本身不做任何驗證——它的價值只在於**串起來、把關、留下紀錄**。
 * 每一項檢查都有自己的 skill，各自可以單獨執行；這裡負責的是
 * 「交付之前有沒有漏跑哪一項」以及「這次交付的狀態長什麼樣子」。
 *
 * 設計上刻意避免的兩件事：
 *
 *   1. **不要把失敗吞掉變成一句「有些檢查沒過」。** 每一項的實際輸出都要留在報告裡，
 *      否則使用者得重跑一次才知道發生什麼事，那這支腳本就只是多花一次時間。
 *
 *   2. **不要讓「非阻擋」變成「可以忽略」。** 非阻擋項目一樣會列在報告最前面，
 *      只是不讓 CI 變紅。分成兩級是因為有些缺口是已知且有意接受的
 *      （例如壓測受當下機器負載影響），不是因為它們不重要。
 *
 * 用法：
 *   node .claude/skills/release-check/check.js
 *   node .claude/skills/release-check/check.js --full     # 加上壓測與多節點成本上限
 */

'use strict';

const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '../../..');
const JAR = path.join(REPO_ROOT, 'gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar');
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8090);
const FULL = process.argv.includes('--full');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ------------------------------------------------------------ 執行單一檢查

function run(command, args, options) {
    const result = spawnSync(command, args, {
        cwd: REPO_ROOT,
        encoding: 'utf8',
        maxBuffer: 32 * 1024 * 1024,
        ...options,
    });

    let output = `${result.stdout || ''}${result.stderr || ''}`.trim();

    // spawn 本身失敗（找不到執行檔、權限不足）時 stdout/stderr 都是空的。
    // 不把 result.error 帶出來的話，報告上會出現「未通過」卻沒有任何原因——
    // 這支腳本開發時就這樣吞掉過一次建置失敗，訊息完全是空白
    if (result.error) {
        output = `${output}\n無法執行 ${command}：${result.error.message}`.trim();
    }

    return { code: result.status === null ? 1 : result.status, output };
}

const node = (script, ...args) => () => run('node', [script, ...args]);

// ------------------------------------------------------------ 網關生命週期

let gateway = null;

function killLingeringJvms() {
    if (process.platform === 'win32') {
        const list = spawnSync('powershell', ['-NoProfile', '-Command',
            "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*gateway-server*' } | ForEach-Object { $_.ProcessId }"],
            { encoding: 'utf8' });
        String(list.stdout || '').split(/\s+/).filter(Boolean)
            .forEach((pid) => spawnSync('taskkill', ['/PID', pid, '/T', '/F'], { stdio: 'ignore' }));
    } else {
        spawnSync('pkill', ['-f', 'gateway-server'], { stdio: 'ignore' });
    }
}

function probe(port, urlPath) {
    return new Promise((resolve) => {
        const request = http.get({ host: 'localhost', port, path: urlPath, timeout: 3000 }, (response) => {
            response.resume();
            resolve(response.statusCode === 200);
        });
        request.on('timeout', () => { request.destroy(); resolve(false); });
        request.on('error', () => resolve(false));
    });
}

async function startGateway() {
    killLingeringJvms();
    await sleep(1500);
    gateway = spawn('java', ['-jar', JAR], { cwd: REPO_ROOT, stdio: 'ignore' });
    for (let i = 0; i < 120; i++) {
        if (gateway.exitCode !== null) throw new Error(`網關啟動後隨即退出（exit ${gateway.exitCode}）`);
        if (await probe(GATEWAY_PORT, '/actuator/health')) return;
        await sleep(500);
    }
    throw new Error('網關在 60 秒內未就緒');
}

function stopGateway() {
    killLingeringJvms();
    gateway = null;
}

// ------------------------------------------------------------ 檢查項目

/**
 * `needsGateway` 的項目會共用同一個已啟動的網關；
 * 其餘項目自己管理網關生命週期（例如 verify-routing 每個情境都要重啟）。
 */
const STAGES = [
    {
        name: '版控狀態',
        blocking: false,
        run: () => {
            const status = run('git', ['status', '--porcelain']);
            const branch = run('git', ['rev-parse', '--abbrev-ref', 'HEAD']).output;
            const head = run('git', ['log', '-1', '--oneline']).output;
            const dirty = status.output.split('\n').filter(Boolean);
            return {
                code: dirty.length ? 1 : 0,
                output: `分支 ${branch}\nHEAD ${head}\n`
                    + (dirty.length ? `未提交的變更 ${dirty.length} 項：\n${dirty.join('\n')}` : '工作區乾淨'),
            };
        },
    },
    {
        name: '建置',
        blocking: true,
        // Windows 上 mvn 是批次檔，必須透過 shell 執行。
        // 但參數要併進命令字串而不是走 args——`shell:true` 搭配 args 陣列
        // 會觸發 Node 的「參數未跳脫」警告
        run: () => run('mvn -B -q clean install -DskipTests', [], { shell: true }),
    },
    {
        name: '金鑰外洩掃描',
        blocking: true,
        run: node('.claude/hooks/secret-guard.js', '--self-test'),
    },
    {
        name: '假 Provider 自我驗證',
        blocking: true,
        run: node('tools/fake-provider/smoke.js'),
    },
    {
        name: 'RAG 檢索品質',
        // 非阻擋是因為它依賴「索引已經建好」這個環境前置條件——
        // 全新的機器上沒有索引是正常的，不該擋住發版。
        // 真正的把關在「RAG 端到端」那一項，它會自己建索引再驗
        blocking: false,
        run: node('.claude/skills/rag-eval/check.js'),
    },
    {
        name: 'RAG 檢查本身有效',
        blocking: true,
        run: node('.claude/skills/rag-eval/check.js', '--self-test'),
    },
    {
        name: '成本風險',
        blocking: true,
        needsGateway: true,
        run: node('.claude/skills/cost-guard/check.js'),
    },
    {
        name: '串流轉發',
        blocking: true,
        needsGateway: true,
        run: node('.claude/skills/stream-check/check.js'),
    },
    {
        name: '儀表板面板有資料',
        // 排在成本與串流之後：那兩項會產生流量，面板才有東西可查。
        // 只有「查詢寫壞了」才會讓它失敗；「這種流量還沒發生」只是警告
        blocking: true,
        needsGateway: true,
        run: node('tools/verify-dashboard.js'),
    },
    {
        name: 'RAG 端到端',
        // 自己管理網關（要用不同設定驗「該啟動失敗」的情境），所以不放進 needsGateway
        blocking: true,
        run: node('tools/verify-rag.js'),
    },
    {
        name: '路由與對沖',
        blocking: true,
        run: node('tools/verify-routing.js'),
    },
    {
        name: '多節點成本上限',
        blocking: true,
        full: true,
        run: node('tools/verify-budget-multinode.js'),
    },
    {
        name: '壓測',
        // 壓測結果本身不該擋發版：它量的是效能而不是正確性，
        // 而且會受當下機器負載影響。報告裡有數字讓人自己判斷
        blocking: false,
        full: true,
        run: node('.claude/skills/bench/bench.js', '--concurrency', '50', '--rounds', '2'),
    },
];

// ------------------------------------------------------------ 前置條件

async function preflight() {
    const problems = [];
    if (!fs.existsSync(JAR)) {
        // 建置階段會產出它，所以只在跳過建置時才是問題；這裡只提醒
        console.log('  （尚未建置，建置階段會產出 jar）');
    }
    for (const port of [9101, 9102, 9103]) {
        if (!await probe(port, '/health')) problems.push(`假 Provider ${port} 不可用`);
    }
    if (!await probe(6333, '/collections')) problems.push('Qdrant（6333）不可用');

    if (problems.length) {
        console.error('✗ 前置條件不滿足：');
        problems.forEach((problem) => console.error(`    ${problem}`));
        console.error('\n  先執行：node .claude/skills/dev-stack/up.js');
        process.exit(2);
    }
}

// ------------------------------------------------------------ 報告

function writeReport(results) {
    const directory = path.join(REPO_ROOT, 'docs/release-check');
    fs.mkdirSync(directory, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const file = path.join(directory, `${stamp}.md`);

    const failed = results.filter((result) => !result.passed);
    const blocking = failed.filter((result) => result.blocking);

    const lines = [];
    lines.push(`# 發版把關報告　${new Date().toISOString()}`, '');
    lines.push(blocking.length
        ? `## ✗ 不可交付：${blocking.length} 項阻擋未通過`
        : '## ✓ 可交付', '');
    if (failed.length && !blocking.length) {
        lines.push(`（有 ${failed.length} 項非阻擋未通過，見下）`, '');
    }

    lines.push('| 項目 | 結果 | 阻擋 |', '| --- | --- | --- |');
    for (const result of results) {
        lines.push(`| ${result.name} | ${result.skipped ? '略過' : (result.passed ? '通過' : '未通過')} `
            + `| ${result.blocking ? '是' : '否'} |`);
    }
    lines.push('');

    // 每一項的實際輸出都要留著。只寫「未通過」等於逼使用者重跑一次
    lines.push('## 各項輸出', '');
    for (const result of results) {
        lines.push(`### ${result.name}${result.skipped ? '（略過）' : ''}`, '');
        if (result.skipped) {
            lines.push(result.skipReason || '未執行', '');
            continue;
        }
        lines.push('```');
        lines.push(result.output || '（無輸出）');
        lines.push('```', '');
    }

    lines.push('## 這份報告涵蓋什麼', '');
    lines.push('- 各項檢查的完整輸出都保留在上方，不需要重跑就能判斷發生什麼事。');
    lines.push('- **阻擋**項目失敗時整支腳本以非 0 結束，可直接接進 CI。');
    lines.push('- 非阻擋項目失敗不影響結束碼，但一樣列在最前面——');
    lines.push('  分成兩級是因為有些缺口是已知且有意接受的，不是因為它們不重要。');
    if (!FULL) {
        lines.push('- 未加 `--full`，因此**沒有**執行壓測與多節點成本上限驗證。');
    }

    fs.writeFileSync(file, lines.join('\n'), 'utf8');
    return file;
}

// ------------------------------------------------------------ 主流程

(async () => {
    console.log(`發版把關${FULL ? '（完整，含壓測與多節點驗證）' : ''}\n`);
    await preflight();

    const stages = STAGES.filter((stage) => !stage.full || FULL);
    const results = [];
    let gatewayRunning = false;

    for (const stage of stages) {
        if (stage.full && !FULL) continue;

        // 需要網關的項目共用同一個實例；不需要的項目之前先收乾淨，
        // 免得它們自己啟動時撞到殘留行程（那會變成「測到上一個」）
        if (stage.needsGateway && !gatewayRunning) {
            try {
                await startGateway();
                gatewayRunning = true;
            } catch (error) {
                results.push({
                    name: stage.name, blocking: stage.blocking, passed: false,
                    output: `無法啟動網關：${error.message}`,
                });
                continue;
            }
        } else if (!stage.needsGateway && gatewayRunning) {
            stopGateway();
            gatewayRunning = false;
        }

        process.stdout.write(`  ${stage.name} ... `);
        const started = Date.now();
        const { code, output } = stage.run();
        const passed = code === 0;
        const seconds = ((Date.now() - started) / 1000).toFixed(1);

        console.log(`${passed ? '✓' : '✗'} ${seconds}s${passed || !stage.blocking ? '' : '（阻擋）'}`);
        if (!passed) {
            // 失敗當下就把最後幾行印出來，不要逼使用者等到報告才知道
            output.split('\n').slice(-6).forEach((line) => console.log(`      ${line}`));
        }
        results.push({ name: stage.name, blocking: stage.blocking, passed, output });
    }

    if (gatewayRunning) stopGateway();

    const file = writeReport(results);
    const blocking = results.filter((result) => !result.passed && result.blocking);
    const warnings = results.filter((result) => !result.passed && !result.blocking);

    console.log(`\n${'='.repeat(60)}`);
    console.log(blocking.length ? `✗ 不可交付：${blocking.length} 項阻擋未通過` : '✓ 可交付');
    blocking.forEach((result) => console.log(`    ✗ ${result.name}`));
    warnings.forEach((result) => console.log(`    ⚠ ${result.name}（非阻擋）`));
    if (!FULL) console.log('\n  未執行壓測與多節點成本上限驗證，加 --full 可一併跑');
    console.log(`\n報告：${path.relative(REPO_ROOT, file)}`);

    process.exit(blocking.length ? 1 : 0);
})();
