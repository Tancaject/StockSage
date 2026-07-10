package com.stocksage.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchTaskLeaseServiceTest {

    @Test
    void processFallbackLeaseAllowsOneHolderUntilTokenIsReleased() {
        ResearchTaskLeaseService service = new ResearchTaskLeaseService(Optional.empty(), 30_000);

        Optional<ResearchTaskLeaseService.Lease> first = service.tryAcquire("rt:nvda:v1");
        Optional<ResearchTaskLeaseService.Lease> second = service.tryAcquire("rt:nvda:v1");

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().backend()).isEqualTo(ResearchTaskLeaseService.Backend.PROCESS);
        assertThat(second).isEmpty();

        service.release(new ResearchTaskLeaseService.Lease(
                "rt:nvda:v1",
                "wrong-token",
                ResearchTaskLeaseService.Backend.PROCESS
        ));
        assertThat(service.tryAcquire("rt:nvda:v1")).isEmpty();

        service.release(first.orElseThrow());
        assertThat(service.tryAcquire("rt:nvda:v1")).isPresent();
    }

    @Test
    void renewProcessLeaseOnlySucceedsForCurrentHolder() {
        ResearchTaskLeaseService service = new ResearchTaskLeaseService(Optional.empty(), 30_000);
        ResearchTaskLeaseService.Lease lease = service.tryAcquire("rt:nvda:v1").orElseThrow();

        assertThat(service.renew(new ResearchTaskLeaseService.Lease(
                "rt:nvda:v1",
                "wrong-token",
                ResearchTaskLeaseService.Backend.PROCESS
        ))).isFalse();
        assertThat(service.renew(lease)).isTrue();
    }
}
