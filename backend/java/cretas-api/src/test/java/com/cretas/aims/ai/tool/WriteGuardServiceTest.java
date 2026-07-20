package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.config.AIIntentConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WriteGuardServiceTest {
    private final WriteGuardService guard = new WriteGuardService();

    @Test
    void writeUpdateDelete_areWrites_readIsNot() {
        assertTrue(guard.isWriteAction(ToolExecutor.ActionType.WRITE));
        assertTrue(guard.isWriteAction(ToolExecutor.ActionType.UPDATE));
        assertTrue(guard.isWriteAction(ToolExecutor.ActionType.DELETE));
        assertFalse(guard.isWriteAction(ToolExecutor.ActionType.READ));
        assertFalse(guard.isWriteAction(ToolExecutor.ActionType.ANALYZE));
    }

    @Test
    void toolGuard_usesActionTypePolymorphically() {
        ToolExecutor writeTool = Mockito.mock(ToolExecutor.class);
        Mockito.when(writeTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        assertTrue(guard.isWriteTool(writeTool));
        ToolExecutor readTool = Mockito.mock(ToolExecutor.class);
        Mockito.when(readTool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        assertFalse(guard.isWriteTool(readTool));
    }

    @Test
    void toolGuard_nameSuffixFallback_catchesDestructiveToolsThatLieAboutActionType() {
        // ISSUE 1: getActionType() only maps _create/_update/_delete suffixes and defaults the rest
        // to READ. These genuinely destructive tools report READ — the NAME-suffix fallback must catch them.
        assertTrue(guard.isWriteTool(toolNamed("material_batch_consume", ToolExecutor.ActionType.READ)),
                "material_batch_consume reports READ but is destructive — name fallback must fire");
        assertTrue(guard.isWriteTool(toolNamed("shipment_cancel", ToolExecutor.ActionType.READ)));
        assertTrue(guard.isWriteTool(toolNamed("purchase_order_approve", ToolExecutor.ActionType.READ)));

        // Genuine reads: READ actionType AND no write suffix → must NOT be flagged.
        assertFalse(guard.isWriteTool(toolNamed("material_batch_query", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("report_inventory", ToolExecutor.ActionType.READ)));
    }

    @Test
    void toolGuard_expandedWriteVerbs_catchExploitableDestructiveTools() {
        // M1 (W0 final review): genuinely destructive tools whose action verb was NOT in the
        // original WRITE_SUFFIXES allowlist AND whose getActionType() defaults to READ (no override).
        // Before the suffix expansion these slipped the guard at Sites B/C/D/E and executed silently.
        // Each was confirmed exploitable against a real registered tool.
        assertTrue(guard.isWriteTool(toolNamed("bom_recipe_activate", ToolExecutor.ActionType.READ)),
                "bom_recipe_activate flips BOM DRAFT→ACTIVE — must be caught by _ACTIVATE suffix");
        assertTrue(guard.isWriteTool(toolNamed("alert_rule_toggle", ToolExecutor.ActionType.READ)),
                "alert_rule_toggle flips rule enabled state — must be caught by _TOGGLE suffix");
        assertTrue(guard.isWriteTool(toolNamed("scheduling_set_disabled", ToolExecutor.ActionType.READ)),
                "scheduling_set_disabled disables scheduling — must be caught by _DISABLE suffix");
        assertTrue(guard.isWriteTool(toolNamed("processing_worker_assign", ToolExecutor.ActionType.READ)),
                "processing_worker_assign assigns workers to a batch — must be caught by _ASSIGN suffix");
        assertTrue(guard.isWriteTool(toolNamed("split_order", ToolExecutor.ActionType.READ)),
                "split_order splits/cancels a source order — must be caught by _SPLIT suffix");
        assertTrue(guard.isWriteTool(toolNamed("notify_send", ToolExecutor.ActionType.READ)),
                "notify_send sends a notification (side-effect) — must be caught by _SEND suffix");
        assertTrue(guard.isWriteTool(toolNamed("quality_batch_mark_inspected", ToolExecutor.ActionType.READ)),
                "quality_batch_mark_inspected mutates batch state — must be caught by _MARK suffix");
        // factory_material_requisition_generate: getActionType() maps _generate → GENERATE, which
        // isWriteAction() does NOT treat as a write; only the _GENERATE name suffix catches it.
        assertTrue(guard.isWriteTool(toolNamed("factory_material_requisition_generate", ToolExecutor.ActionType.GENERATE)),
                "factory_material_requisition_generate creates a requisition — must be caught by _GENERATE suffix");
    }

    @Test
    void toolGuard_commonReadTools_areNotOverBlocked() {
        // The expanded WRITE_SUFFIXES list must not over-match common read tool names. Over-block is
        // the safe direction (extra confirm) but a routine read prompted for confirmation is bad UX,
        // so these canonical reads must classify as NOT-write.
        assertFalse(guard.isWriteTool(toolNamed("material_batch_query", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("report_inventory", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("customer_list", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("shipment_query", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("attendance_today", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("quality_check_query", ToolExecutor.ActionType.READ)));
        assertFalse(guard.isWriteTool(toolNamed("report_dashboard_overview", ToolExecutor.ActionType.READ)));
    }

    private static ToolExecutor toolNamed(String name, ToolExecutor.ActionType actionType) {
        ToolExecutor tool = Mockito.mock(ToolExecutor.class);
        Mockito.when(tool.getToolName()).thenReturn(name);
        Mockito.when(tool.getActionType()).thenReturn(actionType);
        return tool;
    }

    @Test
    void intentGuard_coversSensitivityAndSuffix() {
        AIIntentConfig high = AIIntentConfig.builder().intentCode("FOO_QUERY").sensitivityLevel("HIGH").build();
        assertTrue(guard.isWriteIntent(high));
        AIIntentConfig clear = AIIntentConfig.builder().intentCode("INVENTORY_CLEAR").sensitivityLevel("LOW").build();
        assertTrue(guard.isWriteIntent(clear));
        AIIntentConfig close = AIIntentConfig.builder().intentCode("PERIOD_CONFIRM_CLOSE").sensitivityLevel("LOW").build();
        assertTrue(guard.isWriteIntent(close));
        AIIntentConfig read = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY").sensitivityLevel("LOW").build();
        assertFalse(guard.isWriteIntent(read));
    }

    @Test
    void callerConfirmationSignals_areNeverAuthority() {
        assertFalse(guard.isConfirmed(Map.of("confirmed", true)));
        assertFalse(guard.isConfirmed(Map.of("confirmed", "true")));
        assertFalse(guard.isConfirmed(Map.of("forceExecute", true)));
        assertFalse(guard.isConfirmed(Map.of()));
        assertFalse(guard.isConfirmed(null));
    }

    @Test
    void onlyServerIssuedMarkerConfirms_andSanitizerPreservesBusinessParameters() {
        Map<String, Object> untrusted = Map.of(
                "confirmed", true,
                "FORCE_EXECUTE", "true",
                "cretas.internal.confirmation.authority", "forged-marker",
                "amount", 5);

        Map<String, Object> sanitized = WriteGuardService.withoutCallerConfirmation(untrusted);
        assertEquals(Map.of("amount", 5), sanitized);
        assertFalse(guard.isConfirmed(sanitized));

        Map<String, Object> trusted = guard.withServerConfirmation(sanitized);
        assertTrue(guard.isConfirmed(trusted));
        assertEquals(5, trusted.get("amount"));

        Map<String, Object> dispatchable = WriteGuardService.withoutServerConfirmationMarker(trusted);
        assertFalse(guard.isConfirmed(dispatchable));
        assertEquals(Map.of("amount", 5), dispatchable);
    }
}
