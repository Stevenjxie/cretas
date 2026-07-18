package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolEgressPermit;
import com.cretas.aims.client.RestaurantOwnerActionClient;
import com.cretas.aims.client.RestaurantOwnerActionClient.OwnerActionRequest;
import com.cretas.aims.client.RestaurantOwnerActionClient.TrustedContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestaurantOwnerActionAdvisorToolTest {

    private static final String FACTORY_ID = "RES_DEMO_QHJ";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestaurantOwnerActionClient client;
    private RestaurantOwnerActionAdvisorTool tool;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(RestaurantOwnerActionClient.class);
        tool = new RestaurantOwnerActionAdvisorTool(client);
        Field mapper = tool.getClass().getSuperclass().getDeclaredField("objectMapper");
        mapper.setAccessible(true);
        mapper.set(tool, objectMapper);
    }

    @Test
    void publishesExactAnalyzePermissionAndEgressMetadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_owner_action_advisor");
        assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.ANALYZE);
        assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.LOW);
        assertThat(tool.getVersion()).isEqualTo("2.0.0");
        assertThat(tool.getDomainTags())
                .containsExactlyInAnyOrder("restaurant", "analytics", "decision-support");
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.hasPermission("restaurant_owner")).isFalse();
        assertThat(tool.getRequiredPermissions()).containsExactly("analytics:read");
        assertThat(tool.supportsPreview()).isFalse();
        assertThat(tool.getEgressDestinationIds())
                .containsExactly(RestaurantOwnerActionClient.DESTINATION_ID);
    }

    @Test
    void passesActualToolCallPermitTrustedContextAndTypedBodyToClient() throws Exception {
        ToolCall actualCall = ToolCall.of(
                "request-1",
                RestaurantOwnerActionClient.TOOL_NAME,
                objectMapper.writeValueAsString(Map.of(
                        "message", "今天仓管厨师长前台分别做什么？",
                        "sessionId", "owner-1",
                        "demoScenario", "operations_dispatch",
                        "storeName", "测试门店",
                        "subSector", "中餐",
                        "period", "this_week")));
        ToolEgressPermit permit = permit("request-1", Instant.now().plusSeconds(30));
        when(client.advise(same(actualCall), same(permit), any(), any()))
                .thenReturn(Map.of(
                        "sessionId", "owner-1",
                        "scenario", "operations_dispatch",
                        "answer", "仓管补货，厨师长盯出品，前台盯核销。",
                        "followUpSuggestions", List.of("仓管具体做什么？"),
                        "charts", List.of(),
                        "roleActionPlan", List.of(),
                        "ownerDecisionPage", Map.of()));

        String json = tool.execute(actualCall, trustedContext(permit));
        Map<String, Object> envelope = objectMapper.readValue(json, new TypeReference<>() { });

        assertThat(envelope).containsEntry("success", true);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        assertThat(data.get("dataAvailable")).isEqualTo(true);
        assertThat(data.get("answer")).isEqualTo("仓管补货，厨师长盯出品，前台盯核销。");
        assertThat(data.get("advisorSource")).isEqualTo(RestaurantOwnerActionClient.TOOL_NAME);

        ArgumentCaptor<TrustedContext> context = ArgumentCaptor.forClass(TrustedContext.class);
        ArgumentCaptor<OwnerActionRequest> request = ArgumentCaptor.forClass(OwnerActionRequest.class);
        verify(client).advise(same(actualCall), same(permit), context.capture(), request.capture());
        assertThat(context.getValue()).isEqualTo(new TrustedContext(
                FACTORY_ID, "7", "restaurant_owner", "BRANCH", "request-1"));
        assertThat(request.getValue().message()).isEqualTo("今天仓管厨师长前台分别做什么？");
        assertThat(request.getValue().sessionId()).isEqualTo("owner-1");
    }

    @Test
    void missingPermitAndUpstreamFailureReturnSuccessFalseWithoutFakeData() throws Exception {
        ToolCall missingPermit = ToolCall.of(
                "missing", RestaurantOwnerActionClient.TOOL_NAME, "{}");
        String missingJson = tool.execute(missingPermit, Map.of(
                "factoryId", FACTORY_ID,
                "userId", 7L,
                "userRole", "restaurant_owner",
                "businessType", "RESTAURANT"));
        Map<String, Object> missingEnvelope = objectMapper.readValue(
                missingJson, new TypeReference<>() { });
        assertThat(missingEnvelope).containsEntry("success", false);
        assertThat(missingEnvelope).doesNotContainKey("data");

        ToolCall upstreamFailure = ToolCall.of(
                "upstream", RestaurantOwnerActionClient.TOOL_NAME, "{}");
        ToolEgressPermit permit = permit("upstream", Instant.now().plusSeconds(30));
        when(client.advise(same(upstreamFailure), same(permit), any(), any()))
                .thenThrow(new IOException("sensitive upstream detail"));
        String failedJson = tool.execute(upstreamFailure, trustedContext(permit));
        Map<String, Object> failedEnvelope = objectMapper.readValue(
                failedJson, new TypeReference<>() { });
        assertThat(failedEnvelope).containsEntry("success", false);
        assertThat(failedEnvelope).doesNotContainKey("data");
        assertThat(failedEnvelope.get("error").toString())
                .doesNotContain("sensitive upstream detail");
    }

    @Test
    void malformedToolCallFailsClosedInsteadOfThrowing() throws Exception {
        String response = tool.execute(null, Map.of());
        Map<String, Object> envelope = objectMapper.readValue(
                response, new TypeReference<>() { });
        assertThat(envelope).containsEntry("success", false);
    }

    @Test
    void nonStringParametersFailClosedBeforeClientInteraction() throws Exception {
        ToolCall objectMessage = ToolCall.of(
                "object-message",
                RestaurantOwnerActionClient.TOOL_NAME,
                objectMapper.writeValueAsString(Map.of("message", Map.of("raw", "secret"))));
        Map<String, Object> objectEnvelope = objectMapper.readValue(
                tool.execute(
                        objectMessage,
                        trustedContext(permit(
                                "object-message", Instant.now().plusSeconds(30)))),
                new TypeReference<>() { });
        assertThat(objectEnvelope).containsEntry("success", false);

        ToolCall listPeriod = ToolCall.of(
                "list-period",
                RestaurantOwnerActionClient.TOOL_NAME,
                objectMapper.writeValueAsString(Map.of(
                        "message", "今天先做什么？",
                        "period", List.of("this_week"))));
        Map<String, Object> listEnvelope = objectMapper.readValue(
                tool.execute(
                        listPeriod,
                        trustedContext(permit(
                                "list-period", Instant.now().plusSeconds(30)))),
                new TypeReference<>() { });
        assertThat(listEnvelope).containsEntry("success", false);
        verifyNoInteractions(client);
    }

    private Map<String, Object> trustedContext(ToolEgressPermit permit) throws Exception {
        Method method = ToolEgressPermit.class.getDeclaredMethod(
                "trustedExecutionContext", Map.class, Optional.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) method.invoke(
                null,
                Map.of(
                        "factoryId", FACTORY_ID,
                        "userId", 7L,
                        "userRole", "restaurant_owner",
                        "businessType", "BRANCH"),
                Optional.of(permit));
        return context;
    }

    private ToolEgressPermit permit(String requestId, Instant deadline) throws Exception {
        Constructor<ToolEgressPermit> constructor = ToolEgressPermit.class
                .getDeclaredConstructor(
                        String.class, String.class, String.class, Instant.class, Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                RestaurantOwnerActionClient.TOOL_NAME,
                RestaurantOwnerActionClient.TOOL_VERSION,
                requestId,
                deadline,
                Set.of(RestaurantOwnerActionClient.DESTINATION_ID));
    }
}
