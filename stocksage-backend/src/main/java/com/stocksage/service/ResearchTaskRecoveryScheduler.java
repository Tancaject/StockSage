package com.stocksage.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchTaskRecoveryScheduler {

    private final ResearchTaskService researchTaskService;

    @Value("${stocksage.research-task.stale-after-minutes:15}")
    private long staleAfterMinutes;

    @Value("${stocksage.research-task.max-attempts:3}")
    private int maxAttempts;

    @Scheduled(
            cron = "${stocksage.research-task.recovery-cron:0 */5 * * * *}",
            zone = "${stocksage.research-task.scheduler-zone:Asia/Shanghai}"
    )
    public void recoverStaleResearchTasks() {
        ResearchTaskService.RecoveryResult result = researchTaskService.recoverStaleRunningTasks(
                Duration.ofMinutes(Math.max(1, staleAfterMinutes)),
                maxAttempts
        );
        if (result.retried() > 0 || result.failed() > 0) {
            log.info("Research task recovery finished, retried={}, failed={}",
                    result.retried(), result.failed());
        }
    }
}
