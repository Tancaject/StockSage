package com.stocksage.repository;

import com.stocksage.model.entity.ResearchMemoryConflictGroup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:research-memory-conflict-group;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResearchMemoryConflictGroupRepositoryTest {

    @Autowired
    private ResearchMemoryConflictGroupRepository repository;

    @Test
    void ensureAndLockCreatesOneStableGroupForRepeatedCalls() {
        String conflictKey = "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM";
        LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 1, 1, 12, 0);

        ResearchMemoryConflictGroup first = repository.ensureAndLock(
                "u-a", conflictKey, firstCreatedAt);
        ResearchMemoryConflictGroup second = repository.ensureAndLock(
                "u-a", conflictKey, firstCreatedAt.plusDays(1));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isOne();
        assertThat(second.getUserId()).isEqualTo("u-a");
        assertThat(second.getConflictKey()).isEqualTo(conflictKey);
        assertThat(second.getWinnerEntryId()).isNull();
        assertThat(second.getResolutionStatus())
                .isEqualTo(ResearchMemoryConflictGroup.ResolutionStatus.UNRESOLVED);
        assertThat(second.getCreatedAt()).isEqualTo(firstCreatedAt);
    }
}
