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
        STREAM_ABORTED(true);

        final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }
    }
}
