package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.config.AIIntentConfig;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;

/**
 * W0 write-guard: the single source of truth for "is this a write/destructive operation".
 * Confidence-INDEPENDENT by design. Stateless + thread-safe (callable from worker threads);
 * MUST NOT read ThreadLocal/SecurityContext.
 */
@Service
public class WriteGuardService {

    // Confidence-independent write-verb allowlist. matched via upper.contains(suffix) on the
    // tool/intent NAME. getActionType() only recognizes _create/_update/_delete and defaults the
    // rest to READ/GENERATE/NOTIFY (none of which isWriteAction() treats as a write), so genuinely
    // destructive tools (bom_recipe_activate, alert_rule_toggle, processing_worker_assign,
    // notify_send, factory_material_requisition_generate, ...) would slip the guard if their verb
    // isn't listed here. Over-block of a read tool (extra confirm) is the safe direction.
    //
    // W0 final review (M1): expanded with verbs that were confirmed-exploitable on real tools.
    // Suffixes chosen to avoid substring over-match with common read tool names — verified by
    // grepping every getToolName() literal: zero read-style name (*_query/_list/_detail/_stats/
    // _search/_overview/_trend/_ranking/_report/_status/_history/_today/_monthly) matches any
    // suffix below. NOTE: deliberately NOT adding _SHIP (over-matches _SHIPMENT → would over-block
    // shipment queries) or _SET (over-matches _SETTINGS). _MARK over-matches MARKET-style names
    // (none exist today) which is an acceptable over-block (safe direction).
    private static final Set<String> WRITE_SUFFIXES = Set.of(
            "_CREATE", "_UPDATE", "_DELETE", "_START", "_STOP", "_PAUSE", "_RESUME",
            "_COMPLETE", "_EXECUTE", "_CONSUME", "_RELEASE", "_RESERVE", "_ACKNOWLEDGE",
            "_RESOLVE", "_CLEAR", "_CLOSE", "_REOPEN", "_FREEZE", "_RESET", "_DEDUCT",
            "_APPROVE", "_CANCEL", "_CONFIRM", "_ADJUST", "_SUBMIT",
            // W0 final review (M1) additions:
            "_ACTIVATE", "_DEACTIVATE", "_TOGGLE", "_ASSIGN", "_UNASSIGN", "_GENERATE",
            "_SPLIT", "_MERGE", "_SEND", "_DISPOSE", "_REGISTER", "_TRANSITION", "_SETTLE",
            "_ALLOCATE", "_VOID", "_REJECT", "_DISPATCH", "_DISABLE", "_ENABLE", "_MARK",
            "_IMPORT", "_UPLOAD", "_BIND", "_UNBIND", "_ARCHIVE", "_RESTORE", "_ROLLBACK",
            // Verb-FIRST form: split_order names the verb as a prefix, so "_SPLIT" (leading underscore)
            // never matches "SPLIT_ORDER". Add the "SPLIT_" prefix form. Verified: only SPLIT_ORDER
            // starts with SPLIT_ among all getToolName() literals — no read-style over-match.
            "SPLIT_");

    private static final Set<String> NON_DESTRUCTIVE_GENERATE_NAMES = Set.of("REVENUE_REPORT_GENERATE");

    public boolean isWriteAction(ToolExecutor.ActionType t) {
        return t == ToolExecutor.ActionType.WRITE
                || t == ToolExecutor.ActionType.UPDATE
                || t == ToolExecutor.ActionType.DELETE;
    }

    public boolean isWriteTool(ToolExecutor tool) {
        // Confidence-INDEPENDENT like isWriteIntent: getActionType() only maps _create/_update/_delete
        // name suffixes (AbstractBusinessTool) and defaults everything else to READ — genuinely
        // destructive tools (material_batch_consume, *_approve, equipment_start, ...)
        // would otherwise classify as READ and slip the guard. The tool-NAME suffix fallback catches them.
        // Over-flagging a read tool (extra confirm) is acceptable for a safety net; under-flagging a write is not.
        return tool != null && (isWriteAction(tool.getActionType()) || hasWriteSuffix(tool.getToolName()));
    }

    public boolean isWriteIntent(AIIntentConfig intent) {
        if (intent == null) return false;
        String sens = intent.getSensitivityLevel();
        if ("HIGH".equals(sens) || "CRITICAL".equals(sens)) return true;
        return hasWriteSuffix(intent.getIntentCode());
    }

    public boolean hasWriteSuffix(String intentCode) {
        if (intentCode == null) return false;
        String upper = intentCode.toUpperCase();
        if (NON_DESTRUCTIVE_GENERATE_NAMES.contains(upper)) return false;
        if (upper.contains("CLOCK_IN") || upper.contains("CLOCK_OUT")) return true;
        for (String suffix : WRITE_SUFFIXES) {
            if (upper.contains(suffix)) return true;
        }
        return false;
    }

    public boolean isConfirmed(Map<String, Object> context) {
        if (context == null) return false;
        Object v = context.get("confirmed");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}
