package io.aigateway.core;

/**
 * 一個 Provider／模型能做什麼。
 *
 * <p>路由要看的是能力而不是型號：把上下文長度、串流支援、工具呼叫、單價收斂成這個型別後，
 * 新模型上線時只要加一筆設定，不必改路由程式碼。
 *
 * @param contextWindowTokens     上下文長度上限
 * @param maxOutputTokens         單次輸出上限
 * @param supportsStreaming       是否支援串流
 * @param supportsTools           是否支援工具／函式呼叫
 * @param supportsVision          是否接受圖片輸入
 * @param inputMicroCentsPerToken 每個輸入 token 的成本（百萬分之一美分）
 * @param outputMicroCentsPerToken 每個輸出 token 的成本
 */
public record ModelCapability(
        int contextWindowTokens,
        int maxOutputTokens,
        boolean supportsStreaming,
        boolean supportsTools,
        boolean supportsVision,
        long inputMicroCentsPerToken,
        long outputMicroCentsPerToken) {

    public ModelCapability {
        if (contextWindowTokens <= 0) throw new IllegalArgumentException("contextWindowTokens 必須為正數");
        if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens 必須為正數");
    }

    /** 這個模型能不能承接這個請求。不能的話路由階段就要濾掉，而不是送出去等 Provider 報錯 */
    public boolean canHandle(ChatRequest request) {
        if (request.stream() && !supportsStreaming) return false;
        // 不支援工具的模型收到 tools 時多半靜靜忽略——呼叫端拿到一個沒呼叫工具的答案，卻不知道為什麼
        if (request.usesTools() && !supportsTools) return false;
        if (request.hasImages() && !supportsVision) return false;
        if (request.maxTokens() > maxOutputTokens) return false;
        return request.estimatedPromptTokens() + request.maxTokens() <= contextWindowTokens;
    }

    /** 以估算的 token 數推算這次呼叫的成本，供成本導向路由比較 */
    public long estimatedCostMicroCents(ChatRequest request) {
        return request.estimatedPromptTokens() * inputMicroCentsPerToken
                + (long) request.maxTokens() * outputMicroCentsPerToken;
    }
}
