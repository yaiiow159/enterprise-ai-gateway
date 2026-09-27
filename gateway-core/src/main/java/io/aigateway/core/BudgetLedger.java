package io.aigateway.core;

import java.time.LocalDate;

/**
 * 每日成本上限。
 *
 * <p>這是**最後一道防線**。上游的每一種防護都可能被繞過——有人新增了端點忘了設上限、
 * 有人把對沖改成無條件、上游調漲了價格、某個 prompt 意外變成無限迴圈。
 * 這些都無法窮舉，但一個真的會攔截的每日上限能把損失限制在一天之內。
 *
 * <p><b>「有設定欄位」不等於「有防線」。</b> 一個只有欄位卻不會攔截的上限，
 * 比沒有上限更危險，因為它看起來像有保護。所以這個介面的核心方法會**拋例外**，
 * 而不是回傳一個布林值讓呼叫端自己決定要不要理它。
 *
 * <h2>預扣再結算，而不是事後記帳</h2>
 * 事後記帳擋不住併發：一百個請求同時通過檢查、同時發現額度還夠，然後一起超支。
 * 所以送出前先以**估算值預扣**（估算用 maxTokens，是最壞情況），
 * 實際成本出來後再結算差額。方向刻意保守：寧可暫時擋掉一個本來付得起的請求，
 * 也不要放行一批合起來超支的請求。
 *
 * <h2>兩種實作</h2>
 * {@link InMemoryBudgetLedger} 是單節點的帳，多節點時實際總上限是節點數的倍數；
 * {@link RedisBudgetLedger} 讓多節點共用同一份帳。
 */
public interface BudgetLedger {

    /**
     * 預扣估算成本。
     *
     * @throws ProviderException kind 為 {@code BUDGET_EXCEEDED}，代表這次呼叫會超過上限
     */
    Reservation reserve(String tenant, long estimatedMicroCents);

    /**
     * 用實際成本結算先前的預扣。實際成本為 0 表示這次呼叫沒有產生費用（例如全部候選都失敗），
     * 預扣的額度會全部退回。
     *
     * <p>必須是**冪等**的：串流可能被關閉多次（客戶端斷線、對沖落敗、正常結束）。
     */
    void settle(Reservation reservation, long actualMicroCents);

    /**
     * 記下一筆**已經發生**、沒有對應預扣的支出，例如對沖落敗者已被計費的輸入 token。
     *
     * <p>不做上限檢查也不拋例外：錢已經花掉了，拒絕記帳只會讓帳本低估，
     * 而低估正是讓上限在「對沖被設成無條件」這類情境下失效的原因。
     */
    void record(String tenant, long microCents);

    /** 目前這一天某個租戶已經花掉的金額。tenant 為 null 時回傳全域總額 */
    long spentMicroCents(String tenant);

    /** 0 表示沒有設定上限 */
    long dailyLimitMicroCents();

    /**
     * 一次預扣。金額用整數微美分，不用浮點數——這是要拿去跟帳單對帳的數字。
     *
     * @param day      預扣記在哪一天的帳上。結算必須回到同一天，跨日結算才不會把退款記進新的一天
     * @param degraded 共用帳本不可用、改記在本地備用帳本上。結算必須回到同一本帳
     */
    record Reservation(String tenant, long amountMicroCents, LocalDate day, boolean degraded) {
        public static final Reservation NONE = new Reservation(null, 0, null, false);
    }

    /**
     * 不設上限。
     *
     * <p>刻意做成要**明確選擇**才會得到的東西，而不是「沒設定就自動變成這樣」——
     * 沒有上限是一個需要被意識到的決定，不該是預設值靜悄悄生效的結果。
     */
    static BudgetLedger disabled() {
        return new BudgetLedger() {
            @Override
            public Reservation reserve(String tenant, long estimatedMicroCents) {
                return Reservation.NONE;
            }

            @Override
            public void settle(Reservation reservation, long actualMicroCents) {
                // 沒有上限就沒有帳可結
            }

            @Override
            public void record(String tenant, long microCents) {
                // 沒有上限就沒有帳可記
            }

            @Override
            public long spentMicroCents(String tenant) {
                return 0;
            }

            @Override
            public long dailyLimitMicroCents() {
                return 0;
            }
        };
    }
}
