package com.stocksage.config;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SecurityTestController {

    @GetMapping("/api/auth/me")
    String me() {
        return "me";
    }

    @PostMapping("/api/auth/register")
    String register() {
        return "register";
    }

    @GetMapping("/api/docs/search")
    String docs() {
        return "docs";
    }

    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName(),
                "token", token.getToken());
    }

    @PostMapping("/api/eval/rag")
    String eval() {
        return "eval";
    }
}
