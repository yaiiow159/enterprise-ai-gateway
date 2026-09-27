package io.aigateway.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 對話中的一則訊息。
 *
 * @param role    發話者
 * @param content 訊息的文字部分，用於估算 token 與檢索。這是使用者資料，預設不落地——要記錄時只留 hash 與 token 數
 * @param wire    客戶端送來的原始訊息（OpenAI 格式），原樣轉送給上游；null 表示只有文字。
 *                content 陣列（多模態）、{@code tool_calls}、{@code tool_call_id} 都靠它保留
 */
public record Message(Role role, String content, Map<String, Object> wire) {

    /** 一張圖片以 1000 token 估算：各家依解析度計價，高解析度約在這個量級 */
    static final int TOKENS_PER_IMAGE = 1_000;

    public Message {
        if (role == null) throw new IllegalArgumentException("role 不可為 null");
        if (content == null) throw new IllegalArgumentException("content 不可為 null");
        // 不用 Map.copyOf：OpenAI 訊息的欄位可以是 null（帶 tool_calls 的 assistant 訊息 content 為 null）
        wire = wire == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(wire));
    }

    public Message(Role role, String content) {
        this(role, content, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    /** 送給上游的格式：有原始訊息就原樣轉送，否則只有角色與文字 */
    public Map<String, Object> toWire() {
        if (wire != null) return wire;
        return Map.of("role", role.wireName(), "content", content);
    }

    /** 是否帶圖片 */
    public boolean hasImages() {
        return wire != null && countTextAndImages(wire, new TokenEstimator()) > 0;
    }

    /** 估算這則訊息的 token 數：文字、工具呼叫的參數，以及圖片 */
    public long estimatedTokens() {
        if (wire == null) return TokenEstimator.estimate(content);
        TokenEstimator estimator = new TokenEstimator();
        long images = countTextAndImages(wire, estimator);
        return estimator.tokens() + images * TOKENS_PER_IMAGE;
    }

    /**
     * 在文字內容前面加上一段文字，用於注入檢索結果。
     *
     * <p>原始訊息是多模態陣列時，加成陣列的第一個文字部分，其餘部分（圖片）保持不變。
     */
    public Message withPrependedText(String text) {
        String newContent = text + "\n\n" + content;
        if (wire == null) return new Message(role, newContent);

        Map<String, Object> newWire = new LinkedHashMap<>(wire);
        Object original = wire.get("content");
        if (original instanceof List<?> parts) {
            List<Object> newParts = new ArrayList<>();
            newParts.add(Map.of("type", "text", "text", text));
            newParts.addAll(parts);
            newWire.put("content", newParts);
        } else {
            newWire.put("content", newContent);
        }
        return new Message(role, newContent, newWire);
    }

    /** 走訪訊息結構累計文字；回傳圖片數。圖片的網址或 base64 內容不算文字，否則一張圖會被估成幾十萬 token */
    private static long countTextAndImages(Object node, TokenEstimator estimator) {
        if (node instanceof CharSequence text) {
            estimator.add(text);
            return 0;
        }
        long images = 0;
        if (node instanceof Map<?, ?> map) {
            if ("image_url".equals(map.get("type")) || "input_image".equals(map.get("type"))) return 1;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // 角色、型別與識別碼是結構，不是內容
                if (!"role".equals(entry.getKey()) && !"type".equals(entry.getKey()) && !"id".equals(entry.getKey())) {
                    images += countTextAndImages(entry.getValue(), estimator);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) images += countTextAndImages(item, estimator);
        }
        return images;
    }

    public enum Role {
        SYSTEM, DEVELOPER, USER, ASSISTANT, TOOL, FUNCTION;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @throws IllegalArgumentException 不認得的角色，要當成請求錯誤回給呼叫端 */
        public static Role parse(String raw) {
            for (Role role : values()) {
                if (role.wireName().equals(raw)) return role;
            }
            throw new IllegalArgumentException("不支援的 role：" + raw);
        }
    }
}
