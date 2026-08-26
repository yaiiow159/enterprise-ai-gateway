package io.aigateway.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 包住勝出的串流，在它結束時用**實際**成本結算先前的預扣。
 *
 * <p>為什麼要包一層而不是在呼叫端結算：串流的結束時機有好幾種——正常讀完、
 * 客戶端斷線、上游中途失敗、對沖落敗被中止。每一種都走 {@link ChatStream#close()}，
 * 所以把結算掛在 close 上是唯一能涵蓋全部路徑的位置。
 * 漏掉任何一條路徑，預扣的額度就會永遠留在帳上，額度只減不增，
 * 最後整個系統會被自己的幽靈支出擋死。
 */
final class BudgetedChatStream implements ChatStream {

    private final ChatStream delegate;
    private final BudgetLedger ledger;
    private final BudgetLedger.Reservation reservation;
    private final AtomicBoolean settled = new AtomicBoolean();

    private long actualMicroCents;

    BudgetedChatStream(ChatStream delegate, BudgetLedger ledger, BudgetLedger.Reservation reservation) {
        this.delegate = delegate;
        this.ledger = ledger;
        this.reservation = reservation;
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public boolean hasNext() {
        boolean more = delegate.hasNext();
        // 上游讀完了就立刻結算，不必等呼叫端記得關。close() 仍會再試一次，但結算是冪等的
        if (!more) settle();
        return more;
    }

    @Override
    public ChatChunk next() {
        ChatChunk chunk = delegate.next();
        // 用量通常只在最後一個 chunk 出現，但這裡不假設——任何一個帶用量的 chunk 都採計最新值
        if (chunk.usage() != null) actualMicroCents = chunk.usage().costMicroCents();
        return chunk;
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            settle();
        }
    }

    /**
     * 結算。中途斷線時 {@code actualMicroCents} 可能還是 0——那代表上游還沒回報用量，
     * 但 token 其實已經產生了。這種情況寧可以預扣的估算值入帳，
     * **不要當成免費**：低估成本會讓上限失效，而上限是最後一道防線。
     */
    private void settle() {
        if (!settled.compareAndSet(false, true)) return;
        ledger.settle(reservation, actualMicroCents > 0 ? actualMicroCents : reservation.amountMicroCents());
    }
}
