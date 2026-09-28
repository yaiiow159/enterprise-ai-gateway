package io.aigateway.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.aigateway.core.ChatChunk;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatStream;
import io.aigateway.core.Message;
import io.aigateway.core.ModelCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 用真的 HTTP 伺服器驗證送出的請求內容，以及串流裡工具呼叫的解析 */
class OpenAiCompatibleProviderTest {

    private static final ModelCapability CAPABILITY = new ModelCapability(100_000, 1_000, true, true, true, 10, 20);
    private static final String TOOL_STREAM = String.join("\n\n",
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                    + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"ci\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ty\\\":\\\"台北\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "data: [DONE]", "");

    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(TOOL_STREAM.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void passthroughFieldsCannotOverrideWhatTheGatewayDecided() {
        // 就算 extra 裡混進了 model／max_tokens，也不能蓋掉路由選定的模型與成本預扣的依據
        ChatRequest request = new ChatRequest("chat", List.of(Message.user("hi")), 16, true, null, null,
                Map.of("model", "gpt-expensive", "max_tokens", 100_000, "response_format", Map.of("type", "json_object")));

        drain(provider().stream(request));

        JsonNode body = ProviderJson.readTree("test", receivedBody.get(), "請求");
        assertThat(body.path("model").asText()).isEqualTo("upstream-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(16);
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        // 要求上游回報串流用量，否則每一筆都只能靠本地估算
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void originalMessagesAreForwardedWithToolCallIdsAndImages() {
        Map<String, Object> tool = Map.of("role", "tool", "tool_call_id", "call_1", "content", "晴天");
        Map<String, Object> image = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "這是哪裡？"),
                Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/a.png"))));
        ChatRequest request = new ChatRequest("chat", List.of(
                new Message(Message.Role.TOOL, "晴天", tool),
                new Message(Message.Role.USER, "這是哪裡？", image)), 16, true, null, null, null);

        drain(provider().stream(request));

        JsonNode messages = ProviderJson.readTree("test", receivedBody.get(), "請求").path("messages");
        assertThat(messages.path(0).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.path(1).path("content").path(1).path("type").asText()).isEqualTo("image_url");
    }

    @Test
    void streamedToolCallDeltasArePassedThrough() {
        ChatRequest request = new ChatRequest("chat", List.of(Message.user("台北天氣？")), 16, true, null, null,
                Map.of("tools", List.of(Map.of("type", "function", "function", Map.of("name", "get_weather")))));

        List<ChatChunk> chunks = drain(provider().stream(request));

        List<ChatChunk> withToolCalls = chunks.stream().filter(ChatChunk::hasToolCalls).toList();
        assertThat(withToolCalls).hasSize(2);
        assertThat(withToolCalls.get(0).toolCalls().get(0)).containsEntry("id", "call_1");
        assertThat(chunks).anyMatch(chunk -> chunk.finishReason() == ChatChunk.FinishReason.TOOL_CALLS);
        // 上游沒回用量：以收到的參數文字估算輸出，不是記成 0
        assertThat(chunks.get(chunks.size() - 1).usage().completionTokens()).isPositive();
    }

    private OpenAiCompatibleProvider provider() {
        return new OpenAiCompatibleProvider("test", "http://localhost:" + server.getAddress().getPort(),
                null, "upstream-model", Set.of("chat"), CAPABILITY,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), true);
    }

    private static List<ChatChunk> drain(ChatStream stream) {
        List<ChatChunk> chunks = new ArrayList<>();
        try (stream) {
            while (stream.hasNext()) chunks.add(stream.next());
        }
        return chunks;
    }
}
