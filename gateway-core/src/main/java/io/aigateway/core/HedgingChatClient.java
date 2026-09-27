package io.aigateway.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 依路由順序呼叫 Provider，必要時補發對沖請求，取最先回應者。
 *
 * <p><b>為什麼不用 {@code ExecutorService.invokeAny}</b>：那個方法會立刻啟動所有任務，
 * 而且以「第一個完成」為準。我們要的是兩件不同的事——延遲啟動（省錢）以及
 * 以**首個 token**（而非整段完成）決勝負。串流情境下這個差別是本質的：
 * 一旦開始把某家的 token 送給客戶端就不能改用另一家，所以勝負必須在第一個 token 前分出來。
 *
 * <p><b>為什麼不用 Structured Concurrency</b>：它在 JDK 21 仍是 preview，
 * API 每版都在改，且需要 {@code --enable-preview}。語意上它很適合，
 * 但把正式路徑綁在 preview API 上不划算（見 CLAUDE.md 的技術決定 2）。
 *
 * <p><b>對沖與失敗切換是兩回事</b>，這裡刻意分開處理：
 * <ul>
 *   <li><b>對沖</b>是在前一個候選**還在飛**的時候，額外再打一家賭它比較快。
 *       這是可選的、要多付一次錢的行為，因此受 {@link HedgePolicy} 的 maxAttempts 限制。</li>
 *   <li><b>失敗切換</b>是在飛的候選**全部失敗**之後換下一家。這不是可選的——
 *       不換就等於直接把錯誤丟回給使用者，多 Provider 架構也就失去意義。
 *       因此它不受對沖預算限制。</li>
 * </ul>
 * 早期版本把兩者混在同一個計數裡，結果是主要 Provider 一旦快速失敗（例如 429），
 * 備援根本不會被啟動，即使還有健康的候選也直接回傳失敗。
 *
 * <p>輸家一律呼叫 {@link ChatStream#close()} 中止上游連線。這不只是資源清理——
 * 沒中止就是繼續產生 token、繼續計費。
 */
public final class HedgingChatClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HedgingChatClient.class);

    private final ProviderRegistry registry;
    private final RoutingStrategy routing;
    private final HedgePolicy hedgePolicy;
    private final Duration attemptTimeout;
    private final BudgetLedger budget;
    private final Duration probeInterval;
    private final double probeFailureThreshold;
    private final GatewayCounters counters;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HedgingChatClient(ProviderRegistry registry,
                             RoutingStrategy routing,
                             HedgePolicy hedgePolicy,
                             Duration attemptTimeout,
                             BudgetLedger budget,
                             Duration probeInterval,
                             double probeFailureThreshold,
                             GatewayCounters counters) {
        this.registry = registry;
        this.routing = routing;
        this.hedgePolicy = hedgePolicy;
        this.attemptTimeout = attemptTimeout;
        this.budget = budget;
        this.probeInterval = probeInterval;
        this.probeFailureThreshold = probeFailureThreshold;
        this.counters = counters;
    }

    /**
     * 串流呼叫。回傳的串流已經含有第一個 chunk（判定勝負時必然要先讀一段）。
     *
     * <p>成本上限在**送出任何請求之前**就先預扣：等到收到回應才發現超支，
     * 那筆錢已經花掉了。預扣用 {@code maxTokens} 估最壞情況，實際成本出來後結算差額。
     *
     * @throws ProviderException 所有候選都失敗、錯誤本身不值得重試，或已達每日成本上限
     */
    public ChatStream stream(ChatRequest request) {
        // 一次客戶端呼叫記一次。complete() 內部也走這裡，所以兩條路徑都算得到
        counters.recordRequest();
        Candidates candidates = orderedCandidates(request);

        // 用最貴的候選估算：對沖或失敗切換可能落到任何一家，低估會讓上限形同虛設
        long estimate = candidates.ordered().stream()
                .mapToLong(provider -> provider.capability().estimatedCostMicroCents(request))
                .max().orElse(0);
        BudgetLedger.Reservation reservation = budget.reserve(request.tenant(), estimate);

        try {
            ChatStream winner = dispatch(request, candidates.ordered());

            // 探測成功：清掉失敗窗口讓它立刻回到正常排序。
            // 不清的話，恢復時間會被 128 筆窗口拖到與故障時間完全不成比例
            if (candidates.probedProvider() != null
                    && candidates.probedProvider().equals(winner.providerName())) {
                log.info("半開探測成功，{} 恢復正常排序", winner.providerName());
                registry.statsFor(winner.providerName()).clearFailures();
            }
            return new BudgetedChatStream(winner, budget, reservation);
        } catch (RuntimeException error) {
            // 一個 token 都沒產生，預扣全額退回
            budget.settle(reservation, 0);
            throw error;
        }
    }

    private ChatStream dispatch(ChatRequest request, List<LlmProvider> candidates) {
        // 落敗者的成本沒有對應的預扣，直接記成已發生的支出。
        // 不記的話，對沖被設成 immediate 時實際花費是 N 倍、帳本只看得到 1 倍，
        // 每日上限正好在它要防的情境裡失效
        return new CandidateRace(request, candidates, registry, hedgePolicy, counters,
                executor, attemptTimeout, cost -> budget.record(request.tenant(), cost)).run();
    }

    /** 非串流呼叫。內部沿用同一套對沖邏輯，讀完串流後組成完整回應 */
    public ChatResponse complete(ChatRequest request) {
        long startedAt = System.nanoTime();
        StringBuilder content = new StringBuilder();
        ToolCallAccumulator toolCalls = new ToolCallAccumulator();
        Usage usage = Usage.EMPTY;
        ChatChunk.FinishReason finishReason = ChatChunk.FinishReason.STOP;
        String providerName;

        try (ChatStream stream = stream(request.withStream(true))) {
            providerName = stream.providerName();
            while (stream.hasNext()) {
                ChatChunk chunk = stream.next();
                if (chunk.delta() != null) content.append(chunk.delta());
                toolCalls.add(chunk.toolCalls());
                if (chunk.usage() != null) usage = chunk.usage();
                if (chunk.finishReason() != null) finishReason = chunk.finishReason();
            }
        }

        return new ChatResponse(
                "chatcmpl-" + Long.toHexString(System.nanoTime()),
                request.model(),
                providerName,
                content.toString(),
                finishReason,
                usage,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                toolCalls.result());
    }

    /**
     * 排序候選，並在需要時安插一次半開探測。
     *
     * <p>探測被放到**第一位**而不是尾巴：放尾巴的話只有在前面全部失敗時才會被呼叫，
     * 那不是探測，是等別人也壞掉。放第一位的代價是探測失敗時使用者多等一次失敗切換，
     * 而失敗切換本來就會兜住——這正是那個機制存在的理由。
     */
    private Candidates orderedCandidates(ChatRequest request) {
        if (!registry.servesModel(request.model())) {
            throw new ProviderException("routing", ProviderException.Kind.MODEL_NOT_FOUND, 404,
                    "沒有 Provider 服務模型「%s」".formatted(request.model()), null);
        }
        List<LlmProvider> available = registry.candidatesFor(request);
        if (available.isEmpty()) {
            throw new ProviderException("routing", ProviderException.Kind.BAD_REQUEST, 400,
                    "沒有 Provider 能承接這個請求（檢查上下文長度、maxTokens、串流、工具與圖片支援）", null);
        }

        List<LlmProvider> ordered = new ArrayList<>(routing.order(request, available, registry));

        // 只有在還有別的候選可以兜底時才探測：唯一的候選本來就會被呼叫，
        // 不需要探測，而且那時候「探測失敗」就等於「請求失敗」
        if (ordered.size() > 1) {
            for (int index = 1; index < ordered.size(); index++) {
                LlmProvider candidate = ordered.get(index);
                ProviderStats stats = registry.statsFor(candidate.name());
                if (stats.tryClaimProbe(probeInterval, probeFailureThreshold)) {
                    counters.recordProbe();
                    log.info("半開探測：{}（失敗率 {}）暫時提到第一位，失敗時由失敗切換兜住",
                            candidate.name(), String.format("%.0f%%", stats.failureRate() * 100));
                    ordered.remove(index);
                    ordered.add(0, candidate);
                    return new Candidates(ordered, candidate.name());
                }
            }
        }
        return new Candidates(ordered, null);
    }

    /** @param probedProvider 這次被安插的探測對象；null 表示沒有探測 */
    private record Candidates(List<LlmProvider> ordered, String probedProvider) {
    }

    @Override
    public void close() {
        executor.close();
    }
}
