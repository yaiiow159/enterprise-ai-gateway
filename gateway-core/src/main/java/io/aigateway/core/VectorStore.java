package io.aigateway.core;

import java.util.List;

/**
 * 向量檢索。
 *
 * <p>**網關只讀，不寫。** 建索引是完全不同的生命週期——批次、慢、要決定分塊策略、
 * 要能重跑。把它塞進網關會讓網關變成資料管線，而且索引任務的失敗會影響線上請求。
 * 寫入交給獨立的工具（見 {@code tools/rag-index.js}）。
 */
public interface VectorStore {

    /**
     * 查詢 collection 的實際設定，供**啟動時**的一致性檢查。
     *
     * <p>為什麼一定要在啟動時檢查：嵌入不一致是靜默失效——用 A 模型建索引、
     * 用 B 模型查詢，向量庫照樣回傳最近鄰，只是全部不相關。系統不會報錯，只會變笨，
     * 而「RAG 效果不好」可以歸咎於一百種原因。等到執行期才發現就太晚了。
     *
     * @return collection 不存在時回傳 null
     */
    CollectionInfo describe(String collection);

    /**
     * 取最相似的 topK 筆。
     *
     * @param minScore 低於這個分數就不回傳。0 表示不過濾
     * @throws ProviderException 向量庫不可用
     */
    List<RetrievedDocument> search(String collection, float[] vector, int topK, double minScore);

    /**
     * collection 的實際設定。
     *
     * @param embeddingModel 建立這個索引時用的嵌入模型。Qdrant 原生**不記錄**這件事，
     *                       所以它必須由寫入端存進每個 point 的 payload——
     *                       不記的話，嵌入不一致事後根本無從偵測。
     *                       抽樣發現多個模型時（重建索引跑到一半中斷）回傳 null。
     * @param pointCount     目前的資料筆數。空 collection 的檢索結果一律為空，但不會報錯
     */
    record CollectionInfo(String name, int dimension, String distance, String embeddingModel, long pointCount) {
    }
}
