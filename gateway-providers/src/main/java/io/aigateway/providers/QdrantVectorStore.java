package io.aigateway.providers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.aigateway.core.ProviderException;
import io.aigateway.core.RetrievedDocument;
import io.aigateway.core.VectorStore;

/**
 * Qdrant 的 REST 實作，唯讀。
 *
 * <p>用 HTTP 而非 gRPC 客戶端，是為了不再多拉一組相依——這條路徑上的資料量小
 * （一次幾筆文件），HTTP 的額外開銷相對於嵌入呼叫可以忽略。
 */
public class QdrantVectorStore implements VectorStore {

    /** 判斷 collection 用哪個模型建的時候抽樣幾筆 */
    private static final int MODEL_SAMPLE_SIZE = 64;

    private final URI baseUri;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public QdrantVectorStore(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = URI.create(baseUrl.replaceAll("/+$", ""));
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public CollectionInfo describe(String collection) {
        JsonNode info = send("GET", "/collections/" + collection, null, true);
        if (info == null) return null;

        JsonNode vectors = info.path("result").path("config").path("params").path("vectors");
        long pointCount = info.path("result").path("points_count").asLong(0);

        return new CollectionInfo(
                collection,
                vectors.path("size").asInt(0),
                vectors.path("distance").asText(""),
                sampleEmbeddingModel(collection),
                pointCount);
    }

    /**
     * 抽樣 point 的 payload 判斷這個索引是用哪個模型建的。
     *
     * <p>Qdrant 原生**不記錄** collection 是用哪個嵌入模型建的，所以只能靠寫入端
     * 存進每個 point 的 payload。逐點記錄而不是整個 collection 記一次，
     * 是為了同時抓到**部分重建索引**——一半的點來自舊模型、一半來自新模型。
     * 那種狀態比整個索引錯掉更難發現，因為檢索結果會「有時候對、有時候不對」。
     *
     * @return 抽樣中出現多個模型（或完全沒有宣告）時回傳 null，讓啟動檢查去擋
     */
    private String sampleEmbeddingModel(String collection) {
        Map<String, Object> body = Map.of("limit", MODEL_SAMPLE_SIZE, "with_payload", true, "with_vector", false);
        JsonNode scroll = send("POST", "/collections/" + collection + "/points/scroll", body, false);

        Set<String> models = new LinkedHashSet<>();
        for (JsonNode point : scroll.path("result").path("points")) {
            JsonNode model = point.path("payload").path("_embedding_model");
            if (model.isMissingNode() || model.asText("").isBlank()) return null;
            models.add(model.asText());
        }
        return models.size() == 1 ? models.iterator().next() : null;
    }

    @Override
    public List<RetrievedDocument> search(String collection, float[] vector, int topK, double minScore) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", values);
        body.put("limit", topK);
        body.put("with_payload", true);
        if (minScore > 0) body.put("score_threshold", minScore);

        JsonNode response = send("POST", "/collections/" + collection + "/points/search", body, false);

        List<RetrievedDocument> documents = new ArrayList<>();
        for (JsonNode hit : response.path("result")) {
            JsonNode payload = hit.path("payload");
            documents.add(new RetrievedDocument(
                    hit.path("id").asText(),
                    payload.path("text").asText(""),
                    hit.path("score").asDouble(0),
                    payload.path("_embedding_model").asText("")));
        }
        return documents;
    }

    /**
     * @param nullOn404 collection 不存在是合法的查詢結果（啟動檢查要能區分
     *                  「還沒建」與「向量庫掛了」），其餘情況一律拋錯
     */
    private JsonNode send(String method, String path, Object body, boolean nullOn404) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) builder.header("api-key", apiKey);

        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(ProviderJson.write("qdrant", body), StandardCharsets.UTF_8));

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ProviderException("qdrant", ProviderException.Kind.CONNECTION, 502,
                    "向量庫不可用：" + error.getMessage(), error);
        }

        if (response.statusCode() == 404 && nullOn404) return null;
        if (response.statusCode() >= 400) {
            throw new ProviderException("qdrant", ProviderException.Kind.SERVER_ERROR, response.statusCode(),
                    "向量庫回傳 %d：%s".formatted(response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length()))), null);
        }

        return ProviderJson.readTree("qdrant", response.body(), "向量庫回應");
    }

}
