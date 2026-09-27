package io.aigateway.server;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 驗證 {@code Authorization: Bearer <key>}，把 key 對應的租戶放進請求屬性。
 *
 * <p>沒有這一層，連得到網關的任何人都能用公司的 Provider 金鑰呼叫模型，
 * 而且租戶是自己填的——成本歸戶與單租戶上限都可以被繞過。
 *
 * <p>停用驗證時（只該用在本機開發）改讀 {@code X-Tenant} 標頭，行為與過去相同。
 */
final class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 控制器從這個請求屬性取得租戶 */
    static final String TENANT_ATTRIBUTE = "gateway.tenant";
    private static final String BEARER = "bearer ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 為 null 代表驗證已停用 */
    private final ApiKeyAuthenticator authenticator;

    /** @param authenticator null 代表停用驗證，租戶改讀 X-Tenant 標頭 */
    ApiKeyAuthFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authenticator == null) {
            request.setAttribute(TENANT_ATTRIBUTE, request.getHeader("X-Tenant"));
            chain.doFilter(request, response);
            return;
        }

        Optional<String> tenant = authenticator.tenantFor(bearerToken(request));
        if (tenant.isEmpty()) {
            reject(response);
            return;
        }
        request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
        chain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER.length()) return null;
        if (!header.regionMatches(true, 0, BEARER, 0, BEARER.length())) return null;
        return header.substring(BEARER.length()).trim();
    }

    /** 沿用 OpenAI 的錯誤形狀，SDK 才解得出來；不區分「沒帶」與「帶錯」，避免被拿來試探 key */
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                "message", "缺少或無效的 API key（Authorization: Bearer <key>）",
                "type", "invalid_api_key")));
    }
}
