package io.aigateway.core;

/**
 * 不依賴 tokenizer 的 token 估算，刻意偏高。
 *
 * <p>用途是送出前的上下文檢查、成本預扣，以及上游沒回用量時的兜底。
 * 係數依文字種類分開：中日韓等表意文字每字記 1.5 token（常用字多為 1、罕用字 2～3），
 * 其餘每 3.5 字元記 1 token（英文實際約 4）。混成單一係數的話，
 * 不是讓中文被低估（漏擋、少算錢），就是讓英文被高估一倍（長 prompt 被誤擋）。
 *
 * <p>誤差方向：中文高估約 0～50%，英文高估約 15%。高估的代價是偶爾擋掉剛好塞得下的請求、
 * 預扣偏多；低估的代價是上下文檢查失效與帳本少記，後者比較危險。
 */
public final class TokenEstimator {

    /** 每則訊息的格式開銷（角色標記、分隔符號） */
    public static final int PER_MESSAGE_OVERHEAD = 4;

    private long ideographic;
    private long other;

    /** 估算一段文字的 token 數 */
    public static long estimate(CharSequence text) {
        TokenEstimator estimator = new TokenEstimator();
        estimator.add(text);
        return estimator.tokens();
    }

    /** 累加一段文字，用於串流逐段累計 */
    public void add(CharSequence text) {
        if (text == null) return;
        text.codePoints().forEach(codePoint -> {
            if (isIdeographic(codePoint)) ideographic++;
            else other++;
        });
    }

    /** 目前累計的估算值 */
    public long tokens() {
        // 1.5 與 1/3.5 用整數運算、各自無條件進位
        return (ideographic * 3 + 1) / 2 + (other * 2 + 6) / 7;
    }

    private static boolean isIdeographic(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                // 全形標點與符號（，。「」）在各家 tokenizer 裡也多半自成一個 token
                || Character.UnicodeBlock.of(codePoint) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || Character.UnicodeBlock.of(codePoint) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
