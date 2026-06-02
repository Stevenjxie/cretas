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
