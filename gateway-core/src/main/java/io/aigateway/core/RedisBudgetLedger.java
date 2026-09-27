package io.aigateway.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 記在 Redis 的每日成本上限，讓多節點共用同一份帳。
 *
 * <p>為什麼需要它：{@link InMemoryBudgetLedger} 把帳記在行程記憶體裡，
 * 多節點部署時每個節點各記各的，設定檔上的 $50 在三個節點上就是 $150。
 * 這和「本地熔斷器在 50 個節點下要被打 50 倍次數才會全斷」是同一個問題——
 * 需要跨節點協調的狀態就不能放在各自的記憶體裡。
 *
 * <p><b>判定必須在 Lua 內完成</b>。「先讀支出、在 Java 端判斷、再寫回」不是原子的，
 * 多節點併發下必然超支。這一點和限流的超賣完全同構，解法也一樣：
 * 把讀與寫收進同一次 Redis 執行。
 *
 * <p><b>Redis 故障時退回本地帳本</b>，而不是放行。
 * 放行等於在最需要防線的時候把防線關掉；而完全拒絕服務又讓一個輔助元件的故障
 * 變成全站中斷。退回本地帳本至少還有「節點數 × 設定值」這個上界，
 * 並且會大聲記錄，讓人知道現在的保護是降級的。
 */
public final class RedisBudgetLedger extends AbstractBudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(RedisBudgetLedger.class);
    private static final String SCRIPT_PATH = "/lua/budget_reserve.lua";
    /** 多留一天，避免跨日瞬間 key 剛好過期而讓當天的帳歸零 */
    private static final long KEY_TTL_SECONDS = 2 * 24 * 3600;

    /** 由外部傳入連線時為 null，關閉時不歸這個類別管 */
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final String script;
    private final Clock clock;
    /** Redis 不可用時的退路。不是「放行」，而是降級成單節點保護 */
    private final InMemoryBudgetLedger fallback;

    private volatile String scriptSha;

    public RedisBudgetLedger(String redisUri,
                             long dailyLimitMicroCents,
                             long perTenantLimitMicroCents,
                             Clock clock) {
        this(RedisClient.create(redisUri), null, dailyLimitMicroCents, perTenantLimitMicroCents, clock);
    }

    /** 測試用：使用現成的連線 */
    RedisBudgetLedger(StatefulRedisConnection<String, String> connection,
                      long dailyLimitMicroCents,
                      long perTenantLimitMicroCents,
                      Clock clock) {
        this(null, connection, dailyLimitMicroCents, perTenantLimitMicroCents, clock);
    }

    private RedisBudgetLedger(RedisClient client,
                              StatefulRedisConnection<String, String> connection,
                              long dailyLimitMicroCents,
                              long perTenantLimitMicroCents,
                              Clock clock) {
        super(dailyLimitMicroCents, perTenantLimitMicroCents);
        this.client = client;
        this.connection = connection != null ? connection : client.connect();
        this.script = loadScript();
        this.clock = clock;
        this.fallback = new InMemoryBudgetLedger(dailyLimitMicroCents, perTenantLimitMicroCents, clock);
        this.scriptSha = this.connection.sync().scriptLoad(script);
    }

    private String loadScript() {
        try (InputStream input = RedisBudgetLedger.class.getResourceAsStream(SCRIPT_PATH)) {
            if (input == null) throw new IllegalStateException("找不到 " + SCRIPT_PATH);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("無法載入 " + SCRIPT_PATH, error);
        }
    }

    @Override
    public Reservation reserve(String tenant, long estimatedMicroCents) {
        String key = keyFor(tenant);
        long amount = Math.max(0, estimatedMicroCents);
        // 日期只取一次：跨日瞬間若判定與寫入各取一次，預扣會落在兩天之間
        LocalDate day = LocalDate.now(clock);
        if (amount == 0) return new Reservation(key, 0, day, false);

        List<Long> result;
        try {
            result = evalReserve(day, key, amount);
        } catch (ProviderException budgetError) {
            throw budgetError;
        } catch (RuntimeException error) {
            // Redis 掛了。退回本地帳本——保護降級但不消失，而且要讓人看得到。
            // 標成 degraded，結算才會回到本地帳本：預扣在本地、結算在 Redis 的話，
            // 本地的預扣永遠不會被退回，備用帳本只增不減，最後把所有請求擋掉
            log.error("Redis 成本帳本不可用，暫時退回單節點帳本（保護已降級）：{}", error.toString());
            Reservation local = fallback.reserve(tenant, estimatedMicroCents);
            return new Reservation(local.tenant(), local.amountMicroCents(), local.day(), true);
        }

        if (result.get(0) == 1L) return new Reservation(key, amount, day, false);

        boolean tenantScope = result.get(3) == 1L;
        throw exceeded(
                tenantScope ? "租戶 " + key : "全域",
                tenantScope ? result.get(2) : result.get(1),
                tenantScope ? perTenantLimitMicroCents() : dailyLimitMicroCents(),
                amount);
    }

    @SuppressWarnings("unchecked")
    private List<Long> evalReserve(LocalDate day, String key, long amount) {
        RedisCommands<String, String> commands = connection.sync();
        String[] keys = { globalKey(day), tenantKey(day, key) };
        String[] args = {
                String.valueOf(amount),
                String.valueOf(dailyLimitMicroCents()),
                String.valueOf(perTenantLimitMicroCents()),
                String.valueOf(KEY_TTL_SECONDS),
        };
        try {
            return (List<Long>) commands.evalsha(scriptSha, ScriptOutputType.MULTI, keys, args);
        } catch (RuntimeException error) {
            // Redis 重啟後腳本快取會清空（NOSCRIPT）。重新載入再試一次，
            // 不要讓一次無害的重啟變成全站超支或全站拒絕
            if (!String.valueOf(error.getMessage()).contains("NOSCRIPT")) throw error;
            log.info("Redis 的腳本快取已清空，重新載入 budget_reserve.lua");
            scriptSha = commands.scriptLoad(script);
            return (List<Long>) commands.evalsha(scriptSha, ScriptOutputType.MULTI, keys, args);
        }
    }

    /** 在本地備用帳本預扣的，就在本地結算——兩本帳各自平衡，不能交叉 */
    @Override
    public void settle(Reservation reservation, long actualMicroCents) {
        if (reservation != null && reservation.degraded()) {
            fallback.settle(reservation, actualMicroCents);
            return;
        }
        super.settle(reservation, actualMicroCents);
    }

    /** 寫回預扣當天的 key：跨日後結算的退款若記到今天，今天的額度會憑空變大 */
    @Override
    protected void applyDelta(Reservation reservation, long delta) {
        try {
            RedisCommands<String, String> commands = connection.sync();
            commands.incrby(globalKey(reservation.day()), delta);
            commands.incrby(tenantKey(reservation.day(), reservation.tenant()), delta);
        } catch (RuntimeException error) {
            // 結算失敗只會讓帳偏高（預扣沒退回），方向是保守的。
            // 記下來但不要拋——這時候請求已經服務完了，拋例外只是把成功變成失敗
            log.warn("成本結算寫入 Redis 失敗，該筆預扣會留在帳上直到跨日：{}", error.toString());
        }
    }

    @Override
    public void record(String tenant, long microCents) {
        if (microCents <= 0) return;
        LocalDate day = LocalDate.now(clock);
        try {
            RedisCommands<String, String> commands = connection.sync();
            for (String key : List.of(globalKey(day), tenantKey(day, keyFor(tenant)))) {
                commands.incrby(key, microCents);
                commands.expire(key, KEY_TTL_SECONDS);
            }
        } catch (RuntimeException error) {
            log.warn("已發生的支出寫入 Redis 失敗，改記在本地備用帳本：{}", error.toString());
            fallback.record(tenant, microCents);
        }
    }

    @Override
    public long spentMicroCents(String tenant) {
        LocalDate day = LocalDate.now(clock);
        try {
            String value = connection.sync().get(tenant == null ? globalKey(day) : tenantKey(day, keyFor(tenant)));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException error) {
            return fallback.spentMicroCents(tenant);
        }
    }

    /**
     * 兩個 key 共用同一個 hash tag，Redis Cluster 下才會落在同一個 slot。
     * 沒有 hash tag 的話，同時操作兩個 key 的腳本會被直接拒絕。
     *
     * <p>日期是 UTC（由注入的 Clock 決定）：多節點在不同時區時，
     * 用本地日期會讓歸零時刻不一致而出現額度空窗。
     */
    private static String daySlot(LocalDate day) {
        return "{budget:" + day + "}";
    }

    private static String globalKey(LocalDate day) {
        return daySlot(day) + ":global";
    }

    private static String tenantKey(LocalDate day, String tenant) {
        return daySlot(day) + ":tenant:" + tenant;
    }

    public void close() {
        connection.close();
        if (client != null) client.shutdown();
    }
}
