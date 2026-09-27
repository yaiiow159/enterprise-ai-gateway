package io.aigateway.core;

import java.util.Set;

/**
 * 一個可呼叫的 LLM 後端。
 *
 * <p>實作必須是執行緒安全的：同一個實例會被大量虛擬執行緒同時使用。
 */
public interface LlmProvider {

    /** 唯一名稱，用於路由設定、指標標籤與成本歸戶 */
    String name();

    ModelCapability capability();

    /**
     * 這個 Provider 可以服務的對外模型名稱（呼叫端在請求裡寫的 {@code model}）。
     *
     * <p>同一個名稱底下的 Provider 必須是**等價部署**（同一個模型的不同來源），
     * 對沖與失敗切換只會在它們之間進行——跨模型賽跑會讓同一個問題由誰回答變成隨機的。
     */
    Set<String> servedModels();

    /**
     * 非串流呼叫。
     *
     * @throws ProviderException 呼叫失敗；錯誤要先分類再拋，路由才知道能不能換一家重試
     */
    ChatResponse complete(ChatRequest request);

    /**
     * 串流呼叫。回傳後呼叫端必須負責 {@link ChatStream#close()}，
     * 否則上游連線不會被中止——那等於在使用者離開後繼續付錢。
     */
    ChatStream stream(ChatRequest request);
}
