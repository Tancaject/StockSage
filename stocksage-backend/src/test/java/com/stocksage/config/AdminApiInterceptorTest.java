package com.stocksage.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApiInterceptorTest {

    @Test
    void rejectsMissingTokenWithForbiddenAndAcceptsConfiguredToken() throws Exception {
        AdminApiInterceptor interceptor = new AdminApiInterceptor();
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        ReflectionTestUtils.setField(interceptor, "headerName", "X-StockSage-Admin-Token");
        ReflectionTestUtils.setField(interceptor, "adminToken", "local-admin");

        MockHttpServletResponse denied = new MockHttpServletResponse();
        boolean missingAccepted = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/admin/agent/runtime"), denied, new Object());
        MockHttpServletRequest allowedRequest =
                new MockHttpServletRequest("GET", "/api/admin/agent/runtime");
        allowedRequest.addHeader("X-StockSage-Admin-Token", "local-admin");
        boolean validAccepted = interceptor.preHandle(
                allowedRequest, new MockHttpServletResponse(), new Object());

        assertThat(missingAccepted).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(validAccepted).isTrue();
    }
}
