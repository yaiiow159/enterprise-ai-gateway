package io.aigateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HedgingChatClientTest {

    private static final String MODEL = "chat";
    private static final ChatRequest REQUEST = new ChatRequest(MODEL,
            List.of(Message.user("x".repeat(200))), 50, true, null, "team-a", null);
    /** 估算輸入 token × 單價 10：被中止的候選至少要記這麼多 */
    private static final long INPUT_COST = REQUEST.estimatedPromptTokens() * 10L;
    private static final long OUTPUT_TOKEN_COST = 20;
    private static final Usage WINNER_USAGE = new Usage(REQUEST.estimatedPromptTokens(), 2, INPUT_COST + 40);

    private final InMemoryBudgetLedger ledger =
            new InMemoryBudgetLedger(0, 0, new MutableClock(Instant.parse("2026-09-28T10:00:00Z")));
    private final GatewayCounters counters = new GatewayCounters();
    private ProviderRegistry registry;
    private HedgingChatClient client;

    @AfterEach
    void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void primaryAnsweringInTimeIsNotHedged() {
        ScriptedProvider primary = ScriptedProvider.answering("primary", MODEL, Duration.ofMillis(10), WINNER_USAGE);
        ScriptedProvider backup = ScriptedProvider.answering("backup", MODEL, Duration.ZERO, WINNER_USAGE);
        client = client(HedgePolicy.deferred(2, Duration.ofMillis(500)), primary, backup);

        assertThat(drain(client.stream(REQUEST))).isEqualTo("你好世界");

        assertThat(backup.calls).hasValue(0);
        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(WINNER_USAGE.costMicroCents());
    }

    @Test
    void slowPrimaryIsHedgedAndItsInputCostIsRecorded() throws InterruptedException {
        ScriptedProvider primary = ScriptedProvider.answering("primary", MODEL, Duration.ofSeconds(5), WINNER_USAGE);
        ScriptedProvider backup = ScriptedProvider.answering("backup", MODEL, Duration.ofMillis(10), WINNER_USAGE);
        client = client(HedgePolicy.deferred(2, Duration.ofMillis(100)), primary, backup);

        ChatStream stream = client.stream(REQUEST);
        assertThat(stream.providerName()).isEqualTo("backup");
        drain(stream);

        // 主要 Provider 在等第一個 token 時被中止：輸入 token 已經計費，要記進帳
        await(() -> ledger.spentMicroCents("team-a") == WINNER_USAGE.costMicroCents() + INPUT_COST);
        assertThat(ledger.spentMicroCents(null)).isEqualTo(WINNER_USAGE.costMicroCents() + INPUT_COST);
        assertThat(counters.hedgesLaunched()).isEqualTo(1);
    }

    @Test
    void loserThatAlreadyProducedATokenIsClosedAndCharged() throws InterruptedException {
        ScriptedProvider primary = ScriptedProvider.answering("primary", MODEL, Duration.ofMillis(10), WINNER_USAGE);
        ScriptedProvider backup =
                ScriptedProvider.answeringDespiteCancel("backup", MODEL, Duration.ofMillis(150), WINNER_USAGE);
        client = client(HedgePolicy.immediate(2), primary, backup);

        assertThat(client.stream(REQUEST).providerName()).isEqualTo("primary");

        await(() -> backup.closes.get() == 1);
        await(() -> ledger.spentMicroCents("team-a") >= INPUT_COST + OUTPUT_TOKEN_COST);
        // 勝者還沒讀完，帳上是勝者的預扣加上落敗者已產生的成本
        long winnerReservation = INPUT_COST + 50 * OUTPUT_TOKEN_COST;
        assertThat(ledger.spentMicroCents("team-a"))
                .isEqualTo(winnerReservation + INPUT_COST + OUTPUT_TOKEN_COST);
    }

    @Test
    void rateLimitedPrimaryFailsOverEvenWithoutHedging() {
        ScriptedProvider primary = ScriptedProvider.failing("primary", MODEL, Duration.ZERO, 429);
        ScriptedProvider backup = ScriptedProvider.answering("backup", MODEL, Duration.ZERO, WINNER_USAGE);
        client = client(HedgePolicy.none(), primary, backup);

        ChatStream stream = client.stream(REQUEST);

        assertThat(stream.providerName()).isEqualTo("backup");
        drain(stream);
        // 失敗的嘗試沒有產出，不記成本
        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(WINNER_USAGE.costMicroCents());
        assertThat(counters.failovers()).isEqualTo(1);
    }

    @Test
    void rejectedUpstreamCredentialsFailOverToAnotherProvider() {
        // 401 是這一家的金鑰問題：別家有自己的金鑰，不該因此讓整個請求失敗
        ScriptedProvider primary = ScriptedProvider.failing("primary", MODEL, Duration.ZERO, 401);
        ScriptedProvider backup = ScriptedProvider.answering("backup", MODEL, Duration.ZERO, WINNER_USAGE);
        client = client(HedgePolicy.none(), primary, backup);

        assertThat(client.stream(REQUEST).providerName()).isEqualTo("backup");
    }

    @Test
    void badRequestIsNotRetriedAndDoesNotHurtProviderHealth() {
        ScriptedProvider primary = ScriptedProvider.failing("primary", MODEL, Duration.ZERO, 400);
        ScriptedProvider backup = ScriptedProvider.answering("backup", MODEL, Duration.ZERO, WINNER_USAGE);
        client = client(HedgePolicy.none(), primary, backup);

        assertThatThrownBy(() -> client.stream(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.kind()).isEqualTo(ProviderException.Kind.BAD_REQUEST));
        assertThat(backup.calls).hasValue(0);
        // 壞請求是呼叫端的問題；算進失敗率的話，一個租戶就能把健康的 Provider 拉進降級
        assertThat(registry.statsFor("primary").failureRate()).isZero();
    }

    @Test
    void unknownModelIsRejectedWithoutCallingAnyProvider() {
        ScriptedProvider provider = ScriptedProvider.answering("primary", MODEL, Duration.ZERO, WINNER_USAGE);
        client = client(HedgePolicy.none(), provider);

        ChatRequest unknown = new ChatRequest("gpt-unknown", REQUEST.messages(), 50, true, null, "team-a", null);

        assertThatThrownBy(() -> client.stream(unknown))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.kind()).isEqualTo(ProviderException.Kind.MODEL_NOT_FOUND));
        assertThat(provider.calls).hasValue(0);
    }

    @Test
    void failoverStaysWithinTheRequestedModel() {
        ScriptedProvider sameModel = ScriptedProvider.failing("same-model", MODEL, Duration.ZERO, 503);
        ScriptedProvider otherModel = ScriptedProvider.answering("other-model", "other", Duration.ZERO, WINNER_USAGE);
        client = client(HedgePolicy.deferred(2, Duration.ofMillis(10)), sameModel, otherModel);

        assertThatThrownBy(() -> client.stream(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.kind()).isEqualTo(ProviderException.Kind.SERVER_ERROR));
        // 要的是 chat，就不能因為 chat 掛了而改用另一個模型回答
        assertThat(otherModel.calls).hasValue(0);
    }

    @Test
    void reservationIsRefundedWhenEveryCandidateFails() {
        client = client(HedgePolicy.none(),
                ScriptedProvider.failing("a", MODEL, Duration.ZERO, 500),
                ScriptedProvider.failing("b", MODEL, Duration.ZERO, 502));

        assertThatThrownBy(() -> client.stream(REQUEST)).isInstanceOf(ProviderException.class);

        assertThat(ledger.spentMicroCents("team-a")).isZero();
    }

    private HedgingChatClient client(HedgePolicy policy, LlmProvider... providers) {
        registry = new ProviderRegistry();
        for (LlmProvider provider : providers) registry.register(provider);
        return new HedgingChatClient(registry, RoutingStrategy.latencyAware(Duration.ofMillis(500)), policy,
                Duration.ofSeconds(5), ledger, Duration.ofSeconds(30), 0.5, counters);
    }

    private static String drain(ChatStream stream) {
        StringBuilder content = new StringBuilder();
        try (stream) {
            while (stream.hasNext()) content.append(stream.next().delta());
        }
        return content.toString();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("條件在 3 秒內未成立");
            Thread.sleep(10);
        }
    }
}
