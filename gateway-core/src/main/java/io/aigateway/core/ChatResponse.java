package io.aigateway.core;

/**
 * 非串流的完整回應。
 *
 * @param id           呼叫識別
 * @param model        實際使用的模型
 * @param providerName 實際命中的 Provider
 * @param content      完整內容
 * @param finishReason 結束原因
 * @param usage        用量與成本
 * @param latencyMs    這次呼叫的總耗時
 */
public record ChatResponse(
        String id,
        String model,
        String providerName,
        String content,
        ChatChunk.FinishReason finishReason,
        Usage usage,
        long latencyMs) {
}
