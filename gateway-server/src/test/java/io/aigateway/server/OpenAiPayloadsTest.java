package io.aigateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.aigateway.core.ChatChunk;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatResponse;
import io.aigateway.core.Message;
import io.aigateway.core.Usage;
import org.junit.jupiter.api.Test;

class OpenAiPayloadsTest {

    private static final int DEFAULT_MAX_TOKENS = 1_024;

    @Test
    void sdkDefaultRequestWithoutMaxTokensGetsTheDefaultCap() {
        // OpenAI SDK 預設不帶 max_tokens；拒絕的話「改 base URL 就能接」就不成立
        ChatRequest request = parse(Map.of("model", "chat", "messages", List.of(user("hi"))));

        assertThat(request.maxTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
    }

    @Test
    void maxCompletionTokensIsAcceptedAsAnAlias() {
        ChatRequest request = parse(Map.of("model", "chat", "messages", List.of(user("hi")),
                "max_completion_tokens", 64));

        assertThat(request.maxTokens()).isEqualTo(64);
    }

    @Test
    void toolsAndResponseFormatAreForwardedButUnknownFieldsAreNot() {
        Map<String, Object> body = new HashMap<>(Map.of("model", "chat", "messages", List.of(user("hi")),
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "get_time"))),
                "response_format", Map.of("type", "json_object"),
                "stop", List.of("END"),
                "made_up_billing_flag", true));
        body.put("seed", null);

        ChatRequest request = parse(body);

        assertThat(request.extra()).containsOnlyKeys("tools", "response_format", "stop");
        assertThat(request.usesTools()).isTrue();
    }

    @Test
    void multipleChoicesAreRejectedBecauseTheCostWouldMultiply() {
        assertThatThrownBy(() -> parse(Map.of("model", "chat", "messages", List.of(user("hi")), "n", 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multimodalAndToolMessagesKeepTheirOriginalShape() {
        Map<String, Object> image = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "這是哪裡？"),
                Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/a.png"))));
        Map<String, Object> tool = Map.of("role", "tool", "tool_call_id", "call_1", "content", "晴天");

        ChatRequest request = parse(Map.of("model", "chat", "messages", List.of(image, tool)));

        Message first = request.messages().get(0);
        assertThat(first.content()).isEqualTo("這是哪裡？");
        assertThat(first.hasImages()).isTrue();
        assertThat(request.messages().get(1).toWire()).containsEntry("tool_call_id", "call_1");
    }

    @Test
    void malformedRequestsFailAsClientErrors() {
        assertThatThrownBy(() -> parse(Map.of("messages", List.of(user("hi")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(Map.of("model", "chat", "messages", List.of(Map.of("role", "robot", "content", "x")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(Map.of("model", "chat", "messages", List.of(user("hi")), "max_tokens", "many")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamChunksFollowTheOpenAiShape() {
        ChatRequest request = parse(Map.of("model", "chat", "messages", List.of(user("hi"))));

        Map<String, Object> first = OpenAiPayloads.toChunk(ChatChunk.of("你", "p"), request, true, null);
        Map<String, Object> toolChunk = OpenAiPayloads.toChunk(new ChatChunk("", null, null, "p",
                List.of(Map.of("index", 0, "function", Map.of("arguments", "{}")))), request, false, null);

        Map<String, Object> firstChoice = firstChoice(first);
        assertThat(map(firstChoice.get("delta"))).containsEntry("role", "assistant").containsEntry("content", "你");
        // 未結束時是 null，不是空字串
        assertThat(firstChoice.containsKey("finish_reason")).isTrue();
        assertThat(firstChoice.get("finish_reason")).isNull();

        assertThat(map(firstChoice(toolChunk).get("delta"))).containsKey("tool_calls").doesNotContainKey("content");
    }

    @Test
    void toolOnlyResponseHasNullContent() {
        ChatResponse response = new ChatResponse("id", "chat", "p", "", ChatChunk.FinishReason.TOOL_CALLS,
                Usage.EMPTY, 10, List.of(Map.of("id", "call_1", "type", "function",
                        "function", Map.of("name", "get_time", "arguments", "{}"))));

        Map<String, Object> message = map(firstChoice(OpenAiPayloads.toResponse(response, null)).get("message"));

        assertThat(message.get("content")).isNull();
        assertThat(message.get("tool_calls")).isEqualTo(response.toolCalls());
    }

    @Test
    void modelListUsesTheServedNames() {
        Map<String, Object> list = OpenAiPayloads.toModelList(Set.of("fake-chat", "gpt-4o"));

        assertThat(((List<?>) list.get("data")).stream().map(item -> map(item).get("id")).toList())
                .containsExactly("fake-chat", "gpt-4o");
    }

    private static ChatRequest parse(Map<String, Object> body) {
        return OpenAiPayloads.toChatRequest(body, "team-a", DEFAULT_MAX_TOKENS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> firstChoice(Map<String, Object> payload) {
        return map(((List<?>) payload.get("choices")).get(0));
    }

    private static Map<String, Object> user(String content) {
        return Map.of("role", "user", "content", content);
    }
}
