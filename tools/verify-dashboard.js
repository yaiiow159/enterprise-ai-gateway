#!/usr/bin/env node
/**
 * 驗證儀表板的每一個面板真的有資料。
 *
 * 為什麼需要這個：**一張載入成功、但每個面板都寫著 "No data" 的儀表板，
 * 比沒有儀表板更糟**——它看起來像有監控，於是沒有人再去看日誌。
 * 而指標名稱打錯、標籤名不符、單位算錯，全都不會有任何錯誤訊息。
 *
 * 查詢直接從儀表板 JSON 讀出來，不在這裡另外抄一份：
 * 抄一份就會漂移，然後這支腳本會驗一組儀表板上根本不存在的查詢。
 *
 * 用法：
 *   node tools/verify-dashboard.js
 */

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..');
const DASHBOARD = path.join(REPO_ROOT, 'deploy/grafana/provisioning/dashboards/ai-gateway.json');
const PROMETHEUS_PORT = Number(process.env.PROMETHEUS_PORT || 9091);

function query(expression) {
    return new Promise((resolve) => {
        const url = `/api/v1/query?query=${encodeURIComponent(expression)}`;
        const request = http.get({ host: 'localhost', port: PROMETHEUS_PORT, path: url, timeout: 10000 }, (response) => {
            let text = '';
            response.on('data', (chunk) => { text += chunk; });
            response.on('end', () => {
                try {
                    const body = JSON.parse(text);
                    resolve({
                        ok: body.status === 'success',
                        series: body.data?.result?.length || 0,
                        error: body.error || null,
                        sample: body.data?.result?.[0]?.value?.[1],
                    });
                } catch (error) {
                    resolve({ ok: false, series: 0, error: `回應不是 JSON：${text.slice(0, 120)}` });
                }
            });
        });
        request.on('timeout', () => { request.destroy(); resolve({ ok: false, series: 0, error: '逾時' }); });
        request.on('error', (error) => resolve({ ok: false, series: 0, error: error.message }));
    });
}

/** 樣板變數在 Grafana 端才會被替換，這裡用「全選」的等效值 */
function resolveTemplates(expression) {
    return expression.replace(/=~"\$\w+"/g, '=~".*"');
}

(async () => {
    if (!fs.existsSync(DASHBOARD)) {
        console.error(`✗ 找不到 ${path.relative(REPO_ROOT, DASHBOARD)}`);
        process.exit(2);
    }

    const health = await query('up');
    if (!health.ok) {
        console.error(`✗ Prometheus（localhost:${PROMETHEUS_PORT}）無法查詢：${health.error}`);
        console.error('  先執行 node .claude/skills/dev-stack/up.js');
        process.exit(2);
    }

    // 抓取目標本身要先是 up 的，否則所有面板都沒資料，而原因與查詢無關
    const scrape = await query('up{job="ai-gateway"}');
    const scrapeUp = scrape.sample === '1';
    console.log(`Prometheus 對網關的抓取狀態：${scrapeUp ? '✓ up' : '✗ down'}`);
    if (!scrapeUp) {
        console.error('  網關沒有在被抓取，所有面板都會是空的。先確認網關正在 8090 執行。');
        process.exit(1);
    }

    const dashboard = JSON.parse(fs.readFileSync(DASHBOARD, 'utf8'));
    const panels = dashboard.panels.filter((panel) => panel.type !== 'row');

    let emptyPanels = 0;
    let brokenQueries = 0;

    console.log(`\n共 ${panels.length} 個面板\n`);

    for (const panel of panels) {
        const results = [];
        for (const target of panel.targets || []) {
            const expression = resolveTemplates(target.expr);
            const result = await query(expression);
            results.push({ refId: target.refId, expression, ...result });
        }

        const broken = results.filter((result) => !result.ok);
        const withData = results.filter((result) => result.ok && result.series > 0);

        let mark = '✓';
        if (broken.length) { mark = '✗'; brokenQueries += broken.length; }
        else if (withData.length === 0) { mark = '⚠'; emptyPanels += 1; }

        console.log(`  ${mark} ${panel.title}`);
        for (const result of results) {
            if (!result.ok) {
                console.log(`      [${result.refId}] 查詢錯誤：${result.error}`);
                console.log(`          ${result.expression}`);
            } else if (result.series === 0) {
                console.log(`      [${result.refId}] 沒有資料（查詢合法但回傳空集合）`);
                console.log(`          ${result.expression}`);
            } else {
                console.log(`      [${result.refId}] ${result.series} 條序列，樣本值 ${result.sample}`);
            }
        }
    }

    console.log(`\n${'='.repeat(60)}`);
    if (brokenQueries) {
        console.log(`✗ ${brokenQueries} 個查詢有語法或指標名稱錯誤`);
    }
    if (emptyPanels) {
        console.log(`⚠ ${emptyPanels} 個面板沒有資料`);
        console.log('  可能是還沒有對應的流量（例如對沖／失敗切換尚未發生），');
        console.log('  也可能是指標名稱寫錯。對照上面的查詢字串確認。');
    }
    if (!brokenQueries && !emptyPanels) {
        console.log('✓ 每個面板都查得到資料');
    }

    process.exit(brokenQueries ? 1 : 0);
})();
