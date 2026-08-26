package io.aigateway.server;

import java.time.Duration;

import io.aigateway.core.HedgePolicy;
import io.aigateway.core.HedgingChatClient;
import io.aigateway.core.ModelCapability;
import io.aigateway.core.ProviderRegistry;
import io.aigateway.core.RoutingStrategy;
import io.aigateway.providers.OpenAiCompatibleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    @Bean
    ProviderRegistry providerRegistry(GatewayProperties properties) {
        ProviderRegistry registry = new ProviderRegistry();

        for (GatewayProperties.ProviderConfig config : properties.getProviders()) {
            registry.register(new OpenAiCompatibleProvider(
                    config.getName(),
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    new ModelCapability(
                            config.getContextWindowTokens(),
                            config.getMaxOutputTokens(),
                            config.isSupportsStreaming(),
                            config.isSupportsTools(),
                            config.getInputMicroCentsPerToken(),
                            config.getOutputMicroCentsPerToken()),
                    config.getConnectTimeout(),
                    config.getRequestTimeout()));
            // 只記名稱與位址，不記金鑰
            log.info("已註冊 Provider {} → {}（model={}）",
                    config.getName(), config.getBaseUrl(), config.getModel());
        }

        if (registry.all().isEmpty()) {
            // 沒有 Provider 的網關無法服務任何請求，讓它安靜地起來只會在第一個請求時才爆
            throw new IllegalStateException("未設定任何 Provider：請檢查 gateway.providers");
        }
        return registry;
    }

    @Bean
    RoutingStrategy routingStrategy(GatewayProperties properties) {
        String strategy = properties.getRouting().getStrategy();
        return switch (strategy) {
            case "latency-aware" -> RoutingStrategy.latencyAware(
                    properties.getRouting().getUnknownProviderAssumption());
            case "cost-aware" -> RoutingStrategy.costAware();
            default -> throw new IllegalArgumentException("未知的路由策略：" + strategy);
        };
    }

    @Bean
    HedgePolicy hedgePolicy(GatewayProperties properties) {
        GatewayProperties.Hedge hedge = properties.getHedge();
        return switch (hedge.getPolicy()) {
            case "none" -> HedgePolicy.none();
            case "deferred" -> HedgePolicy.deferred(hedge.getMaxAttempts(), hedge.getFallbackDelay());
            case "immediate" -> {
                // 這個設定會讓每個請求的成本乘上候選數，值得在啟動時就大聲提醒一次
                log.warn("對沖策略為 immediate：每個請求都會同時打 {} 家 Provider，"
                        + "成本約為單一 Provider 的 {} 倍。請確認已設定成本上限。",
                        hedge.getMaxAttempts(), hedge.getMaxAttempts());
                yield HedgePolicy.immediate(hedge.getMaxAttempts());
            }
            default -> throw new IllegalArgumentException("未知的對沖策略：" + hedge.getPolicy());
        };
    }

    @Bean(destroyMethod = "close")
    HedgingChatClient hedgingChatClient(ProviderRegistry registry,
                                        RoutingStrategy routing,
                                        HedgePolicy hedgePolicy,
                                        GatewayProperties properties) {
        return new HedgingChatClient(registry, routing, hedgePolicy, properties.getAttemptTimeout());
    }

    /** 讓設定裡的逾時可以寫成 3s／60s 這種可讀格式 */
    static Duration parse(String value) {
        return Duration.parse(value.startsWith("PT") ? value : "PT" + value);
    }
}
