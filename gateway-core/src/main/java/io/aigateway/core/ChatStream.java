package io.aigateway.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 串流回應。
 *
 * <p>刻意設計成**阻塞式的迭代**而非 reactive 型別：整個網關跑在虛擬執行緒上，
 * 阻塞讀取是正確且最直白的寫法，堆疊追蹤也完整（見 CLAUDE.md 的技術決定 1）。
 *
 * <p>{@link #close()} 的語意是**取消上游請求**，這是這個介面最重要的部分：
 * 對沖時輸掉的候選、客戶端斷線時的正在進行的請求，都要靠它停止計費。
 * 實作必須真的中斷底層 HTTP 連線，而不只是停止讀取。
 */
public interface ChatStream extends Iterator<ChatChunk>, AutoCloseable {

    /** 產出這個串流的 Provider 名稱 */
    String providerName();

    @Override
    void close();

    /** 把已經讀出來的第一個 chunk 放回串流前端。對沖判定 TTFT 時必然會先讀一段 */
    static ChatStream withPrefetched(ChatChunk prefetched, ChatStream delegate) {
        return new ChatStream() {
            private ChatChunk pending = prefetched;

            @Override
            public String providerName() {
                return delegate.providerName();
            }

            @Override
            public boolean hasNext() {
                return pending != null || delegate.hasNext();
            }

            @Override
            public ChatChunk next() {
                if (pending != null) {
                    ChatChunk chunk = pending;
                    pending = null;
                    return chunk;
                }
                if (!delegate.hasNext()) throw new NoSuchElementException();
                return delegate.next();
            }

            @Override
            public void close() {
                pending = null;
                delegate.close();
            }
        };
    }
}
