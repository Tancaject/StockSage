package com.stocksage.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import static org.assertj.core.api.Assertions.assertThat;

class SpaCsrfTokenRequestHandlerTest {

    private final SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();

    @Test
    void resolvesRawHeaderTokenFromSpaCookieClient() {
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", "raw-token");

        assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo("raw-token");
    }

    @Test
    void resolvesMaskedHeaderTokenFromSpringCsrfEndpoint() {
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");
        MockHttpServletRequest maskingRequest = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler()
                .handle(maskingRequest, new MockHttpServletResponse(), () -> token);
        CsrfToken maskedToken = (CsrfToken) maskingRequest.getAttribute("_csrf");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", maskedToken.getToken());

        assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo("raw-token");
    }

    @Test
    void handleLoadsDeferredTokenSoRepositoryCanRenderCookie() {
        AtomicBoolean loaded = new AtomicBoolean(false);
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");

        handler.handle(new MockHttpServletRequest(), new MockHttpServletResponse(), () -> {
            loaded.set(true);
            return token;
        });

        assertThat(loaded).isTrue();
    }
}
