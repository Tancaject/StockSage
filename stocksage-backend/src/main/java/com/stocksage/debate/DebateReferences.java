package com.stocksage.debate;

import com.stocksage.debate.DebateModels.DebatePoint;
import com.stocksage.debate.DebateModels.DebateTurn;
import com.stocksage.debate.DebateModels.EvidenceRef;
import com.stocksage.debate.DebateModels.PointType;
import com.stocksage.debate.DebateModels.Side;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.research.deep.AnalysisState;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 辩论提示词里的论点短编号，以及按轮统计的证据引用。
 *
 * <p>pointId 是 67 位哈希，多轮之后光论点索引就会挤占历史预算。提示词改用由轮次和位置决定的短编号
 * （如 {@code BULL-2.1}；匿名评分用 {@code A-2.1}），解析时再映射回 pointId，存储和 Trace 仍是原 ID。</p>
 */
public final class DebateReferences {

    private DebateReferences() {
    }

    /** pointId → 带方向的短编号，供 Bull/Bear、续停和报告阶段使用。 */
    public static Map<String, String> sideAliases(List<DebateTurn> turns) {
        return aliases(turns, side -> side == Side.BULL ? "BULL" : "BEAR");
    }

    /** pointId → 匿名位置短编号（A/B），评分阶段不得泄露多空名称。 */
    public static Map<String, String> positionAliases(List<DebateTurn> turns, boolean positionAIsBull) {
        return aliases(turns, side -> (side == Side.BULL) == positionAIsBull ? "A" : "B");
    }

    /** 短编号（不区分大小写）→ pointId。 */
    public static Map<String, String> reverse(Map<String, String> pointIdToAlias) {
        Map<String, String> reversed = new LinkedHashMap<>();
        pointIdToAlias.forEach((pointId, alias) -> reversed.put(alias.toUpperCase(Locale.ROOT), pointId));
        return reversed;
    }

    /** 模型写的是短编号就换回 pointId；写的就是 pointId 时原样返回。 */
    public static String resolve(String value, Map<String, String> aliasToPointId) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return aliasToPointId.getOrDefault(stripped.toUpperCase(Locale.ROOT), stripped);
    }

    public static String alias(String pointId, Map<String, String> pointIdToAlias) {
        return pointIdToAlias.getOrDefault(pointId, pointId);
    }

    /** 早于指定轮次的发言里引用过的证据 ID。 */
    public static Set<String> citedBefore(List<DebateTurn> turns, int round) {
        Set<String> cited = new LinkedHashSet<>();
        for (DebateTurn turn : safe(turns)) {
            if (turn != null && turn.round() < round) {
                evidenceIds(turn).forEach(cited::add);
            }
        }
        return cited;
    }

    /** 指定轮次首次被任一方引用的证据 ID；这是“这一轮带来新材料”的确定性信号。 */
    public static Set<String> newEvidenceIds(List<DebateTurn> turns, int round) {
        Set<String> before = citedBefore(turns, round);
        Set<String> fresh = new LinkedHashSet<>();
        for (DebateTurn turn : safe(turns)) {
            if (turn != null && turn.round() == round) {
                evidenceIds(turn).filter(id -> !before.contains(id)).forEach(fresh::add);
            }
        }
        return fresh;
    }

    /** 全部轮次累计引用过的证据 ID。 */
    public static Set<String> citedEvidenceIds(List<DebateTurn> turns) {
        return citedBefore(turns, Integer.MAX_VALUE);
    }

    /** 可用但双方都还没引用过的证据，按账本顺序，最多 limit 条。 */
    public static List<EvidenceEnvelope> unusedUsableEvidence(AnalysisState state, int limit) {
        EvidenceLedger ledger = state == null ? null : state.getEvidenceLedger();
        if (ledger == null) {
            return List.of();
        }
        Set<String> usable = ledger.usableEvidenceIds();
        Set<String> cited = citedEvidenceIds(state.getDebateTurns());
        return ledger.evidence().stream()
                .filter(item -> item != null && usable.contains(item.evidenceId()) && !cited.contains(item.evidenceId()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public static int usableEvidenceCount(AnalysisState state) {
        EvidenceLedger ledger = state == null ? null : state.getEvidenceLedger();
        return ledger == null ? 0 : ledger.usableEvidenceIds().size();
    }

    /**
     * 争点状态。OPEN 表示本轮仍有交锋、对方下一轮可以回应；其余状态的交锋已经停下或在原地打转，不再是继续辩论的理由。
     */
    public enum CruxStatus {
        /** 本轮仍有交锋。 */
        OPEN,
        /** 连续两轮仍在往来，但都没有为该争点带来新证据。 */
        STALLED,
        /** 持方被反驳后没有回应。 */
        CONCEDED,
        /** 持方回应后对方没有再反驳。 */
        HELD,
        /** 双方在同一轮各自发言后都没有继续。 */
        DEADLOCK,
        /** 从未被反驳。 */
        UNCHALLENGED
    }

    /** 一个第一轮根论点及其反驳链的当前状态；moves 为链上的反驳 pointId，按轮次顺序。 */
    public record Crux(DebatePoint thesis, Side owner, CruxStatus status, int lastRound, List<String> moves) {
    }

    /**
     * 按 respondsTo 链计算每个根论点的争点状态，纯确定性统计。
     *
     * <p>双方每轮同时发言，所以第 r 轮的发言要到第 r+1 轮才可能被回应：只有交锋停在本轮的争点才是 OPEN。</p>
     */
    public static List<Crux> cruxes(List<DebateTurn> turns, int completedRound) {
        Map<String, DebatePoint> allPoints = new LinkedHashMap<>();
        Map<String, Integer> roundOf = new HashMap<>();
        Map<String, Side> sideOf = new HashMap<>();
        for (DebateTurn turn : safe(turns)) {
            if (turn == null) {
                continue;
            }
            for (DebatePoint point : turn.points()) {
                if (allPoints.putIfAbsent(point.pointId(), point) == null) {
                    roundOf.put(point.pointId(), turn.round());
                    sideOf.put(point.pointId(), turn.side());
                }
            }
        }
        List<Crux> cruxes = new ArrayList<>();
        for (DebateTurn turn : safe(turns)) {
            if (turn == null || turn.round() != 1) {
                continue;
            }
            for (DebatePoint thesis : turn.points()) {
                if (thesis.type() != PointType.THESIS) {
                    continue;
                }
                List<String> moves = allPoints.keySet().stream()
                        .filter(id -> isRelatedRebuttal(thesis.pointId(), id, allPoints, new LinkedHashSet<>()))
                        .sorted(java.util.Comparator.comparingInt(roundOf::get))
                        .toList();
                int lastRound = moves.stream().mapToInt(roundOf::get).max().orElse(1);
                CruxStatus status;
                if (lastRound == completedRound) {
                    status = stalled(thesis, moves, roundOf, allPoints, completedRound)
                            ? CruxStatus.STALLED : CruxStatus.OPEN;
                } else if (moves.isEmpty()) {
                    status = CruxStatus.UNCHALLENGED;
                } else {
                    Set<Side> lastMovers = new LinkedHashSet<>();
                    moves.stream().filter(id -> roundOf.get(id) == lastRound).forEach(id -> lastMovers.add(sideOf.get(id)));
                    status = lastMovers.size() > 1 ? CruxStatus.DEADLOCK
                            : lastMovers.contains(turn.side()) ? CruxStatus.HELD : CruxStatus.CONCEDED;
                }
                cruxes.add(new Crux(thesis, turn.side(), status, lastRound, moves));
            }
        }
        return cruxes;
    }

    /** 反驳是否沿 respondsTo 链（可经过其他反驳）指向该根论点；评分校验与争点统计共用这一规则。 */
    public static boolean isRelatedRebuttal(String thesisPointId, String rebuttalPointId,
                                            Map<String, DebatePoint> allPoints, Set<String> visiting) {
        DebatePoint rebuttal = allPoints.get(rebuttalPointId);
        if (rebuttal == null || rebuttal.type() != PointType.REBUTTAL || !visiting.add(rebuttalPointId)) {
            return false;
        }
        for (String targetId : rebuttal.respondsToPointIds()) {
            if (thesisPointId.equals(targetId)) {
                return true;
            }
            DebatePoint target = allPoints.get(targetId);
            if (target != null && target.type() == PointType.REBUTTAL
                    && isRelatedRebuttal(thesisPointId, targetId, allPoints, visiting)) {
                return true;
            }
        }
        return false;
    }

    /** 本轮和上一轮都有交锋，且两轮都没有引用该争点此前未出现过的证据。 */
    private static boolean stalled(DebatePoint thesis, List<String> moves, Map<String, Integer> roundOf,
                                   Map<String, DebatePoint> allPoints, int completedRound) {
        if (completedRound < 3) {
            return false;
        }
        for (int round = completedRound - 1; round <= completedRound; round++) {
            int current = round;
            Set<String> earlier = new LinkedHashSet<>();
            thesis.evidenceRefs().forEach(ref -> earlier.add(ref.evidenceId()));
            moves.stream().filter(id -> roundOf.get(id) < current)
                    .forEach(id -> allPoints.get(id).evidenceRefs().forEach(ref -> earlier.add(ref.evidenceId())));
            List<String> inRound = moves.stream().filter(id -> roundOf.get(id) == current).toList();
            if (inRound.isEmpty() || inRound.stream().flatMap(id -> allPoints.get(id).evidenceRefs().stream())
                    .anyMatch(ref -> !earlier.contains(ref.evidenceId()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> aliases(List<DebateTurn> turns, java.util.function.Function<Side, String> prefix) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (DebateTurn turn : safe(turns)) {
            if (turn == null || turn.side() == null) {
                continue;
            }
            for (int index = 0; index < turn.points().size(); index++) {
                DebatePoint point = turn.points().get(index);
                aliases.putIfAbsent(point.pointId(), prefix.apply(turn.side()) + "-" + turn.round() + "." + (index + 1));
            }
        }
        return aliases;
    }

    private static java.util.stream.Stream<String> evidenceIds(DebateTurn turn) {
        return turn.points().stream().flatMap(point -> point.evidenceRefs().stream()).map(EvidenceRef::evidenceId);
    }

    private static List<DebateTurn> safe(List<DebateTurn> turns) {
        return turns == null ? List.of() : turns;
    }
}
