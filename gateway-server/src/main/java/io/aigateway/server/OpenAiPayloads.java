package io.aigateway.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 原樣轉送給上游的請求欄位。
     *
     * <p>用白名單而不是「全部轉送」：未知欄位可能改變計費（例如 {@code n} 會讓輸出乘上 n 倍、
     * 預扣卻只算一份），或讓網關自己決定的欄位被覆蓋。白名單以外的欄位靜靜略過，
     * 與 OpenAI 對未知欄位的處理一致。
     */
    static final Set<String> PASSTHROUGH = Set.of(
            "tools", "tool_choice", "parallel_tool_calls", "response_format", "stop", "top_p", "seed",
            "presence_penalty", "frequency_penalty", "logit_bias", "logprobs", "top_logprobs", "user");

    private OpenAiPayloads() {
    }

    /**
     * 解析請求。刻意不做寬鬆容錯：欄位缺漏或型別錯誤要當場以 400 失敗，而不是帶著空值往下走。
     *
     * @param defaultMaxTokens 請求沒帶 max_tokens／max_completion_tokens 時套用的輸出上限
     * @throws IllegalArgumentException 請求格式錯誤
     */
    @SuppressWarnings("unchecked")
    static ChatRequest toChatRequest(Map<String, Object> body, String tenant, int defaultMaxTokens) {
        if (!(body.get("model") instanceof String model) || model.isBlank()) {
            throw new IllegalArgumentException("model 不可為空");
        }
        if (!(body.get("messages") instanceof List<?> rawMessages) || rawMessages.isEmpty()) {
            throw new IllegalArgumentException("messages 不可為空");
        }
        if (body.get("n") != null && number(body, "n").intValue() != 1) {
            throw new IllegalArgumentException("網關只支援 n=1：多個候選回答會讓輸出成本乘上 n 倍");
        }

        List<Message> messages = rawMessages.stream()
                .map(raw -> {
                    if (!(raw instanceof Map<?, ?> message)) throw new IllegalArgumentException("messages 的每一項都要是物件");
                    return toMessage((Map<String, Object>) message);
                })
                .toList();

        // max_completion_tokens 是 OpenAI 較新的名稱，新版 SDK 對部分模型只送這個
        Number maxTokens = body.get("max_tokens") != null ? number(body, "max_tokens")
                : body.get("max_completion_tokens") != null ? number(body, "max_completion_tokens")
                : defaultMaxTokens;

        Map<String, Object> extra = new LinkedHashMap<>();
        for (String key : PASSTHROUGH) {
            if (body.get(key) != null) extra.put(key, body.get(key));
        }

        return new ChatRequest(
                model,
                messages,
                maxTokens.intValue(),
                Boolean.TRUE.equals(body.get("stream")),
                body.get("temperature") == null ? null : number(body, "temperature").doubleValue(),
                tenant,
                extra);
    }

    /** 保留原始訊息（多模態陣列、tool_calls、tool_call_id），文字部分另外取出供估算與檢索 */
    private static Message toMessage(Map<String, Object> raw) {
        Object role = raw.get("role");
        if (!(role instanceof String roleName)) throw new IllegalArgumentException("訊息缺少 role");
        return new Message(Message.Role.parse(roleName), textOf(raw.get("content")), raw);
    }

    /** content 可能是字串、內容片段陣列，或 null（只帶 tool_calls 的 assistant 訊息） */
    private static String textOf(Object content) {
        if (content == null) return "";
        if (content instanceof String text) return text;
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") instanceof String value) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(value);
                }
            }
            return text.toString();
        }
        throw new IllegalArgumentException("content 必須是字串或內容片段陣列");
    }

    private static Number number(Map<String, Object> body, String key) {
        if (body.get(key) instanceof Number number) return number;
        throw new IllegalArgumentException(key + " 必須是數字");
    }

    static Map<String, Object> toResponse(ChatResponse response, RagAugmenter.RagResult retrieval) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        // OpenAI 的慣例：只呼叫工具、沒有文字時 content 是 null
        message.put("content", response.content().isEmpty() && !response.toolCalls().isEmpty()
                ? null : response.content());
        if (!response.toolCalls().isEmpty()) message.put("tool_calls", response.toolCalls());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason(response.finishReason()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", response.id());
        payload.put("object", "chat.completion");
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("model", response.model());
        payload.put("choices", List.of(choice));
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

    /**
     * @param first     是不是這個串流的第一個 chunk：OpenAI 在第一個 delta 帶 role
     * @param retrieval 只在第一個 chunk 帶檢索資訊；其餘傳 null
     */
    static Map<String, Object> toChunk(ChatChunk chunk, ChatRequest request, boolean first,
                                       RagAugmenter.RagResult retrieval) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (first) delta.put("role", "assistant");
        if (chunk.delta() != null && !chunk.delta().isEmpty()) delta.put("content", chunk.delta());
        if (chunk.hasToolCalls()) delta.put("tool_calls", chunk.toolCalls());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        // 規格上未結束時是 null；空字串會讓部分 SDK 以為收到了一個不認得的結束原因
        choice.put("finish_reason", chunk.finishReason() == null ? null : finishReason(chunk.finishReason()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("object", "chat.completion.chunk");
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("model", request.model());
        payload.put("choices", List.of(choice));

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

    /** {@code GET /v1/models} 的回應 */
    static Map<String, Object> toModelList(Set<String> models) {
        List<Map<String, Object>> data = models.stream().sorted()
                .map(id -> Map.<String, Object>of("id", id, "object", "model", "created", 0, "owned_by", "ai-gateway"))
                .toList();
        return Map.of("object", "list", "data", data);
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
        if (reason == null) return null;
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
