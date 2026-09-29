package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.DebateContractParser.DebateContractException;
import com.stocksage.agent.DebateContractParser.ErrorCode;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebateContractParserTest {

    private static final String EVIDENCE_ID = "e-fundamentals-1";
    private static final String SNAPSHOT_CONTENT = """
            Revenue grew 10 percent year over year.
            Free cash flow improved while gross margin remained stable.
            The filing also identifies demand concentration as a material risk.
            """;

    private final DebateContractParser parser = new DebateContractParser(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesRoundOneThesesAndGeneratesStableOpaquePointIds() {
        AnalysisState state = stateWithUsableEvidence();

        DebateTurn first = parser.parse(openingContract(), 1, Side.BULL, state);
        DebateTurn repeated = parser.parse(openingContract(), 1, Side.BULL, state);

        assertThat(first.round()).isEqualTo(1);
        assertThat(first.side()).isEqualTo(Side.BULL);
        assertThat(first.points()).hasSize(3).allMatch(point -> point.type() == PointType.THESIS);
        List<String> firstPointIds = first.points().stream().map(point -> point.pointId()).toList();
        List<String> repeatedPointIds = repeated.points().stream().map(point -> point.pointId()).toList();
        assertThat(firstPointIds)
                .containsExactlyElementsOf(repeatedPointIds)
                .allMatch(pointId -> pointId.startsWith("pt-")
                        && !pointId.contains("BULL")
                        && !pointId.contains("BEAR"));
        assertThat(state.getDebateTurns()).isEmpty();
    }

    @Test
    void extractsStrictPointsJsonFromBoundedNaturalLanguageSummary() {
        AnalysisState state = stateWithUsableEvidence();
        String modelOutput = """
                核心结论：增长与现金流证据共同支持当前论点。
                ```json
                %s
                ```
                以上为本轮结构化论证。
                """.formatted(openingContract());

        DebateTurn turn = parser.parse(modelOutput, 1, Side.BULL, state);

        assertThat(turn.points()).hasSize(3);
    }

    @Test
    void acceptsNormalizedExcerptOnlyInsideItsOwnEvidenceSegment() {
        AnalysisState state = stateWithUsableEvidence();
        String normalizedWhitespace = openingContract()
                .replace("Revenue grew 10 percent", "Revenue   grew 10 percent");

        DebateTurn turn = parser.parse(normalizedWhitespace, 1, Side.BEAR, state);

        assertThat(turn.points()).allMatch(point ->
                point.evidenceRefs().stream().allMatch(ref -> EVIDENCE_ID.equals(ref.evidenceId())));
    }

    @Test
    void rejectsExcerptThatIsNotInReferencedSnapshotSegment() {
        AnalysisState state = stateWithUsableEvidence();
        String unsupported = openingContract()
                .replace("Revenue grew 10 percent", "Operating margin doubled");

        assertThatExceptionOfType(DebateContractException.class)
                .isThrownBy(() -> parser.parse(unsupported, 1, Side.BULL, state))
                .satisfies(error -> assertThat(error.code())
                        .isEqualTo(ErrorCode.EVIDENCE_EXCERPT_MISMATCH));
    }

    @Test
    void rejectsEvidenceIdThatIsNotUsableInCurrentLedger() {
        AnalysisState state = stateWithUsableEvidence();
        String unknownId = openingContract().replace(EVIDENCE_ID, "e-unknown");

        assertThatExceptionOfType(DebateContractException.class)
                .isThrownBy(() -> parser.parse(unknownId, 1, Side.BULL, state))
                .satisfies(error -> assertThat(error.code())
                        .isEqualTo(ErrorCode.EVIDENCE_ID_UNUSABLE));
    }

    @Test
    void roundTwoRebuttalsMustReferenceExistingOpponentPointsWithoutMutatingState() {
        AnalysisState state = stateWithUsableEvidence();
        DebateTurn bearOpening = parser.parse(openingContract(), 1, Side.BEAR, state);
        state.getDebateTurns().add(bearOpening);
        String opponentPointId = bearOpening.points().get(0).pointId();

        DebateTurn rebuttal = parser.parse(
                rebuttalContract(opponentPointId), 2, Side.BULL, state);

        assertThat(rebuttal.points()).hasSize(2)
                .allMatch(point -> point.type() == PointType.REBUTTAL)
                .allMatch(point -> point.respondsToPointIds().equals(List.of(opponentPointId)));
        assertThat(state.getDebateTurns()).containsExactly(bearOpening);
    }

    @Test
    void roundTwoCannotRespondToOwnPriorPoint() {
        AnalysisState state = stateWithUsableEvidence();
        DebateTurn bullOpening = parser.parse(openingContract(), 1, Side.BULL, state);
        state.getDebateTurns().add(bullOpening);

        assertThatExceptionOfType(DebateContractException.class)
                .isThrownBy(() -> parser.parse(
                        rebuttalContract(bullOpening.points().get(0).pointId()),
                        2,
                        Side.BULL,
                        state))
                .satisfies(error -> assertThat(error.code())
                        .isEqualTo(ErrorCode.RESPONSE_POINT_NOT_OPPONENT));
    }

    @Test
    void rejectsWrongRoundPointCountAndNonStrictHorizon() {
        AnalysisState state = stateWithUsableEvidence();
        String twoOpeningPoints = "{\"points\":[" + thesis("Growth remains durable")
                + "," + thesis("Cash generation supports reinvestment") + "]}";

        assertThatExceptionOfType(DebateContractException.class)
                .isThrownBy(() -> parser.parse(twoOpeningPoints, 1, Side.BULL, state))
                .satisfies(error -> assertThat(error.code())
                        .isEqualTo(ErrorCode.INVALID_POINT_COUNT));

        String invalidHorizon = openingContract().replace("MEDIUM_TERM", "medium_term");
        assertThatExceptionOfType(DebateContractException.class)
                .isThrownBy(() -> parser.parse(invalidHorizon, 1, Side.BULL, state))
                .satisfies(error -> assertThat(error.code())
                        .isEqualTo(ErrorCode.INVALID_HORIZON));
    }

    private AnalysisState stateWithUsableEvidence() {
        Instant observedAt = Instant.parse("2026-08-24T00:00:00Z");
        EvidenceEnvelope envelope = new EvidenceEnvelope(
                EVIDENCE_ID,
                EvidenceDimension.FUNDAMENTALS,
                "getFinancialReports",
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "sec:10-k:AAPL",
                "SEC",
                observedAt,
                observedAt,
                "payload-hash",
                true
        );
        return AnalysisState.builder()
                .query("Should I invest in AAPL?")
                .primaryTicker("AAPL")
                .fundamentalsReport(evidenceSegment(envelope, SNAPSHOT_CONTENT))
                .evidenceLedger(new EvidenceLedger(
                        TargetIdentity.resolved("AAPL"), List.of(envelope)))
                .build();
    }

    private String evidenceSegment(EvidenceEnvelope envelope, String content) {
        return """
                [[STOCKSAGE_EVIDENCE_BEGIN]]
                name: getFinancialReports(annual,5)
                evidenceId: %s
                status: AVAILABLE
                provider: SEC
                sourceRef: sec:10-k:AAPL
                asOf: 2026-08-24T00:00:00Z
                citable: true
                [[STOCKSAGE_CONTENT_BEGIN]]
                %s
                [[STOCKSAGE_CONTENT_END]]
                [[STOCKSAGE_EVIDENCE_END]]
                """.formatted(envelope.evidenceId(), content.strip());
    }

    private String openingContract() {
        return "{\"points\":["
                + thesis("Revenue growth supports the base case") + ","
                + thesis("Cash generation improves strategic flexibility") + ","
                + thesis("Stable margin limits near-term downside")
                + "]}";
    }

    @Test
    void rejectsTrivialExcerptEvenWhenItIsASnapshotSubstring() {
        AnalysisState state = stateWithUsableEvidence();
        String contract = openingContract().replace(
                "Revenue grew 10 percent",
                "Revenue"
        );

        assertThatThrownBy(() -> parser.parse(contract, 1, Side.BULL, state))
                .isInstanceOf(DebateContractParser.DebateContractException.class)
                .extracting(error -> ((DebateContractParser.DebateContractException) error).code())
                .isEqualTo(DebateContractParser.ErrorCode.EVIDENCE_EXCERPT_TOO_SHORT);
    }

    private String thesis(String claim) {
        return pointJson(
                "THESIS",
                claim,
                "Revenue grew 10 percent",
                "[]"
        );
    }

    private String rebuttalContract(String respondsToPointId) {
        String responses = "[\"" + respondsToPointId + "\"]";
        return "{\"points\":["
                + pointJson(
                "REBUTTAL",
                "The cited risk does not outweigh current cash generation",
                "Free cash flow improved",
                responses) + ","
                + pointJson(
                "REBUTTAL",
                "Stable margin weakens the downside scenario",
                "gross margin remained stable",
                responses)
                + "]}";
    }

    private String pointJson(String type, String claim, String excerpt, String responseIdsJson) {
        return """
                {
                  "type":"%s",
                  "claim":"%s",
                  "horizon":"MEDIUM_TERM",
                  "evidenceRefs":[{"evidenceId":"%s","excerpt":"%s"}],
                  "reasoning":"The cited operating evidence directly affects the stated investment case.",
                  "assumption":"The reported trend remains representative during the selected horizon.",
                  "invalidationCondition":"A later filing reverses the cited operating trend.",
                  "respondsToPointIds":%s
                }
                """.formatted(type, claim, EVIDENCE_ID, excerpt, responseIdsJson).strip();
    }
}
