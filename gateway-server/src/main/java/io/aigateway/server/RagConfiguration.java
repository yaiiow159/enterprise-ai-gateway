package io.aigateway.server;

import java.util.Set;

import io.aigateway.core.BudgetLedger;
import io.aigateway.core.EmbeddingProvider;
import io.aigateway.core.RagAugmenter;
import io.aigateway.core.VectorStore;
import io.aigateway.providers.OpenAiCompatibleEmbeddingProvider;
import io.aigateway.providers.QdrantVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 檢索增強的組裝與**啟動時的一致性檢查**。
 *
 * <p>整個組態只在 {@code gateway.rag.enabled=true} 時生效：沒有索引的 RAG
 * 只會讓每個請求多付一次嵌入費用然後檢索到空結果。
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.rag", name = "enabled", havingValue = "true")
public class RagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagConfiguration.class);

    @Bean
    EmbeddingProvider embeddingProvider(GatewayProperties properties) {
        GatewayProperties.Rag rag = properties.getRag();
        return new OpenAiCompatibleEmbeddingProvider(
                "embedding",
                rag.getEmbeddingBaseUrl(),
                rag.getEmbeddingApiKey(),
                rag.getEmbeddingModel(),
                rag.getEmbeddingDimension(),
                rag.getEmbeddingInputMicroCentsPerToken(),
                rag.getConnectTimeout(),
                rag.getRequestTimeout());
    }

    @Bean
    VectorStore vectorStore(GatewayProperties properties) {
        GatewayProperties.Rag rag = properties.getRag();
        return new QdrantVectorStore(rag.getVectorStoreUrl(), rag.getVectorStoreApiKey(),
                rag.getConnectTimeout(), rag.getRequestTimeout());
    }

    /**
     * 啟動時比對索引與設定，不符就**拒絕啟動**。
     *
     * <p>嵌入不一致是靜默失效：用 A 模型建索引、用 B 模型查詢，向量庫照樣回傳最近鄰，
     * 只是全部不相關。沒有例外、沒有錯誤碼、延遲也不變——系統不會壞掉，只會變笨。
     * 等到執行期靠「答案品質變差」發現，中間可能已經過了幾週。
     *
     * <p>維度不同其實算幸運（查詢時會直接報錯）；**維度相同但模型不同才是災難**，
     * 因為兩邊都「正常運作」。所以模型名稱的比對比維度更重要。
     */
    @Bean
    RagAugmenter ragAugmenter(EmbeddingProvider embeddings, VectorStore vectorStore,
                              BudgetLedger budget, GatewayProperties properties) {
        GatewayProperties.Rag rag = properties.getRag();
        Set<String> collections = rag.allowedCollections();
        // 每一個可以被檢索的 collection 都要檢查；只查預設那一個的話，其他的等於沒有防線
        collections.forEach(collection -> verifyConsistency(vectorStore, rag, collection));
        return new RagAugmenter(embeddings, vectorStore, budget, collections);
    }

    private void verifyConsistency(VectorStore vectorStore, GatewayProperties.Rag rag, String collection) {
        VectorStore.CollectionInfo info;
        try {
            info = vectorStore.describe(collection);
        } catch (RuntimeException error) {
            // 向量庫連不上與「設定錯了」是不同的問題，訊息要分得開，
            // 否則有人會去改設定而不是去看向量庫
            throw new IllegalStateException(
                    "無法連線到向量庫 %s：%s".formatted(rag.getVectorStoreUrl(), error.getMessage()), error);
        }

        if (info == null) {
            fail(rag, "collection「%s」不存在。先用 tools/rag-index.js 建索引"
                    .formatted(collection));
            return;
        }
        if (info.pointCount() == 0) {
            // 空索引的檢索結果一律為空，但不會報錯——那是「RAG 沒效果」最無聲的原因
            log.warn("collection「{}」是空的：檢索會一律回傳空結果，但不會有任何錯誤",
                    collection);
        }
        if (info.dimension() != rag.getEmbeddingDimension()) {
            fail(rag, "維度不符：collection 是 %d 維，設定是 %d 維"
                    .formatted(info.dimension(), rag.getEmbeddingDimension()));
            return;
        }
        if (info.embeddingModel() == null) {
            fail(rag, ("collection「%s」的 point 沒有宣告 _embedding_model，或混雜了多個模型。"
                    + "前者代表索引不是由 rag-index 建的，後者代表重建索引跑到一半中斷")
                    .formatted(collection));
            return;
        }
        if (!info.embeddingModel().equals(rag.getEmbeddingModel())) {
            fail(rag, ("嵌入模型不符：索引是用「%s」建的，設定要求「%s」。"
                    + "維度相同所以不會報錯，但檢索結果會全部不相關")
                    .formatted(info.embeddingModel(), rag.getEmbeddingModel()));
            return;
        }

        log.info("RAG 就緒：collection={} 模型={} 維度={} 距離={} 資料筆數={}",
                info.name(), info.embeddingModel(), info.dimension(), info.distance(), info.pointCount());
    }

    private void fail(GatewayProperties.Rag rag, String message) {
        if (rag.isFailOnMismatch()) {
            throw new IllegalStateException("RAG 設定與索引不一致：" + message
                    + "（要在不一致的情況下啟動，設 gateway.rag.fail-on-mismatch=false，"
                    + "但檢索結果將不可信）");
        }
        log.error("RAG 設定與索引不一致，但 fail-on-mismatch=false 所以繼續啟動：{}", message);
        log.error("檢索會照常回傳結果，只是那些結果不可信——這正是嵌入不一致最危險的地方");
    }
}
