package com.stocksage.integration;

import java.net.HttpCookie;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(
        classes = AuthIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.session.store-type=redis",
                "stocksage.admin.token=local-admin"
        }
)
abstract class AuthIntegrationTestBase {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("stocksage_it")
            .withUsername("stocksage")
            .withPassword("stocksage");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        // All subclasses share one cached Spring context, so their backing containers must
        // have the same JVM-wide lifecycle instead of being restarted for every test class.
        MYSQL.start();
        REDIS.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    private Csrf csrf;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void fetchCsrfToken() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/auth/csrf",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                });
        csrf = new Csrf(
                String.valueOf(response.getBody().get("headerName")),
                String.valueOf(response.getBody().get("token")),
                cookies(response)
        );
    }

    protected LoginSession registerAndLogin(String email) {
        post("/api/auth/register", Map.of("email", email, "password", "demo1234"), csrf.cookies());
        ResponseEntity<Map<String, Object>> response = post(
                "/api/auth/login",
                Map.of("email", email, "password", "demo1234"),
                csrf.cookies());
        String sessionCookies = mergeCookies(csrf.cookies(), cookies(response));
        return new LoginSession(sessionCookies, csrf.headerName(), csrf.token());
    }

    protected ResponseEntity<Map<String, Object>> get(String path, LoginSession session) {
        return rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers(session.cookies(), null, null)),
                new ParameterizedTypeReference<>() {
                });
    }

    protected ResponseEntity<Map<String, Object>> post(String path, Object body, LoginSession session) {
        return post(path, body, session.cookies(), session.csrfHeaderName(), session.csrfToken());
    }

    protected ResponseEntity<Map<String, Object>> post(String path, Object body, String cookies) {
        return post(path, body, cookies, csrf.headerName(), csrf.token());
    }

    protected ResponseEntity<Map<String, Object>> postWithoutCsrf(String path, Object body, String cookies) {
        return post(path, body, cookies, null, null);
    }

    protected ResponseEntity<Map<String, Object>> postWithAdminToken(String path) {
        HttpHeaders headers = headers("", null, null);
        headers.set("X-StockSage-Admin-Token", "local-admin");
        return rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Map<String, Object>> post(
            String path,
            Object body,
            String cookies,
            String csrfHeaderName,
            String csrfToken
    ) {
        return rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers(cookies, csrfHeaderName, csrfToken)),
                new ParameterizedTypeReference<>() {
                });
    }

    private HttpHeaders headers(String cookies, String csrfHeaderName, String csrfToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookies != null && !cookies.isBlank()) {
            headers.set(HttpHeaders.COOKIE, cookies);
        }
        if (csrfHeaderName != null && csrfToken != null) {
            headers.set(csrfHeaderName, csrfToken);
        }
        return headers;
    }

    protected String cookies(ResponseEntity<?> response) {
        List<String> values = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }

    private String mergeCookies(String first, String second) {
        return Arrays.stream((first + "; " + second).split(";"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.joining("; "));
    }

    protected record LoginSession(String cookies, String csrfHeaderName, String csrfToken) {
    }

    private record Csrf(String headerName, String token, String cookies) {
    }
}
