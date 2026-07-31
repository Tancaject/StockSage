package com.stocksage.repository;

import com.stocksage.model.entity.ResearchMemoryEntry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ResearchMemoryEntryRepositoryTest {

    @Autowired
    private ResearchMemoryEntryRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void vectorStateCasUpdatesActiveEntryButCannotReviveRevokedEntry() {
        ResearchMemoryEntry active = repository.saveAndFlush(entry("active"));
        ResearchMemoryEntry revoked = repository.saveAndFlush(entry("revoked"));
        revoked.setRevokedAt(LocalDateTime.now());
        revoked.setVectorStatus(ResearchMemoryEntry.VectorStatus.REVOKED);
        repository.saveAndFlush(revoked);
        entityManager.clear();

        int activeUpdated = repository.updateVectorStateIfIndexable(
                active.getId(),
                List.of(ResearchMemoryEntry.VectorStatus.PENDING,
                        ResearchMemoryEntry.VectorStatus.FAILED),
                ResearchMemoryEntry.VectorStatus.INDEXED,
                null,
                LocalDateTime.now()
        );
        int revokedRejected = repository.updateVectorStateIfIndexable(
                revoked.getId(),
                List.of(ResearchMemoryEntry.VectorStatus.PENDING,
                        ResearchMemoryEntry.VectorStatus.FAILED),
                ResearchMemoryEntry.VectorStatus.INDEXED,
                null,
                LocalDateTime.now()
        );
        entityManager.flush();
        entityManager.clear();

        assertThat(activeUpdated).isOne();
        assertThat(revokedRejected).isZero();
        assertThat(repository.findById(active.getId()).orElseThrow().getVectorStatus())
                .isEqualTo(ResearchMemoryEntry.VectorStatus.INDEXED);
        ResearchMemoryEntry stillRevoked = repository.findById(revoked.getId()).orElseThrow();
        assertThat(stillRevoked.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.REVOKED);
        assertThat(stillRevoked.getRevokedAt()).isNotNull();
    }

    @Test
    void retrievalTruthLookupReturnsOnlyActiveIndexedEntries() {
        ResearchMemoryEntry indexed = entry("indexed");
        indexed.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        indexed = repository.saveAndFlush(indexed);
        ResearchMemoryEntry pending = repository.saveAndFlush(entry("pending"));

        List<ResearchMemoryEntry> result =
                repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                        Set.of(indexed.getId(), pending.getId()),
                        "u-a",
                        ResearchMemoryEntry.VectorStatus.INDEXED
                );

        assertThat(result).extracting(ResearchMemoryEntry::getId)
                .containsExactly(indexed.getId());
    }

    private ResearchMemoryEntry entry(String sourceId) {
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setUserId("u-a");
        entry.setTicker("NVDA");
        entry.setSourceType("INVESTMENT_REPORT_VERSION");
        entry.setSourceId(sourceId);
        entry.setSourceCitations("[\"SEC\"]");
        entry.setSnapshotHash("a".repeat(64));
        entry.setContentHash("b".repeat(64));
        entry.setMemoryText("sourced memory");
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
        return entry;
    }
}
