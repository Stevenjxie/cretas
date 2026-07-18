package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolDispatchServicePreviewSafetyTest {

    @Test
    void previewOnlyNeverFallsThroughToExecuteWhenToolHasNoSafePreview() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolDispatchService dispatch = new ToolDispatchService(
                registry,
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));

        ToolExecutor writeTool = mock(ToolExecutor.class);
        when(writeTool.getToolName()).thenReturn("material_batch_delete");
        when(writeTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        when(writeTool.supportsPreview()).thenReturn(false);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("MATERIAL_BATCH_DELETE")
                .intentName("删除物料批次")
                .intentCategory("DATA_OP")
                .sensitivityLevel("HIGH")
                .toolName("material_batch_delete")
                .build();
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .userInput("预览删除批次 B001")
                .previewOnly(true)
                .build();

        IntentExecuteResponse response = dispatch.executeWithTool(
                writeTool, "F006", request, intent, 42L, "factory_super_admin", null);

        assertThat(response.getStatus()).isEqualTo("PREVIEW_UNSUPPORTED");
        assertThat(response.getMessage()).contains("未执行任何操作");
        verify(writeTool, never()).execute(any(ToolCall.class), anyMap());
        verify(writeTool, never()).preview(any(ToolCall.class), anyMap());
    }
}
