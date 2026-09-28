package io.aigateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class HedgePolicyTest {

    private static final long SHORT_PROMPT = 200;
    private static final long LONG_PROMPT = 20_000;

    @Test
    void longPromptsAreComparedWithOtherLongPrompts() {
        ProviderStats stats = new ProviderStats("primary");
        // 長 prompt 稀少：整體窗口裡幾乎全是短 prompt，整體 p95 落在短 prompt 的 300ms
        for (int index = 0; index < 10; index++) stats.recordTtft(Duration.ofMillis(3_000 + index * 100), LONG_PROMPT);
        for (int index = 0; index < 128; index++) stats.recordTtft(Duration.ofMillis(300), SHORT_PROMPT);
        assertThat(stats.ttftPercentile(0.95, Duration.ZERO)).isEqualTo(Duration.ofMillis(300));

        HedgePolicy policy = HedgePolicy.deferred(2, Duration.ofMillis(100));

        Duration shortDelay = policy.delayBeforeNextAttempt(0, stats, SHORT_PROMPT).orElseThrow();
        Duration longDelay = policy.delayBeforeNextAttempt(0, stats, LONG_PROMPT).orElseThrow();

        assertThat(shortDelay).isEqualTo(Duration.ofMillis(300));
        // 用整體 p95（300ms）的話，每一個長 prompt 在正常的 3 秒延遲下都會被對沖，而它們是最貴的請求
        assertThat(longDelay).isGreaterThanOrEqualTo(Duration.ofMillis(3_800));
    }

    @Test
    void bucketWithoutEnoughSamplesFallsBackToTheOverallPercentile() {
        ProviderStats stats = new ProviderStats("primary");
        for (int index = 0; index < 20; index++) stats.recordTtft(Duration.ofMillis(500), SHORT_PROMPT);

        Duration delay = HedgePolicy.deferred(2, Duration.ofMillis(100))
                .delayBeforeNextAttempt(0, stats, LONG_PROMPT).orElseThrow();

        assertThat(delay).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void noHedgeOnceTheAttemptBudgetIsUsed() {
        assertThat(HedgePolicy.deferred(2, Duration.ofMillis(100))
                .delayBeforeNextAttempt(1, new ProviderStats("primary"), SHORT_PROMPT)).isEmpty();
    }
}
