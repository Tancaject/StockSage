package com.stocksage.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIT extends AuthIntegrationTestBase {

    @Test
    void registerLoginMeLogoutFlowUsesSessionCookie() {
        LoginSession session = registerAndLogin("flow@example.com");

        ResponseEntity<Map<String, Object>> me = get("/api/auth/me", session);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("email", "flow@example.com");

        ResponseEntity<Map<String, Object>> logout = post("/api/auth/logout", Map.of(), session);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> afterLogout = rest.exchange(
                "/api/auth/me",
                HttpMethod.GET,
                org.springframework.http.HttpEntity.EMPTY,
                new org.springframework.core.ParameterizedTypeReference<>() {
                });
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
