package io.aigateway.server;

import java.time.Clock;
import java.time.Duration;

import io.aigateway.core.BudgetLedger;
import io.aigateway.core.HedgePolicy;
import io.aigateway.core.HedgingChatClient;
import io.aigateway.core.InMemoryBudgetLedger;
import io.aigateway.core.ModelCapability;
import io.aigateway.core.ProviderRegistry;
import io.aigateway.core.RoutingStrategy;
import io.aigateway.providers.OpenAiCompatibleProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
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

    /**
     * 每日成本上限，最後一道防線。
     *
     * <p>關閉時**大聲說出來**：一個沒有成本上限的 LLM 網關，任何一個失控的迴圈、
     * 忘了設 maxTokens 的端點、或被誤設成無條件對沖的策略，都會一路燒到有人發現為止。
     * 這件事不該只存在於設定檔的某一行裡。
     */
    @Bean
    BudgetLedger budgetLedger(GatewayProperties properties) {
        GatewayProperties.Budget budget = properties.getBudget();
        if (!budget.isEnabled()) {
            log.warn("每日成本上限已停用：失控的請求不會被任何機制擋下來。"
                    + "只有在外部已有成本控制時才該這樣設定。");
            return BudgetLedger.disabled();
        }

        log.info("每日成本上限：全域 ${}，單一租戶 ${}",
                budget.getDailyLimitUsd(), budget.getPerTenantDailyLimitUsd());
        // UTC：多節點在不同時區時，用本地時區會讓歸零時刻不一致而出現額度空窗
        return new InMemoryBudgetLedger(
                budget.dailyLimitMicroCents(),
                budget.perTenantLimitMicroCents(),
                Clock.systemUTC());
    }

    /**
     * 把今日支出與上限輸出成指標。
     *
     * <p>**看不見的上限只有一半用處**：撞到上限那一刻服務就開始回 429，
     * 但真正需要的是在撞牆之前就看得到「今天已經花了七成」。
     * 沒有這個指標，成本上限就從一道防線退化成一個突然的故障。
     *
     * <p>指標放在 server 模組而不是 core：core 刻意不依賴 Spring 與 Micrometer。
     */
    @Bean
    MeterBinder budgetMetrics(BudgetLedger ledger) {
        return registry -> {
            Gauge.builder("gateway.budget.spent.microcents", ledger, value -> value.spentMicroCents(null))
                    .description("今日已花費（微美分，UTC 日）")
                    .register(registry);
            Gauge.builder("gateway.budget.limit.microcents", ledger, BudgetLedger::dailyLimitMicroCents)
                    .description("每日成本上限（微美分）；0 表示未設限")
                    .register(registry);
        };
    }

    @Bean(destroyMethod = "close")
    HedgingChatClient hedgingChatClient(ProviderRegistry registry,
                                        RoutingStrategy routing,
                                        HedgePolicy hedgePolicy,
                                        BudgetLedger budgetLedger,
                                        GatewayProperties properties) {
        return new HedgingChatClient(registry, routing, hedgePolicy,
                properties.getAttemptTimeout(), budgetLedger);
    }

    /** 讓設定裡的逾時可以寫成 3s／60s 這種可讀格式 */
    static Duration parse(String value) {
        return Duration.parse(value.startsWith("PT") ? value : "PT" + value);
    }
}
