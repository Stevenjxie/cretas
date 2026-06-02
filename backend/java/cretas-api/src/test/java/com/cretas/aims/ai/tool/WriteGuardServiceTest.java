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
    void confirmedSignal_recognized() {
        assertTrue(guard.isConfirmed(Map.of("confirmed", true)));
        assertTrue(guard.isConfirmed(Map.of("confirmed", "true")));
        assertFalse(guard.isConfirmed(Map.of()));
        assertFalse(guard.isConfirmed(null));
    }
}
