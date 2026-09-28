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
 * @param extra      透傳給 Provider 的額外參數（tools、response_format 等）。
 *                   只能放白名單內的欄位，而且不會覆蓋 model、messages、max_tokens、stream
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

    /** 請求是否帶了工具定義 */
    public boolean usesTools() {
        return extra.get("tools") instanceof List<?> tools && !tools.isEmpty();
    }

    /** 是否有任何訊息帶圖片 */
    public boolean hasImages() {
        return messages.stream().anyMatch(Message::hasImages);
    }

    /** 估算輸入 token 數，刻意偏高（見 {@link TokenEstimator}）。用於上下文檢查與成本預扣 */
    public int estimatedPromptTokens() {
        long tokens = messages.stream()
                .mapToLong(message -> message.estimatedTokens() + TokenEstimator.PER_MESSAGE_OVERHEAD)
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, tokens));
    }
}
