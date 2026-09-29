package com.stocksage.conversation;

import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(ConversationMessageService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationMessageServiceTransactionTest {
    private static final LocalDateTime ORIGINAL_UPDATED_AT = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

    @Autowired private ConversationMessageService service;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @Test void failedInsertRestoresTheReplacedBranchWithoutACallerTransaction() {
        Conversation conversation = conversation("failed-insert-owner");
        Long id = conversation.getId();
        Message user = message(id, "user", "question to preserve");
        Message answer = message(id, "assistant", "answer to preserve");

        // A real persistence rejection must roll back the earlier deletion through the service's own transaction.
        assertThatThrownBy(() -> service.appendUserTurn("failed-insert-owner", id, null, true))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).extracting(Message::getId)
                .containsExactly(user.getId(), answer.getId());
        assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(ORIGINAL_UPDATED_AT);
    }

    @Test void replacingLastTurnRollsBackDeletionNewUserAndConversationTouchTogether() {
        Conversation conversation = conversation("replace-owner");
        Long id = conversation.getId();
        List<Message> original = List.of(
                message(id, "user", "original first question"),
                message(id, "assistant", "original first answer"),
                message(id, "user", "original last question"),
                message(id, "assistant", "original last answer"));
        List<Long> originalIds = original.stream().map(Message::getId).toList();

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            var turn = service.appendUserTurn("replace-owner", id, "replacement question", true);
            assertThat(turn.replaced()).isTrue();
            assertThat(turn.remainingHistory()).extracting(Message::getId).containsExactlyElementsOf(originalIds.subList(0, 2));
            assertThat(turn.routingTurns()).containsExactly("user: original first question", "assistant: original first answer");
            entityManager.flush();
            entityManager.clear();
            assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).extracting(Message::getContent)
                    .containsExactly("original first question", "original first answer", "replacement question");
            assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isAfter(ORIGINAL_UPDATED_AT);
            throw new RollbackProbe();
        })).isInstanceOf(RollbackProbe.class);

        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).extracting(Message::getId)
                .containsExactlyElementsOf(originalIds);
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).extracting(Message::getContent)
                .containsExactly("original first question", "original first answer", "original last question", "original last answer");
        assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(ORIGINAL_UPDATED_AT);
    }

    @Test void committedAppendReturnsPersistedSourceAndOnlyPriorCompletedRoutingTurns() {
        Conversation conversation = conversation("append-owner");
        Long id = conversation.getId();
        message(id, "user", "previous question");
        message(id, "assistant", "previous answer");

        var turn = service.appendUserTurn("append-owner", id, "new current question", false);

        assertThat(turn.replaced()).isFalse();
        assertThat(turn.remainingHistory()).isEmpty();
        assertThat(turn.routingTurns()).containsExactly("user: previous question", "assistant: previous answer");
        assertThat(turn.sourceMessage().getId()).isPositive();
        assertThat(turn.sourceMessage().getCreatedAt()).isNotNull();
        Message persisted = messageRepository.findById(turn.sourceMessage().getId()).orElseThrow();
        assertThat(persisted.getConversationId()).isEqualTo(id);
        assertThat(persisted.getRole()).isEqualTo("user");
        assertThat(persisted.getContent()).isEqualTo("new current question");
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).extracting(Message::getContent)
                .containsExactly("previous question", "previous answer", "new current question");
        assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isAfter(ORIGINAL_UPDATED_AT);
    }

    @Test void deepReportPublicationJoinsTheCallerTransactionAndRollsBackItsTouch() {
        Conversation conversation = conversation("report-owner");
        Long id = conversation.getId();

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            service.persistAssistantReport(id, "report-owner", "final report must roll back", "trace-report-rollback");
            entityManager.flush();
            entityManager.clear();
            assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).singleElement().satisfies(message -> {
                assertThat(message.getRole()).isEqualTo("assistant");
                assertThat(message.getContent()).isEqualTo("final report must roll back");
                assertThat(message.getTraceId()).isEqualTo("trace-report-rollback");
                assertThat(message.getModelTier()).isEqualTo("STRONG");
            });
            assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isAfter(ORIGINAL_UPDATED_AT);
            throw new RollbackProbe();
        })).isInstanceOf(RollbackProbe.class);

        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)).isEmpty();
        assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(ORIGINAL_UPDATED_AT);
    }

    private Conversation conversation(String userId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setOrigin("chat");
        conversation.setTitle("Transaction boundary fixture");
        Conversation saved = conversationRepository.saveAndFlush(conversation);
        // Bulk fixture setup bypasses @PreUpdate, so a touch is detectable without waiting on the clock.
        transaction().executeWithoutResult(status -> entityManager.createQuery(
                        "update Conversation c set c.updatedAt = :updatedAt where c.id = :id")
                .setParameter("updatedAt", ORIGINAL_UPDATED_AT).setParameter("id", saved.getId()).executeUpdate());
        return conversationRepository.findById(saved.getId()).orElseThrow();
    }

    private Message message(Long conversationId, String role, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        return messageRepository.saveAndFlush(message);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static final class RollbackProbe extends RuntimeException { }
}
