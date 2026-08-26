package io.aigateway.core;

/**
 * 對話中的一則訊息。
 *
 * @param role    發話者
 * @param content 內容。這是使用者資料，預設不落地——要記錄時只留 hash 與 token 數
 */
public record Message(Role role, String content) {

    public Message {
        if (role == null) throw new IllegalArgumentException("role 不可為 null");
        if (content == null) throw new IllegalArgumentException("content 不可為 null");
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }
}
