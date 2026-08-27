package io.aigateway.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 讓一組候選 Provider 賽跑，取最先送出第一個 token 的那一個。
 *
 * <h2>為什麼獨立成一個類別</h2>
 * 這段邏輯原本是 {@code HedgingChatClient} 裡的一個方法，用七個區域變數維護狀態
 * （在飛的、失敗的、下一個候選、已補發幾次對沖、期限、是否已分出勝負、結果佇列），
 * 而迴圈裡同時處理三種完全不同的決策。把狀態變成欄位、把三種決策各自命名之後，
 * 「什麼情況下會走哪一條」才看得出來。
 *
 * <p>更重要的是**它是一次性的**：每個 race 物件只服務一次呼叫，
 * 跑完就丟。原本那些區域變數其實就是這個物件的狀態，只是被迫寫成區域變數。
 *
 * <h2>三種決策是不同的東西</h2>
 * <ul>
 *   <li><b>失敗切換</b>——在飛的候選全掛了。這不是可選的：不換就等於直接把錯誤丟回去，
 *       多 Provider 架構也失去意義。因此**不受對沖預算限制**。</li>
 *   <li><b>對沖</b>——前一個候選還在飛，只是慢。這是拿錢換延遲的可選行為，
 *       受 {@link HedgePolicy} 的 maxAttempts 限制。</li>
 *   <li><b>放棄</b>——沒有候選可換，或已超過整體逾時。</li>
 * </ul>
 * 早期版本把前兩者混在同一個計數裡，結果是主要 Provider 一旦快速失敗（例如 429），
 * 備援根本不會被啟動，即使還有健康的候選也直接回傳失敗。
 */
final class CandidateRace {

    private static final Logger log = LoggerFactory.getLogger(CandidateRace.class);

    private final ChatRequest request;
    private final List<LlmProvider> candidates;
    private final ProviderRegistry registry;
    private final HedgePolicy hedgePolicy;
    private final GatewayCounters counters;
    private final ExecutorService executor;
    private final long deadlineNanos;

    /** 已經有人勝出。落敗者看到它就知道要中止自己的上游連線 */
    private final AtomicBoolean settled = new AtomicBoolean();
    /** 容量要能裝下所有候選，否則落敗者的 offer 會被丟棄而無法清理 */
    private final BlockingQueue<Attempt> finished;
    private final List<Future<?>> running = new ArrayList<>();
    private final List<ProviderException> failures = new ArrayList<>();

    private int nextCandidate;
    private int hedgesLaunched;

    CandidateRace(ChatRequest request,
                  List<LlmProvider> candidates,
                  ProviderRegistry registry,
                  HedgePolicy hedgePolicy,
                  GatewayCounters counters,
                  ExecutorService executor,
                  Duration attemptTimeout) {
        this.request = request;
        this.candidates = candidates;
        this.registry = registry;
        this.hedgePolicy = hedgePolicy;
        this.counters = counters;
        this.executor = executor;
        this.deadlineNanos = System.nanoTime() + attemptTimeout.toNanos();
        this.finished = new ArrayBlockingQueue<>(Math.max(1, candidates.size()));
    }

    /**
     * 跑完這場賽跑。
     *
     * @return 勝出者的串流，第一個 chunk 已經預先讀出並放回前端
     * @throws ProviderException 全部候選都失敗、遇到不值得重試的錯誤，或整體逾時
     */
    ChatStream run() {
        try {
            launch(nextCandidate++, "主要");

            while (true) {
                if (allLaunchedFailed()) {
                    launchFailover();
                    continue;
                }

                Optional<Duration> hedgeAt = hedgePoint();
                Attempt outcome = pollFor(hedgeAt.orElseGet(this::untilDeadline));

                if (outcome == null) {
                    // 等到約定的時間點都沒有人送出第一個 token
                    if (hedgeAt.isEmpty()) throw noCandidateSucceeded();
                    launchHedge(hedgeAt.get());
                    continue;
                }
                if (outcome.succeeded()) return claim(outcome);

                failures.add(outcome.failure);
                // 換一家也是一樣的結果（請求格式錯、金鑰錯、內容被拒），重試只是多付一次錢
                if (!outcome.failure.retryable()) throw outcome.failure;
            }
        } catch (RuntimeException error) {
            abandonAll();
            throw error;
        }
    }

    // ------------------------------------------------------------ 三種決策

    private boolean allLaunchedFailed() {
        return failures.size() == running.size();
    }

    private boolean haveSpareCandidate() {
        return nextCandidate < candidates.size();
    }

    private void launchFailover() {
        if (!haveSpareCandidate()) throw noCandidateSucceeded();
        counters.recordFailover();
        launch(nextCandidate++, "失敗切換");
    }

    /**
     * 現在該不該等到對沖點？
     *
     * @return 有值代表「等這麼久之後就補發下一家」；空值代表「沒有對沖可做，等到逾時為止」
     */
    private Optional<Duration> hedgePoint() {
        if (!haveSpareCandidate()) return Optional.empty();

        ProviderStats primaryStats = registry.statsFor(candidates.get(0).name());
        Optional<Duration> delay = hedgePolicy.delayBeforeNextAttempt(hedgesLaunched, primaryStats);

        // 對沖點比整體逾時還晚的話就沒有意義了，那時候請求早就該放棄
        return delay.filter(value -> value.compareTo(untilDeadline()) < 0);
    }

    private void launchHedge(Duration waited) {
        hedgesLaunched++;
        counters.recordHedge();
        log.debug("主要 Provider 超過 {} 未回應，補發對沖", waited);
        launch(nextCandidate++, "對沖");
    }

    private Duration untilDeadline() {
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    // ------------------------------------------------------------ 單一候選

    private void launch(int index, String why) {
        LlmProvider provider = candidates.get(index);
        log.debug("啟動候選 {}（{}）", provider.name(), why);
        running.add(executor.submit(() -> attempt(provider)));
    }

    /**
     * 開串流、讀第一個 chunk、回報結果。
     *
     * <p>讀到第一個 chunk 才算成功——這是 TTFT 的定義，也是對沖的判準。
     * 只是「連線建立」不算，那時候上游可能還在排隊。
     */
    private void attempt(LlmProvider provider) {
        long startedAt = System.nanoTime();
        ChatStream stream = null;
        ProviderStats stats = registry.statsFor(provider.name());
        // 送出就算一次嘗試，不管後來成功、失敗還是被中止——成本倍率要算的是「送出幾次」
        stats.recordAttempt();

        try {
            stream = provider.stream(request);
            if (!stream.hasNext()) {
                throw new ProviderException(provider.name(), ProviderException.Kind.STREAM_ABORTED, 502,
                        "上游未送出任何內容就結束", null);
            }
            ChatChunk first = stream.next();
            stats.recordTtft(Duration.ofNanos(System.nanoTime() - startedAt));

            if (settled.get()) {
                // 已經有人勝出，這條是輸家：立刻中止上游，不要繼續產生 token
                stream.close();
                stats.recordCancelled();
                return;
            }
            finished.offer(new Attempt(provider.name(), ChatStream.withPrefetched(first, stream), null));
        } catch (RuntimeException error) {
            if (stream != null) stream.close();

            if (settled.get()) {
                // 被勝出者中止的落敗者，不是真的失敗。記成失敗會讓它的 failureRate 上升，
                // 之後更不容易被排為主要 Provider；而越少被選中就越少機會刷新統計——
                // 一次對沖落敗會變成永久降級。落敗只代表「這次比較慢」，不代表「壞掉」。
                log.debug("{} 在對沖中落敗並已中止", provider.name());
                stats.recordCancelled();
                return;
            }
            stats.recordFailure();
            finished.offer(new Attempt(provider.name(), null, asProviderException(provider, error)));
        }
    }

    // ------------------------------------------------------------ 收尾

    private ChatStream claim(Attempt winner) {
        settled.set(true);
        log.debug("由 {} 勝出", winner.providerName);

        // 落敗者可能還在路上；用另一條虛擬執行緒把它們收乾淨，不要讓呼叫端等
        executor.submit(this::abandonAll);
        return winner.stream;
    }

    /**
     * 中止所有還在飛的候選，並關掉佇列裡殘留的串流。
     *
     * <p>每一條沒關的串流都是還在計費的上游請求——這不只是資源清理。
     */
    private void abandonAll() {
        settled.set(true);
        running.forEach(future -> future.cancel(true));

        Attempt pending;
        while ((pending = finished.poll()) != null) {
            if (pending.stream != null) pending.stream.close();
        }
    }

    private Attempt pollFor(Duration timeout) {
        try {
            return finished.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderException("hedging", ProviderException.Kind.TIMEOUT, 504,
                    "等待候選回應時被中斷", interrupted);
        }
    }

    private ProviderException asProviderException(LlmProvider provider, RuntimeException error) {
        if (error instanceof ProviderException providerException) return providerException;
        return new ProviderException(provider.name(), ProviderException.Kind.CONNECTION, 502,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
    }

    private ProviderException noCandidateSucceeded() {
        if (failures.isEmpty()) {
            return new ProviderException("hedging", ProviderException.Kind.TIMEOUT, 504,
                    "所有候選都在期限內未回應（model=%s）".formatted(request.model()), null);
        }
        // 保留第一個失敗作為 cause：多數情況下主要 Provider 的錯誤才是根因，
        // 備援的失敗常常只是「主要掛了所以全都掛了」的延伸
        ProviderException primary = failures.get(0);
        return new ProviderException("hedging", primary.kind(), primary.statusCode(),
                "所有 %d 個候選都失敗，第一個錯誤：%s".formatted(failures.size(), primary.getMessage()), primary);
    }

    private record Attempt(String providerName, ChatStream stream, ProviderException failure) {
        boolean succeeded() {
            return stream != null;
        }
    }
}
