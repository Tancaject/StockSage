package com.stocksage.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApiInterceptorTest {

    @Test
    void rejectsRequestsWhenAdminTokenIsNotConfigured() throws Exception {
        AdminApiInterceptor interceptor = interceptor(true, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/admin/agent/runtime"), response, new Object());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Admin token is not configured");
    }

    @Test
    void disablingAdminApiFailsClosedInsteadOfBypassingAuthentication() throws Exception {
        AdminApiInterceptor interceptor = interceptor(false, "test-only-admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/admin/agent/runtime"), response, new Object());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Admin API is disabled");
    }

    @Test
    void rejectsMissingTokenWithForbiddenAndAcceptsConfiguredToken() throws Exception {
        AdminApiInterceptor interceptor = interceptor(true, "test-only-admin-token");

        MockHttpServletResponse denied = new MockHttpServletResponse();
        boolean missingAccepted = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/admin/agent/runtime"), denied, new Object());
        MockHttpServletRequest allowedRequest =
                new MockHttpServletRequest("GET", "/api/admin/agent/runtime");
        allowedRequest.addHeader("X-StockSage-Admin-Token", "test-only-admin-token");
        boolean validAccepted = interceptor.preHandle(
                allowedRequest, new MockHttpServletResponse(), new Object());

        assertThat(missingAccepted).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(validAccepted).isTrue();
    }

    private AdminApiInterceptor interceptor(boolean enabled, String token) {
        AdminApiInterceptor interceptor = new AdminApiInterceptor();
        ReflectionTestUtils.setField(interceptor, "enabled", enabled);
        ReflectionTestUtils.setField(interceptor, "headerName", "X-StockSage-Admin-Token");
        ReflectionTestUtils.setField(interceptor, "adminToken", token);
        return interceptor;
    }
}
