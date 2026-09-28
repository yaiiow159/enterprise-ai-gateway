package io.aigateway.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 對外 API 的身分驗證。
 *
 * <p>預設開啟，且開啟時沒有設定任何 key 就**拒絕啟動**：一個沒有 key 的網關要嘛誰都進不來、
 * 要嘛誰都進得來，兩種都不該在沒人察覺的情況下發生。停用必須是明確的設定。
 */
@Configuration
public class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(GatewayProperties properties) {
        GatewayProperties.Auth auth = properties.getAuth();
        ApiKeyAuthenticator authenticator = null;

        if (auth.isEnabled()) {
            authenticator = ApiKeyAuthenticator.parse(auth.getApiKeys());
            if (authenticator.isEmpty()) {
                throw new IllegalStateException("API key 驗證已開啟，但沒有設定任何 key。"
                        + "以環境變數 GATEWAY_API_KEYS 設定（格式：租戶=key，多筆以逗號分隔）；"
                        + "僅限本機開發時可設 GATEWAY_AUTH_ENABLED=false 停用");
            }
            // 只記數量，不記任何 key
            log.info("API key 驗證已開啟：{} 把 key", authenticator.size());
        } else {
            log.warn("API key 驗證已停用：任何連得到網關的人都能呼叫模型，租戶改由 X-Tenant 標頭自報。"
                    + "只該用在本機開發");
        }

        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(authenticator));
        registration.addUrlPatterns("/v1/*");
        return registration;
    }
}
