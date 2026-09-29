package com.stocksage.controller;

import com.stocksage.config.AdminApiInterceptor;
import com.stocksage.config.SecurityConfig;
import com.stocksage.config.WebConfig;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.evolution.EvolutionReplayService;
import com.stocksage.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvolutionReplayControllerTest {
    private final EvolutionReplayService service = mock(EvolutionReplayService.class);

    @Test
    void actualWebAndSecurityConfigurationProtectTheProfileOnlyRoute() throws Exception {
        for (String scenario : new String[]{"evaluation", "ordinary", "disabled", "unconfigured"}) {
            try (var context = new AnnotationConfigWebApplicationContext()) {
                context.setServletContext(new MockServletContext());
                if (!scenario.equals("ordinary")) context.getEnvironment().setActiveProfiles("evolution-eval");
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                        "stocksage.admin.enabled=" + !scenario.equals("disabled"),
                        "stocksage.admin.token=" + (scenario.equals("unconfigured") ? "" : "test-token"));
                context.register(WebBoundary.class);
                context.refresh();
                MockMvc web = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                var replay = context.getBean(EvolutionReplayService.class);
                String body = "{\"caseId\":\"case-one\",\"bundleId\":\"baseline-v1\",\"runId\":\"run-one\"}";
                String route = "/api/eval/evolution/replay";
                if (scenario.equals("evaluation")) {
                    for (String token : new String[]{"", "wrong-token"}) {
                        web.perform(post(route).header("X-StockSage-Admin-Token", token)
                                        .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isForbidden());
                    }
                    verifyNoInteractions(replay);
                    web.perform(post(route).header("X-StockSage-Admin-Token", "test-token")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body.replace("}", ",\"method\":\"untrusted\"}")))
                            .andExpect(status().isBadRequest());
                    verifyNoInteractions(replay);
                    web.perform(post(route).header("X-StockSage-Admin-Token", "test-token")
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andExpect(status().isOk());
                    verify(replay).replay(new EvolutionReplayService.Request("case-one", "baseline-v1", "run-one"));
                } else if (scenario.equals("ordinary")) {
                    assertThat(context.getBeansOfType(EvolutionReplayController.class)).isEmpty();
                    var mappings = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
                    assertThat(mappings.getHandlerMethods().values())
                            .noneMatch(handler -> handler.getBeanType().equals(EvolutionReplayController.class));
                    verifyNoInteractions(replay);
                } else {
                    web.perform(post(route).header("X-StockSage-Admin-Token", "test-token")
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andExpect(status().isServiceUnavailable());
                    verifyNoInteractions(replay);
                }
            }
        }
    }

    // Exercise production filter/interceptor registration; only model execution and account storage are substituted.
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({WebConfig.class, SecurityConfig.class, AdminApiInterceptor.class,
            EvolutionReplayController.class, GlobalExceptionHandler.class})
    static class WebBoundary {
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
        @Bean UserAccountRepository userAccountRepository() { return mock(UserAccountRepository.class); }
        @Bean EvolutionReplayService replayService() { return mock(EvolutionReplayService.class); }
        @Bean AsyncTaskExecutor applicationTaskExecutor() { return new TaskExecutorAdapter(Runnable::run); }
        @Bean org.springframework.boot.autoconfigure.web.servlet.DispatcherServletPath dispatcherServletPath() {
            return () -> "/";
        }
    }

    @Test
    void endpointIsAbsentUnlessTheEvaluationProfileIsActive() {
        try (var ordinary = new AnnotationConfigApplicationContext()) {
            ordinary.register(EvolutionReplayController.class, EvolutionReplayService.class);
            ordinary.refresh();
            assertThat(ordinary.getBeansOfType(EvolutionReplayController.class)).isEmpty();
            assertThat(ordinary.getBeansOfType(EvolutionReplayService.class)).isEmpty();
        }
        try (var evaluation = new AnnotationConfigApplicationContext()) {
            evaluation.getEnvironment().setActiveProfiles("evolution-eval");
            evaluation.registerBean(EvolutionReplayService.class, () -> service);
            evaluation.register(EvolutionReplayController.class);
            evaluation.refresh();
            assertThat(evaluation.getBeansOfType(EvolutionReplayController.class)).hasSize(1);
        }
    }

    @Test
    void adminAuthenticationRunsBeforeTheService() throws Exception {
        mvc().perform(post("/api/eval/evolution/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-one\",\"bundleId\":\"baseline-v1\",\"runId\":\"run-one\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void onlyTheThreeRegisteredIdentifiersCanReachTheService() throws Exception {
        String valid = "{\"caseId\":\"case-one\",\"bundleId\":\"baseline-v1\",\"runId\":\"run-one\"}";
        mvc().perform(post("/api/eval/evolution/replay").header("X-StockSage-Admin-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());
        verify(service).replay(new EvolutionReplayService.Request("case-one", "baseline-v1", "run-one"));
        clearInvocations(service);
        for (String extra : new String[]{"path", "method", "model", "messages", "candidate"}) {
            mvc().perform(post("/api/eval/evolution/replay").header("X-StockSage-Admin-Token", "test-token")
                            .contentType(MediaType.APPLICATION_JSON).content(valid.replace("}", ",\"" + extra + "\":\"untrusted\"}")))
                    .andExpect(status().isBadRequest());
        }
        mvc().perform(post("/api/eval/evolution/replay").header("X-StockSage-Admin-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caseId\":{},\"bundleId\":\"baseline-v1\",\"runId\":\"run-one\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private MockMvc mvc() {
        AdminApiInterceptor guard = new AdminApiInterceptor();
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "headerName", "X-StockSage-Admin-Token");
        ReflectionTestUtils.setField(guard, "adminToken", "test-token");
        return MockMvcBuilders.standaloneSetup(new EvolutionReplayController(service))
                .addInterceptors(guard).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
