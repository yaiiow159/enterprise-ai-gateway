package io.aigateway.core;

/**
 * 兩種帳本共用的部分：key 規則、結算差額的算法、超限訊息的措辭。
 *
 * <h2>刻意不放進來的：{@code reserve} 的演算法</h2>
 * 記憶體版是「先加、再檢查、超過就回滾」，Redis 版是把整段判斷收進 Lua 腳本。
 * 這個差異**不是實作細節，而是這兩個類別存在的理由**——
 * Redis 版之所以要寫 Lua，正是因為跨節點的「讀後決策」不可能在 Java 端做成原子的。
 * 硬把它拉成共用的模板，等於把最需要被看見的差異藏起來。
 *
 * <h2>放進來的東西為什麼值得共用</h2>
 * 這三件事一旦兩邊不一致，症狀都是「帳對不起來」而不是「程式報錯」：
 * <ul>
 *   <li><b>key 規則</b>——兩邊對「未指定租戶」的處理若不同，
 *       切換 {@code memory} ↔ {@code redis} 後端時同一個租戶會被記到兩個不同的位置。</li>
 *   <li><b>結算差額</b>——預扣與實際的差額算錯方向，額度只減不增，
 *       最後系統會被自己的幽靈支出擋死。</li>
 *   <li><b>金額格式化</b>——只影響訊息可讀性，但兩份實作各自演化的話，
 *       同一種錯誤在不同後端下會給出不同措辭，排查時徒增困惑。</li>
 * </ul>
 */
abstract class AbstractBudgetLedger implements BudgetLedger {

    /** 未指定租戶時的歸戶 key。兩種後端必須一致，否則切換後端會讓帳分裂 */
    protected static final String UNSPECIFIED_TENANT = "(未指定租戶)";

    private final long dailyLimitMicroCents;
    private final long perTenantLimitMicroCents;

    protected AbstractBudgetLedger(long dailyLimitMicroCents, long perTenantLimitMicroCents) {
        this.dailyLimitMicroCents = dailyLimitMicroCents;
        this.perTenantLimitMicroCents = perTenantLimitMicroCents;
    }

    @Override
    public final long dailyLimitMicroCents() {
        return dailyLimitMicroCents;
    }

    protected final long perTenantLimitMicroCents() {
        return perTenantLimitMicroCents;
    }

    /**
     * 結算：把預扣的估算值換成實際花費。
     *
     * <p>用差額而不是「先退全額再扣實際」：後者中間會有一瞬間額度被放大，
     * 併發下剛好在那個瞬間進來的請求就會通過本該被擋下的檢查。
     */
    @Override
    public final void settle(Reservation reservation, long actualMicroCents) {
        if (reservation == null || reservation.amountMicroCents() == 0) return;

        long delta = Math.max(0, actualMicroCents) - reservation.amountMicroCents();
        if (delta == 0) return;
        applyDelta(reservation.tenant(), delta);
    }

    /** 把結算差額寫回去。負值代表退回多扣的部分 */
    protected abstract void applyDelta(String tenantKey, long delta);

    protected static String keyFor(String tenant) {
        return tenant == null || tenant.isBlank() ? UNSPECIFIED_TENANT : tenant;
    }

    protected final ProviderException exceeded(String scope, long alreadySpent, long limit, long requested) {
        return new ProviderException("budget", ProviderException.Kind.BUDGET_EXCEEDED, 429,
                "%s的每日成本上限已達到：已用 %s / 上限 %s，本次請求估算 %s".formatted(
                        scope, usd(alreadySpent), usd(limit), usd(requested)),
                null);
    }

    /**
     * 金額給人看時換算成美元；內部一律用整數微美分。
     *
     * <p>小額時改印微美分：固定小數位在測試或低單價模型下會全部退化成 {@code $0.00}，
     * 那樣的訊息看不出「離上限還有多遠」，對讀它的人沒有用。
     */
    protected static String usd(long microCents) {
        double dollars = microCents / 100.0 / 1_000_000.0;
        return dollars >= 0.01 || microCents == 0
                ? "$%.2f".formatted(dollars)
                : "%d 微美分".formatted(microCents);
    }
}
