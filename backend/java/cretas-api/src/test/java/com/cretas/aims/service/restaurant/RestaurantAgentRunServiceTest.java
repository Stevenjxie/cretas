package com.cretas.aims.service.restaurant;

import com.cretas.aims.client.RestaurantAgentRuntimeClient;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.TrustedContext;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.UpstreamHttpException;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.UpstreamStream;
import com.cretas.aims.config.RestaurantAgentRuntimeProperties;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunCancelResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestaurantAgentRunServiceTest {

    private RestaurantAgentRuntimeClient client;
    private RestaurantAgentRuntimeProperties properties;
    private IntentConfigManagementService configService;
    private RestaurantAgentRunService service;

    @BeforeEach
    void setUp() {
        client = mock(RestaurantAgentRuntimeClient.class);
        properties = new RestaurantAgentRuntimeProperties();
        configService = mock(IntentConfigManagementService.class);
        Executor directExecutor = Runnable::run;
        service = new RestaurantAgentRunService(client, properties, configService, directExecutor);
    }

    @Test
    void offValidatesLocalBusinessContextButPerformsZeroPythonCalls() throws Exception {
        when(configService.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");

        assertThatThrownBy(() -> start())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));
        verify(configService).resolveBusinessDomain("R001");
        verifyNoInteractions(client);
    }

    @Test
    void activeWithBlankSecretFailsClosedBeforeOpen() throws Exception {
        active();
        when(configService.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");
        when(client.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> start())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                        .isEqualTo("RESTAURANT_AGENT_RUNTIME_SECRET_MISSING"));
        verify(client, never()).openStartStream(any(), any());
    }

    @Test
    void factoryBusinessDomainMustBeRestaurant() {
        active();
        when(client.isConfigured()).thenReturn(true);
        when(configService.resolveBusinessDomain("R001")).thenReturn("FACTORY");

        assertThatThrownBy(() -> start())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verifyNoInteractions(client);
    }

    @Test
    void roleMustHavePriceAccess() {
        active();
        when(client.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.start(
                "R001", "42", "viewer", "corr-001", request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                        .isEqualTo("FINANCIAL_ACCESS_REQUIRED"));
        verifyNoInteractions(configService, client);
    }

    @Test
    void chatAdmissionIsReadOnlyAndFailsClosedForRoleDomainAndRollout() throws Exception {
        assertThat(service.isAvailableTo("R001", "restaurant_owner")).isFalse();
        verifyNoInteractions(configService, client);

        active();
        when(client.isConfigured()).thenReturn(false);
        assertThat(service.isAvailableTo("R001", "restaurant_owner")).isFalse();
        verifyNoInteractions(configService);

        when(client.isConfigured()).thenReturn(true);
        assertThat(service.isAvailableTo("R001", "viewer")).isFalse();
        verifyNoInteractions(configService);

        when(configService.resolveBusinessDomain("R001")).thenReturn("FACTORY");
        assertThat(service.isAvailableTo("R001", "restaurant_owner")).isFalse();

        when(configService.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");
        assertThat(service.isAvailableTo("R001", " Restaurant_Owner ")).isTrue();
        verify(client, never()).openStartStream(any(), any());
    }

    @Test
    void activeStreamForwardsOnlyPersistedEventAndCleansUpOnce() throws Exception {
        active();
        when(client.isConfigured()).thenReturn(true);
        when(configService.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");
        UpstreamStream upstream = mock(UpstreamStream.class);
        String raw = "{\"schemaVersion\":\"1.0\","
                + "\"runId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"sequence\":1,\"eventType\":\"RUN_STARTED\","
                + "\"stepId\":null,\"toolName\":null,\"payload\":{}}";
        when(upstream.source()).thenReturn(new Buffer().writeUtf8(
                "id: 1\nevent: agent.event.v1\ndata: " + raw + "\n\n"));
        when(upstream.runId())
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(client.openStartStream(any(), any())).thenReturn(upstream);

        RestaurantAgentRunService.StreamResult result = start();

        assertThat(result.runId()).isEqualTo(
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        verify(client).validateEventFrame(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "1", raw);
        verify(upstream, times(1)).cancel();
        verify(upstream, times(1)).close();

        ArgumentCaptor<TrustedContext> contextCaptor = ArgumentCaptor.forClass(TrustedContext.class);
        verify(client).openStartStream(any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEqualTo(new TrustedContext(
                "R001", "42", "restaurant_owner", "RESTAURANT", "corr-001"));
    }

    @Test
    void nullRoleAndBusinessLookupFailureAreControlledAndDoNotCallUpstream() throws Exception {
        active();
        when(client.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.start(
                "R001", "42", null, "corr-001", request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(401));
        verify(client, never()).openStartStream(any(), any());

        when(configService.resolveBusinessDomain("R001"))
                .thenThrow(new IllegalStateException("database detail"));
        assertThatThrownBy(() -> start())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException response = (ResponseStatusException) ex;
                    assertThat(response.getStatusCode().value()).isEqualTo(503);
                    assertThat(response.getReason()).isEqualTo("RESTAURANT_BUSINESS_LOOKUP_FAILED");
                });
        verify(client, never()).openStartStream(any(), any());
    }

    @Test
    void replayPreservesDurableContractAndMapsMissingRun() throws Exception {
        active();
        when(client.isConfigured()).thenReturn(true);
        when(configService.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        RestaurantAgentRunReplayResponse expected = new RestaurantAgentRunReplayResponse(
                "1.0", runId.toString(), "RUNNING",
                RestaurantAgentRunStartRequest.ROUTE_CODE, 0, List.of(), null, null);
        when(client.replay(runId, 0, new TrustedContext(
                "R001", "42", "restaurant_owner", "RESTAURANT", "corr-001")))
                .thenReturn(expected);

        assertThat(service.replay(
                "R001", "42", "restaurant_owner", "corr-001", runId, 0))
                .isSameAs(expected);

        when(client.replay(runId, 1, new TrustedContext(
                "R001", "42", "restaurant_owner", "RESTAURANT", "corr-001")))
                .thenThrow(new UpstreamHttpException(404));
        assertThatThrownBy(() -> service.replay(
                "R001", "42", "restaurant_owner", "corr-001", runId, 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void cancelPreservesTrustedContextAndMapsMissingRun() throws Exception {
        active();
        when(client.isConfigured()).thenReturn(true);
        when(configService.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TrustedContext context = new TrustedContext(
                "R001", "42", "restaurant_owner", "RESTAURANT", "corr-001");
        RestaurantAgentRunCancelResponse expected = new RestaurantAgentRunCancelResponse(
                "1.0", runId.toString(), "REQUESTED", "RUNNING", 9);
        when(client.cancel(runId, context)).thenReturn(expected);

        assertThat(service.cancel(
                "R001", "42", "restaurant_owner", "corr-001", runId)).isSameAs(expected);

        when(client.cancel(runId, context)).thenThrow(new UpstreamHttpException(404));
        assertThatThrownBy(() -> service.cancel(
                "R001", "42", "restaurant_owner", "corr-001", runId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(404));
    }

    private RestaurantAgentRunService.StreamResult start() {
        return service.start("R001", "42", "restaurant_owner", "corr-001", request());
    }

    private RestaurantAgentRunStartRequest request() {
        RestaurantAgentRunStartRequest request = new RestaurantAgentRunStartRequest();
        request.setSchemaVersion(RestaurantAgentRunStartRequest.SCHEMA_VERSION);
        request.setRouteCode(RestaurantAgentRunStartRequest.ROUTE_CODE);
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 7, 18));
        return request;
    }

    private void active() {
        properties.setMode(RestaurantAgentRuntimeProperties.Mode.ACTIVE);
    }
}
