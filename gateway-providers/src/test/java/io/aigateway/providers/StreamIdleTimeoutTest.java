package io.aigateway.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatStream;
import io.aigateway.core.Message;
import io.aigateway.core.ModelCapability;
import io.aigateway.core.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 用真的 HTTP 伺服器驗證：串流開始後上游卡住，讀取會在閒置逾時後結束，而不是永遠阻塞 */
class StreamIdleTimeoutTest {

    private static final String CHUNK = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n";
    private static final ChatRequest REQUEST =
            new ChatRequest("chat", List.of(Message.user("hello")), 16, true, null, null, null);
    private static final ModelCapability CAPABILITY = new ModelCapability(100_000, 1_000, true, true, false, 1, 1);

    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        // 送一個 chunk 後卡住，模擬上游生成到一半沒有回應
        server.createContext("/stall/v1/chat/completions", exchange -> respond(exchange, 0, true));
        // 第一個 chunk 前等 400ms，之後正常結束
        server.createContext("/slow-start/v1/chat/completions", exchange -> respond(exchange, 400, false));
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void stalledStreamEndsWithAnErrorAfterTheIdleTimeout() {
        ChatStream stream = provider("stall", Duration.ofMillis(300)).stream(REQUEST);
        assertThat(stream.next().delta()).isEqualTo("hi");

        long startedAt = System.nanoTime();
        assertThatThrownBy(stream::hasNext)
                .isInstanceOfSatisfying(ProviderException.class, error -> {
                    assertThat(error.kind()).isEqualTo(ProviderException.Kind.STREAM_ABORTED);
                    assertThat(error.getMessage()).contains("閒置");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        stream.close();
    }

    @Test
    void waitingForTheFirstChunkIsNotCountedAsIdle() {
        // 第一個 chunk 之前由對沖賽跑的期限負責；閒置逾時若套用在這裡，長 prompt 會被誤殺
        try (ChatStream stream = provider("slow-start", Duration.ofMillis(100)).stream(REQUEST)) {
            StringBuilder content = new StringBuilder();
            while (stream.hasNext()) content.append(stream.next().delta());
            assertThat(content.toString()).isEqualTo("hi");
        }
    }

    private OpenAiCompatibleProvider provider(String path, Duration idleTimeout) {
        return new OpenAiCompatibleProvider("test", "http://localhost:" + server.getAddress().getPort() + "/" + path,
                null, "upstream-model", Set.of("chat"), CAPABILITY,
                Duration.ofSeconds(2), Duration.ofSeconds(5), idleTimeout, true);
    }

    private void respond(HttpExchange exchange, long firstChunkDelayMillis, boolean stallAfterFirstChunk)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream body = exchange.getResponseBody()) {
            sleep(firstChunkDelayMillis);
            body.write(CHUNK.getBytes(StandardCharsets.UTF_8));
            body.flush();
            if (stallAfterFirstChunk) {
                awaitRelease();
                return;
            }
            body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException clientGone) {
            // 客戶端中止連線時寫入會失敗，這正是預期的結果
        }
    }

    private void awaitRelease() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
