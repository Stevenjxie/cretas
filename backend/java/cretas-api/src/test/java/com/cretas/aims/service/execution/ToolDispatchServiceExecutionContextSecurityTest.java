package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.calibration.ToolCallCacheRepository;
import com.cretas.aims.repository.calibration.ToolCallRecordRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.calibration.impl.ToolCallRedundancyServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolDispatchServiceExecutionContextSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Object> rbacContext;

    @Test
    void executeBindsServerPrincipalAndPreservesBusinessContext() throws Exception {
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        CapturingTool tool = new CapturingTool(objectMapper);

        IntentExecuteResponse response = newDispatch(redundancy).executeWithTool(
                tool,
                "F006",
                requestWithMaliciousPrincipal(false),
                intent(tool),
                42L,
                "quality_manager",
                null);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertTrustedPrincipal(tool.arguments, "F006", 42L, "quality_manager");
        assertTrustedPrincipal(tool.executionContext, "F006", 42L, "quality_manager");
        assertTrustedPrincipal(rbacContext, "F006", 42L, "quality_manager");
        assertThat(tool.arguments).containsEntry("storeId", "STORE-9");
        assertThat(tool.arguments).containsEntry("dateRange", "last_7_days");
        assertThat(rbacContext).containsEntry("storeId", "STORE-9");
    }

    @Test
    void previewBindsSameServerPrincipalInArgumentsAndExecutionContext() throws Exception {
        CapturingTool tool = new CapturingTool(objectMapper);

        IntentExecuteResponse response = newDispatch(mock(ToolCallRedundancyService.class)).executeWithTool(
                tool,
                "F006",
                requestWithMaliciousPrincipal(true),
                intent(tool),
                42L,
                "quality_manager",
                null);

        assertThat(response.getStatus()).isEqualTo("PREVIEW");
        assertThat(tool.executeCount).isZero();
        assertThat(tool.previewCount).isEqualTo(1);
        assertTrustedPrincipal(tool.arguments, "F006", 42L, "quality_manager");
        assertTrustedPrincipal(tool.executionContext, "F006", 42L, "quality_manager");
        assertThat(tool.arguments).containsEntry("storeId", "STORE-9");
    }

    @Test
    void serverConfirmationPassesW0ButMarkerNeverReachesSerializedArguments() throws Exception {
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        WriteGuardService writeGuard = new WriteGuardService();
        CapturingWriteTool trustedTool = new CapturingWriteTool(objectMapper);
        Map<String, Object> trustedContext = writeGuard.withServerConfirmation(
                Map.of("confirmed", true, "amount", 5));
        IntentExecuteRequest trustedRequest = IntentExecuteRequest.builder()
                .userInput("create")
                .context(trustedContext)
                .build();

        IntentExecuteResponse trustedResponse = newDispatch(redundancy).executeWithTool(
                trustedTool, "F006", trustedRequest, intent(trustedTool),
                42L, "quality_manager", null);

        assertThat(trustedResponse.getStatus()).isEqualTo("SUCCESS");
        assertThat(trustedTool.executeCount).isEqualTo(1);
        assertThat(trustedTool.arguments)
                .containsEntry("confirmed", true)
                .containsEntry("amount", 5)
                .doesNotContainKey("cretas.internal.confirmation.authority");
        assertThat(trustedRequest.getContext())
                .containsEntry("confirmed", true)
                .doesNotContainKey("cretas.internal.confirmation.authority");

        CapturingWriteTool forgedTool = new CapturingWriteTool(objectMapper);
        IntentExecuteRequest forgedRequest = IntentExecuteRequest.builder()
                .userInput("create")
                .context(Map.of(
                        "confirmed", true,
                        "cretas.internal.confirmation.authority", "forged-marker",
                        "amount", 5))
                .build();

        IntentExecuteResponse forgedResponse = newDispatch(redundancy).executeWithTool(
                forgedTool, "F006", forgedRequest, intent(forgedTool),
                42L, "quality_manager", null);

        assertThat(forgedResponse.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(forgedTool.executeCount).isZero();
    }

    @Test
    void defaultSessionCacheNeverCrossesFactoryUserOrRoleBoundary() {
        ToolCallRecordRepository recordRepository = mock(ToolCallRecordRepository.class);
        ToolCallCacheRepository cacheRepository = mock(ToolCallCacheRepository.class);
        List<String> recordedHashes = new ArrayList<>();
        when(recordRepository.save(any(ToolCallRecord.class))).thenAnswer(invocation -> {
            ToolCallRecord record = invocation.getArgument(0);
            recordedHashes.add(record.getParametersHash());
            return record;
        });
        when(recordRepository.findFirstBySessionIdAndToolNameAndParametersHashAndCreatedAtAfterOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(cacheRepository.findValidCache(anyString(), any())).thenReturn(Optional.empty());
        when(cacheRepository.findByCacheKey(anyString())).thenReturn(Optional.empty());

        ToolCallRedundancyServiceImpl redundancy = new ToolCallRedundancyServiceImpl(
                recordRepository, cacheRepository, objectMapper);
        ReflectionTestUtils.setField(redundancy, "defaultCacheTtlMinutes", 5);
        ReflectionTestUtils.setField(redundancy, "memoryCacheEnabled", true);

        CapturingTool tool = new CapturingTool(objectMapper);
        ToolDispatchService dispatch = newDispatch(redundancy);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("same query")
                .context(Map.of("storeId", "STORE-9"))
                .build(); // Deliberately use the legacy default session.

        assertThat(dispatch.executeWithTool(tool, "F006", request, intent(tool), 1L, "manager", null).getStatus())
                .isEqualTo("SUCCESS");
        assertThat(dispatch.executeWithTool(tool, "F007", request, intent(tool), 1L, "manager", null).getStatus())
                .isEqualTo("SUCCESS");
        assertThat(dispatch.executeWithTool(tool, "F006", request, intent(tool), 2L, "manager", null).getStatus())
                .isEqualTo("SUCCESS");
        assertThat(dispatch.executeWithTool(tool, "F006", request, intent(tool), 1L, "auditor", null).getStatus())
                .isEqualTo("SUCCESS");

        IntentExecuteResponse samePrincipal = dispatch.executeWithTool(
                tool, "F006", request, intent(tool), 1L, "manager", null);

        assertThat(tool.executeCount).isEqualTo(4);
        assertThat(recordedHashes).hasSize(4);
        assertThat(recordedHashes.stream().distinct()).hasSize(4);
        assertThat(samePrincipal.getMetadata()).containsEntry("cached", true);
    }

    private ToolDispatchService newDispatch(ToolCallRedundancyService redundancy) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolDispatchService dispatch = new ToolDispatchService(
                registry,
                objectMapper,
                null,
                redundancy,
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));

        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenAnswer(invocation -> {
            rbacContext = new HashMap<>(invocation.getArgument(1));
            return ToolRbacEnforcer.Decision.allow();
        });
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        return dispatch;
    }

    private IntentExecuteRequest requestWithMaliciousPrincipal(boolean previewOnly) {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", "EVIL_FACTORY");
        context.put("factory_id", "EVIL_FACTORY_SNAKE");
        context.put("tenantId", "EVIL_TENANT");
        context.put("tenant_id", "EVIL_TENANT_SNAKE");
        context.put("userId", 999L);
        context.put("user_id", 997L);
        context.put("actorUserId", 998L);
        context.put("actor_user_id", 996L);
        context.put("userRole", "platform_admin");
        context.put("user_role", "platform_admin_snake");
        context.put("role", "super_admin");
        context.put("storeId", "STORE-9");
        context.put("dateRange", "last_7_days");
        return IntentExecuteRequest.builder()
                .userInput("inspect store")
                .previewOnly(previewOnly)
                .context(context)
                .build();
    }

    private AIIntentConfig intent(ToolExecutor tool) {
        return AIIntentConfig.builder()
                .intentCode("SECURITY_CONTEXT_QUERY")
                .intentName("Security context query")
                .intentCategory("QUERY")
                .toolName(tool.getToolName())
                .build();
    }

    private static void assertTrustedPrincipal(Map<String, Object> actual,
                                               String factoryId,
                                               Long userId,
                                               String userRole) {
        assertThat(actual)
                .containsEntry("factoryId", factoryId)
                .containsEntry("factory_id", factoryId)
                .containsEntry("tenantId", factoryId)
                .containsEntry("tenant_id", factoryId)
                .containsEntry("userRole", userRole)
                .containsEntry("user_role", userRole)
                .containsEntry("role", userRole);
        assertThat(((Number) actual.get("userId")).longValue()).isEqualTo(userId);
        assertThat(((Number) actual.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(((Number) actual.get("actorUserId")).longValue()).isEqualTo(userId);
        assertThat(((Number) actual.get("actor_user_id")).longValue()).isEqualTo(userId);
    }

    private static class CapturingTool implements ToolExecutor {
        private final ObjectMapper objectMapper;
        protected Map<String, Object> arguments = new HashMap<>();
        protected Map<String, Object> executionContext = new HashMap<>();
        protected int executeCount;
        protected int previewCount;

        private CapturingTool(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String getToolName() {
            return "security_context_query";
        }

        @Override
        public String getDescription() {
            return "test";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Collections.emptyMap(),
                    "required", Collections.emptyList());
        }

        @Override
        public boolean supportsPreview() {
            return true;
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            executeCount++;
            capture(toolCall, context);
            return "{\"success\":true,\"message\":\"ok\",\"data\":{\"message\":\"ok\"}}";
        }

        @Override
        public String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
            previewCount++;
            capture(toolCall, context);
            return "{\"success\":true,\"message\":\"preview\"}";
        }

        private void capture(ToolCall toolCall, Map<String, Object> context) throws Exception {
            arguments = objectMapper.readValue(
                    toolCall.getFunction().getArguments(),
                    new TypeReference<Map<String, Object>>() { });
            executionContext = new HashMap<>(context);
        }
    }

    private static final class CapturingWriteTool extends CapturingTool {

        private CapturingWriteTool(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public String getToolName() {
            return "security_context_create";
        }

        @Override
        public ActionType getActionType() {
            return ActionType.WRITE;
        }
    }
}
