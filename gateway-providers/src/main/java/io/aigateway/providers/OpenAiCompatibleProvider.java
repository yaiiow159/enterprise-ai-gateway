package io.aigateway.providers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aigateway.core.ChatChunk;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatResponse;
import io.aigateway.core.ChatStream;
import io.aigateway.core.LlmProvider;
import io.aigateway.core.ModelCapability;
import io.aigateway.core.ProviderException;
import io.aigateway.core.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 對接任何 OpenAI 相容的端點：OpenAI 本身、Ollama、vLLM，以及本專案的假 Provider。
 *
 * <p>用 JDK 內建的 {@link HttpClient} 而非 Spring 的 RestClient，理由是**取消**：
 * 串流以 {@code BodyHandlers.ofInputStream()} 取得，關閉那個 InputStream 會中止底層交換，
 * 上游因此停止產生 token。取消能不能真的傳到上游，是這個系統會不會「使用者離開後
 * 還在付錢」的關鍵，不能交給一層看不見底的抽象。
 *
 * <p>阻塞式呼叫在虛擬執行緒上是正確用法，不需要非同步 API。
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final ModelCapability capability;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public OpenAiCompatibleProvider(String name,
                                    String baseUrl,
                                    String apiKey,
                                    String model,
                                    ModelCapability capability,
                                    Duration connectTimeout,
                                    Duration requestTimeout) {
        this.name = name;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.capability = capability;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelCapability capability() {
        return capability;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(buildRequest(request, false), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw connectionFailure(error);
        }

        if (response.statusCode() >= 400) {
            throw toProviderException(response.statusCode(), response.body());
        }

        try {
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            return new ChatResponse(
                    root.path("id").asText(""),
                    root.path("model").asText(model),
                    name,
                    choice.path("message").path("content").asText(""),
                    finishReason(choice.path("finish_reason").asText(null)),
                    parseUsage(root.path("usage")),
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        } catch (IOException error) {
            throw new ProviderException(name, ProviderException.Kind.SERVER_ERROR, response.statusCode(),
                    "回應不是合法的 JSON", error);
        }
    }

    @Override
    public ChatStream stream(ChatRequest request) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(buildRequest(request, true), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw connectionFailure(error);
        }

        if (response.statusCode() >= 400) {
            String body = readAll(response.body());
            throw toProviderException(response.statusCode(), body);
        }

        return new SseChatStream(name, response.body());
    }

    private HttpRequest buildRequest(ChatRequest request, boolean streaming) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", request.messages().stream()
                .map(message -> Map.of(
                        "role", message.role().name().toLowerCase(),
                        "content", message.content()))
                .toList());
        // max_tokens 一定會送出：ChatRequest 在建構時就強制要求，這裡不需要再判斷 null
        payload.put("max_tokens", request.maxTokens());
        payload.put("stream", streaming);
        if (request.temperature() != null) payload.put("temperature", request.temperature());
        payload.putAll(request.extra());

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));

        // 金鑰只從設定注入，且永遠不進日誌或錯誤訊息
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException error) {
            throw new ProviderException(name, ProviderException.Kind.BAD_REQUEST, 400, "無法序列化請求", error);
        }
    }

    private ProviderException connectionFailure(Exception error) {
        ProviderException.Kind kind = error instanceof HttpTimeoutException
                ? ProviderException.Kind.TIMEOUT
                : ProviderException.Kind.CONNECTION;
        return new ProviderException(name, kind, 502, String.valueOf(error.getMessage()), error);
    }

    /**
     * HTTP 狀態碼轉成分類過的錯誤。
     *
     * <p>分類的目的是決定「值不值得換一家再試」：429 與 5xx 值得，
     * 400 與 401 換誰都一樣，重試只是多付一次錢又多等一次。
     */
    private ProviderException toProviderException(int status, String body) {
        ProviderException.Kind kind = switch (status) {
            case 400, 404, 422 -> ProviderException.Kind.BAD_REQUEST;
            case 401, 403 -> ProviderException.Kind.AUTHENTICATION;
            case 429 -> ProviderException.Kind.RATE_LIMITED;
            default -> status >= 500 ? ProviderException.Kind.SERVER_ERROR : ProviderException.Kind.BAD_REQUEST;
        };
        return new ProviderException(name, kind, status, extractErrorMessage(body), null);
    }

    /** 只取上游錯誤訊息本身，避免把整包回應（可能含請求內容）帶進日誌 */
    private String extractErrorMessage(String body) {
        try {
            JsonNode message = MAPPER.readTree(body).path("error").path("message");
            if (!message.isMissingNode()) return message.asText();
        } catch (IOException ignored) {
            // 上游未必回 JSON，退回截斷後的原文
        }
        return body == null ? "" : body.substring(0, Math.min(200, body.length()));
    }

    private static String readAll(InputStream input) {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    private static ChatChunk.FinishReason finishReason(String raw) {
        if (raw == null) return null;
        return switch (raw) {
            case "stop" -> ChatChunk.FinishReason.STOP;
            case "length" -> ChatChunk.FinishReason.LENGTH;
            case "content_filter" -> ChatChunk.FinishReason.CONTENT_FILTER;
            case "tool_calls" -> ChatChunk.FinishReason.TOOL_CALLS;
            default -> ChatChunk.FinishReason.STOP;
        };
    }

    private static Usage parseUsage(JsonNode node) {
        if (node.isMissingNode()) return Usage.EMPTY;
        long prompt = node.path("prompt_tokens").asLong(0);
        long completion = node.path("completion_tokens").asLong(0);
        // 假 Provider 會回這個欄位；真的 Provider 不會，成本由 ModelCapability 的單價推算
        long costMicroCents = Math.round(node.path("estimated_cost_cents").asDouble(0) * 1_000_000);
        return new Usage(prompt, completion, costMicroCents);
    }

    /**
     * 逐行解析 SSE。
     *
     * <p>{@link #close()} 會關閉底層 InputStream，JDK 的 HttpClient 會因此中止該次交換——
     * 這正是對沖落敗者與客戶端斷線時停止計費的機制。
     */
    static final class SseChatStream implements ChatStream {

        private final String providerName;
        private final InputStream source;
        private final BufferedReader reader;
        private ChatChunk nextChunk;
        private boolean finished;

        SseChatStream(String providerName, InputStream source) {
            this.providerName = providerName;
            this.source = source;
            this.reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public boolean hasNext() {
            if (nextChunk != null) return true;
            if (finished) return false;
            nextChunk = readNext();
            return nextChunk != null;
        }

        @Override
        public ChatChunk next() {
            if (!hasNext()) throw new NoSuchElementException();
            ChatChunk chunk = nextChunk;
            nextChunk = null;
            return chunk;
        }

        private ChatChunk readNext() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();

                    if ("[DONE]".equals(payload)) {
                        finished = true;
                        return null;
                    }

                    JsonNode root = MAPPER.readTree(payload);
                    JsonNode choice = root.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    ChatChunk.FinishReason reason = finishReason(choice.path("finish_reason").asText(null));
                    Usage usage = root.has("usage") ? parseUsage(root.path("usage")) : null;

                    // 結束標記本身通常沒有內容，但帶著 usage，仍要往下傳
                    if (delta.isEmpty() && reason == null && usage == null) continue;
                    return new ChatChunk(delta, reason, usage, providerName);
                }
            } catch (IOException error) {
                finished = true;
                // 串流中途斷線：已經送出的內容無法收回，只能讓呼叫端知道它是不完整的
                throw new ProviderException(providerName, ProviderException.Kind.STREAM_ABORTED, 502,
                        "串流在結束前中斷：" + error.getMessage(), error);
            }

            // 讀到 EOF 卻沒看到 [DONE]，同樣是不完整的串流
            finished = true;
            throw new ProviderException(providerName, ProviderException.Kind.STREAM_ABORTED, 502,
                    "上游在送出結束標記前就關閉連線", null);
        }

        @Override
        public void close() {
            finished = true;
            nextChunk = null;
            try {
                // 關閉 InputStream 即中止 HTTP 交換，上游會停止產生 token。
                // 這一行就是「使用者離開後不再付錢」的實際機制
                source.close();
            } catch (IOException error) {
                log.debug("關閉 {} 的串流時發生例外（多半是連線已中止）", providerName, error);
            } catch (UncheckedIOException ignored) {
                // HttpClient 在已取消的交換上關閉可能拋這個，屬預期
            }
        }
    }

}
