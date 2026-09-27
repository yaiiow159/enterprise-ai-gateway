package io.aigateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void chineseIsNotUnderestimated() {
        // 常用中文字在各家 tokenizer 裡至少 1 token；估算不能比字數少，否則上下文檢查會漏擋
        String chinese = "網關把成本上限、對沖與失敗切換收斂到同一個地方。".repeat(20);
        long characters = chinese.codePointCount(0, chinese.length());

        assertThat(TokenEstimator.estimate(chinese)).isGreaterThanOrEqualTo(characters);
    }

    @Test
    void englishIsNotOverestimatedByAFactorOfTwo() {
        // 英文約 4 字元一個 token；過去除以 2 會高估一倍，約 6.4 萬 token 以上的英文 prompt 被誤擋
        String english = "The gateway reserves the worst-case cost before sending any request. ".repeat(50);

        long estimate = TokenEstimator.estimate(english);

        assertThat(estimate).isGreaterThanOrEqualTo(english.length() / 4);
        assertThat(estimate).isLessThan(english.length() / 2);
    }

    @Test
    void imagesAreCountedWithoutReadingTheirPayloadAsText() {
        String base64Image = "data:image/png;base64," + "A".repeat(200_000);
        Message message = new Message(Message.Role.USER, "這張圖是什麼？", Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", "這張圖是什麼？"),
                        Map.of("type", "image_url", "image_url", Map.of("url", base64Image)))));

        assertThat(message.hasImages()).isTrue();
        // 圖片以固定值估算；把 base64 當文字的話會估成五萬多 token
        assertThat(message.estimatedTokens()).isBetween((long) Message.TOKENS_PER_IMAGE, 2_000L);
    }

    @Test
    void toolCallArgumentsCountTowardsTheEstimate() {
        Message assistant = new Message(Message.Role.ASSISTANT, "", Map.of(
                "role", "assistant",
                "tool_calls", List.of(Map.of("id", "call_1", "type", "function",
                        "function", Map.of("name", "search", "arguments", "{\"query\":\"" + "x".repeat(700) + "\"}")))));

        assertThat(assistant.estimatedTokens()).isGreaterThan(200);
    }
}
