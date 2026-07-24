package com.stocksage.controller;

import com.stocksage.config.RequestIdentity;
import com.stocksage.service.ResearchMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user/me/research-memory")
@RequiredArgsConstructor
public class ResearchMemoryController {

    private final ResearchMemoryService researchMemoryService;
    private final RequestIdentity requestIdentity;

    @GetMapping
    public List<ResearchMemoryService.MemoryView> list(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return researchMemoryService.listForUser(requestIdentity.currentUserId(), limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        return researchMemoryService.revoke(requestIdentity.currentUserId(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
