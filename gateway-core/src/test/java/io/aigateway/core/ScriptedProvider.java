package io.aigateway.core;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可以指定延遲、錯誤與輸出內容的 Provider 替身，並記錄被呼叫與被關閉的次數。
 *
 * <p>延遲發生在讀第一個 chunk 時，而且可以被中斷——與真的 HTTP 串流一樣，
 * 對沖落敗者被取消時是在「等第一個 token」的途中被打斷。
 */
final class ScriptedProvider implements LlmProvider {

    static final ModelCapability CAPABILITY = new ModelCapability(100_000, 1_000, true, true, false, 10, 20);

    private final String name;
    private final Set<String> servedModels;
    private final Duration firstChunkDelay;
    private final ProviderException failure;
    private final List<String> deltas;
    private final Usage usage;
    /** 模擬第一個 token 已經在路上、中斷來不及攔下的上游 */
    private final boolean ignoresCancel;

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();

    private ScriptedProvider(String name, Set<String> servedModels, Duration firstChunkDelay,
                             ProviderException failure, List<String> deltas, Usage usage, boolean ignoresCancel) {
        this.name = name;
        this.servedModels = servedModels;
        this.firstChunkDelay = firstChunkDelay;
        this.failure = failure;
        this.deltas = deltas;
        this.usage = usage;
        this.ignoresCancel = ignoresCancel;
    }

    /** 延遲 {@code delay} 後送出兩段內容，最後一段帶 {@code usage} */
    static ScriptedProvider answering(String name, String model, Duration delay, Usage usage) {
        return new ScriptedProvider(name, Set.of(model), delay, null, List.of("你好", "世界"), usage, false);
    }

    /** 同 {@link #answering}，但被取消時仍會送出第一個 chunk */
    static ScriptedProvider answeringDespiteCancel(String name, String model, Duration delay, Usage usage) {
        return new ScriptedProvider(name, Set.of(model), delay, null, List.of("你好", "世界"), usage, true);
    }

    /** 延遲 {@code delay} 後以指定的 HTTP 狀態碼失敗 */
    static ScriptedProvider failing(String name, String model, Duration delay, int status) {
        ProviderException error = new ProviderException(name, ProviderException.kindForHttpStatus(status), status,
                "scripted failure", null);
        return new ScriptedProvider(name, Set.of(model), delay, error, List.of(), Usage.EMPTY, false);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelCapability capability() {
        return CAPABILITY;
    }

    @Override
    public Set<String> servedModels() {
        return servedModels;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        throw new UnsupportedOperationException("網關一律走串流");
    }

    @Override
    public ChatStream stream(ChatRequest request) {
        calls.incrementAndGet();
        return new ScriptedStream();
    }

    private final class ScriptedStream implements ChatStream {
        private Iterator<ChatChunk> chunks;
        private boolean closed;

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public boolean hasNext() {
            if (closed) return false;
            if (chunks == null) chunks = firstRead();
            return chunks.hasNext();
        }

        @Override
        public ChatChunk next() {
            if (!hasNext()) throw new NoSuchElementException();
            return chunks.next();
        }

        private Iterator<ChatChunk> firstRead() {
            long deadline = System.nanoTime() + firstChunkDelay.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                } catch (InterruptedException interrupted) {
                    if (ignoresCancel) continue;
                    Thread.currentThread().interrupt();
                    throw new ProviderException(name, ProviderException.Kind.STREAM_ABORTED, 502, "被中止", interrupted);
                }
            }
            if (failure != null) throw failure;
            return List.of(
                    ChatChunk.of(deltas.get(0), name),
                    new ChatChunk(deltas.get(1), ChatChunk.FinishReason.STOP, usage, name)).iterator();
        }

        @Override
        public void close() {
            if (!closed) closes.incrementAndGet();
            closed = true;
        }
    }
}
