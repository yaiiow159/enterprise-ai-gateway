package io.aigateway.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 路由層級的事件計數，供指標輸出。
 *
 * <p>這些數字回答的是「**為什麼**這次呼叫花了這麼多錢／這麼久」，
 * 而不只是「花了多少」。三個事件各自對應一種成本或延遲的來源：
 *
 * <ul>
 *   <li><b>對沖</b>——多打一家換延遲，是**可選的花費**。它的觸發率乘上單次成本
 *       就是對沖這個機制的帳單，也是決定要不要調整 fallback-delay 的依據。</li>
 *   <li><b>失敗切換</b>——上游出問題的直接證據。它上升代表某家 Provider 正在惡化，
 *       而且**使用者已經在承受多一次往返的延遲**了。</li>
 *   <li><b>半開探測</b>——降級中的 Provider 正在被試。持續不為零代表某家一直沒好，
 *       歸零則代表它恢復了（探測成功會清空失敗窗口，之後就不再觸發）。</li>
 * </ul>
 *
 * <p>與 per-provider 的統計分開，是因為這些事件屬於**一次呼叫的決策過程**，
 * 不屬於任何單一 Provider——對沖是「主要慢了所以多打一家」，
 * 記在哪一家頭上都會誤導。
 *
 * <p>刻意用單純的 {@link AtomicLong} 而不引入 Micrometer：
 * core 不依賴 Spring 生態，指標的轉換在 server 模組做。
 */
public final class GatewayCounters {

    /**
     * 客戶端請求數，成本倍率的分母。
     *
     * <p>不用 Spring 的 {@code http_server_requests_seconds_count} 當分母，
     * 是因為**兩個序列的起點不對稱**：那個序列要等第一個請求進來才存在，
     * 而這裡的計數器從啟動就存在且為 0。`rate()` 對半途才出現的序列會向前外推，
     * 把分母灌大——實測算出來的成本倍率是 0.65，而那在物理上不可能
     * （上游請求不會少於客戶端請求）。兩個計數器同時誕生才比得準。
     */
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong hedgesLaunched = new AtomicLong();
    private final AtomicLong failovers = new AtomicLong();
    private final AtomicLong probes = new AtomicLong();

    public void recordRequest() {
        requests.incrementAndGet();
    }

    public long requests() {
        return requests.get();
    }

    public void recordHedge() {
        hedgesLaunched.incrementAndGet();
    }

    public void recordFailover() {
        failovers.incrementAndGet();
    }

    public void recordProbe() {
        probes.incrementAndGet();
    }

    public long hedgesLaunched() {
        return hedgesLaunched.get();
    }

    public long failovers() {
        return failovers.get();
    }

    public long probes() {
        return probes.get();
    }
}
