package io.aigateway.core;

import java.util.List;

/**
 * 產生向量的上游。
 *
 * <p>與 {@link LlmProvider} 分開，因為它們的失敗語意不同：
 * chat 失敗可以換一家再試（答案會不一樣但仍然可用），
 * **嵌入不行**——用 A 模型建的索引只能用 A 模型查詢，換一家等於整個檢索失效。
 * 所以這裡沒有對沖，也沒有跨模型的失敗切換。
 *
 * <p>它一樣是**付費的上游呼叫**，必須計入成本帳本。漏掉的話成本倍率會低估，
 * 而低估的比例正好隨檢索量成長。
 */
public interface EmbeddingProvider {

    String name();

    /** 模型識別。索引與查詢必須用同一個，這個字串就是比對的依據 */
    String model();

    /** 向量維度。啟動時要拿它跟 collection 的實際維度比對 */
    int dimension();

    /**
     * 批次產生向量。回傳順序與輸入一一對應。
     *
     * @throws ProviderException 呼叫失敗
     */
    List<float[]> embed(List<String> inputs);

    /** 這次呼叫的用量與成本。實作要保證**不會回傳 0 成本**，理由同 chat 的成本歸戶 */
    Usage lastUsage();
}
