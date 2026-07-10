package com.stocksage.integration;

import com.stocksage.config.RequestIdentity;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.InvestmentReportVersionRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
class TestOwnedResourceController {

    private final RequestIdentity requestIdentity;
    private final ConversationRepository conversationRepository;
    private final InvestmentReportVersionRepository reportRepository;

    @PostMapping("/conversations")
    Map<String, Object> createConversation() {
        Conversation conversation = new Conversation();
        conversation.setUserId(requestIdentity.currentUserId());
        conversation.setTitle("IT conversation");
        Conversation saved = conversationRepository.save(conversation);
        return Map.of("id", saved.getId(), "userId", saved.getUserId());
    }

    @GetMapping("/conversations/{id}")
    Map<String, Object> getConversation(@PathVariable Long id) {
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!requestIdentity.currentUserId().equals(conversation.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Map.of("id", conversation.getId(), "userId", conversation.getUserId());
    }

    @PostMapping("/reports")
    Map<String, Object> createReport() {
        InvestmentReportVersion report = new InvestmentReportVersion();
        report.setUserId(requestIdentity.currentUserId());
        report.setTicker("NVDA");
        report.setRecommendation("HOLD");
        report.setReportVersion(1);
        report.setDataSnapshotHash(requestIdentity.currentUserId() + "-snapshot");
        report.setContextHash(requestIdentity.currentUserId() + "-context");
        report.setReportJson("{\"ticker\":\"NVDA\"}");
        InvestmentReportVersion saved = reportRepository.save(report);
        return Map.of("id", saved.getId(), "userId", saved.getUserId());
    }

    @GetMapping("/reports/{id}")
    Map<String, Object> getReport(@PathVariable Long id) {
        InvestmentReportVersion report = reportRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!requestIdentity.currentUserId().equals(report.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Map.of("id", report.getId(), "userId", report.getUserId());
    }
}
