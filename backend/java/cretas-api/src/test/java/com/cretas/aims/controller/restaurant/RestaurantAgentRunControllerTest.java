package com.cretas.aims.controller.restaurant;

import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunCancelResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionPreviewResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionWorkflowResponse;
import com.cretas.aims.filter.CorrelationIdFilter;
import com.cretas.aims.service.restaurant.RestaurantAgentActionWorkflowService;
import com.cretas.aims.service.restaurant.RestaurantAgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class RestaurantAgentRunControllerTest {

    private RestaurantAgentRunService service;
    private RestaurantAgentActionWorkflowService actionWorkflowService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RestaurantAgentRunService.class);
        actionWorkflowService = mock(RestaurantAgentActionWorkflowService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RestaurantAgentRunController(service, actionWorkflowService)).build();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void postUsesOnlyVerifiedRequestAttributesAndCapturesCorrelationSynchronously() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        SseEmitter emitter = new SseEmitter();
        when(service.start(eq("R001"), eq("42"), eq("restaurant_owner"),
                eq("corr-001"), any()))
                .thenReturn(new RestaurantAgentRunService.StreamResult(emitter, runId));
        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID, "corr-001");

        org.springframework.test.web.servlet.MvcResult initial = mockMvc.perform(
                        post("/api/mobile/R001/restaurant-agent/runs")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "RESTAURANT_OWNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validBody()))
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.complete();
        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("X-Agent-Run-Id", runId.toString()))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"));

        verify(service).start(eq("R001"), eq("42"), eq("restaurant_owner"),
                eq("corr-001"), any());
    }

    @Test
    void springSseStringConverterKeepsRawJsonUnquoted() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String raw = "{\"schemaVersion\":\"1.0\",\"runId\":\"" + runId
                + "\",\"sequence\":1,\"eventType\":\"RUN_STARTED\",\"stepId\":null,"
                + "\"toolName\":null,\"payload\":{}}";
        SseEmitter emitter = new SseEmitter();
        when(service.start(eq("R001"), eq("42"), eq("restaurant_owner"),
                anyString(), any()))
                .thenReturn(new RestaurantAgentRunService.StreamResult(emitter, runId));

        org.springframework.test.web.servlet.MvcResult initial = mockMvc.perform(
                        post("/api/mobile/R001/restaurant-agent/runs")
                                .requestAttr("factoryId", "R001")
                                .requestAttr("userId", 42L)
                                .requestAttr("role", "restaurant_owner")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content(validBody()))
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.send(SseEmitter.event().id("1").name("agent.event.v1").data(raw));
        emitter.complete();
        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:" + raw)))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data:\"{\\\"schemaVersion"))));
    }

    @Test
    void pathFactoryMustExactlyMatchVerifiedTokenFactory() throws Exception {
        mockMvc.perform(post("/api/mobile/R002/restaurant-agent/runs")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "restaurant_owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());

        verify(service, never()).start(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void missingVerifiedActorIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("role", "restaurant_owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        verify(service, never()).start(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void bodyTenantFieldIsRejectedBeforeService() throws Exception {
        String body = validBody().replace("\"dishTopN\":10", "\"dishTopN\":10,\"factoryId\":\"ATTACKER\"");

        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "restaurant_owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).start(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void invalidDateWindowIsRejectedBeforeService() throws Exception {
        String body = validBody()
                .replace("2026-07-01", "2026-07-19")
                .replace("2026-07-18", "2026-07-01");

        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "restaurant_owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).start(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void replayReturnsDirectDurableContractWithoutApiEnvelope() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        RestaurantAgentRunReplayResponse replay = new RestaurantAgentRunReplayResponse(
                "1.0", runId.toString(), "RUNNING", "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                0, List.of(), null, null);
        when(service.replay(eq("R001"), eq("42"), eq("restaurant_owner"),
                anyString(), eq(runId), eq(7L))).thenReturn(replay);

        mockMvc.perform(get("/api/mobile/R001/restaurant-agent/runs/{runId}/events", runId)
                        .param("afterSequence", "7")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "restaurant_owner"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.routeCode").value("GROSS_MARGIN_DECLINE_ATTRIBUTION"))
                .andExpect(jsonPath("$.success").doesNotExist());

        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        verify(service).replay(eq("R001"), eq("42"), eq("restaurant_owner"),
                correlation.capture(), eq(runId), eq(7L));
        assertThat(correlation.getValue()).matches("[0-9a-f-]{36}");
    }

    @Test
    void cancelReturnsDirectDurableAcknowledgementAndTrustedContext() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        RestaurantAgentRunCancelResponse response = new RestaurantAgentRunCancelResponse(
                "1.0", runId.toString(), "REQUESTED", "RUNNING", 8L);
        when(service.cancel(eq("R001"), eq("42"), eq("restaurant_owner"),
                anyString(), eq(runId))).thenReturn(response);

        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs/{runId}/cancel", runId)
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "RESTAURANT_OWNER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.result").value("REQUESTED"))
                .andExpect(jsonPath("$.nextEventSequence").value(8L))
                .andExpect(jsonPath("$.success").doesNotExist());

        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        verify(service).cancel(eq("R001"), eq("42"), eq("restaurant_owner"),
                correlation.capture(), eq(runId));
        assertThat(correlation.getValue()).matches("[0-9a-f-]{36}");
    }

    @Test
    void previewUsesTrustedContextAndReturnsNoStoreDirectContract() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        RestaurantAgentActionPreviewResponse response = new RestaurantAgentActionPreviewResponse(
                "1.0", runId.toString(), "COMPLETE_DISH_COST_DATA_PROPOSAL",
                "REVIEW_DISH_COST_DATA", "READ_ONLY_PROPOSAL",
                List.of("DISH_MARGIN_UNAVAILABLE"),
                List.of(new RestaurantAgentActionPreviewResponse.EvidenceReference(
                        "evidence-1", "fact-1", "GROSS_MARGIN_DECLINE_OBSERVED")),
                "restaurant.dish-cost-data-review.v1",
                "00000000-0000-0000-0000-000000000099",
                LocalDateTime.of(2026, 7, 20, 12, 0));
        when(actionWorkflowService.preview(eq("R001"), eq("42"), eq("restaurant_owner"),
                anyString(), eq(runId), eq("COMPLETE_DISH_COST_DATA_PROPOSAL")))
                .thenReturn(response);

        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs/{runId}/action-proposals/{proposalCode}/preview",
                        runId, "COMPLETE_DISH_COST_DATA_PROPOSAL")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "RESTAURANT_OWNER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.proposalCode").value("COMPLETE_DISH_COST_DATA_PROPOSAL"))
                .andExpect(jsonPath("$.executionMode").value("READ_ONLY_PROPOSAL"))
                .andExpect(jsonPath("$.evidenceReferences[0].factId").value("fact-1"));
    }

    @Test
    void confirmAcceptsOnlyPreviewTokenAndDoesNotExposeNavigationBeforeApproval() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String token = "00000000-0000-0000-0000-000000000099";
        RestaurantAgentActionWorkflowResponse response = new RestaurantAgentActionWorkflowResponse(
                "1.0", runId.toString(), "COMPLETE_DISH_COST_DATA_PROPOSAL",
                "restaurant.dish-cost-data-review.v1", "workflow-instance-1", "RUNNING",
                false, null);
        when(actionWorkflowService.confirm(eq("R001"), eq("42"), eq("restaurant_owner"),
                anyString(), eq(runId), eq("COMPLETE_DISH_COST_DATA_PROPOSAL"), eq(token)))
                .thenReturn(response);

        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs/{runId}/action-proposals/{proposalCode}/confirm",
                        runId, "COMPLETE_DISH_COST_DATA_PROPOSAL")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "RESTAURANT_OWNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.workflowStatus").value("RUNNING"))
                .andExpect(jsonPath("$.navigationTarget").doesNotExist());

        mockMvc.perform(post("/api/mobile/R001/restaurant-agent/runs/{runId}/action-proposals/{proposalCode}/confirm",
                        runId, "COMPLETE_DISH_COST_DATA_PROPOSAL")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "RESTAURANT_OWNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + token
                                + "\",\"actionCode\":\"WRITE_RECIPE\"}"))
                .andExpect(status().isBadRequest());

        verify(actionWorkflowService).confirm(eq("R001"), eq("42"), eq("restaurant_owner"),
                anyString(), eq(runId), eq("COMPLETE_DISH_COST_DATA_PROPOSAL"), eq(token));
    }

    private String validBody() throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.of(
                "schemaVersion", "1.0",
                "routeCode", "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                "startDate", "2026-07-01",
                "endDate", "2026-07-18",
                "storeTopN", 20,
                "dishTopN", 10));
    }
}
