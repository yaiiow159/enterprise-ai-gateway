package io.aigateway.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    /** 單一候選的整體逾時 */
    private Duration attemptTimeout = Duration.ofSeconds(60);

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

    public static class ProviderConfig {
        private String name;
        private String baseUrl;
        private String apiKey;
        private String model;
        private int contextWindowTokens = 128_000;
        private int maxOutputTokens = 4_096;
        private boolean supportsStreaming = true;
        private boolean supportsTools = false;
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
    }

    public static class Routing {
        /** latency-aware 或 cost-aware */
        private String strategy = "latency-aware";
        /** 尚無統計樣本時假設的 TTFT，用於排序新加入的 Provider */
        private Duration unknownProviderAssumption = Duration.ofMillis(500);

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public Duration getUnknownProviderAssumption() { return unknownProviderAssumption; }
        public void setUnknownProviderAssumption(Duration value) { this.unknownProviderAssumption = value; }
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
}
