package io.aigateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class InMemoryBudgetLedgerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T23:59:00Z"));

    @Test
    void reservationBeyondTheGlobalLimitIsRejectedAndLeavesNoTrace() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(1_000, 0, clock);
        ledger.reserve("team-a", 800);

        assertThatThrownBy(() -> ledger.reserve("team-b", 300))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.kind()).isEqualTo(ProviderException.Kind.BUDGET_EXCEEDED));
        assertThat(ledger.spentMicroCents(null)).isEqualTo(800);
        assertThat(ledger.spentMicroCents("team-b")).isZero();
    }

    @Test
    void oneTenantCannotExhaustAnotherTenantsShare() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(10_000, 1_000, clock);
        ledger.reserve("team-a", 1_000);

        assertThatThrownBy(() -> ledger.reserve("team-a", 1)).isInstanceOf(ProviderException.class);
        ledger.reserve("team-b", 1_000);
    }

    @Test
    void settlementReplacesTheEstimateWithTheActualCost() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(0, 0, clock);
        BudgetLedger.Reservation reservation = ledger.reserve("team-a", 1_000);

        ledger.settle(reservation, 300);

        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(300);
        assertThat(ledger.spentMicroCents(null)).isEqualTo(300);
    }

    @Test
    void recordedSpendIsAddedEvenPastTheLimit() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(1_000, 500, clock);
        ledger.reserve("team-a", 500);

        // 已經花掉的錢不能拒絕記帳，否則帳本低估
        ledger.record("team-a", 700);

        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(1_200);
        assertThat(ledger.spentMicroCents(null)).isEqualTo(1_200);
    }

    @Test
    void settlementAfterMidnightDoesNotRefundIntoTheNewDay() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(0, 0, clock);
        BudgetLedger.Reservation reservation = ledger.reserve("team-a", 1_000);

        clock.advance(Duration.ofMinutes(2));
        ledger.settle(reservation, 100);

        // 退回的 900 屬於前一天；記進今天的話，今天的額度會憑空多出 900
        assertThat(ledger.spentMicroCents("team-a")).isZero();
        assertThat(ledger.spentMicroCents(null)).isZero();
    }
}
