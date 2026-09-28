package io.aigateway.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 網關自己核發的 API key 與租戶的對應。
 *
 * <p>租戶一律由 key 推導，不接受客戶端自己宣告：成本歸戶與單租戶上限都以租戶為單位，
 * 讓客戶端自報租戶的話，換一個名稱就拿到一份新的額度，也能把花費記到別的團隊頭上。
 *
 * <p>記憶體裡只存 key 的 SHA-256，不存明文；同一個租戶可以有多把 key，輪替時新舊並存。
 */
final class ApiKeyAuthenticator {

    /** 租戶名稱會成為成本帳本的 key 一部分，限制字元以免混進分隔符號 */
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** 太短的 key 等於沒有 key */
    private static final int MIN_KEY_LENGTH = 24;

    private final Map<String, String> tenantByKeyHash;

    private ApiKeyAuthenticator(Map<String, String> tenantByKeyHash) {
        this.tenantByKeyHash = Map.copyOf(tenantByKeyHash);
    }

    /**
     * 解析 {@code 租戶=key} 清單，項目之間以逗號、分號或換行分隔。
     *
     * <p>錯誤訊息只提到第幾筆與租戶名稱，**不含 key 本身**：這個訊息會出現在啟動日誌裡。
     *
     * @throws IllegalArgumentException 格式錯誤、租戶名稱不合法、key 太短或重複
     */
    static ApiKeyAuthenticator parse(String spec) {
        Map<String, String> tenantByKeyHash = new HashMap<>();
        if (spec == null || spec.isBlank()) return new ApiKeyAuthenticator(tenantByKeyHash);

        String[] entries = spec.split("[,;\\n]");
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index].trim();
            if (entry.isEmpty()) continue;

            int separator = entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("第 %d 筆 API key 設定缺少「租戶=key」的等號".formatted(index + 1));
            }
            String tenant = entry.substring(0, separator).trim();
            String key = entry.substring(separator + 1).trim();

            if (!TENANT.matcher(tenant).matches()) {
                throw new IllegalArgumentException(
                        "第 %d 筆的租戶名稱只能用英數字、點、底線與連字號（1～64 字）".formatted(index + 1));
            }
            if (key.length() < MIN_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "租戶 %s 的 key 太短，至少要 %d 個字元".formatted(tenant, MIN_KEY_LENGTH));
            }
            if (tenantByKeyHash.putIfAbsent(sha256(key), tenant) != null) {
                throw new IllegalArgumentException("租戶 %s 的 key 與前面的設定重複".formatted(tenant));
            }
        }
        return new ApiKeyAuthenticator(tenantByKeyHash);
    }

    /**
     * @param presentedKey 客戶端送來的 key；null 視為未提供
     * @return key 對應的租戶；key 無效時為空
     */
    Optional<String> tenantFor(String presentedKey) {
        if (presentedKey == null || presentedKey.isEmpty()) return Optional.empty();
        return Optional.ofNullable(tenantByKeyHash.get(sha256(presentedKey)));
    }

    boolean isEmpty() {
        return tenantByKeyHash.isEmpty();
    }

    int size() {
        return tenantByKeyHash.size();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 必定提供 SHA-256", impossible);
        }
    }
}
