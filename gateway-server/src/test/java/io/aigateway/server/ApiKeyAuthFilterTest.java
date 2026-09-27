package io.aigateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyAuthFilterTest {

    private static final String TEAM_A_KEY = "test-only-key-team-a-000000000";
    private static final String TEAM_A_ROTATED = "test-only-key-team-a-111111111";
    private static final String TEAM_B_KEY = "test-only-key-team-b-000000000";

    private final ApiKeyAuthenticator authenticator = ApiKeyAuthenticator.parse(
            "team-a=" + TEAM_A_KEY + ", team-a=" + TEAM_A_ROTATED + "\nteam-b=" + TEAM_B_KEY);

    @Test
    void validKeyResolvesItsOwnTenantAndIgnoresTheSelfDeclaredOne() throws Exception {
        MockHttpServletRequest request = chatRequest("Bearer " + TEAM_A_KEY);
        // 客戶端自報成別的團隊：不能採信，否則成本會記到別人頭上
        request.addHeader("X-Tenant", "team-b");
        MockFilterChain chain = new MockFilterChain();

        new ApiKeyAuthFilter(authenticator).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE)).isEqualTo("team-a");
    }

    @Test
    void rotatedKeysOfTheSameTenantBothWork() throws Exception {
        MockHttpServletRequest request = chatRequest("bearer " + TEAM_A_ROTATED);

        new ApiKeyAuthFilter(authenticator).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE)).isEqualTo("team-a");
    }

    @Test
    void missingOrWrongKeyIsRejectedBeforeReachingTheController() throws Exception {
        for (String header : new String[] { null, "Bearer wrong-key-000000000000000000", "Basic abc" }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            new ApiKeyAuthFilter(authenticator).doFilter(chatRequest(header), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("invalid_api_key");
            assertThat(chain.getRequest()).as("請求不該被放行：%s", header).isNull();
        }
    }

    @Test
    void disabledAuthenticationFallsBackToTheSelfDeclaredTenant() throws Exception {
        MockHttpServletRequest request = chatRequest(null);
        request.addHeader("X-Tenant", "local-dev");
        MockFilterChain chain = new MockFilterChain();

        new ApiKeyAuthFilter(null).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE)).isEqualTo("local-dev");
    }

    @Test
    void invalidKeyConfigurationIsRejectedWithoutEchoingTheKey() {
        String shortKey = "too-short";
        assertThatThrownBy(() -> ApiKeyAuthenticator.parse("team-a=" + shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> ApiKeyAuthenticator.parse("team a=" + TEAM_A_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyAuthenticator.parse("team-a=" + TEAM_A_KEY + ",team-b=" + TEAM_A_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TEAM_A_KEY);
        assertThatThrownBy(() -> ApiKeyAuthenticator.parse(TEAM_A_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TEAM_A_KEY);
    }

    @Test
    void enabledAuthenticationWithoutKeysRefusesToStart() {
        GatewayProperties properties = new GatewayProperties();
        properties.getAuth().setEnabled(true);
        properties.getAuth().setApiKeys("");

        assertThatThrownBy(() -> new AuthConfiguration().apiKeyAuthFilter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GATEWAY_API_KEYS");
    }

    private static MockHttpServletRequest chatRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }
}
