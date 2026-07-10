package com.stocksage.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestAdminEvalController {

    @PostMapping("/api/eval/rag")
    Map<String, Object> eval() {
        return Map.of("status", "ok");
    }
}
