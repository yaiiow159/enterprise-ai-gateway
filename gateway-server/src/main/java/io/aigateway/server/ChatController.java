package io.aigateway.server;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aigateway.core.ChatChunk;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatResponse;
import io.aigateway.core.ChatStream;
import io.aigateway.core.HedgingChatClient;
import io.aigateway.core.Message;
import io.aigateway.core.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * OpenAI 相容的對話端點。
 *
 * <p>刻意對齊 OpenAI 的請求與回應格式：既有的 SDK（Python {@code openai}、LangChain、
 * 各種前端）只要改 base URL 就能接進來，不必為了網關重寫呼叫端。
 *
 * <p>串流用 {@link SseEmitter} 而非 reactive 型別——整個網關跑在虛擬執行緒上，
 * 逐 chunk 轉發用直線程式碼就能寫完（見 CLAUDE.md 的技術決定 1）。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HedgingChatClient client;

    ChatController(HedgingChatClient client) {
        this.client = client;
    }

    @PostMapping(path = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletions(@RequestBody Map<String, Object> body,
                                  @RequestHeader(value = "X-Tenant", required = false) String tenant) {
        ChatRequest request = toChatRequest(body, tenant);
        return request.stream() ? streamResponse(request) : ResponseEntity.ok(toOpenAiResponse(client.complete(request)));
    }

    /**
     * 串流轉發。
     *
     * <p><b>先取得上游串流，再建立 {@link SseEmitter}</b>，順序不可以顛倒。
     * 一旦把 emitter 回傳給 Spring，回應狀態碼就送出去了；那之後才發現上游失敗，
     * 就只能用一個錯誤事件補救，而 HTTP 狀態碼已經是 200。對只檢查狀態碼的客戶端、
     * 監控與重試中介層來說，那是一次「成功」——上游全部掛掉也一樣。
     * {@link HedgingChatClient#stream} 會阻塞到某個候選送出第一個 token，
     * 在那之前拋出的錯誤都還來得及變成正確的狀態碼（429 仍是 429）。
     *
     * <p>代價是回應標頭會等到第一個 token 才送出。這是刻意的取捨：對沖依定義要以
     * 首個 token 決勝負（見 CLAUDE.md 技術決定 4），所以「連上了但還沒有內容」這個
     * 中間狀態無法在不先承諾狀態碼的情況下傳達。誠實的狀態碼比提早幾百毫秒的標頭重要。
     *
     * <p>其餘容易寫錯的地方：
     * <ul>
     *   <li>逾時設為 0（不逾時）——LLM 的生成時間本來就長，用預設的 30 秒會把長回應砍斷。</li>
     *   <li>{@code onCompletion} 與 {@code onError} 都要關上游串流。客戶端斷線時
     *       SseEmitter 會走 onError，沒關就是使用者早就離開、我們還在付錢。</li>
     *   <li>轉發要在**另一條虛擬執行緒**上跑，不要佔住請求執行緒。</li>
     * </ul>
     */
    private SseEmitter streamResponse(ChatRequest request) {
        ChatStream upstream = client.stream(request);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean();

        emitter.onCompletion(() -> closeQuietly(upstream, closed));
        emitter.onTimeout(() -> closeQuietly(upstream, closed));
        emitter.onError(error -> closeQuietly(upstream, closed));

        Thread.ofVirtual().name("sse-relay-" + request.model()).start(() -> {
            try {
                while (upstream.hasNext()) {
                    ChatChunk chunk = upstream.next();
                    emitter.send(SseEmitter.event().data(toOpenAiChunk(chunk, request), MediaType.APPLICATION_JSON));
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (ProviderException error) {
                sendErrorEvent(emitter, error);
            } catch (IOException error) {
                log.debug("客戶端已斷線，停止轉發：{}", error.getMessage());
                emitter.completeWithError(error);
            } catch (RuntimeException error) {
                log.error("串流轉發失敗", error);
                emitter.completeWithError(error);
            } finally {
                closeQuietly(upstream, closed);
            }
        });
        return emitter;
    }

    private void sendErrorEvent(SseEmitter emitter, ProviderException error) {
        try {
            emitter.send(SseEmitter.event().data(MAPPER.writeValueAsString(Map.of(
                    "error", Map.of(
                            "message", error.getMessage(),
                            "type", error.kind().name().toLowerCase(),
                            "provider", error.providerName()))), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(error);
        }
    }

    private void closeQuietly(ChatStream stream, AtomicBoolean closed) {
        if (stream != null && closed.compareAndSet(false, true)) {
            stream.close();
        }
    }

    @SuppressWarnings("unchecked")
    private ChatRequest toChatRequest(Map<String, Object> body, String tenant) {
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

    private Map<String, Object> toOpenAiResponse(ChatResponse response) {
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
        payload.put("x_gateway", Map.of(
                "provider", response.providerName(),
                "latency_ms", response.latencyMs(),
                "cost_micro_cents", response.usage().costMicroCents()));
        return payload;
    }

    private String toOpenAiChunk(ChatChunk chunk, ChatRequest request) {
        try {
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
            payload.put("x_gateway", Map.of("provider", chunk.providerName()));
            if (chunk.usage() != null) {
                payload.put("usage", Map.of(
                        "prompt_tokens", chunk.usage().promptTokens(),
                        "completion_tokens", chunk.usage().completionTokens(),
                        "total_tokens", chunk.usage().totalTokens()));
            }
            return MAPPER.writeValueAsString(payload);
        } catch (IOException error) {
            throw new IllegalStateException("無法序列化串流 chunk", error);
        }
    }

    private String finishReason(ChatChunk.FinishReason reason) {
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

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<Map<String, Object>> handleProviderException(ProviderException error) {
        HttpStatus status = switch (error.kind()) {
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AUTHENTICATION -> HttpStatus.UNAUTHORIZED;
            case BAD_REQUEST, CONTENT_FILTERED -> HttpStatus.BAD_REQUEST;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "message", error.getMessage(),
                "type", error.kind().name().toLowerCase(),
                "provider", error.providerName())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "message", error.getMessage(),
                "type", "invalid_request_error")));
    }
}
