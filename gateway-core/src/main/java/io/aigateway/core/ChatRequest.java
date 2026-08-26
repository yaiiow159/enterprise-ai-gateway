package io.aigateway.core;

import java.util.List;
import java.util.Map;

/**
 * 與 Provider 無關的對話請求。
 *
 * @param model      呼叫端要求的模型或模型別名；路由會據此挑選實際的 Provider
 * @param messages   對話內容
 * @param maxTokens  輸出上限。**不可為 null**——沒有上限的請求是這個系統最典型的成本失控來源
 * @param stream     是否串流
 * @param temperature 取樣溫度，null 表示用 Provider 預設
 * @param tenant     成本歸戶用的租戶／團隊識別
 * @param extra      直接透傳給 Provider 的額外參數
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        Integer maxTokens,
        boolean stream,
        Double temperature,
        String tenant,
        Map<String, Object> extra) {

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不可為空");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不可為空");
        }
        // 刻意在型別層面就要求 maxTokens：讓「忘記設上限」變成編譯期之後最早能發現的錯誤，
        // 而不是等到帳單出來才發現某個端點在無上限地生成
        if (maxTokens == null || maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必須為正數：未設定輸出上限是成本失控的主因");
        }
        messages = List.copyOf(messages);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public ChatRequest withStream(boolean streaming) {
        return new ChatRequest(model, messages, maxTokens, streaming, temperature, tenant, extra);
    }

    /** 估算輸入 token 數。用於在送出前擋掉超過上下文長度的請求（見 CLAUDE.md 的待釐清問題 1）*/
    public int estimatedPromptTokens() {
        int characters = messages.stream().mapToInt(message -> message.content().length()).sum();
        // 近似值：英文約 4 字元／token，中日韓更接近 1.5。取保守的 2 以免低估而漏擋
        return Math.max(1, characters / 2);
    }
}
