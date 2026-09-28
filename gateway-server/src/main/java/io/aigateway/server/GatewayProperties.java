package io.aigateway.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 網關設定。
 *
 * <p>Provider 的金鑰刻意只接受環境變數佔位（例如 {@code ${OPENAI_API_KEY:}}），
 * 設定檔本身不該出現任何金鑰字面值。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final List<ProviderConfig> providers = new ArrayList<>();

    private Routing routing = new Routing();

    private Hedge hedge = new Hedge();

    private Budget budget = new Budget();

    private Rag rag = new Rag();

    private Auth auth = new Auth();

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Rag getRag() {
        return rag;
    }

    public void setRag(Rag rag) {
        this.rag = rag;
    }

    /** 等到第一個 token 的期限（含對沖與失敗切換） */
    private Duration attemptTimeout = Duration.ofSeconds(60);

    /**
     * 請求沒帶 max_tokens 時套用的輸出上限。
     *
     * <p>OpenAI SDK 預設不帶這個欄位；直接拒絕的話「改 base URL 就能接」不成立，
     * 放行又等於無上限生成。套一個預設值兩者都顧到，而且成本預扣仍有依據。
     */
    private int defaultMaxTokens = 1_024;

    public int getDefaultMaxTokens() {
        return defaultMaxTokens;
    }

    public void setDefaultMaxTokens(int defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public Hedge getHedge() {
        return hedge;
    }

    public void setHedge(Hedge hedge) {
        this.hedge = hedge;
    }

    public Duration getAttemptTimeout() {
        return attemptTimeout;
    }

    public void setAttemptTimeout(Duration attemptTimeout) {
        this.attemptTimeout = attemptTimeout;
    }

    /**
     * 對外 API 的身分驗證。
     *
     * <p>{@code apiKeys} 只接受環境變數佔位，格式為「租戶=key」，多筆以逗號分隔。
     */
    public static class Auth {
        private boolean enabled = true;
        private String apiKeys = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKeys() { return apiKeys; }
        public void setApiKeys(String apiKeys) { this.apiKeys = apiKeys; }
    }

    public static class ProviderConfig {
        private String name;
        private String baseUrl;
        private String apiKey;
        private String model;
        /**
         * 對外服務的模型名稱。同一個名稱底下的 Provider 必須是同一個模型的等價部署，
         * 對沖與失敗切換只在它們之間進行。空的話只服務 {@code model} 本身。
         */
        private List<String> servedModels = new ArrayList<>();
        /** 串流開始後兩個 chunk 之間最長可以等多久；0 表示不限 */
        private Duration streamIdleTimeout = Duration.ofSeconds(30);
        private int contextWindowTokens = 128_000;
        private int maxOutputTokens = 4_096;
        private boolean supportsStreaming = true;
        private boolean supportsTools = false;
        private boolean supportsVision = false;
        /**
         * 串流時要求上游回報用量（{@code stream_options.include_usage}）。OpenAI 預設不回，
         * 不要的話每一筆都只能靠本地估算。只有上游不認得這個參數時才關掉
         */
        private boolean streamUsage = true;
        private long inputMicroCentsPerToken = 50;
        private long outputMicroCentsPerToken = 150;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(60);

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(int value) { this.contextWindowTokens = value; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int value) { this.maxOutputTokens = value; }
        public boolean isSupportsStreaming() { return supportsStreaming; }
        public void setSupportsStreaming(boolean value) { this.supportsStreaming = value; }
        public boolean isSupportsTools() { return supportsTools; }
        public void setSupportsTools(boolean value) { this.supportsTools = value; }
        public long getInputMicroCentsPerToken() { return inputMicroCentsPerToken; }
        public void setInputMicroCentsPerToken(long value) { this.inputMicroCentsPerToken = value; }
        public long getOutputMicroCentsPerToken() { return outputMicroCentsPerToken; }
        public void setOutputMicroCentsPerToken(long value) { this.outputMicroCentsPerToken = value; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
        public boolean isSupportsVision() { return supportsVision; }
        public void setSupportsVision(boolean value) { this.supportsVision = value; }
        public boolean isStreamUsage() { return streamUsage; }
        public void setStreamUsage(boolean value) { this.streamUsage = value; }
        public List<String> getServedModels() { return servedModels; }
        public void setServedModels(List<String> value) { this.servedModels = value; }
        public Duration getStreamIdleTimeout() { return streamIdleTimeout; }
        public void setStreamIdleTimeout(Duration value) { this.streamIdleTimeout = value; }
    }

    public static class Routing {
        /** latency-aware 或 cost-aware */
        private String strategy = "latency-aware";
        /** 尚無統計樣本時假設的 TTFT，用於排序新加入的 Provider */
        private Duration unknownProviderAssumption = Duration.ofMillis(500);

        /**
         * 降級中的 Provider 多久放行一次半開探測。
         *
         * <p>太短會把流量倒回還沒好的上游，太長則讓短暫故障拖成長時間降級。
         * 30 秒是個折衷：一次探測失敗的代價只是一次失敗切換的延遲。
         */
        private Duration probeInterval = Duration.ofSeconds(30);
        /** 失敗率超過這個比例才算降級、才需要探測 */
        private double probeFailureThreshold = 0.5;

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public Duration getUnknownProviderAssumption() { return unknownProviderAssumption; }
        public void setUnknownProviderAssumption(Duration value) { this.unknownProviderAssumption = value; }
        public Duration getProbeInterval() { return probeInterval; }
        public void setProbeInterval(Duration probeInterval) { this.probeInterval = probeInterval; }
        public double getProbeFailureThreshold() { return probeFailureThreshold; }
        public void setProbeFailureThreshold(double v) { this.probeFailureThreshold = v; }
    }

    public static class Hedge {
        /**
         * none / deferred / immediate。
         * <p>預設是 deferred：immediate 會讓每個請求的成本乘上候選數，
         * 只應該用在極少數對延遲極度敏感、且有成本上限保護的端點。
         */
        private String policy = "deferred";
        private int maxAttempts = 2;
        /** 統計樣本不足時的對沖延遲 */
        private Duration fallbackDelay = Duration.ofMillis(800);

        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getFallbackDelay() { return fallbackDelay; }
        public void setFallbackDelay(Duration fallbackDelay) { this.fallbackDelay = fallbackDelay; }
    }

    /**
     * 每日成本上限。
     *
     * <p>金額用美元設定（人看的），內部一律換算成整數微美分（對帳用的）。
     * 設成 0 表示不限制——而 {@code enabled} 預設為 true 且上限有預設值，
     * 是刻意的：「沒有上限」應該要是一個明確的決定，不該是忘了設定的結果。
     */
    public static class Budget {
        private boolean enabled = true;
        /**
         * memory 或 redis。
         *
         * <p>memory 是**單節點**的帳：多節點部署時實際總上限會是節點數的倍數。
         * 正式環境用 redis，否則設定檔上的 $50 在三個節點上就是 $150。
         */
        private String backend = "memory";
        private String redisUri = "redis://localhost:6380";

        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }
        public String getRedisUri() { return redisUri; }
        public void setRedisUri(String redisUri) { this.redisUri = redisUri; }

        /** 全域每日上限（美元）。0 表示不限 */
        private double dailyLimitUsd = 50;
        /** 單一租戶的每日上限（美元）。0 表示不限 */
        private double perTenantDailyLimitUsd = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getDailyLimitUsd() { return dailyLimitUsd; }
        public void setDailyLimitUsd(double dailyLimitUsd) { this.dailyLimitUsd = dailyLimitUsd; }
        public double getPerTenantDailyLimitUsd() { return perTenantDailyLimitUsd; }
        public void setPerTenantDailyLimitUsd(double v) { this.perTenantDailyLimitUsd = v; }

        /** 美元 → 微美分。整數運算，金額不用浮點數 */
        public long dailyLimitMicroCents() { return Math.round(dailyLimitUsd * 100 * 1_000_000L); }
        public long perTenantLimitMicroCents() { return Math.round(perTenantDailyLimitUsd * 100 * 1_000_000L); }
    }

    /**
     * 檢索增強。
     *
     * <p>預設關閉：沒有索引的 RAG 只會讓每個請求多付一次嵌入費用然後檢索到空結果。
     * 要開之前先用 {@code tools/rag-index.js} 建索引。
     */
    public static class Rag {
        private boolean enabled = false;
        private String collection = "docs";
        /**
         * 除了 {@code collection} 以外，還允許呼叫端以 {@code x_rag.collection} 指定的 collection。
         * 每一個都會在啟動時檢查嵌入一致性；不在清單內的一律回 400。
         */
        private List<String> extraCollections = new ArrayList<>();
        /** 索引與查詢必須是同一個模型。啟動時會拿它跟 collection 實際記錄的比對 */
        private String embeddingModel = "fake-embed-v1";
        private int embeddingDimension = 32;
        private String embeddingBaseUrl = "http://localhost:9101";
        private String embeddingApiKey = "";
        private long embeddingInputMicroCentsPerToken = 50;
        private String vectorStoreUrl = "http://localhost:6333";
        private String vectorStoreApiKey = "";
        /**
         * 啟動時發現嵌入模型或維度不符就拒絕啟動。
         *
         * <p>**預設 true。** 不符卻照樣啟動的話，檢索會照常回傳最近鄰、只是全部不相關——
         * 系統不報錯，只是變笨，而「RAG 效果不好」可以歸咎於一百種原因。
         */
        private boolean failOnMismatch = true;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public List<String> getExtraCollections() { return extraCollections; }
        public void setExtraCollections(List<String> v) { this.extraCollections = v; }

        /** 可以被檢索的所有 collection：預設的那一個加上 extraCollections */
        public Set<String> allowedCollections() {
            Set<String> allowed = new LinkedHashSet<>();
            allowed.add(collection);
            allowed.addAll(extraCollections);
            return allowed;
        }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String v) { this.embeddingModel = v; }
        public int getEmbeddingDimension() { return embeddingDimension; }
        public void setEmbeddingDimension(int v) { this.embeddingDimension = v; }
        public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
        public void setEmbeddingBaseUrl(String v) { this.embeddingBaseUrl = v; }
        public String getEmbeddingApiKey() { return embeddingApiKey; }
        public void setEmbeddingApiKey(String v) { this.embeddingApiKey = v; }
        public long getEmbeddingInputMicroCentsPerToken() { return embeddingInputMicroCentsPerToken; }
        public void setEmbeddingInputMicroCentsPerToken(long v) { this.embeddingInputMicroCentsPerToken = v; }
        public String getVectorStoreUrl() { return vectorStoreUrl; }
        public void setVectorStoreUrl(String v) { this.vectorStoreUrl = v; }
        public String getVectorStoreApiKey() { return vectorStoreApiKey; }
        public void setVectorStoreApiKey(String v) { this.vectorStoreApiKey = v; }
        public boolean isFailOnMismatch() { return failOnMismatch; }
        public void setFailOnMismatch(boolean v) { this.failOnMismatch = v; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration v) { this.requestTimeout = v; }
    }
}
