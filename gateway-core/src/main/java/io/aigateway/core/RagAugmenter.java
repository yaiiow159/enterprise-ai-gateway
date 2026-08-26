package io.aigateway.core;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 檢索並把結果注入 prompt。
 *
 * <h2>呼叫順序不可以顛倒：檢索必須在成本預扣之前</h2>
 * 預算預扣用 {@link ChatRequest#estimatedPromptTokens()} 估算，
 * 而檢索會往 prompt 塞進成千個 token。先預扣再注入的話，那些 token
 * **完全沒有被算進去**——成本上限會系統性低估，而且低估的比例正好隨檢索量成長。
 * 這是「有防線但擋不住」的典型形態：帳面上一切正常，實際上防線是漏的。
 *
 * <h2>失敗預設是失敗，降級要明確 opt-in</h2>
 * 向量庫掛掉時靜默降級成「不帶 context 的普通問答」，是這個系統最典型的失敗形態：
 * 狀態碼 200、答案通順、只是完全沒有根據。使用者無從察覺，監控也看不出來。
 * 所以預設拋錯；真要降級必須在 {@link RagOptions} 明確要求，
 * 而且結果會標記 {@code degraded}，讓呼叫端知道這個答案沒有依據。
 */
public final class RagAugmenter {

    private static final Logger log = LoggerFactory.getLogger(RagAugmenter.class);

    private final EmbeddingProvider embeddings;
    private final VectorStore vectorStore;
    private final BudgetLedger budget;

    public RagAugmenter(EmbeddingProvider embeddings, VectorStore vectorStore, BudgetLedger budget) {
        this.embeddings = embeddings;
        this.vectorStore = vectorStore;
        this.budget = budget;
    }

    /**
     * 檢索並回傳「已注入 context」的請求。
     *
     * @throws ProviderException 檢索失敗且未開啟降級，或已達成本上限
     */
    public RagResult augment(ChatRequest request, RagOptions options) {
        String query = lastUserMessage(request);
        if (query.isBlank()) {
            throw new ProviderException("rag", ProviderException.Kind.BAD_REQUEST, 400,
                    "找不到可用於檢索的使用者訊息", null);
        }

        List<RetrievedDocument> documents;
        try {
            documents = retrieve(query, options);
        } catch (ProviderException error) {
            if (error.kind() == ProviderException.Kind.BUDGET_EXCEEDED) throw error;
            if (!options.degradeOnFailure()) throw error;

            // 降級是明確要求的，但仍然要留下痕跡——不然就變成靜默降級
            log.warn("檢索失敗，依設定降級為不帶 context 的呼叫（答案將沒有依據）：{}", error.getMessage());
            return new RagResult(request, List.of(), true, error.getMessage(), Usage.EMPTY);
        }

        if (documents.isEmpty()) {
            // 「沒有相關文件」與「檢索壞了」是不同的事，前者不該被當成錯誤，
            // 但也不該被當成正常——標記出來讓呼叫端自己決定
            log.debug("檢索沒有命中任何文件（collection={}）", options.collection());
            return new RagResult(request, List.of(), false, "沒有命中任何文件", embeddings.lastUsage());
        }

        return new RagResult(inject(request, documents), documents, false, null, embeddings.lastUsage());
    }

    private List<RetrievedDocument> retrieve(String query, RagOptions options) {
        // 嵌入也是付費的上游呼叫。不記帳的話成本倍率會低估，
        // 而且低估的比例正好隨檢索量成長——與 chat 的成本歸戶是同一個問題
        BudgetLedger.Reservation reservation = budget.reserve(options.tenant(), options.estimatedEmbeddingCost());
        try {
            float[] vector = embeddings.embed(List.of(query)).get(0);
            budget.settle(reservation, embeddings.lastUsage().costMicroCents());
            return vectorStore.search(options.collection(), vector, options.topK(), options.minScore());
        } catch (RuntimeException error) {
            budget.settle(reservation, 0);
            throw error;
        }
    }

    /**
     * 把檢索結果放進 system 訊息，而不是接在使用者訊息後面。
     *
     * <p>接在使用者訊息裡的話，模型分不出哪些是使用者說的、哪些是我們塞的——
     * 而檢索到的內容來自外部文件，**不該被當成使用者的指令**。
     * 這與「Provider 回傳的內容不可信」是同一個道理。
     */
    private ChatRequest inject(ChatRequest request, List<RetrievedDocument> documents) {
        StringBuilder context = new StringBuilder("以下是從知識庫檢索到的參考資料，"
                + "回答時請以它們為依據；若資料不足以回答，請明說而不要臆測。\n\n");
        for (int index = 0; index < documents.size(); index++) {
            RetrievedDocument document = documents.get(index);
            context.append("[%d] (id=%s, score=%.3f)\n%s\n\n"
                    .formatted(index + 1, document.id(), document.score(), document.text()));
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Message.Role.SYSTEM, context.toString().trim()));
        messages.addAll(request.messages());

        return new ChatRequest(request.model(), messages, request.maxTokens(), request.stream(),
                request.temperature(), request.tenant(), request.extra());
    }

    private static String lastUserMessage(ChatRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            Message message = request.messages().get(index);
            if (message.role() == Message.Role.USER) return message.content();
        }
        return "";
    }

    /**
     * @param degraded 檢索失敗但依設定繼續。答案沒有依據，呼叫端必須知道
     * @param note     沒有命中或降級的原因；正常時為 null
     */
    public record RagResult(ChatRequest request,
                            List<RetrievedDocument> documents,
                            boolean degraded,
                            String note,
                            Usage embeddingUsage) {
    }
}
