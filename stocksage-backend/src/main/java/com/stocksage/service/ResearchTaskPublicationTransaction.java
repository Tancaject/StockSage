package com.stocksage.service;

import com.stocksage.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Atomic publication boundary for a research-task result and its owner-fenced terminal CAS.
 *
 * <p>Callers keep orchestration decisions outside this class and submit only the database work
 * that must commit together. Any runtime failure, including an owner-CAS rejection, rolls the
 * whole publication back.</p>
 */
@Service
public class ResearchTaskPublicationTransaction {

    private final UserAccountRepository userAccountRepository;

    public ResearchTaskPublicationTransaction(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public <T> T execute(Supplier<T> publication) {
        return Objects.requireNonNull(publication, "publication").get();
    }

    @Transactional
    public void execute(Runnable publication) {
        Objects.requireNonNull(publication, "publication").run();
    }

    /**
     * Runs one user's report/message/task publication under a stable database mutex.
     *
     * <p>The user row is locked before the publication callback starts. This serializes only
     * publications for the same user, so report snapshot de-duplication and per-ticker version
     * allocation cannot race into a unique-key violation that marks the enclosing transaction
     * rollback-only.</p>
     */
    @Transactional
    public <T> T executeForUser(String userId, Supplier<T> publication) {
        String normalizedUserId = requireUserId(userId);
        Supplier<T> requiredPublication = Objects.requireNonNull(publication, "publication");
        userAccountRepository.lockByUserIdForUpdate(normalizedUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "publication user does not exist: " + normalizedUserId));
        return requiredPublication.get();
    }

    @Transactional
    public void executeForUser(String userId, Runnable publication) {
        Runnable requiredPublication = Objects.requireNonNull(publication, "publication");
        executeForUser(userId, () -> {
            requiredPublication.run();
            return null;
        });
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("publication user id is required");
        }
        return userId.trim();
    }
}
