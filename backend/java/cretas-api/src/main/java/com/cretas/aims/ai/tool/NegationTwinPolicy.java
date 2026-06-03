package com.cretas.aims.ai.tool;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.QueryPreprocessorService.NegationInfo;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * W1b: negation veto + read/write twin rerank policy.
 * Stateless + thread-safe (callable from worker threads); MUST NOT read ThreadLocal/SecurityContext.
 * Single source of truth for the write->read twin map (was duplicated in
 * IntentRecognitionPipelineServiceImpl.convertNegationIntent + SemanticRouterServiceImpl.READ_WRITE_TWIN_PAIRS).
 */
@Service
public class NegationTwinPolicy {

    private final WriteGuardService writeGuard;

    public NegationTwinPolicy(WriteGuardService writeGuard) {
        this.writeGuard = writeGuard;
    }

    /** Component-2 rerank margin. Read twin within this score gap of a write top → promote read. */
    static final double TWIN_RERANK_MARGIN = 0.10;

    /** canonical write -> read twin. Verified codes only (see spec §5.1 finding 1). */
    private static final Map<String, String> WRITE_TO_READ_TWIN = Map.ofEntries(
            Map.entry("PROCESSING_BATCH_COMPLETE", "PROCESSING_BATCH_LIST"),
            Map.entry("PROCESSING_BATCH_START", "PROCESSING_BATCH_LIST"),
            Map.entry("PROCESSING_BATCH_PAUSE", "PROCESSING_BATCH_LIST"),
            Map.entry("PROCESSING_BATCH_CREATE", "PROCESSING_BATCH_LIST"),
            Map.entry("ALERT_ACKNOWLEDGE", "ALERT_LIST"),
            Map.entry("ALERT_CREATE", "ALERT_LIST"),
            Map.entry("EQUIPMENT_STOP", "EQUIPMENT_STATUS"),
            Map.entry("EQUIPMENT_START", "EQUIPMENT_STATUS"),
            Map.entry("EQUIPMENT_CONTROL", "EQUIPMENT_STATUS"),
            Map.entry("EQUIPMENT_STATUS_UPDATE", "EQUIPMENT_STATUS"),
            Map.entry("SHIPMENT_STATUS_UPDATE", "SHIPMENT_QUERY"),
            Map.entry("SHIPMENT_CREATE", "SHIPMENT_QUERY"),
            Map.entry("SHIPMENT_UPDATE", "SHIPMENT_QUERY"),
            Map.entry("MATERIAL_BATCH_CREATE", "MATERIAL_BATCH_QUERY"),
            Map.entry("MATERIAL_BATCH_CONSUME", "MATERIAL_BATCH_QUERY"),
            Map.entry("MATERIAL_EXPIRED_QUERY", "MATERIAL_BATCH_QUERY"),
            Map.entry("QUALITY_CHECK_EXECUTE", "QUALITY_CHECK_QUERY"),
            Map.entry("QUALITY_DISPOSITION_EXECUTE", "QUALITY_CHECK_QUERY"),
            Map.entry("CLOCK_IN", "ATTENDANCE_QUERY"),
            Map.entry("CLOCK_OUT", "ATTENDANCE_QUERY"),
            Map.entry("ATTENDANCE_RECORD", "ATTENDANCE_QUERY"),
            Map.entry("SUPPLIER_EVALUATE", "SUPPLIER_QUERY"),
            Map.entry("SCALE_ADD_DEVICE", "MATERIAL_BATCH_QUERY"),
            // W1b additions (verified-exist; INVENTORY_SUMMARY_QUERY is NOT config-backed → use INVENTORY_QUERY)
            Map.entry("INVENTORY_CLEAR", "INVENTORY_QUERY"),
            Map.entry("ORDER_DELETE", "ORDER_LIST"),
            Map.entry("ORDER_CANCEL", "ORDER_LIST"));

    public String readTwinOf(String writeIntentCode) {
        return writeIntentCode == null ? null : WRITE_TO_READ_TWIN.get(writeIntentCode);
    }

    /**
     * Unified negation-veto + twin-rerank decision. Does not mutate the input list.
     * VETO_READ  → drop all candidates (user negated the query); caller returns clarification.
     * VETO_WRITE → each write candidate → its read twin (or dropped if no twin).
     * NONE/EXCLUDE_CONTENT → component-2 twin rerank, only for read-phrased queries (QUERY).
     * Safety invariant: after a VETO_*, the result contains NO write intent.
     */
    public List<CandidateIntent> applyNegationVetoAndTwinRerank(
            List<CandidateIntent> candidates,
            NegationInfo negation,
            IntentKnowledgeBase.ActionType queryActionType,
            Function<String, AIIntentConfig> configResolver) {

        if (candidates == null || candidates.isEmpty()) return candidates;
        NegationKind kind = (negation == null || negation.getKind() == null)
                ? NegationKind.NONE : negation.getKind();
        List<CandidateIntent> result = new ArrayList<>(candidates);

        if (kind == NegationKind.VETO_READ) {
            result.clear();
        } else if (kind == NegationKind.VETO_WRITE) {
            List<CandidateIntent> converted = new ArrayList<>();
            for (CandidateIntent c : result) {
                if (isWrite(c, configResolver)) {
                    String twin = readTwinOf(c.getIntentCode());
                    if (twin != null) converted.add(retarget(c, twin));
                    // else: drop the write (no read twin)
                } else {
                    converted.add(c);
                }
            }
            result = converted;
        } else {
            // NONE / EXCLUDE_CONTENT → component-2 rerank only for read-phrased queries
            if (queryActionType == IntentKnowledgeBase.ActionType.QUERY) {
                result = twinRerank(result, configResolver);
            }
            return result;  // no veto safety filter on non-veto paths
        }

        // Safety invariant (铁律): VETO_* must never emit a write candidate.
        result.removeIf(c -> isWrite(c, configResolver));
        return result;
    }

    /** True if a VETO_* emptied a previously-non-empty list → caller should clarify, not execute. */
    public boolean isVetoToClarification(List<CandidateIntent> original,
                                         List<CandidateIntent> afterPolicy,
                                         NegationInfo negation) {
        if (negation == null || negation.getKind() == null) return false;
        boolean vetoFired = negation.getKind() == NegationKind.VETO_READ
                || negation.getKind() == NegationKind.VETO_WRITE;
        return vetoFired
                && (afterPolicy == null || afterPolicy.isEmpty())
                && original != null && !original.isEmpty();
    }

    private boolean isWrite(CandidateIntent c, Function<String, AIIntentConfig> resolver) {
        if (c == null || c.getIntentCode() == null) return false;
        AIIntentConfig cfg = resolver == null ? null : resolver.apply(c.getIntentCode());
        // isWriteIntent(null)==false → fall back to name-suffix (catches suffix-based writes when cfg unresolved)
        return writeGuard.isWriteIntent(cfg) || writeGuard.hasWriteSuffix(c.getIntentCode());
    }

    /** read-phrased query whose top is a write with a comparable read present → promote the read. */
    private List<CandidateIntent> twinRerank(List<CandidateIntent> result,
                                             Function<String, AIIntentConfig> resolver) {
        if (result.size() < 2) return result;
        CandidateIntent top = result.get(0);
        if (!isWrite(top, resolver)) return result;
        double topScore = top.getConfidence() == null ? 0.0 : top.getConfidence();
        for (int i = 1; i < result.size(); i++) {
            CandidateIntent r = result.get(i);
            double rScore = r.getConfidence() == null ? 0.0 : r.getConfidence();
            if (!isWrite(r, resolver) && (topScore - rScore) <= TWIN_RERANK_MARGIN) {
                List<CandidateIntent> reordered = new ArrayList<>();
                reordered.add(r);
                for (CandidateIntent c : result) if (c != r) reordered.add(c);
                return reordered;
            }
        }
        return result;
    }

    private CandidateIntent retarget(CandidateIntent from, String newCode) {
        return CandidateIntent.builder()
                .intentCode(newCode)
                .confidence(from.getConfidence())
                .matchScore(from.getMatchScore())
                .matchMethod(from.getMatchMethod())
                .matchedKeywords(from.getMatchedKeywords())
                .build();
    }
}
