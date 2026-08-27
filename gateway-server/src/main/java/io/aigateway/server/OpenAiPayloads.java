package io.aigateway.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.aigateway.core.ChatChunk;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatResponse;
import io.aigateway.core.Message;
import io.aigateway.core.RagAugmenter;

/**
 * OpenAI 線路格式與內部型別之間的對映。
 *
 * <p>從 Controller 拆出來的理由不是「Controller 太長」，而是**變動來源不同**：
 * 這裡的每一行都只為了對齊某個外部規格（欄位名、巢狀結構、finish_reason 的字串），
 * 會隨 OpenAI 改格式而動；Controller 則是編排邏輯，會隨我們自己的需求而動。
 * 兩種變動節奏混在同一個類別裡，每次改協定都要重讀一次編排邏輯才敢動手。
 *
 * <p>也因為它只做轉換、不碰任何狀態，未來要支援第二種線路格式
 * （例如 Anthropic 的 Messages API）時，加的是同層級的另一個類別，
 * 而不是在 Controller 裡加分支。
 */
final class OpenAiPayloads {

    private OpenAiPayloads() {
    }

    /** 解析請求。刻意不做寬鬆容錯：欄位缺漏要當場失敗，而不是帶著空值往下走 */
    @SuppressWarnings("unchecked")
    static ChatRequest toChatRequest(Map<String, Object> body, String tenant) {
        List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) body.get("messages");
        if (rawMessages == null || rawMessages.isEmpty()) {
            throw new IllegalArgumentException("messages 不可為空");
        }

        List<Message> messages = rawMessages.stream()
                .map(raw -> new Message(
                        Message.Role.valueOf(String.valueOf(raw.getOrDefault("role", "user")).toUpperCase()),
                        String.valueOf(raw.getOrDefault("content", ""))))
                .toList();

        Object maxTokens = body.get("max_tokens");
        return new ChatRequest(
                String.valueOf(body.getOrDefault("model", "default")),
                messages,
                maxTokens == null ? null : ((Number) maxTokens).intValue(),
                Boolean.TRUE.equals(body.get("stream")),
                body.get("temperature") == null ? null : ((Number) body.get("temperature")).doubleValue(),
                tenant,
                Map.of());
    }

    static Map<String, Object> toResponse(ChatResponse response, RagAugmenter.RagResult retrieval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", response.id());
        payload.put("object", "chat.completion");
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("model", response.model());
        payload.put("choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", response.content()),
                "finish_reason", finishReason(response.finishReason()))));
        payload.put("usage", Map.of(
                "prompt_tokens", response.usage().promptTokens(),
                "completion_tokens", response.usage().completionTokens(),
                "total_tokens", response.usage().totalTokens()));

        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("provider", response.providerName());
        gateway.put("latency_ms", response.latencyMs());
        gateway.put("cost_micro_cents", response.usage().costMicroCents());
        if (retrieval != null) gateway.put("rag", ragSummary(retrieval));
        payload.put("x_gateway", gateway);
        return payload;
    }

    /** @param retrieval 只在第一個 chunk 帶檢索資訊；其餘傳 null */
    static Map<String, Object> toChunk(ChatChunk chunk, ChatRequest request, RagAugmenter.RagResult retrieval) {
        Map<String, Object> delta = chunk.delta() == null || chunk.delta().isEmpty()
                ? Map.of()
                : Map.of("content", chunk.delta());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("object", "chat.completion.chunk");
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("model", request.model());
        payload.put("choices", List.of(new LinkedHashMap<>(Map.of(
                "index", 0,
                "delta", delta,
                "finish_reason", chunk.finishReason() == null ? "" : finishReason(chunk.finishReason())))));

        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("provider", chunk.providerName());
        if (retrieval != null) gateway.put("rag", ragSummary(retrieval));
        payload.put("x_gateway", gateway);

        if (chunk.usage() != null) {
            payload.put("usage", Map.of(
                    "prompt_tokens", chunk.usage().promptTokens(),
                    "completion_tokens", chunk.usage().completionTokens(),
                    "total_tokens", chunk.usage().totalTokens()));
        }
        return payload;
    }

    /** 串流中途失敗時的錯誤事件。沿用 OpenAI 的錯誤形狀，客戶端才解得出來 */
    static Map<String, Object> toErrorEvent(String message, String type, String provider) {
        return Map.of("error", Map.of("message", message, "type", type, "provider", provider));
    }

    /**
     * 回報這次回答依據了什麼。
     *
     * <p>三件事都要出去，因為它們對應三種不同的狀況：
     * <ul>
     *   <li><b>來源與分數</b>——分數普遍偏低代表「檢索到了但都不相關」，
     *       那與「沒檢索到」是不同的問題，只看有沒有結果會把兩者混為一談。</li>
     *   <li><b>degraded</b>——檢索失敗但依設定繼續，**這個答案沒有依據**。
     *       不講的話呼叫端會把它當成有根據的回答。</li>
     *   <li><b>note</b>——沒有命中或降級的原因。</li>
     * </ul>
     */
    private static Map<String, Object> ragSummary(RagAugmenter.RagResult retrieval) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("degraded", retrieval.degraded());
        summary.put("documents", retrieval.documents().stream()
                .map(document -> Map.<String, Object>of(
                        "id", document.id(),
                        "score", document.score()))
                .toList());
        summary.put("embedding_cost_micro_cents", retrieval.embeddingUsage().costMicroCents());
        if (retrieval.note() != null) summary.put("note", retrieval.note());
        return summary;
    }

    private static String finishReason(ChatChunk.FinishReason reason) {
        if (reason == null) return "";
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case TOOL_CALLS -> "tool_calls";
            case UPSTREAM_ABORTED -> "upstream_aborted";
            case ERROR -> "error";
        };
    }
}
