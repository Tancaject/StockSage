package com.stocksage.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfAndAdminIT extends AuthIntegrationTestBase {

    @Test
    void postWithoutCsrfIsRejected() {
        LoginSession session = registerAndLogin("csrf@example.com");

        var response = postWithoutCsrf("/api/test/conversations", Map.of(), session.cookies());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminTokenEndpointWorksWithoutSessionOrCsrf() {
        var response = postWithAdminToken("/api/eval/rag");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
