package com.stocksage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.GlobalExceptionHandler;
import com.stocksage.model.entity.User;
import com.stocksage.security.AuthenticatedUser;
import com.stocksage.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthService authService;
    private AuthenticationManager authenticationManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        authenticationManager = mock(AuthenticationManager.class);
        mockMvc = buildMockMvc();
    }

    @Test
    void registerRejectsShortPasswordAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "new@example.com",
                                "password", "short"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("password")));
    }

    @Test
    void loginValidationDoesNotLeakAccountExistence() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "not-an-email",
                                "password", "whatever"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
    }

    @Test
    void loginRotatesExistingSessionIdAfterSuccessfulAuthentication() throws Exception {
        when(authService.normalizeEmail("user@example.com")).thenReturn("user@example.com");
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(authenticatedUser(), null, null));
        MockHttpSession existingSession = new MockHttpSession();
        String oldSessionId = existingSession.getId();

        mockMvc.perform(post("/api/auth/login")
                        .session(existingSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "password", "demo1234"
                        ))))
                .andExpect(status().isOk());

        assertThat(existingSession.getId()).isNotEqualTo(oldSessionId);
    }

    private MockMvc buildMockMvc() {
        AuthController controller = new AuthController(
                authService,
                authenticationManager,
                new HttpSessionSecurityContextRepository()
        );
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private AuthenticatedUser authenticatedUser() {
        User user = new User();
        user.setUserId("u_login_001");
        user.setEmail("user@example.com");
        user.setPasswordHash("hash");
        user.setEmailVerified(true);
        user.setNickname("User");
        return new AuthenticatedUser(user);
    }
}
