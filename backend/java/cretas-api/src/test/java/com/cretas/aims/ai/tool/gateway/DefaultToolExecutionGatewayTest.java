package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease.Lease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService.Reservation;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.enums.FactoryType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultToolExecutionGatewayTest {

    @Mock ToolPrincipalPolicy principalPolicy;
    @Mock ToolRuntimeRegistry runtimeRegistry;
    @Mock ToolConfirmationLease confirmationLease;
    @Mock ToolExecutionLedgerService ledgerService;
    @Mock ToolExecutor executor;

    ObjectMapper objectMapper;
    DefaultToolExecutionGateway gateway;
    ExecutionPrincipal currentPrincipal;
    RehydratedPrincipal current;
    ToolDescriptor descriptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        gateway = new DefaultToolExecutionGateway(
                principalPolicy, runtimeRegistry, confirmationLease, ledgerService, objectMapper);
        currentPrincipal = new ExecutionPrincipal(
                "F-1", "FACTORY", "42", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of("hr:read_write"), Set.of());
        current = new RehydratedPrincipal(currentPrincipal, Map.of(
                "factoryId", "F-1", "tenantId", "F-1", "businessType", "FACTORY",
                "userId", 42L, "userRole", "factory_super_admin"));
        descriptor = new ToolDescriptor(
                "user_disable", ToolExecutor.ActionType.UPDATE, ToolExecutor.RiskLevel.HIGH,
                Set.of("hr:read_write"), Set.of(), Set.of(FactoryType.FACTORY),
                Set.of("user", "hr", "identity"), "2.0.0", false,
                ConfirmationPolicy.REQUIRED_FOR_EXECUTION, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.REQUIRED_FOR_EXECUTION, DataClassification.RESTRICTED,
                Set.of(ToolExecutionSource.AI_CHAT), ToolEgressPolicy.denyAll(),
                DescriptorProvenance.EXPLICIT);
    }

    @Test
    void executesExactlyOnceWithClaimedPersistedParametersAndTrustedContext() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        Lease lease = new Lease(
                "token-1", "claim-1", JsonNodeFactory.instance.objectNode().put("userId", 99));
        String digest = digest(command);
        when(confirmationLease.claim(command, currentPrincipal, digest))
                .thenReturn(Optional.of(lease));
        when(confirmationLease.resolve(lease, true)).thenReturn(true);
        when(executor.execute(any(ToolCall.class), any())).thenReturn(
                "{\"success\":true,\"data\":{\"changed\":true}}");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).execute(callCaptor.capture(), contextCaptor.capture());
        assertThat(callCaptor.getValue().getFunction().getArguments())
                .contains("99").doesNotContain("7");
        assertThat(contextCaptor.getValue())
                .containsEntry("factoryId", "F-1")
                .containsEntry("userId", 42L)
                .doesNotContainKey("parameters");
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.SUCCEEDED,
                ToolExecutionStatus.SUCCEEDED, GatewayResultCode.TOOL_SUCCEEDED);
    }

    @Test
    void previewUsesOnlyPreviewWithTrustedContextAndNoExecutionSecurityState() throws Exception {
        descriptor = previewDescriptor();
        ToolExecutionCommand command = previewCommand();
        stubPolicy(command);
        when(executor.preview(any(ToolCall.class), any())).thenReturn(
                "{\"success\":true,\"data\":{\"draft\":true}}");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(result.payload().path("data").path("draft").asBoolean()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).preview(any(ToolCall.class), contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("factoryId", "F-1")
                .containsEntry("userId", 42L)
                .doesNotContainKey("parameters");
        verify(executor, never()).execute(any(), any());
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(confirmationLease, never()).resolve(any(), anyBoolean());
        verify(ledgerService, never()).reserve(any());
        verify(ledgerService, never()).bindSecurityEvidence(
                anyString(), anyString(), anyString(), anyString());
        verify(ledgerService).completeAudit(
                "audit-1",
                ToolExecutionStatus.SUCCEEDED,
                GatewayResultCode.TOOL_PREVIEW_SUCCEEDED);
    }

    @Test
    void previewFalseFailsClosedWithoutExecuteOrReservation() throws Exception {
        descriptor = previewDescriptor();
        ToolExecutionCommand command = previewCommand();
        stubPolicy(command);
        when(executor.preview(any(), any())).thenReturn(
                "{\"success\":false,\"error\":\"safe validation message\"}");

        ToolExecutionResult failed = gateway.execute(command);

        assertThat(failed.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(failed.message()).isEqualTo("Tool preview failed");
        verify(executor, never()).execute(any(), any());
        verify(ledgerService, never()).reserve(any());
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(ledgerService).completeAudit(
                "audit-1", ToolExecutionStatus.FAILED, GatewayResultCode.TOOL_PREVIEW_FAILED);
    }

    @Test
    void previewMalformedResponseFailsClosedWithEmptyPayload() throws Exception {
        descriptor = previewDescriptor();
        ToolExecutionCommand command = previewCommand();
        stubPolicy(command);
        when(executor.preview(any(), any())).thenReturn("{\"data\":{}}");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.payload()).isEmpty();
        verify(executor, never()).execute(any(), any());
        verify(ledgerService, never()).reserve(any());
        verify(ledgerService).completeAudit(
                "audit-1", ToolExecutionStatus.FAILED, GatewayResultCode.TOOL_PREVIEW_FAILED);
    }

    @Test
    void previewExceptionReturnsFixedNonSensitiveFailure() throws Exception {
        descriptor = previewDescriptor();
        ToolExecutionCommand command = previewCommand();
        stubPolicy(command);
        when(executor.preview(any(), any()))
                .thenThrow(new IllegalStateException("database secret detail"));

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.message()).isEqualTo("Tool preview failed");
        assertThat(result.message()).doesNotContain("secret");
        verify(executor, never()).execute(any(), any());
        verify(ledgerService, never()).reserve(any());
    }

    @Test
    void completedExactBindingReturnsReplayWithoutClaimOrExecution() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        stubPolicy(command);
        when(ledgerService.reserve(any(Reservation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        ToolExecutionIdempotencyRecord existing = org.mockito.Mockito.mock(
                ToolExecutionIdempotencyRecord.class);
        when(existing.getBusinessType()).thenReturn("FACTORY");
        when(existing.getCommandDigest()).thenReturn(digest(command));
        when(existing.getConfirmationFingerprint()).thenReturn(
                ToolCommandDigest.persistentSecretFingerprint("token-1"));
        when(existing.getState()).thenReturn(ToolExecutionIdempotencyRecord.State.SUCCEEDED);
        when(existing.getOutcomeStatus()).thenReturn(ToolExecutionStatus.SUCCEEDED);
        when(ledgerService.findExisting(any(Reservation.class))).thenReturn(Optional.of(existing));

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.IDEMPOTENT_REPLAY);
        assertThat(result.replayed()).isTrue();
        assertThat(result.payload().path("originalStatus").asText()).isEqualTo("SUCCEEDED");
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(executor, never()).execute(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sameIdempotencyKeyWithDifferentDigestOrProofFingerprintIsDenied(
            boolean changeDigest) throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        stubPolicy(command);
        when(ledgerService.reserve(any(Reservation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        ToolExecutionIdempotencyRecord existing = org.mockito.Mockito.mock(
                ToolExecutionIdempotencyRecord.class);
        when(existing.getBusinessType()).thenReturn("FACTORY");
        when(existing.getCommandDigest()).thenReturn(
                changeDigest ? ToolCommandDigest.persistentSecretFingerprint("other-command")
                        : digest(command));
        if (!changeDigest) {
            when(existing.getConfirmationFingerprint()).thenReturn(
                    ToolCommandDigest.persistentSecretFingerprint("other-token"));
        }
        when(ledgerService.findExisting(any(Reservation.class))).thenReturn(Optional.of(existing));

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.DENIED);
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void inProgressExactBindingReturnsOutcomeUnknownAndNeverRetries() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        stubPolicy(command);
        when(ledgerService.reserve(any(Reservation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        ToolExecutionIdempotencyRecord existing = org.mockito.Mockito.mock(
                ToolExecutionIdempotencyRecord.class);
        when(existing.getBusinessType()).thenReturn("FACTORY");
        when(existing.getCommandDigest()).thenReturn(digest(command));
        when(existing.getConfirmationFingerprint()).thenReturn(
                ToolCommandDigest.persistentSecretFingerprint("token-1"));
        when(existing.getState()).thenReturn(ToolExecutionIdempotencyRecord.State.IN_PROGRESS);
        when(ledgerService.findExisting(any(Reservation.class))).thenReturn(Optional.of(existing));

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void genericWriteFailureIsInDoubtAndNotClassifiedAsSafeFailure() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        Lease lease = new Lease(
                "token-1", "claim-1", JsonNodeFactory.instance.objectNode().put("userId", 7));
        when(confirmationLease.claim(command, currentPrincipal, digest(command)))
                .thenReturn(Optional.of(lease));
        when(confirmationLease.resolve(lease, false)).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(
                "{\"success\":false,\"message\":\"sanitized\"}");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                ToolExecutionStatus.OUTCOME_UNKNOWN, GatewayResultCode.TOOL_OUTCOME_UNCERTAIN);
    }

    @Test
    void rejectedConfirmationClaimDurablyFailsReservationWithoutExecution() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        when(confirmationLease.claim(command, currentPrincipal, digest(command)))
                .thenReturn(Optional.empty());

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        verify(executor, never()).execute(any(), any());
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.FAILED,
                ToolExecutionStatus.CONFIRMATION_REQUIRED,
                GatewayResultCode.CONFIRMATION_REJECTED);
    }

    @Test
    void uncertainConfirmationClaimIsInDoubtWithoutExecution() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        when(confirmationLease.claim(command, currentPrincipal, digest(command)))
                .thenThrow(new IllegalStateException("claim acknowledgement lost"));

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        verify(executor, never()).execute(any(), any());
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                ToolExecutionStatus.OUTCOME_UNKNOWN,
                GatewayResultCode.CONFIRMATION_CLAIM_UNCERTAIN);
    }

    @Test
    void expiredConfirmationDurablyFailsReservationWithoutClaimOrExecution() throws Exception {
        ToolExecutionCommand initial = command(
                ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ConfirmationProof expired = new ConfirmationProof(
                "token-1", digest(initial), Instant.now().minusSeconds(1));
        ToolExecutionCommand command = withProofAndDeadline(
                initial, expired, Instant.now().plusSeconds(30));
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(executor, never()).execute(any(), any());
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.FAILED,
                ToolExecutionStatus.CONFIRMATION_REQUIRED,
                GatewayResultCode.CONFIRMATION_REJECTED);
    }

    @Test
    void successfulToolWithConfirmationResolveFailureIsInDoubt() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        Lease lease = new Lease(
                "token-1", "claim-1", JsonNodeFactory.instance.objectNode().put("userId", 7));
        when(confirmationLease.claim(command, currentPrincipal, digest(command)))
                .thenReturn(Optional.of(lease));
        when(confirmationLease.resolve(lease, true)).thenReturn(false);
        when(executor.execute(any(), any())).thenReturn("{\"success\":true}");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        verify(executor, times(1)).execute(any(), any());
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                ToolExecutionStatus.OUTCOME_UNKNOWN,
                GatewayResultCode.CONFIRMATION_RESOLUTION_UNCERTAIN);
    }

    @Test
    void successfulToolWithConfirmationResolveExceptionIsInDoubt() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        Lease lease = new Lease(
                "token-1", "claim-1", JsonNodeFactory.instance.objectNode().put("userId", 7));
        when(confirmationLease.claim(command, currentPrincipal, digest(command)))
                .thenReturn(Optional.of(lease));
        when(confirmationLease.resolve(lease, true))
                .thenThrow(new IllegalStateException("resolve acknowledgement lost"));
        when(executor.execute(any(), any())).thenReturn("{\"success\":true}");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        verify(executor, times(1)).execute(any(), any());
        verify(ledgerService).completeExecution(
                record.getId(), "audit-1", ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                ToolExecutionStatus.OUTCOME_UNKNOWN,
                GatewayResultCode.CONFIRMATION_RESOLUTION_UNCERTAIN);
    }

    @Test
    void successfulToolWithLedgerFinalizeFailureReturnsUnknownAndStillExecutesOnce() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionIdempotencyRecord record = record(command, "audit-1");
        stubExecutable(command, record);
        Lease lease = new Lease(
                "token-1", "claim-1", JsonNodeFactory.instance.objectNode().put("userId", 7));
        when(confirmationLease.claim(command, currentPrincipal, digest(command)))
                .thenReturn(Optional.of(lease));
        when(confirmationLease.resolve(lease, true)).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn("{\"success\":true}");
        doThrow(new IllegalStateException("commit acknowledgement lost"))
                .doNothing()
                .when(ledgerService).completeExecution(
                        anyString(), anyString(), any(), any(), any());

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        verify(executor, times(1)).execute(any(), any());
        verify(ledgerService, times(2)).completeExecution(
                anyString(), anyString(), any(), any(), any());
    }

    @Test
    void previewAndProofMismatchNeverCallExecutor() throws Exception {
        ToolExecutionCommand preview = command(ToolExecutionMode.PREVIEW, 7, "idem-1", "token-1");
        stubPolicy(preview);
        ToolExecutionResult previewResult = gateway.execute(preview);
        assertThat(previewResult.status()).isEqualTo(ToolExecutionStatus.PREVIEW_UNSUPPORTED);

        ToolExecutionCommand mismatched = command(ToolExecutionMode.EXECUTE, 7, "idem-2", "token-2");
        ConfirmationProof wrongProof = new ConfirmationProof(
                "token-2", "not-the-command-digest", Instant.now().plusSeconds(60));
        mismatched = new ToolExecutionCommand(
                mismatched.requestId(), mismatched.correlationId(), mismatched.traceId(),
                mismatched.toolName(), mismatched.expectedDescriptorVersion(),
                mismatched.parameters(), mismatched.principal(), mismatched.source(), mismatched.mode(),
                mismatched.idempotencyKey(), Optional.of(wrongProof), Optional.empty(),
                mismatched.deadline());
        when(principalPolicy.rehydrate(mismatched.principal())).thenReturn(Optional.of(current));
        when(ledgerService.beginAudit(mismatched, currentPrincipal)).thenReturn("audit-2");
        when(runtimeRegistry.resolve(mismatched, currentPrincipal))
                .thenReturn(Optional.of(new ResolvedTool(descriptor, executor)));

        ToolExecutionResult mismatchResult = gateway.execute(mismatched);
        assertThat(mismatchResult.status()).isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        verify(executor, never()).execute(any(), any());
        verify(ledgerService, never()).reserve(any());
    }

    @Test
    void principalRejectionStopsBeforeRuntimeClaimReservationAndExecution() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        when(principalPolicy.rehydrate(command.principal())).thenReturn(Optional.empty());
        when(ledgerService.beginAudit(command, command.principal())).thenReturn("audit-denied");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.DENIED);
        verify(runtimeRegistry, never()).resolve(any(), any());
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(ledgerService, never()).reserve(any());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void expiredDeadlineStopsBeforePolicyClaimReservationAndExecution() throws Exception {
        ToolExecutionCommand initial = command(
                ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        ToolExecutionCommand command = withProofAndDeadline(
                initial, initial.confirmationProof().orElseThrow(), Instant.now().minusSeconds(1));
        when(principalPolicy.rehydrate(command.principal())).thenReturn(Optional.of(current));
        when(ledgerService.beginAudit(command, currentPrincipal)).thenReturn("audit-timeout");

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.TIMEOUT);
        verify(runtimeRegistry, never()).resolve(any(), any());
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(ledgerService, never()).reserve(any());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void runtimePolicyDenialStopsBeforeClaimReservationAndExecution() throws Exception {
        ToolExecutionCommand command = command(ToolExecutionMode.EXECUTE, 7, "idem-1", "token-1");
        when(principalPolicy.rehydrate(command.principal())).thenReturn(Optional.of(current));
        when(ledgerService.beginAudit(command, currentPrincipal)).thenReturn("audit-policy-denied");
        when(runtimeRegistry.resolve(command, currentPrincipal))
                .thenReturn(Optional.empty());

        ToolExecutionResult result = gateway.execute(command);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.DENIED);
        verify(confirmationLease, never()).claim(any(), any(), anyString());
        verify(ledgerService, never()).reserve(any());
        verify(executor, never()).execute(any(), any());
    }

    private void stubPolicy(ToolExecutionCommand command) {
        when(principalPolicy.rehydrate(command.principal())).thenReturn(Optional.of(current));
        when(ledgerService.beginAudit(command, currentPrincipal)).thenReturn("audit-1");
        when(runtimeRegistry.resolve(command, currentPrincipal))
                .thenReturn(Optional.of(new ResolvedTool(descriptor, executor)));
    }

    private void stubExecutable(
            ToolExecutionCommand command,
            ToolExecutionIdempotencyRecord record) {
        stubPolicy(command);
        when(ledgerService.reserve(any(Reservation.class))).thenReturn(record);
    }

    private ToolExecutionCommand command(
            ToolExecutionMode mode,
            int targetUserId,
            String idempotencyKey,
            String token) {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode().put("userId", targetUserId);
        String digest = ToolCommandDigest.commandDigest(
                "F-1", 42L, "user_disable", "2.0.0", mode, parameters);
        return new ToolExecutionCommand(
                "request-" + idempotencyKey,
                "correlation-1",
                "trace-1",
                "user_disable",
                "2.0.0",
                parameters,
                currentPrincipal,
                ToolExecutionSource.AI_CHAT,
                mode,
                Optional.of(idempotencyKey),
                Optional.of(new ConfirmationProof(
                        token, digest, Instant.now().plusSeconds(60))),
                Optional.empty(),
                Instant.now().plusSeconds(30));
    }

    private ToolExecutionCommand previewCommand() {
        return new ToolExecutionCommand(
                "preview-request-1",
                "preview-correlation-1",
                "preview-trace-1",
                "canvas_work_process_catalog",
                "1.0.0",
                JsonNodeFactory.instance.objectNode()
                        .put("action", "create")
                        .put("processName", "腌制"),
                currentPrincipal,
                ToolExecutionSource.HTTP_CONTROLLER,
                ToolExecutionMode.PREVIEW,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Instant.now().plusSeconds(30));
    }

    private ToolDescriptor previewDescriptor() {
        return new ToolDescriptor(
                "canvas_work_process_catalog",
                ToolExecutor.ActionType.UPDATE,
                ToolExecutor.RiskLevel.MEDIUM,
                Set.of(),
                Set.of("factory_super_admin", "permission_admin"),
                Set.of(FactoryType.FACTORY, FactoryType.CENTRAL_KITCHEN),
                Set.of("canvas", "production", "work-process", "master-data"),
                "1.0.0",
                true,
                ConfirmationPolicy.NOT_REQUIRED,
                ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED,
                DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.HTTP_CONTROLLER),
                ToolEgressPolicy.denyAll(),
                DescriptorProvenance.EXPLICIT);
    }

    private ToolExecutionIdempotencyRecord record(
            ToolExecutionCommand command,
            String auditEventId) {
        return ToolExecutionIdempotencyRecord.reserve(
                "ledger-1",
                "F-1",
                "FACTORY",
                PrincipalType.USER,
                "42",
                "user_disable",
                "2.0.0",
                ToolCommandDigest.persistentSecretFingerprint(
                        command.idempotencyKey().orElseThrow()),
                digest(command),
                ToolCommandDigest.persistentSecretFingerprint(
                        command.confirmationProof().orElseThrow().proofToken()),
                auditEventId,
                LocalDateTime.now());
    }

    private static ToolExecutionCommand withProofAndDeadline(
            ToolExecutionCommand command,
            ConfirmationProof proof,
            Instant deadline) {
        return new ToolExecutionCommand(
                command.requestId(), command.correlationId(), command.traceId(),
                command.toolName(), command.expectedDescriptorVersion(), command.parameters(),
                command.principal(), command.source(), command.mode(), command.idempotencyKey(),
                Optional.of(proof), command.approvalProof(), deadline);
    }

    private static String digest(ToolExecutionCommand command) {
        return ToolCommandDigest.commandDigest(
                "F-1", 42L, command.toolName(), command.expectedDescriptorVersion(),
                command.mode(), command.parameters());
    }
}
