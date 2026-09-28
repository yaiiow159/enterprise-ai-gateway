package io.aigateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageAndToolCallTest {

    @Test
    void originalMessageIsForwardedUnchanged() {
        Map<String, Object> wire = Map.of("role", "tool", "tool_call_id", "call_1", "content", "晴天");
        Message message = new Message(Message.Role.TOOL, "晴天", wire);

        // tool_call_id 若在轉送時掉了，上游會拒絕這則訊息
        assertThat(message.toWire()).isEqualTo(wire);
    }

    @Test
    void prependingTextKeepsImagePartsIntact() {
        Map<String, Object> image = Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/a.png"));
        Message message = new Message(Message.Role.USER, "描述這張圖", Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", "描述這張圖"), image)));

        Message augmented = message.withPrependedText("<documents>…</documents>");

        List<?> parts = (List<?>) augmented.toWire().get("content");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).isEqualTo(Map.of("type", "text", "text", "<documents>…</documents>"));
        assertThat(parts.get(2)).isEqualTo(image);
        assertThat(augmented.content()).startsWith("<documents>").endsWith("描述這張圖");
    }

    @Test
    void unknownRoleIsARequestError() {
        assertThat(Message.Role.parse("developer")).isEqualTo(Message.Role.DEVELOPER);
        assertThatThrownBy(() -> Message.Role.parse("robot")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamedToolCallDeltasAreAssembledByIndex() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        accumulator.add(List.of(Map.of("index", 0, "id", "call_a", "type", "function",
                "function", Map.of("name", "get_weather", "arguments", "{\"ci"))));
        accumulator.add(List.of(Map.of("index", 1, "id", "call_b", "type", "function",
                "function", Map.of("name", "get_time", "arguments", ""))));
        accumulator.add(List.of(Map.of("index", 0, "function", Map.of("arguments", "ty\":\"台北\"}"))));

        List<Map<String, Object>> calls = accumulator.result();

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).containsEntry("id", "call_a")
                .containsEntry("function", Map.of("name", "get_weather", "arguments", "{\"city\":\"台北\"}"));
        assertThat(calls.get(1)).containsEntry("id", "call_b");
    }

    @Test
    void requestsUsingToolsOnlyGoToProvidersThatSupportThem() {
        ModelCapability plain = new ModelCapability(100_000, 1_000, true, false, false, 1, 1);
        ModelCapability withTools = new ModelCapability(100_000, 1_000, true, true, false, 1, 1);
        ChatRequest request = new ChatRequest("chat", List.of(Message.user("幾點了？")), 100, true, null, null,
                Map.of("tools", List.of(Map.of("type", "function", "function", Map.of("name", "get_time")))));

        assertThat(plain.canHandle(request)).isFalse();
        assertThat(withTools.canHandle(request)).isTrue();
    }
}
