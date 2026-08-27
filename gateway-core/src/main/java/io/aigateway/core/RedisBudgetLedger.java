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

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final String script;
    private final Clock clock;
    /** Redis 不可用時的退路。不是「放行」，而是降級成單節點保護 */
    private final BudgetLedger fallback;

    private volatile String scriptSha;

    public RedisBudgetLedger(String redisUri,
                             long dailyLimitMicroCents,
                             long perTenantLimitMicroCents,
                             Clock clock) {
        super(dailyLimitMicroCents, perTenantLimitMicroCents);
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.script = loadScript();
        this.clock = clock;
        this.fallback = new InMemoryBudgetLedger(dailyLimitMicroCents, perTenantLimitMicroCents, clock);
        this.scriptSha = connection.sync().scriptLoad(script);
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
        if (amount == 0) return new Reservation(key, 0);

        List<Long> result;
        try {
            result = evalReserve(key, amount);
        } catch (ProviderException budgetError) {
            throw budgetError;
        } catch (RuntimeException error) {
            // Redis 掛了。退回本地帳本——保護降級但不消失，而且要讓人看得到
            log.error("Redis 成本帳本不可用，暫時退回單節點帳本（保護已降級）：{}", error.toString());
            return fallback.reserve(tenant, estimatedMicroCents);
        }

        if (result.get(0) == 1L) return new Reservation(key, amount);

        boolean tenantScope = result.get(3) == 1L;
        throw exceeded(
                tenantScope ? "租戶 " + key : "全域",
                tenantScope ? result.get(2) : result.get(1),
                tenantScope ? perTenantLimitMicroCents() : dailyLimitMicroCents(),
                amount);
    }

    @SuppressWarnings("unchecked")
    private List<Long> evalReserve(String key, long amount) {
        RedisCommands<String, String> commands = connection.sync();
        String[] keys = { globalKey(), tenantKey(key) };
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

    @Override
    protected void applyDelta(String tenant, long delta) {
        try {
            RedisCommands<String, String> commands = connection.sync();
            commands.incrby(globalKey(), delta);
            commands.incrby(tenantKey(tenant), delta);
        } catch (RuntimeException error) {
            // 結算失敗只會讓帳偏高（預扣沒退回），方向是保守的。
            // 記下來但不要拋——這時候請求已經服務完了，拋例外只是把成功變成失敗
            log.warn("成本結算寫入 Redis 失敗，該筆預扣會留在帳上直到跨日：{}", error.toString());
        }
    }

    @Override
    public long spentMicroCents(String tenant) {
        try {
            String value = connection.sync().get(tenant == null ? globalKey() : tenantKey(keyFor(tenant)));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException error) {
            return fallback.spentMicroCents(tenant);
        }
    }

    /**
     * 兩個 key 共用同一個 hash tag，Redis Cluster 下才會落在同一個 slot。
     * 沒有 hash tag 的話，同時操作兩個 key 的腳本會被直接拒絕。
     */
    private String daySlot() {
        // UTC：多節點在不同時區時，用本地日期會讓歸零時刻不一致而出現額度空窗
        return "{budget:" + LocalDate.now(clock) + "}";
    }

    private String globalKey() {
        return daySlot() + ":global";
    }

    private String tenantKey(String tenant) {
        return daySlot() + ":tenant:" + tenant;
    }


    public void close() {
        connection.close();
        client.shutdown();
    }
}
