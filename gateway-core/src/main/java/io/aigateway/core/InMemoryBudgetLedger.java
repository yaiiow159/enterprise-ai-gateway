package io.aigateway.core;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 記在行程記憶體裡的每日成本上限。
 *
 * <p>同時管兩層上限，因為它們防的是不同的事：
 * <ul>
 *   <li><b>全域上限</b>防的是「整個系統今天花太多」——包含所有租戶加總。</li>
 *   <li><b>單租戶上限</b>防的是「某一個租戶把大家的額度吃光」。
 *       只有全域上限的話，一個失控的租戶會讓其他所有人一起被擋。</li>
 * </ul>
 *
 * <p><b>單節點限制</b>：帳記在記憶體裡，多節點時實際總上限會是節點數的倍數。
 * 見 {@link BudgetLedger} 的說明。
 */
public final class InMemoryBudgetLedger extends AbstractBudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBudgetLedger.class);
    private static final String GLOBAL = "__global__";

    private final Clock clock;

    private final Map<String, AtomicLong> spend = new ConcurrentHashMap<>();
    // 純記憶體操作、不會阻塞，但仍用 ReentrantLock 而非 synchronized：
    // 專案慣例是在虛擬執行緒上一律避開 synchronized，少一處要逐一判斷「這裡會不會 pin 住」的地方
    private final ReentrantLock rolloverLock = new ReentrantLock();
    private volatile LocalDate currentDay;

    public InMemoryBudgetLedger(long dailyLimitMicroCents, long perTenantLimitMicroCents, Clock clock) {
        super(dailyLimitMicroCents, perTenantLimitMicroCents);
        this.clock = clock;
        this.currentDay = LocalDate.now(clock);
    }

    @Override
    public Reservation reserve(String tenant, long estimatedMicroCents) {
        LocalDate day = rolloverIfNeeded();
        String key = keyFor(tenant);
        long amount = Math.max(0, estimatedMicroCents);

        // 先加再檢查，超過就退回。這個順序在併發下才安全：
        // 「先查再加」會讓多個請求同時看到「還有額度」然後一起超支
        long globalAfter = counter(GLOBAL).addAndGet(amount);
        long tenantAfter = counter(key).addAndGet(amount);

        if (dailyLimitMicroCents() > 0 && globalAfter > dailyLimitMicroCents()) {
            add(key, -amount);
            throw exceeded("全域", globalAfter - amount, dailyLimitMicroCents(), amount);
        }
        if (perTenantLimitMicroCents() > 0 && tenantAfter > perTenantLimitMicroCents()) {
            add(key, -amount);
            throw exceeded("租戶 " + key, tenantAfter - amount, perTenantLimitMicroCents(), amount);
        }

        return new Reservation(key, amount, day, false);
    }

    /**
     * 預扣當天已經不是今天的話，差額直接捨棄：那一天的帳在跨日時已經歸零，
     * 把退款記進今天會讓今天的額度憑空變大。
     */
    @Override
    protected void applyDelta(Reservation reservation, long delta) {
        if (!rolloverIfNeeded().equals(reservation.day())) return;
        add(reservation.tenant(), delta);
    }

    @Override
    public void record(String tenant, long microCents) {
        if (microCents <= 0) return;
        rolloverIfNeeded();
        add(keyFor(tenant), microCents);
    }

    @Override
    public long spentMicroCents(String tenant) {
        rolloverIfNeeded();
        return counter(tenant == null ? GLOBAL : keyFor(tenant)).get();
    }

    private void add(String key, long amount) {
        counter(GLOBAL).addAndGet(amount);
        counter(key).addAndGet(amount);
    }

    private AtomicLong counter(String key) {
        return spend.computeIfAbsent(key, unused -> new AtomicLong());
    }

    /**
     * 跨日就把帳歸零。
     *
     * <p>用 UTC 日期而不是本地時區：多節點部署在不同時區時，各自在不同時刻歸零，
     * 全域上限就會出現一段兩邊都以為自己是新的一天的空窗。
     *
     * @return 今天（歸零之後的帳所屬的日期）
     */
    private LocalDate rolloverIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(currentDay)) return today;

        rolloverLock.lock();
        try {
            if (today.equals(currentDay)) return today;
            log.info("成本帳本跨日歸零：{} → {}（前一日全域支出 {} 微美分）",
                    currentDay, today, counter(GLOBAL).get());
            spend.clear();
            currentDay = today;
            return today;
        } finally {
            rolloverLock.unlock();
        }
    }

}
