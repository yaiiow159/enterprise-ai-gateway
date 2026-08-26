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

    private Budget budget = new Budget();

    /** 單一候選的整體逾時 */
    private Duration attemptTimeout = Duration.ofSeconds(60);

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

    /**
     * 每日成本上限。
     *
     * <p>金額用美元設定（人看的），內部一律換算成整數微美分（對帳用的）。
     * 設成 0 表示不限制——而 {@code enabled} 預設為 true 且上限有預設值，
     * 是刻意的：「沒有上限」應該要是一個明確的決定，不該是忘了設定的結果。
     */
    public static class Budget {
        private boolean enabled = true;
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
}
