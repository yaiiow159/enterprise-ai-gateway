package io.aigateway.providers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.aigateway.core.EmbeddingProvider;
import io.aigateway.core.ProviderException;
import io.aigateway.core.Usage;

/**
 * 對接 OpenAI 相容的 {@code /v1/embeddings}。
 *
 * <p>與 chat 不同，這裡沒有串流也沒有取消——嵌入是一次性的短呼叫。
 * 但**成本歸戶的問題完全相同**：不能因為上游沒回 usage 就記成 0。
 */
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final String name;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final long inputMicroCentsPerToken;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /**
     * 最近一次呼叫的用量。
     *
     * <p>用 ThreadLocal 而不是實例欄位：多個虛擬執行緒同時呼叫時，
     * 共用一個欄位會互相覆蓋，於是 A 請求的成本被記到 B 頭上。
     * 這種錯誤不會報錯，只會讓帳目悄悄對不起來。
     */
    private final ThreadLocal<Usage> lastUsage = ThreadLocal.withInitial(() -> Usage.EMPTY);

    public OpenAiCompatibleEmbeddingProvider(String name,
                                             String baseUrl,
                                             String apiKey,
                                             String model,
                                             int dimension,
                                             long inputMicroCentsPerToken,
                                             Duration connectTimeout,
                                             Duration requestTimeout) {
        this.name = name;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/embeddings");
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.inputMicroCentsPerToken = inputMicroCentsPerToken;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(build(inputs), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ProviderException(name, ProviderException.Kind.CONNECTION, 502,
                    "嵌入呼叫失敗：" + error.getMessage(), error);
        }

        if (response.statusCode() >= 400) {
            throw new ProviderException(name, ProviderException.kindForHttpStatus(response.statusCode()),
                    response.statusCode(), "嵌入呼叫回傳 " + response.statusCode(), null);
        }

        JsonNode root = ProviderJson.readTree(name, response.body(), "嵌入回應");

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode values = item.path("embedding");
            float[] vector = new float[values.size()];
            for (int index = 0; index < values.size(); index++) {
                vector[index] = (float) values.get(index).asDouble();
            }
            vectors.add(vector);
        }

        if (vectors.size() != inputs.size()) {
            throw new ProviderException(name, ProviderException.Kind.SERVER_ERROR, 502,
                    "回傳 %d 筆向量，但送出 %d 筆輸入".formatted(vectors.size(), inputs.size()), null);
        }
        // 維度不符要當場擋下來，不要讓一個維度錯誤的向量流進檢索——
        // 向量庫會直接報錯，但錯誤訊息會指向查詢而不是這裡
        for (float[] vector : vectors) {
            if (vector.length != dimension) {
                throw new ProviderException(name, ProviderException.Kind.SERVER_ERROR, 502,
                        "上游回傳 %d 維，設定為 %d 維".formatted(vector.length, dimension), null);
            }
        }

        lastUsage.set(parseUsage(root.path("usage"), inputs));
        return vectors;
    }

    @Override
    public Usage lastUsage() {
        return lastUsage.get();
    }

    /** 與 chat 同樣的兩層兜底：上游沒回成本用本地單價算，連 usage 都沒回就自己估 token */
    private Usage parseUsage(JsonNode node, List<String> inputs) {
        long promptTokens = node.path("prompt_tokens").asLong(0);
        if (promptTokens <= 0) {
            // 保守估算：中日韓約 1.5 字元／token，取 2 以免低估而讓成本被少算
            long characters = inputs.stream().mapToLong(String::length).sum();
            promptTokens = Math.max(1, characters / 2);
        }

        long upstreamCost = Math.round(node.path("estimated_cost_cents").asDouble(0) * 1_000_000);
        long cost = upstreamCost > 0 ? upstreamCost : promptTokens * inputMicroCentsPerToken;
        return new Usage(promptTokens, 0, cost);
    }

    private HttpRequest build(List<String> inputs) {
        Map<String, Object> payload = Map.of("model", model, "input", inputs);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(name, payload), StandardCharsets.UTF_8));
        // 金鑰只從設定注入，永遠不進日誌或錯誤訊息
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        return builder.build();
    }

}
