package com.stocksage.repository;

import com.stocksage.model.dto.AnalysisHorizon;
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

    @Test
    void conflictCandidateLockOrdersByIdAndPersistsResolutionFields() {
        String conflictKey = "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM";
        ResearchMemoryEntry older = entry("older-report");
        older.setAnalysisHorizon(AnalysisHorizon.MEDIUM_TERM);
        older.setRecommendation("HOLD");
        older.setConflictKey(conflictKey);
        older = repository.saveAndFlush(older);

        ResearchMemoryEntry newer = entry("newer-report");
        newer.setAnalysisHorizon(AnalysisHorizon.MEDIUM_TERM);
        newer.setRecommendation("BUY");
        newer.setConflictKey(conflictKey);
        newer.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.CURRENT);
        newer = repository.saveAndFlush(newer);

        LocalDateTime supersededAt = LocalDateTime.of(2026, 2, 1, 12, 0);
        older.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.SUPERSEDED);
        older.setSupersededById(newer.getId());
        older.setSupersededAt(supersededAt);
        older.setResolutionReason("NEWEST_DATA_CUTOFF");
        repository.saveAndFlush(older);

        ResearchMemoryEntry unrelated = entry("different-horizon");
        unrelated.setAnalysisHorizon(AnalysisHorizon.LONG_TERM);
        unrelated.setRecommendation("HOLD");
        unrelated.setConflictKey("REPORT_RECOMMENDATION|NVDA|LONG_TERM");
        repository.saveAndFlush(unrelated);
        entityManager.clear();

        List<ResearchMemoryEntry> locked = repository.findConflictCandidatesForUpdate(
                "u-a", conflictKey);

        assertThat(locked).extracting(ResearchMemoryEntry::getId)
                .containsExactly(older.getId(), newer.getId());
        ResearchMemoryEntry persistedOlder = locked.get(0);
        assertThat(persistedOlder.getAnalysisHorizon()).isEqualTo(AnalysisHorizon.MEDIUM_TERM);
        assertThat(persistedOlder.getRecommendation()).isEqualTo("HOLD");
        assertThat(persistedOlder.getConflictKey()).isEqualTo(conflictKey);
        assertThat(persistedOlder.getResolutionStatus())
                .isEqualTo(ResearchMemoryEntry.ResolutionStatus.SUPERSEDED);
        assertThat(persistedOlder.getSupersededById()).isEqualTo(newer.getId());
        assertThat(persistedOlder.getSupersededAt()).isEqualTo(supersededAt);
        assertThat(persistedOlder.getResolutionReason()).isEqualTo("NEWEST_DATA_CUTOFF");
        assertThat(locked.get(1).getResolutionStatus())
                .isEqualTo(ResearchMemoryEntry.ResolutionStatus.CURRENT);
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
