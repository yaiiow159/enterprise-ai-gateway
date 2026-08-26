package io.aigateway.core;

import java.util.Map;

/**
 * 單次請求的檢索設定，來自請求裡的 {@code x_rag} 擴充欄位。
 *
 * <p>為什麼用擴充欄位而不是自動注入或獨立端點：
 * <ul>
 *   <li><b>自動注入</b>——呼叫端不知道發生了什麼。答案變了卻查不出為什麼，
 *       因為送出去的 prompt 和他寫的不一樣。</li>
 *   <li><b>獨立端點</b>——誠實，但 OpenAI SDK 用不了，等於要求每個呼叫端改程式碼。</li>
 *   <li><b>擴充欄位</b>——SDK 用 {@code extra_body} 就能傳，而且是明示的。
 *       回應已經有 {@code x_gateway} 的慣例，請求端對稱地用 {@code x_rag} 最一致。</li>
 * </ul>
 *
 * @param degradeOnFailure 檢索失敗時是否繼續（答案將沒有依據）。
 *                         **預設 false**：靜默降級是這個系統最典型的失敗形態
 */
public record RagOptions(String collection,
                         int topK,
                         double minScore,
                         boolean degradeOnFailure,
                         String tenant,
                         long estimatedEmbeddingCost) {

    public RagOptions {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("x_rag.collection 不可為空");
        }
        if (topK <= 0) throw new IllegalArgumentException("x_rag.top_k 必須為正數");
    }

    /** 從請求的 {@code x_rag} 欄位解析。缺少該欄位時回傳 null，代表這次不做檢索 */
    @SuppressWarnings("unchecked")
    public static RagOptions from(Map<String, Object> body, String tenant, long estimatedEmbeddingCost) {
        Object raw = body.get("x_rag");
        if (!(raw instanceof Map)) return null;

        Map<String, Object> options = (Map<String, Object>) raw;
        return new RagOptions(
                String.valueOf(options.getOrDefault("collection", "")),
                options.get("top_k") == null ? 5 : ((Number) options.get("top_k")).intValue(),
                options.get("min_score") == null ? 0 : ((Number) options.get("min_score")).doubleValue(),
                Boolean.TRUE.equals(options.get("degrade_on_failure")),
                tenant,
                estimatedEmbeddingCost);
    }
}
