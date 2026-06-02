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

    private static final Set<String> WRITE_SUFFIXES = Set.of(
            "_CREATE", "_UPDATE", "_DELETE", "_START", "_STOP", "_PAUSE", "_RESUME",
            "_COMPLETE", "_EXECUTE", "_CONSUME", "_RELEASE", "_RESERVE", "_ACKNOWLEDGE",
            "_RESOLVE", "_CLEAR", "_CLOSE", "_REOPEN", "_FREEZE", "_RESET", "_DEDUCT",
            "_APPROVE", "_CANCEL", "_CONFIRM", "_ADJUST", "_SUBMIT");

    public boolean isWriteAction(ToolExecutor.ActionType t) {
        return t == ToolExecutor.ActionType.WRITE
                || t == ToolExecutor.ActionType.UPDATE
                || t == ToolExecutor.ActionType.DELETE;
    }

    public boolean isWriteTool(ToolExecutor tool) {
        return tool != null && isWriteAction(tool.getActionType());
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
