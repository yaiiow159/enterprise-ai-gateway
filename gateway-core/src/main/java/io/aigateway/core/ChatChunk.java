package io.aigateway.core;

/**
 * 串流中的一段增量內容。
 *
 * @param delta        這一段新增的文字
 * @param finishReason 非 null 表示這是最後一段
 * @param usage        通常只有最後一段才帶用量
 * @param providerName 產出這段內容的 Provider，供除錯與歸戶使用
 */
public record ChatChunk(String delta, FinishReason finishReason, Usage usage, String providerName) {

    public static ChatChunk of(String delta, String providerName) {
        return new ChatChunk(delta, null, null, providerName);
    }

    public boolean isTerminal() {
        return finishReason != null;
    }

    public enum FinishReason {
        STOP,
        LENGTH,
        CONTENT_FILTER,
        TOOL_CALLS,
        /** 上游在送出結束標記前就斷線。串流已經送出的部分無法收回，呼叫端必須能分辨這個情況 */
        UPSTREAM_ABORTED,
        ERROR
    }
}
