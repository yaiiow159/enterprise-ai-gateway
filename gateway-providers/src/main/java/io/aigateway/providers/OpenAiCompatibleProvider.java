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

        JsonNode root = ProviderJson.readTree(name, response.body(), "回應");
        JsonNode choice = root.path("choices").path(0);
        return new ChatResponse(
                root.path("id").asText(""),
                root.path("model").asText(model),
                name,
                choice.path("message").path("content").asText(""),
                finishReason(choice.path("finish_reason").asText(null)),
                parseUsage(root.path("usage"), capability, request.estimatedPromptTokens(), -1),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
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

        return new SseChatStream(name, response.body(), capability, request.estimatedPromptTokens());
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
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(name, payload), StandardCharsets.UTF_8));

        // 金鑰只從設定注入，且永遠不進日誌或錯誤訊息
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
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
        return new ProviderException(name, ProviderException.kindForHttpStatus(status), status,
                extractErrorMessage(body), null);
    }

    /** 只取上游錯誤訊息本身，避免把整包回應（可能含請求內容）帶進日誌 */
    private String extractErrorMessage(String body) {
        // 這裡刻意用 quietly：真正的錯誤是 HTTP 狀態碼，
        // 不該被「錯誤回應剛好不是 JSON」這件事蓋掉
        JsonNode parsed = ProviderJson.readTreeQuietly(body);
        if (parsed != null) {
            JsonNode message = parsed.path("error").path("message");
            if (!message.isMissingNode()) return message.asText();
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

    /**
     * 解析用量並算出成本。
     *
     * <p><b>絕對不能回傳成本 0</b>。早期版本只讀 {@code estimated_cost_cents}——
     * 而那是本專案假 Provider 才有的欄位，真的 Provider 不回它。後果是接上真 Provider
     * 之後每一筆的成本都記成 0：不會報錯，帳目上永遠看起來免費，直到對帳的時候。
     * 這也讓任何以成本為基礎的防線（每日上限、成本歸戶）全部失效，
     * 因為它們看到的永遠是零。
     *
     * <p>所以有兩層兜底：
     * <ol>
     *   <li>上游沒回成本 → 用 {@link ModelCapability} 的單價自行推算。</li>
     *   <li>上游連 usage 都沒回（**串流時很常見**，OpenAI 要另外送
     *       {@code stream_options.include_usage} 才會給）→ 用本地估算的 token 數推算。</li>
     * </ol>
     *
     * <p>估算會有誤差，但**估錯的成本遠小於記成零的成本**：前者是帳目偏差幾個百分點，
     * 後者是防線完全不存在。
     *
     * @param promptEstimate   本地估算的輸入 token 數，上游沒給時使用
     * @param completionCount  實際看到的輸出 chunk 數，上游沒給時使用；-1 表示不可用
     */
    private static Usage parseUsage(JsonNode node,
                                    ModelCapability capability,
                                    long promptEstimate,
                                    long completionCount) {
        long prompt = node.path("prompt_tokens").asLong(0);
        long completion = node.path("completion_tokens").asLong(0);

        if (prompt <= 0 && promptEstimate > 0) prompt = promptEstimate;
        if (completion <= 0 && completionCount >= 0) completion = completionCount;

        long upstreamCost = Math.round(node.path("estimated_cost_cents").asDouble(0) * 1_000_000);
        long cost = upstreamCost > 0
                ? upstreamCost
                : prompt * capability.inputMicroCentsPerToken()
                        + completion * capability.outputMicroCentsPerToken();

        return new Usage(prompt, completion, cost);
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
        private final ModelCapability capability;
        private final long promptEstimate;

        private ChatChunk nextChunk;
        private boolean finished;
        /** 上游有沒有自己回報用量。沒有的話結束前要補一個，否則這次呼叫的成本會是 0 */
        private boolean usageReported;
        private long contentChunks;

        SseChatStream(String providerName, InputStream source, ModelCapability capability, long promptEstimate) {
            this.providerName = providerName;
            this.source = source;
            this.capability = capability;
            this.promptEstimate = promptEstimate;
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
                        // 上游沒回用量就自己補一個。少了這一步，串流請求的成本一律是 0，
                        // 而每日成本上限看到的就永遠是零——一道不會觸發的防線
                        return usageReported ? null : syntheticUsageChunk();
                    }

                    JsonNode root = ProviderJson.readTree(providerName, payload, "串流 chunk");
                    JsonNode choice = root.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    ChatChunk.FinishReason reason = finishReason(choice.path("finish_reason").asText(null));

                    Usage usage = null;
                    if (root.has("usage") && !root.path("usage").isNull()) {
                        usage = parseUsage(root.path("usage"), capability, promptEstimate, contentChunks);
                        usageReported = true;
                    }
                    if (!delta.isEmpty()) contentChunks += 1;

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

        /**
         * 上游沒回用量時，用本地估算補一個。
         *
         * <p>OpenAI 的串流預設**不回** usage，要另外送 {@code stream_options.include_usage}。
         * 本專案的假 Provider 一律會回，所以這條路徑在假 Provider 上永遠走不到——
         * 這正是測試替身造成的盲點：替身比真貨慷慨，程式碼就理所當然地依賴那份慷慨。
         * 假 Provider 因此加了 {@code omitUsage} 設定來重現真貨的這個限制。
         */
        private ChatChunk syntheticUsageChunk() {
            usageReported = true;
            Usage estimated = parseUsage(ProviderJson.mapper().createObjectNode(),
                    capability, promptEstimate, contentChunks);
            log.debug("{} 未回報用量，以本地估算補上：輸入 {}、輸出 {} token",
                    providerName, estimated.promptTokens(), estimated.completionTokens());
            return new ChatChunk("", null, estimated, providerName);
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
