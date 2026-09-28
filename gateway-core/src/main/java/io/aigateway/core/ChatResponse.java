package io.aigateway.core;

import java.util.List;
import java.util.Map;

/**
 * 非串流的完整回應。
 *
 * @param id           呼叫識別
 * @param model        呼叫端要求的模型名稱
 * @param providerName 實際命中的 Provider
 * @param content      完整內容
 * @param finishReason 結束原因
 * @param usage        用量與成本
 * @param latencyMs    這次呼叫的總耗時
 * @param toolCalls    組合完成的工具呼叫（OpenAI {@code message.tool_calls} 格式）；沒有時為空清單
 */
public record ChatResponse(
        String id,
        String model,
        String providerName,
        String content,
        ChatChunk.FinishReason finishReason,
        Usage usage,
        long latencyMs,
        List<Map<String, Object>> toolCalls) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
