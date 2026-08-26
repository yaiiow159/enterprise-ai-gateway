package io.aigateway.core;

import java.util.Comparator;
import java.util.List;

/**
 * 決定候選 Provider 的**優先順序**。
 *
 * <p>回傳的清單是有序的：第一個是主要選擇，其餘依序作為對沖與失敗切換的備援。
 * 能力過濾已經由 {@link ProviderRegistry#candidatesFor} 做完，這裡只負責排序。
 */
public interface RoutingStrategy {

    String name();

    List<LlmProvider> order(ChatRequest request, List<LlmProvider> candidates, ProviderRegistry registry);

    /**
     * 以 TTFT 為主要依據，並把失敗率高的往後排。
     *
     * <p>用 p95 而非平均：平均會被少數極慢的請求拉高，但真正影響體驗的是尾巴，
     * 而尾巴正是對沖要解決的問題。
     */
    static RoutingStrategy latencyAware(java.time.Duration unknownProviderAssumption) {
        return new RoutingStrategy() {
            @Override
            public String name() {
                return "latency-aware";
            }

            @Override
            public List<LlmProvider> order(ChatRequest request, List<LlmProvider> candidates, ProviderRegistry registry) {
                return candidates.stream()
                        .sorted(Comparator.comparingLong(provider -> {
                            ProviderStats providerStats = registry.statsFor(provider.name());
                            long ttft = providerStats.ttftPercentile(0.95, unknownProviderAssumption).toMillis();
                            // 失敗率直接反映成延遲懲罰：五成失敗率等於把它的延遲當成兩倍看待，
                            // 這樣不需要另一套權重就能把不穩的 Provider 自然往後排
                            return Math.round(ttft * (1 + providerStats.failureRate()));
                        }))
                        .toList();
            }
        };
    }

    /** 以估算成本排序。適合對延遲不敏感的批次任務 */
    static RoutingStrategy costAware() {
        return new RoutingStrategy() {
            @Override
            public String name() {
                return "cost-aware";
            }

            @Override
            public List<LlmProvider> order(ChatRequest request, List<LlmProvider> candidates, ProviderRegistry registry) {
                return candidates.stream()
                        .sorted(Comparator.comparingLong(provider ->
                                provider.capability().estimatedCostMicroCents(request)))
                        .toList();
            }
        };
    }
}
