package io.aigateway.core;

/**
 * 一次呼叫的用量與成本。
 *
 * @param promptTokens     輸入 token 數
 * @param completionTokens 輸出 token 數
 * @param costMicroCents   成本，單位為百萬分之一美分的整數
 *
 * <p>金額用整數而非浮點數：LLM 的單次成本常常小到 0.0001 美分，
 * 浮點數在累加數百萬筆之後會產生可觀的誤差，而這是要拿去跟帳單對帳的數字。
 */
public record Usage(long promptTokens, long completionTokens, long costMicroCents) {

    public static final Usage EMPTY = new Usage(0, 0, 0);

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public Usage plus(Usage other) {
        return new Usage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                costMicroCents + other.costMicroCents);
    }
}
