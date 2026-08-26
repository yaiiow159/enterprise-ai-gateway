package io.aigateway.core;

/**
 * 檢索到的一筆文件。
 *
 * @param id             向量庫裡的識別碼，讓答案可以被追溯回來源
 * @param text           文件內容，會被注入 prompt
 * @param score          相似度。回傳給呼叫端是刻意的——分數普遍偏低時，
 *                       代表「檢索到了但都不相關」，那與「沒檢索到」是不同的問題，
 *                       只看有沒有結果會把兩者混為一談
 * @param embeddingModel 這筆資料是用哪個模型嵌入的
 */
public record RetrievedDocument(String id, String text, double score, String embeddingModel) {
}
