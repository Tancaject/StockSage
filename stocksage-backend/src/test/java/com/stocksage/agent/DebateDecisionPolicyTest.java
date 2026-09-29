package com.stocksage.agent;

import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceTiming;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.ArgumentAssessment;
import com.stocksage.model.dto.DebateModels.AssessmentParseStatus;
import com.stocksage.model.dto.DebateModels.AssessmentReasonCode;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.DebateVerdict;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.LeadingSide;
import com.stocksage.model.dto.DebateModels.ManagerAssessment;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.stocksage.model.dto.DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID;
import static com.stocksage.model.dto.DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebateDecisionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private final DebateDecisionPolicy policy = new DebateDecisionPolicy();

    @Test
    void computesStrongBullVerdictFromTopThreeAndTwoEvidenceDimensions() {
        AnalysisState state = freshBullState();
        ManagerAssessment assessment = assessment(
                state,
                List.of(
                        scored("B1", 4, "fundamentals-1"),
                        scored("B2", 4, "news-1"),
                        scored("B3", 4, "fundamentals-1"),
                        scored("S1", 1, "market-1"),
                        scored("S2", 1, "news-1"),
                        scored("S3", 1, "market-1")
                )
        );

        DebateVerdict verdict = policy.decide(state, assessment);

        assertThat(verdict.leadingSide()).isEqualTo(LeadingSide.BULL);
        assertThat(verdict.recommendation()).isEqualTo("BUY");
        assertThat(verdict.bullScore()).isEqualTo(100.0);
        assertThat(verdict.bearScore()).isLessThan(40.0);
        assertThat(verdict.decisivePointIds()).containsExactly("B1", "B2", "B3");
        assertThat(verdict.analysisHorizon()).isEqualTo(AnalysisHorizon.MEDIUM_TERM);

        state.setTimeSensitivity(TimeSensitivity.RECENT);
        DebateVerdict recentVerdict = policy.decide(state, assessment(state, assessment.assessments()));
        assertThat(recentVerdict.recommendation()).isNotEqualTo("BUY");
        assertThat(recentVerdict.unresolvedPointIds()).contains("B1", "B3");
    }

    @Test
    void unknownOrStaleAcceptedCitationCapsTheWholeArgumentWithoutRemovingReferences() {
        for (boolean stale : List.of(false, true)) {
            AnalysisState state = freshBullState();
            String constrainedId = stale ? "news-1" : "market-1";
            List<DebatePoint> bull = new ArrayList<>(state.getDebateTurns().get(0).points());
            DebatePoint point = bull.get(0);
            bull.set(0, new DebatePoint(point.pointId(), point.type(), point.claim(), point.horizon(),
                    List.of(new EvidenceRef("fundamentals-1", "fundamental facts"),
                            new EvidenceRef(constrainedId, "additional facts")),
                    point.reasoning(), point.assumption(), point.invalidationCondition(), point.respondsToPointIds()));
            state.setDebateTurns(List.of(new DebateTurn(1, Side.BULL, bull), state.getDebateTurns().get(1)));
            if (stale) {
                var items = new ArrayList<>(state.getEvidenceLedger().evidence());
                Instant oldPublication = NOW.minus(8, java.time.temporal.ChronoUnit.DAYS);
                items.set(2, withTiming(items.get(2), new EvidenceTiming(1, NOW, null, null,
                        new EvidenceTiming.Search("w", "w", 7, oldPublication, oldPublication, null, null, 1, 0, 0))));
                state.setEvidenceLedger(new EvidenceLedger(TargetIdentity.resolved("NVDA"), items));
            }
            var b1 = new ArgumentAssessment("B1", 4, 4, 4, 4, 4, List.of("fundamentals-1", constrainedId),
                    List.of(), List.of(AssessmentReasonCode.SUPPORTED), "Both facts support the claim");
            var manager = assessment(state, List.of(b1, scored("B2", 4, "news-1"),
                    scored("B3", 4, "fundamentals-1"), scored("S1", 1, "market-1"),
                    scored("S2", 1, "news-1"), scored("S3", 1, "market-1")));

            DebateVerdict verdict = policy.decide(state, manager);

            assertThat(verdict.recommendation()).isNotEqualTo("BUY");
            assertThat(verdict.unresolvedPointIds()).contains("B1");
            assertThat(verdict.bullScore()).isEqualTo(stale ? 93.33 : 97.08);
            assertThat(state.getEvidenceLedger().usableEvidenceIds()).contains(constrainedId);
            assertThat(state.getEvidenceLedger().evidence()).allMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
        }
    }

    @Test
    void recentLegacyAsOfDoesNotEstablishTypedFreshness() {
        AnalysisState state = completeState(false);
        var manager = assessment(state, List.of(scored("B1", 4, "fundamentals-1"), scored("B2", 4, "market-1"),
                scored("B3", 4, "fundamentals-1"), scored("S1", 1, "market-1"),
                scored("S2", 1, "news-1"), scored("S3", 1, "market-1")));
        DebateVerdict verdict = policy.decide(state, manager);
        assertThat(verdict.bullScore()).isEqualTo(91.25);
        assertThat(verdict.recommendation()).isNotEqualTo("BUY");
        assertThat(verdict.unresolvedPointIds()).contains("B1", "B2", "B3", "S1", "S2", "S3");
    }

    @Test
    void returnsBalancedHoldWhenScoresAreTooClose() {
        AnalysisState state = completeState(false);
        ManagerAssessment assessment = assessment(
                state,
                List.of(
                        scored("B1", 3, "fundamentals-1"),
                        scored("B2", 3, "market-1"),
                        scored("B3", 3, "fundamentals-1"),
                        scored("S1", 3, "market-1"),
                        scored("S2", 3, "news-1"),
                        scored("S3", 3, "market-1")
                )
        );

        DebateVerdict verdict = policy.decide(state, assessment);

        assertThat(verdict.leadingSide()).isEqualTo(LeadingSide.BALANCED);
        assertThat(verdict.recommendation()).isEqualTo("HOLD");
        assertThat(verdict.scoreMargin()).isLessThan(8.0);
        assertThat(verdict.decisivePointIds()).isEmpty();
        assertThat(verdict.unresolvedPointIds())
                .contains("B1", "B2", "B3", "S1", "S2", "S3");
    }

    @Test
    void returnsInsufficientWhenAcceptedEvidenceDoesNotBelongToPoint() {
        AnalysisState state = completeState(false);
        ManagerAssessment assessment = assessment(
                state,
                List.of(
                        scored("B1", 4, "fundamentals-1"),
                        scored("B2", 4, "news-1"),
                        scored("B3", 4, "fundamentals-1"),
                        scored("S1", 1, "market-1"),
                        scored("S2", 1, "news-1"),
                        scored("S3", 1, "market-1")
                )
        );

        DebateVerdict verdict = policy.decide(state, assessment);

        assertThat(verdict.leadingSide()).isEqualTo(LeadingSide.INSUFFICIENT);
        assertThat(verdict.recommendation()).isEqualTo("HOLD");
        assertThat(verdict.unresolvedPointIds()).contains("B2");
    }

    @Test
    void excludesDuplicateRootClaimsEvenWhenManagerScoresThemHighly() {
        AnalysisState state = completeState(true);
        ManagerAssessment assessment = assessment(
                state,
                List.of(
                        scored("B1", 4, "fundamentals-1"),
                        scored("B2", 4, "market-1"),
                        scored("B3", 4, "fundamentals-1"),
                        scored("S1", 1, "market-1"),
                        scored("S2", 1, "news-1"),
                        scored("S3", 1, "market-1")
                )
        );

        DebateVerdict verdict = policy.decide(state, assessment);

        assertThat(verdict.leadingSide()).isEqualTo(LeadingSide.INSUFFICIENT);
        assertThat(verdict.unresolvedPointIds()).contains("B1", "B2");
    }

    @Test
    void unknownBusinessAsOfReceivesLowFreshnessInsteadOfFullCredit() {
        AnalysisState state = completeState(false);
        state.setEvidenceLedger(new EvidenceLedger(
                TargetIdentity.resolved("NVDA"),
                List.of(
                        evidence("fundamentals-1", EvidenceDimension.FUNDAMENTALS, null),
                        evidence("market-1", EvidenceDimension.MARKET, null),
                        evidence("news-1", EvidenceDimension.NEWS, null)
                )
        ));
        ManagerAssessment assessment = assessment(
                state,
                List.of(
                        scored("B1", 4, "fundamentals-1"),
                        scored("B2", 4, "market-1"),
                        scored("B3", 4, "fundamentals-1"),
                        scored("S1", 1, "market-1"),
                        scored("S2", 1, "news-1"),
                        scored("S3", 1, "market-1")
                )
        );

        DebateVerdict verdict = policy.decide(state, assessment);

        // Semantic dimensions are perfect, but unknown asOf contributes 0.5/4 rather than 4/4.
        assertThat(verdict.bullScore()).isEqualTo(91.25);
        assertThat(verdict.bullScore()).isLessThan(100.0);
        assertThat(verdict.recommendation()).isNotEqualTo("BUY");
    }

    @Test
    void currentVerdictRequiresSameInputPolicySnapshotAndManagerAssessment() {
        AnalysisState state = completeState(false);
        ManagerAssessment assessment = assessment(
                state,
                List.of(
                        scored("B1", 4, "fundamentals-1"),
                        scored("B2", 4, "market-1"),
                        scored("B3", 4, "fundamentals-1"),
                        scored("S1", 1, "market-1"),
                        scored("S2", 1, "news-1"),
                        scored("S3", 1, "market-1")
                )
        );
        state.setManagerAssessment(assessment);
        DebateVerdict verdict = policy.decide(state);

        assertThat(policy.isCurrentVerdict(state, verdict)).isTrue();
        state.setTimeSensitivity(TimeSensitivity.REAL_TIME);
        assertThat(policy.isCurrentVerdict(state, verdict)).isFalse();
        state.setTimeSensitivity(TimeSensitivity.UNSPECIFIED);
        assertThat(policy.isCurrentVerdict(state, verdict)).isTrue();

        EvidenceLedger originalLedger = state.getEvidenceLedger();
        List<EvidenceEnvelope> changed = new ArrayList<>(originalLedger.evidence());
        EvidenceEnvelope item = changed.get(1);
        changed.set(1, new EvidenceEnvelope(item.evidenceId(), item.dimension(), item.capabilityId(), item.targetKey(),
                item.status(), item.sourceRef(), item.provider(), item.observedAt(), item.asOf(), item.payloadHash(),
                item.approvedReadOnly(), new EvidenceTiming(1, NOW,
                new EvidenceTiming.Market("US", "1d", "INSTANT", null, item.asOf(), true), null, null)));
        state.setEvidenceLedger(new EvidenceLedger(originalLedger.target(), changed));
        assertThat(policy.isCurrentVerdict(state, verdict)).isFalse();
        state.setEvidenceLedger(originalLedger);
        assertThat(policy.isCurrentVerdict(state, verdict)).isTrue();
        DebateVerdict tampered = new DebateVerdict(
                verdict.policyId(),
                verdict.version(),
                verdict.inputHash(),
                verdict.dataSnapshotHash(),
                verdict.bullScore(),
                verdict.bearScore(),
                verdict.leadingSide(),
                verdict.scoreMargin(),
                "SELL",
                verdict.analysisHorizon(),
                verdict.decisivePointIds(),
                verdict.unresolvedPointIds(),
                verdict.assessments()
        );
        assertThat(policy.isCurrentVerdict(state, tampered)).isFalse();

        state.setQuery("Changed question");
        assertThat(policy.isCurrentVerdict(state, verdict)).isFalse();
    }

    @Test
    void rejectsSemanticScoresOutsideZeroToFour() {
        assertThatThrownBy(() -> new ArgumentAssessment(
                "B1",
                5,
                4,
                4,
                4,
                4,
                List.of("fundamentals-1"),
                List.of(),
                List.of(AssessmentReasonCode.SUPPORTED),
                "out of range"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceSupport");
    }

    private ManagerAssessment assessment(
            AnalysisState state,
            List<ArgumentAssessment> assessments
    ) {
        String inputHash = policy.computeInputHash(state);
        int finalNibble = Character.digit(inputHash.charAt(inputHash.length() - 1), 16);
        return new ManagerAssessment(
                MANAGER_ASSESSMENT_CONTRACT_ID,
                MANAGER_ASSESSMENT_CONTRACT_VERSION,
                inputHash,
                finalNibble < 0 || finalNibble % 2 == 0,
                assessments,
                AssessmentParseStatus.VALID,
                List.of()
        );
    }

    private AnalysisState freshBullState() {
        AnalysisState state = completeState(false);
        var items = new ArrayList<>(state.getEvidenceLedger().evidence());
        items.set(0, withTiming(items.get(0), new EvidenceTiming(1, NOW, null,
                new EvidenceTiming.Financial("annual", java.time.LocalDate.parse("2026-06-30"),
                        java.time.LocalDate.parse("2026-07-25")), null)));
        items.set(1, withTiming(items.get(1), new EvidenceTiming(1, NOW,
                new EvidenceTiming.Market("US", "1d", "INSTANT", null, items.get(1).asOf(), false), null, null)));
        Instant publication = items.get(2).asOf();
        items.set(2, withTiming(items.get(2), new EvidenceTiming(1, NOW, null, null,
                new EvidenceTiming.Search("w", "w", 7, publication, publication, null, null, 1, 0, 0))));
        state.setEvidenceLedger(new EvidenceLedger(TargetIdentity.resolved("NVDA"), items));
        var bull = new ArrayList<>(state.getDebateTurns().get(0).points());
        bull.set(1, thesis("B2", "Dated news confirms execution", "news-1"));
        state.setDebateTurns(List.of(new DebateTurn(1, Side.BULL, bull), state.getDebateTurns().get(1)));
        return state;
    }

    private EvidenceEnvelope withTiming(EvidenceEnvelope item, EvidenceTiming timing) {
        return new EvidenceEnvelope(item.evidenceId(), item.dimension(), item.capabilityId(), item.targetKey(),
                item.status(), item.sourceRef(), item.provider(), item.observedAt(), item.asOf(), item.payloadHash(),
                item.approvedReadOnly(), timing);
    }

    private ArgumentAssessment scored(String pointId, int score, String evidenceId) {
        return new ArgumentAssessment(
                pointId,
                score,
                score,
                score,
                score,
                score,
                List.of(evidenceId),
                List.of(),
                List.of(score == 0
                        ? AssessmentReasonCode.PARTIALLY_SUPPORTED
                        : AssessmentReasonCode.SUPPORTED),
                "Bounded assessment for " + pointId
        );
    }

    private AnalysisState completeState(boolean duplicateBullClaim) {
        List<DebatePoint> bull = new ArrayList<>();
        bull.add(thesis("B1", "Durable demand supports growth", "fundamentals-1"));
        bull.add(thesis(
                "B2",
                duplicateBullClaim
                        ? "Durable demand supports growth"
                        : "Market trend confirms execution",
                "market-1"
        ));
        bull.add(thesis("B3", "Margins can remain resilient", "fundamentals-1"));

        List<DebatePoint> bear = List.of(
                thesis("S1", "Valuation leaves limited room", "market-1"),
                thesis("S2", "Near-term news introduces risk", "news-1"),
                thesis("S3", "Momentum can reverse", "market-1")
        );

        return AnalysisState.builder()
                .query("Should I invest in NVDA?")
                .primaryTicker("NVDA")
                .dataSnapshotHash("snapshot-1")
                .contextHash("context-1")
                .evidenceLedger(new EvidenceLedger(
                        TargetIdentity.resolved("NVDA"),
                        List.of(
                                evidence(
                                        "fundamentals-1",
                                        EvidenceDimension.FUNDAMENTALS,
                                        NOW.minus(30, java.time.temporal.ChronoUnit.DAYS)
                                ),
                                evidence(
                                        "market-1",
                                        EvidenceDimension.MARKET,
                                        NOW.minus(1, java.time.temporal.ChronoUnit.DAYS)
                                ),
                                evidence(
                                        "news-1",
                                        EvidenceDimension.NEWS,
                                        NOW.minus(2, java.time.temporal.ChronoUnit.DAYS)
                                )
                        )
                ))
                .debateTurns(List.of(
                        new DebateTurn(1, Side.BULL, bull),
                        new DebateTurn(1, Side.BEAR, bear)
                ))
                .build();
    }

    private DebatePoint thesis(String pointId, String claim, String evidenceId) {
        return new DebatePoint(
                pointId,
                PointType.THESIS,
                claim,
                AnalysisHorizon.MEDIUM_TERM,
                List.of(new EvidenceRef(evidenceId, "verbatim evidence for " + evidenceId)),
                "The evidence materially supports the claim.",
                "The observed relationship persists.",
                "New evidence invalidates the observed relationship.",
                List.of()
        );
    }

    private EvidenceEnvelope evidence(
            String evidenceId,
            EvidenceDimension dimension,
            Instant asOf
    ) {
        return new EvidenceEnvelope(
                evidenceId,
                dimension,
                "capability-" + evidenceId,
                "NVDA",
                EvidenceStatus.AVAILABLE,
                "source-" + evidenceId,
                "provider-" + evidenceId,
                NOW,
                asOf,
                "payload-" + evidenceId,
                true
        );
    }
}
