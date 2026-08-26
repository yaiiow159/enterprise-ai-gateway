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
    /**
     * 送出的上游請求數。
     *
     * <p>與 successes + failures 不同：被對沖中止的落敗者兩邊都不算，
     * 但它**確實送出去了、確實花了錢**。成本倍率要用這個數字算，
     * 用成功數算會低估——而低估的正是對沖多花的那一部分。
     */
    private final AtomicLong totalAttempts = new AtomicLong();
    /** 被中止的次數（對沖落敗或客戶端斷線）。這是「省下來的錢」的直接證據 */
    private final AtomicLong totalCancelled = new AtomicLong();

    /** 下一次半開探測的時間點。{@link #NOT_SCHEDULED} 表示目前沒有在降級狀態 */
    private static final long NOT_SCHEDULED = Long.MIN_VALUE;
    private final AtomicLong nextProbeAtNanos = new AtomicLong(NOT_SCHEDULED);

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

    /**
     * 嘗試取得一次「半開探測」的資格。
     *
     * <p>解決的是滾動窗口留下的另一半問題：窗口讓**恢復成為可能**，
     * 但恢復的前提是「還會被選中」。一家 Provider 連續失敗到排序墊底之後，
     * 若前面的候選都健康，它就永遠不會再被呼叫，也就永遠不會有新的樣本刷新統計——
     * 暫時故障於是變成永久除名。
     *
     * <p>所以要主動放行：降級中的 Provider 每隔一段時間拿到一次機會。
     * 探測失敗的代價由失敗切換兜住（使用者多等一次切換，而不是收到錯誤）；
     * 探測成功則清空失敗窗口，立刻回到正常排序——這一點很重要，
     * 否則以 128 筆窗口計，光靠探測流量要跑幾十次才爬得出來，
     * 一次幾分鐘的故障會拖成幾小時的降級。
     *
     * <p>用 CAS 而不是「讀了再寫」：同一時刻可能有上百個請求同時判斷，
     * 沒有 CAS 就會一次放行一整批探測，那不是探測而是把流量倒回故障節點。
     *
     * @return 只有搶到資格的那一個呼叫者會拿到 true
     */
    public boolean tryClaimProbe(Duration interval, double failureThreshold) {
        if (failureRate() < failureThreshold) return false;

        long now = System.nanoTime();
        long scheduled = nextProbeAtNanos.get();

        if (scheduled == NOT_SCHEDULED) {
            // 剛進入降級狀態：先排定下一次，不要立刻探——
            // 故障當下馬上探幾乎必然失敗，只是多打一次已經有問題的上游
            nextProbeAtNanos.compareAndSet(NOT_SCHEDULED, now + interval.toNanos());
            return false;
        }
        if (now < scheduled) return false;
        return nextProbeAtNanos.compareAndSet(scheduled, now + interval.toNanos());
    }

    /**
     * 探測成功後清空失敗紀錄，讓它立刻回到正常排序。
     *
     * <p>對應熔斷器的「半開 → 關閉」轉換。不清的話，窗口要靠探測流量慢慢稀釋，
     * 恢復時間會被拉長到與故障時間完全不成比例。
     */
    public void clearFailures() {
        Arrays.fill(outcomes, UNSET);
        outcomeCount.set(0);
        nextProbeAtNanos.set(NOT_SCHEDULED);
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

    public void recordAttempt() {
        totalAttempts.incrementAndGet();
    }

    public void recordCancelled() {
        totalCancelled.incrementAndGet();
    }

    public long totalSuccesses() {
        return totalSuccesses.get();
    }

    public long totalFailures() {
        return totalFailures.get();
    }

    public long totalAttempts() {
        return totalAttempts.get();
    }

    public long totalCancelled() {
        return totalCancelled.get();
    }

    /** 目前窗口內的 TTFT 分位數，供指標輸出。樣本不足時回傳 -1 而不是猜一個值 */
    public long ttftMillis(double percentile) {
        Duration sentinel = Duration.ofMillis(-1);
        return ttftPercentile(percentile, sentinel).toMillis();
    }
}
