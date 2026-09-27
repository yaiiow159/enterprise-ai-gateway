package io.aigateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedisBudgetLedgerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T23:59:00Z"));
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private RedisBudgetLedger ledger;

    @BeforeEach
    void setUp() {
        when(connection.sync()).thenReturn(commands);
        when(commands.scriptLoad(anyString())).thenReturn("sha");
        ledger = new RedisBudgetLedger(connection, 1_000_000, 100_000, clock);
    }

    @Test
    void reservationTakenInTheFallbackIsSettledInTheFallback() {
        redisIsDown();

        BudgetLedger.Reservation reservation = ledger.reserve("team-a", 10_000);
        ledger.settle(reservation, 3_000);

        assertThat(reservation.degraded()).isTrue();
        // 結算回到本地帳本：本地只剩實際花費，Redis 沒有收到它從沒預扣過的負差額
        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(3_000);
        verify(commands, never()).incrby(anyString(), anyLong());
    }

    @Test
    void fallbackKeepsServingDuringALongOutage() {
        redisIsDown();

        // 單租戶上限 100_000；每次預扣 10_000、實際 1_000。
        // 結算若沒有回到本地帳本，第 11 次就會被擋下
        for (int request = 0; request < 50; request++) {
            ledger.settle(ledger.reserve("team-a", 10_000), 1_000);
        }

        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(50_000);
    }

    @Test
    void settlementIsWrittenToTheDayOfTheReservation() {
        when(commands.evalsha(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 10_000L, 10_000L, -1L));

        BudgetLedger.Reservation reservation = ledger.reserve("team-a", 10_000);
        clock.advance(Duration.ofMinutes(2));
        ledger.settle(reservation, 4_000);

        verify(commands).incrby("{budget:2026-09-28}:global", -6_000L);
        verify(commands).incrby("{budget:2026-09-28}:tenant:team-a", -6_000L);
        verify(commands, never()).incrby(eq("{budget:2026-09-29}:global"), anyLong());
    }

    @Test
    void recordedSpendFallsBackToTheLocalLedgerWhenRedisIsDown() {
        redisIsDown();
        when(commands.incrby(anyString(), anyLong())).thenThrow(new RedisConnectionException("down"));

        ledger.record("team-a", 2_500);

        assertThat(ledger.spentMicroCents("team-a")).isEqualTo(2_500);
    }

    private void redisIsDown() {
        RedisConnectionException down = new RedisConnectionException("down");
        when(commands.evalsha(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenThrow(down);
        when(commands.get(anyString())).thenThrow(down);
    }
}
