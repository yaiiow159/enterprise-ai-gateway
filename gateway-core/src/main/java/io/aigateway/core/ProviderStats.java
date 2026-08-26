package io.aigateway.core;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 單一 Provider 的滾動統計，路由與對沖的判斷依據。
 *
 * <p>量的是 **TTFT（首個 token 抵達）** 而非總完成時間：串流情境下使用者感受到的是首字延遲，
 * 而對沖的勝負也必須在第一個 token 之前分出來——一旦開始把某家的 token 送給客戶端，
 * 就不能中途改用另一家的結果。
 *
 * <p>用固定大小的環狀緩衝而非完整的直方圖：路由只需要「大概多快」，
 * 精確的分位數留給指標系統。這個結構在熱路徑上會被高頻讀取，要夠便宜。
 */
public final class ProviderStats {

    private static final int WINDOW = 128;

    private static final byte UNSET = -1;
    private static final byte SUCCESS = 0;
    private static final byte FAILURE = 1;

    private final String providerName;
    private final long[] ttftSamples = new long[WINDOW];
    private final AtomicLong sampleCount = new AtomicLong();

    /**
     * 最近 {@value #WINDOW} 次嘗試的成敗，與 TTFT 用同一個窗口大小。
     *
     * <p>刻意**不用**累計計數算失敗率：累計值不會衰減，一小時前的一次故障會永遠留在分母裡，
     * 讓已經恢復的 Provider 再也回不到原本的排序位置。而排序越後面就越少被選中、
     * 越少被選中就越沒機會證明自己已經好了——累計失敗率會把暫時故障變成永久降級。
     */
    private final byte[] outcomes = new byte[WINDOW];
    private final AtomicLong outcomeCount = new AtomicLong();

    // 累計值只供指標輸出，不參與路由決策
    private final AtomicLong totalFailures = new AtomicLong();
    private final AtomicLong totalSuccesses = new AtomicLong();

    public ProviderStats(String providerName) {
        this.providerName = providerName;
        Arrays.fill(ttftSamples, -1);
        Arrays.fill(outcomes, UNSET);
    }

    public String providerName() {
        return providerName;
    }

    public void recordTtft(Duration ttft) {
        long index = sampleCount.getAndIncrement();
        // 環狀寫入；容忍極少數的競態覆寫，統計值不需要精確到單筆
        ttftSamples[(int) (index % WINDOW)] = ttft.toMillis();
        totalSuccesses.incrementAndGet();
        recordOutcome(SUCCESS);
    }

    public void recordFailure() {
        totalFailures.incrementAndGet();
        recordOutcome(FAILURE);
    }

    private void recordOutcome(byte outcome) {
        long index = outcomeCount.getAndIncrement();
        outcomes[(int) (index % WINDOW)] = outcome;
    }

    /**
     * 目前窗口的 TTFT 分位數。樣本不足時回傳 {@code fallback}——
     * 剛啟動就用兩三個樣本推估 p95 會得到極不穩定的值，反而讓路由亂跳。
     */
    public Duration ttftPercentile(double percentile, Duration fallback) {
        long[] snapshot = Arrays.stream(ttftSamples).filter(value -> value >= 0).sorted().toArray();
        if (snapshot.length < 8) return fallback;
        int index = Math.min(snapshot.length - 1, (int) Math.floor(snapshot.length * percentile));
        return Duration.ofMillis(snapshot[index]);
    }

    /** 最近一個窗口內的失敗比例。沒有任何紀錄時回傳 0（新 Provider 不該被預設懲罰） */
    public double failureRate() {
        int failed = 0;
        int total = 0;
        for (byte outcome : outcomes) {
            if (outcome == UNSET) continue;
            total++;
            if (outcome == FAILURE) failed++;
        }
        return total == 0 ? 0 : (double) failed / total;
    }

    public long sampleCount() {
        return sampleCount.get();
    }

    public long totalSuccesses() {
        return totalSuccesses.get();
    }

    public long totalFailures() {
        return totalFailures.get();
    }
}
