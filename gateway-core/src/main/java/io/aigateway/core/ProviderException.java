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

    /**
     * HTTP 狀態碼 → 錯誤分類。
     *
     * <p>放在這裡而不是各個 Provider 各寫一份：這條映射決定的是「值不值得換一家再試」，
     * 是整個系統的共同語意。散在多處的後果不是抽象不夠漂亮，而是**行為會分岔**——
     * 重構前 chat 與 embedding 兩支對「未列舉的 4xx」的判斷剛好相反
     * （一邊 BAD_REQUEST 不重試、一邊 SERVER_ERROR 會重試），
     * 而兩邊各自看起來都正確，沒有任何測試會失敗。
     *
     * <p>統一採用「4xx 一律不重試」：請求本身有問題時，換誰都是一樣的結果，
     * 重試只是多等一次、多付一次錢。
     */
    public static Kind kindForHttpStatus(int status) {
        return switch (status) {
            case 401, 403 -> Kind.AUTHENTICATION;
            case 429 -> Kind.RATE_LIMITED;
            default -> status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_REQUEST;
        };
    }

    public enum Kind {
        RATE_LIMITED(true),
        SERVER_ERROR(true),
        TIMEOUT(true),
        CONNECTION(true),
        /** 請求本身有問題，換一家 Provider 也一樣會失敗 */
        BAD_REQUEST(false),
        AUTHENTICATION(false),
        CONTENT_FILTERED(false),
        /** 上游在串流中途斷線 */
        STREAM_ABORTED(true),
        /**
         * 已達每日成本上限。
         *
         * <p>不可重試——換一家 Provider 一樣要花錢，額度是我們自己的，不是上游的。
         * 這個分類存在的意義就是不讓它落進「5xx 就換一家再試」那條路徑，
         * 否則超支時反而會把每個請求都打滿所有候選。
         */
        BUDGET_EXCEEDED(false);

        final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }
    }
}
