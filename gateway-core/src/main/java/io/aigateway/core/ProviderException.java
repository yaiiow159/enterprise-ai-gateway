package io.aigateway.core;

/**
 * Provider 呼叫失敗。
 *
 * <p>錯誤要分類，因為後續處理完全不同：可重試的（429、5xx、連線問題）值得換一家再試，
 * 不可重試的（請求格式錯、內容被拒）換誰都一樣，重試只是多付一次錢。
 */
public class ProviderException extends RuntimeException {

    private final transient String providerName;
    private final transient Kind kind;
    private final transient int statusCode;

    public ProviderException(String providerName, Kind kind, int statusCode, String message, Throwable cause) {
        super("[%s] %s (HTTP %d)：%s".formatted(providerName, kind, statusCode, message), cause);
        this.providerName = providerName;
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public String providerName() {
        return providerName;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return kind.retryable;
    }

    /** 這個錯誤是不是 Provider 本身的問題，要不要算進它的失敗率 */
    public boolean countsAgainstProvider() {
        return kind.countsAgainstProvider;
    }

    /**
     * HTTP 狀態碼 → 錯誤分類。
     *
     * <p>放在這裡而不是各個 Provider 各寫一份：這條映射決定的是「值不值得換一家再試」，
     * 是整個系統的共同語意。散在多處的後果不是抽象不夠漂亮，而是**行為會分岔**——
     * 重構前 chat 與 embedding 兩支對「未列舉的 4xx」的判斷剛好相反
     * （一邊 BAD_REQUEST 不重試、一邊 SERVER_ERROR 會重試），
     * 而兩邊各自看起來都正確，沒有任何測試會失敗。
     *
     * <p>判斷的依據是「問題出在誰身上」：401／403／404 是**這一家**的金鑰、權限或端點設定，
     * 換一家不受影響；其餘 4xx 是請求本身，同一模型群組裡換誰都一樣。
     */
    public static Kind kindForHttpStatus(int status) {
        return switch (status) {
            case 401, 403 -> Kind.AUTHENTICATION;
            case 404 -> Kind.UPSTREAM_NOT_FOUND;
            case 408 -> Kind.TIMEOUT;
            case 429 -> Kind.RATE_LIMITED;
            default -> status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_REQUEST;
        };
    }

    /**
     * 兩個獨立的屬性：
     * <ul>
     *   <li><b>retryable</b>——換一家有沒有機會成功。</li>
     *   <li><b>countsAgainstProvider</b>——要不要拉低這一家的健康度。請求本身的錯誤不算：
     *       否則一個一直送壞請求的租戶，能把健康的 Provider 拉進降級與探測。</li>
     * </ul>
     */
    public enum Kind {
        RATE_LIMITED(true, true),
        SERVER_ERROR(true, true),
        TIMEOUT(true, true),
        CONNECTION(true, true),
        /** 請求本身有問題，同一模型群組裡換誰都一樣會失敗 */
        BAD_REQUEST(false, false),
        /** 沒有任何 Provider 服務呼叫端要求的模型名稱 */
        MODEL_NOT_FOUND(false, false),
        /** 上游拒絕的是這一家的金鑰或權限；別家有自己的金鑰，換一家不受影響 */
        AUTHENTICATION(true, true),
        /** 上游回 404：這一家的端點或模型名稱設定錯了 */
        UPSTREAM_NOT_FOUND(true, true),
        CONTENT_FILTERED(false, false),
        /** 上游在串流中途斷線 */
        STREAM_ABORTED(true, true),
        /**
         * 已達每日成本上限。
         *
         * <p>不可重試——換一家 Provider 一樣要花錢，額度是我們自己的，不是上游的。
         * 這個分類存在的意義就是不讓它落進「5xx 就換一家再試」那條路徑，
         * 否則超支時反而會把每個請求都打滿所有候選。
         */
        BUDGET_EXCEEDED(false, false);

        final boolean retryable;
        final boolean countsAgainstProvider;

        Kind(boolean retryable, boolean countsAgainstProvider) {
            this.retryable = retryable;
            this.countsAgainstProvider = countsAgainstProvider;
        }
    }
}
