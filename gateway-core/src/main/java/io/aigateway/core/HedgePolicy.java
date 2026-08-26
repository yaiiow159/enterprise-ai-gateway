package io.aigateway.core;

import java.time.Duration;
import java.util.Optional;

/**
 * 決定「要不要、以及何時」補發對沖請求。
 *
 * <p>這個介面存在的理由是成本：**同時打三家取最快，就是付三倍的錢**。
 * LLM 的輸入 token 在請求送出當下就計費，取消請求不會退還已產生的費用，
 * 只能減少後續的輸出 token。無條件對沖在單次請求上看不出問題，
 * 但乘上日流量之後是三倍帳單。
 */
public interface HedgePolicy {

    /**
     * 主要請求送出後，要等多久才補發下一個候選。
     *
     * @return 空值表示不再對沖
     */
    Optional<Duration> delayBeforeNextAttempt(int attemptIndex, ProviderStats primaryStats);

    /** 完全不對沖。成本最低，長尾延遲最差 */
    static HedgePolicy none() {
        return (attemptIndex, primaryStats) -> Optional.empty();
    }

    /**
     * 延遲對沖：只有在主要 Provider 超過它自己的 p95 TTFT 仍未回應時才補發。
     *
     * <p>依定義只有約 5% 的請求會觸發，成本增加約 5% 而非 200%，
     * 但長尾延遲的改善幾乎與無條件對沖相同——因為被改善的本來就只有那 5%。
     *
     * @param maxAttempts 含主要請求在內最多幾個候選
     * @param fallbackDelay 樣本不足以推估 p95 時使用的延遲
     */
    static HedgePolicy deferred(int maxAttempts, Duration fallbackDelay) {
        return (attemptIndex, primaryStats) -> {
            if (attemptIndex + 1 >= maxAttempts) return Optional.empty();
            Duration p95 = primaryStats.ttftPercentile(0.95, fallbackDelay);
            // 下限保護：p95 若異常小（例如全是快取命中），會退化成無條件對沖
            return Optional.of(p95.compareTo(fallbackDelay) < 0 ? fallbackDelay : p95);
        };
    }

    /**
     * 立刻對沖，成本為候選數的倍數。
     *
     * <p><b>只應該用於展示或極端要求延遲的少數端點</b>，且必須有每日成本上限作為防線。
     * 預設組態不會使用它。
     */
    static HedgePolicy immediate(int maxAttempts) {
        return (attemptIndex, primaryStats) ->
                attemptIndex + 1 >= maxAttempts ? Optional.empty() : Optional.of(Duration.ZERO);
    }
}
