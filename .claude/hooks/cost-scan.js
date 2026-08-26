#!/usr/bin/env node
/**
 * PostToolUse hook：改到 Java 或設定檔就掃一次成本風險
 *
 * 為什麼是 PostToolUse 而不是 PreToolUse（與 secret-guard 相反）：
 *   成本風險常常需要人判斷。`immediate` 對沖有正當用途（極端要求延遲的少數端點），
 *   無退避重試在某些情境也可以接受。機械式地擋住寫入只會讓這個 hook 被關掉，
 *   然後它就什麼都不提醒了。金鑰外洩才適合阻擋——那個沒有「其實沒關係」的情況。
 *
 * 為什麼只在**阻擋級**發現時出聲：
 *   警告級的項目（例如「還沒有每日成本上限」）是已知且記在文件裡的缺口。
 *   每次存檔都刷一遍只會訓練大家忽略這個 hook，而被忽略的告警等於沒有告警。
 *
 * 行為：無論如何都 exit 0（不阻擋），只是在有嚴重發現時印出來。
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const WATCHED = ['.java', '.yml', '.yaml'];

let raw = '';
try {
    raw = fs.readFileSync(0, 'utf8');
} catch {
    process.exit(0);
}

let payload;
try {
    payload = JSON.parse(raw);
} catch {
    process.exit(0);
}

const filePath = payload?.tool_input?.file_path;
if (!filePath || !WATCHED.some((extension) => filePath.endsWith(extension))) {
    process.exit(0);
}

const repoRoot = path.resolve(__dirname, '../..');
const result = spawnSync('node', [path.join(repoRoot, '.claude/skills/cost-guard/check.js'), '--hook'], {
    encoding: 'utf8',
    cwd: repoRoot,
});

const output = String(result.stdout || '').trim();
if (output) console.log(output);

// 永遠放行。這個 hook 的職責是提醒，不是把關
process.exit(0);
