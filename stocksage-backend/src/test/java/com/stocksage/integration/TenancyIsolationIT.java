package com.stocksage.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenancyIsolationIT extends AuthIntegrationTestBase {

    @Test
    void conversationsAndReportsAreIsolatedByAuthenticatedUser() {
        LoginSession userA = registerAndLogin("tenant-a@example.com");
        LoginSession userB = registerAndLogin("tenant-b@example.com");

        ResponseEntity<Map<String, Object>> conversation = post("/api/test/conversations", Map.of(), userA);
        ResponseEntity<Map<String, Object>> report = post("/api/test/reports", Map.of(), userA);

        Long conversationId = ((Number) conversation.getBody().get("id")).longValue();
        Long reportId = ((Number) report.getBody().get("id")).longValue();

        assertThat(get("/api/test/conversations/" + conversationId, userA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/test/conversations/" + conversationId, userB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/test/reports/" + reportId, userA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/test/reports/" + reportId, userB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
