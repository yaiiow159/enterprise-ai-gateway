/**
 * 工具呼叫網關時的 API key。
 *
 * 網關預設要求 API key（GATEWAY_API_KEYS，格式「租戶=key」）。
 * - 呼叫已經在跑的網關：從環境變數 GATEWAY_API_KEY 讀要送的那一把；
 *   沒設就不帶標頭，對應本機以 GATEWAY_AUTH_ENABLED=false 啟動的網關。
 * - 工具自己啟動網關：先呼叫 ensureLocalGatewayKey()，產生一把只在這次執行有效的 key，
 *   寫進 process.env，之後啟動的網關與子行程都會繼承。
 */
'use strict';

const crypto = require('node:crypto');

/** 要附加在每個網關請求上的標頭 */
function gatewayAuthHeaders() {
    const key = process.env.GATEWAY_API_KEY;
    return key ? { Authorization: `Bearer ${key}` } : {};
}

/** 替工具自己啟動的網關準備一把 key；已經設定過就沿用 */
function ensureLocalGatewayKey(tenant = 'local-tools') {
    if (!process.env.GATEWAY_API_KEY) {
        process.env.GATEWAY_API_KEY = `local-${crypto.randomBytes(24).toString('hex')}`;
    }
    process.env.GATEWAY_API_KEYS = `${tenant}=${process.env.GATEWAY_API_KEY}`;
    process.env.GATEWAY_AUTH_ENABLED = 'true';
}

module.exports = { gatewayAuthHeaders, ensureLocalGatewayKey };
