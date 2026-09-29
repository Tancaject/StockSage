package com.stocksage.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void directAndWrappedCapacityExceptionsUse503AndTheExistingErrorContract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CapacityController()).setControllerAdvice(handler).build();
        for (String mode : java.util.List.of("direct", "wrapped")) {
            String body = mvc.perform(get("/capacity/" + mode))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value(true))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).contains("执行容量不足", "请稍后重试").doesNotContain("private wrapper");
        }
    }

    @RestController
    static class CapacityController {
        @GetMapping("/capacity/{mode}")
        String capacity(@PathVariable("mode") String mode) {
            var capacity = new ResearchCapacityExceededException("provider-admission", null);
            if (mode.equals("wrapped")) throw new IllegalStateException("private wrapper", capacity);
            throw capacity;
        }
    }

    @Test
    void mapsMissingResourcesToNotFoundInsteadOfInternalServerError() {
        var response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "api/workbench/stocks/search")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Resource not found");
    }

    @Test
    void mapsResourceNotFoundExceptionToNotFound() {
        var response = handler.handleResourceNotFound(
                new ResourceNotFoundException("Conversation not found")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Conversation not found");
    }
}
