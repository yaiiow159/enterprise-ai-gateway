package io.aigateway.server;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aigateway.core.ChatChunk;
import io.aigateway.core.ChatRequest;
import io.aigateway.core.ChatStream;
import io.aigateway.core.HedgingChatClient;
import io.aigateway.core.Message;
import io.aigateway.core.ProviderException;
import io.aigateway.core.ProviderRegistry;
import io.aigateway.core.RagAugmenter;
import io.aigateway.core.RagOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * OpenAI 相容的對話端點。
 *
 * <p>刻意對齊 OpenAI 的請求與回應格式：既有的 SDK（Python {@code openai}、LangChain、
 * 各種前端）只要改 base URL 就能接進來，不必為了網關重寫呼叫端。
 *
 * <p>串流用 {@link SseEmitter} 而非 reactive 型別——整個網關跑在虛擬執行緒上，
 * 逐 chunk 轉發用直線程式碼就能寫完（見 CLAUDE.md 的技術決定 1）。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 預扣嵌入成本時用的單價猜測（微美分／token）。
     *
     * 真實單價在 EmbeddingProvider 的設定裡，但預扣發生在呼叫之前、拿不到它。
     * 取一個偏高的值：預扣估高只會讓額度暫時緊一點，實際成本出來後就結算退回；
     * 估低則是讓上限漏掉一部分，方向不能反。
     */
    private static final long EMBEDDING_COST_PER_TOKEN_GUESS = 100;

    private final HedgingChatClient client;
    private final ProviderRegistry registry;
    private final int defaultMaxTokens;
    /** 沒開啟 RAG 時為 null——沒有索引的檢索只會多付一次嵌入費用然後回傳空結果 */
    private final RagAugmenter rag;

    ChatController(HedgingChatClient client, ProviderRegistry registry, GatewayProperties properties,
                   ObjectProvider<RagAugmenter> rag) {
        this.client = client;
        this.registry = registry;
        this.defaultMaxTokens = properties.getDefaultMaxTokens();
        this.rag = rag.getIfAvailable();
    }

    /** 列出可以在 {@code model} 欄位使用的名稱，也就是各 Provider 宣告的 served-models */
    @GetMapping(path = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> models() {
        Set<String> models = new TreeSet<>();
        registry.all().forEach(provider -> models.addAll(provider.servedModels()));
        return OpenAiPayloads.toModelList(models);
    }

    @PostMapping(path = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletions(@RequestBody Map<String, Object> body,
                                  @RequestAttribute(name = ApiKeyAuthFilter.TENANT_ATTRIBUTE, required = false)
                                  String tenant) {
        ChatRequest request = OpenAiPayloads.toChatRequest(body, tenant, defaultMaxTokens);

        // 檢索**必須在這裡**，也就是在 client 的成本預扣之前。
        // 預扣用 estimatedPromptTokens() 估算，而檢索會往 prompt 塞進成千個 token；
        // 先預扣再注入的話那些 token 完全沒被算進去，成本上限會系統性低估，
        // 而且低估的比例正好隨檢索量成長。
        RagAugmenter.RagResult retrieval = retrieve(body, request, tenant);
        ChatRequest effective = retrieval == null ? request : retrieval.request();

        return effective.stream()
                ? streamResponse(effective, retrieval)
                : ResponseEntity.ok(OpenAiPayloads.toResponse(client.complete(effective), retrieval));
    }

    private RagAugmenter.RagResult retrieve(Map<String, Object> body, ChatRequest request, String tenant) {
        RagOptions options = RagOptions.from(body, tenant, estimatedEmbeddingCost(request));
        if (options == null) return null;
        if (rag == null) {
            throw new IllegalArgumentException(
                    "請求帶了 x_rag，但這個網關沒有啟用檢索（gateway.rag.enabled=false）。"
                            + "靜靜忽略它會讓呼叫端以為答案有依據");
        }
        return rag.augment(request, options);
    }

    /**
     * 嵌入呼叫的成本估算，用於預扣。
     *
     * <p>查詢字串通常很短，但仍然要估——**不估就是記成 0**，
     * 而那正是成本防線失效的起點。
     */
    private long estimatedEmbeddingCost(ChatRequest request) {
        return Math.max(1, request.estimatedPromptTokens()) * EMBEDDING_COST_PER_TOKEN_GUESS;
    }

    /**
     * 串流轉發。
     *
     * <p><b>先取得上游串流，再建立 {@link SseEmitter}</b>，順序不可以顛倒。
     * 一旦把 emitter 回傳給 Spring，回應狀態碼就送出去了；那之後才發現上游失敗，
     * 就只能用一個錯誤事件補救，而 HTTP 狀態碼已經是 200。對只檢查狀態碼的客戶端、
     * 監控與重試中介層來說，那是一次「成功」——上游全部掛掉也一樣。
     * {@link HedgingChatClient#stream} 會阻塞到某個候選送出第一個 token，
     * 在那之前拋出的錯誤都還來得及變成正確的狀態碼（429 仍是 429）。
     *
     * <p>代價是回應標頭會等到第一個 token 才送出。這是刻意的取捨：對沖依定義要以
     * 首個 token 決勝負（見 CLAUDE.md 技術決定 4），所以「連上了但還沒有內容」這個
     * 中間狀態無法在不先承諾狀態碼的情況下傳達。誠實的狀態碼比提早幾百毫秒的標頭重要。
     *
     * <p>其餘容易寫錯的地方：
     * <ul>
     *   <li>逾時設為 0（不逾時）——LLM 的生成時間本來就長，用預設的 30 秒會把長回應砍斷。</li>
     *   <li>{@code onCompletion} 與 {@code onError} 都要關上游串流。客戶端斷線時
     *       SseEmitter 會走 onError，沒關就是使用者早就離開、我們還在付錢。</li>
     *   <li>轉發要在**另一條虛擬執行緒**上跑，不要佔住請求執行緒。</li>
     * </ul>
     */
    private SseEmitter streamResponse(ChatRequest request, RagAugmenter.RagResult retrieval) {
        ChatStream upstream = client.stream(request);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean();

        emitter.onCompletion(() -> closeQuietly(upstream, closed));
        emitter.onTimeout(() -> closeQuietly(upstream, closed));
        emitter.onError(error -> closeQuietly(upstream, closed));

        Thread.ofVirtual().name("sse-relay-" + request.model()).start(() -> {
            try {
                // 檢索資訊只放在第一個 chunk：每個 chunk 都塞會讓串流內容膨脹，
                // 而呼叫端需要的是「這次回答依據了什麼」，那在開頭知道就夠了
                boolean first = true;
                while (upstream.hasNext()) {
                    ChatChunk chunk = upstream.next();
                    emitter.send(SseEmitter.event()
                            .data(json(OpenAiPayloads.toChunk(chunk, request, first, first ? retrieval : null)),
                                    MediaType.APPLICATION_JSON));
                    first = false;
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (ProviderException error) {
                sendErrorEvent(emitter, error);
            } catch (IOException error) {
                log.debug("客戶端已斷線，停止轉發：{}", error.getMessage());
                emitter.completeWithError(error);
            } catch (RuntimeException error) {
                log.error("串流轉發失敗", error);
                emitter.completeWithError(error);
            } finally {
                closeQuietly(upstream, closed);
            }
        });
        return emitter;
    }

    private void sendErrorEvent(SseEmitter emitter, ProviderException error) {
        try {
            emitter.send(SseEmitter.event().data(json(OpenAiPayloads.toErrorEvent(
                    error.getMessage(), error.kind().name().toLowerCase(), error.providerName())),
                    MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(error);
        }
    }

    /** SSE 的 data 欄位要是字串，序列化只在這一處發生 */
    private String json(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (IOException error) {
            throw new IllegalStateException("無法序列化回應內容", error);
        }
    }

    private void closeQuietly(ChatStream stream, AtomicBoolean closed) {
        if (stream != null && closed.compareAndSet(false, true)) {
            stream.close();
        }
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<Map<String, Object>> handleProviderException(ProviderException error) {
        HttpStatus status = switch (error.kind()) {
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            // 額度用盡沿用 429：對客戶端而言「現在不要再送了」的處理方式是一樣的，
            // 差別在 type 欄位（insufficient_quota），與 OpenAI 的語意一致
            case BUDGET_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            // 上游拒絕的是網關自己的金鑰，不是客戶端的——回 401 會讓客戶端以為自己的 key 錯了
            case AUTHENTICATION, UPSTREAM_NOT_FOUND -> HttpStatus.BAD_GATEWAY;
            case BAD_REQUEST, CONTENT_FILTERED -> HttpStatus.BAD_REQUEST;
            case MODEL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "message", error.getMessage(),
                "type", error.kind().name().toLowerCase(),
                "provider", error.providerName())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "message", error.getMessage(),
                "type", "invalid_request_error")));
    }
}
