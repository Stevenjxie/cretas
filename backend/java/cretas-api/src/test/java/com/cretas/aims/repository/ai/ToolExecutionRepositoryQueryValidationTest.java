package com.cretas.aims.repository.ai;

import com.cretas.aims.ai.tool.gateway.GatewayResultCode;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Hibernate startup and behavior gate for the gateway ledger queries and constraints. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(ToolExecutionLedgerService.class)
class ToolExecutionRepositoryQueryValidationTest {

    @Autowired ToolExecutionAuditEventRepository auditRepository;
    @Autowired ToolExecutionIdempotencyRepository idempotencyRepository;
    @Autowired ToolExecutionLedgerService ledgerService;

    @Test
    void requiresNewServiceTransactionsPersistAndAtomicallyCloseAuditAndReservation() {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F-SERVICE", "FACTORY", "42", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of("hr:read_write"), Set.of());
        ToolExecutionCommand command = new ToolExecutionCommand(
                "request-service", "correlation-service", "trace-service",
                "user_disable", "2.0.0",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("userId", 7),
                principal, ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.of("idem-service"), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(30));
        String auditId = ledgerService.beginAudit(command, principal);
        String commandDigest = hash("command-service");
        String confirmationFingerprint = hash("token-service");
        String idempotencyHash = hash("idem-service");
        ledgerService.bindSecurityEvidence(
                auditId, commandDigest, confirmationFingerprint, idempotencyHash);
        ToolExecutionIdempotencyRecord reservation = ledgerService.reserve(
                new ToolExecutionLedgerService.Reservation(
                        principal, "user_disable", "2.0.0", idempotencyHash,
                        commandDigest, confirmationFingerprint, auditId));

        ledgerService.completeExecution(
                reservation.getId(), auditId, ToolExecutionIdempotencyRecord.State.SUCCEEDED,
                ToolExecutionStatus.SUCCEEDED, GatewayResultCode.TOOL_SUCCEEDED);

        assertThat(idempotencyRepository.findById(reservation.getId())).get()
                .extracting(ToolExecutionIdempotencyRecord::getState)
                .isEqualTo(ToolExecutionIdempotencyRecord.State.SUCCEEDED);
        assertThat(auditRepository.findById(auditId)).get()
                .extracting(ToolExecutionAuditEvent::getState)
                .isEqualTo(ToolExecutionAuditEvent.State.COMPLETED);
    }

    @Test
    void realJpaContextBindsEvidenceAndConditionallyClosesBothLedgers() {
        LocalDateTime now = LocalDateTime.now();
        ToolExecutionAuditEvent audit = audit("audit-1", now);
        auditRepository.saveAndFlush(audit);
        assertThat(auditRepository.bindSecurityEvidence(
                "audit-1", hash("command"), hash("token"), hash("idem"),
                ToolExecutionAuditEvent.State.STARTED)).isEqualTo(1);

        ToolExecutionIdempotencyRecord record = record("ledger-1", "audit-1", now);
        idempotencyRepository.saveAndFlush(record);
        assertThat(idempotencyRepository
                .findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                        "F-JPA", PrincipalType.USER, "42", "user_disable", "2.0.0", hash("idem")))
                .isPresent();

        assertThat(idempotencyRepository.completeFromState(
                "ledger-1", ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                ToolExecutionIdempotencyRecord.State.SUCCEEDED,
                ToolExecutionStatus.SUCCEEDED, GatewayResultCode.TOOL_SUCCEEDED.name(), now))
                .isEqualTo(1);
        assertThat(idempotencyRepository.completeFromState(
                "ledger-1", ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                ToolExecutionIdempotencyRecord.State.FAILED,
                ToolExecutionStatus.FAILED, GatewayResultCode.TOOL_NEEDS_INFO.name(), now))
                .isZero();
        assertThat(auditRepository.completeStarted(
                "audit-1", ToolExecutionAuditEvent.State.STARTED,
                ToolExecutionAuditEvent.State.COMPLETED,
                ToolExecutionStatus.SUCCEEDED, GatewayResultCode.TOOL_SUCCEEDED.name(), now))
                .isEqualTo(1);
        assertThat(auditRepository.completeStarted(
                "audit-1", ToolExecutionAuditEvent.State.STARTED,
                ToolExecutionAuditEvent.State.COMPLETED,
                ToolExecutionStatus.FAILED, GatewayResultCode.TOOL_NEEDS_INFO.name(), now))
                .isZero();
    }

    @Test
    void replayLocatorUniqueConstraintRejectsSequentialDuplicateReservation() {
        LocalDateTime now = LocalDateTime.now();
        auditRepository.saveAndFlush(audit("audit-1", now));
        auditRepository.saveAndFlush(audit("audit-2", now));
        idempotencyRepository.saveAndFlush(record("ledger-1", "audit-1", now));

        assertThatThrownBy(() -> idempotencyRepository.saveAndFlush(
                record("ledger-2", "audit-2", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRequiresNewReservationsHaveExactlyOneWinner() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F-RACE-" + suffix, "FACTORY", "42", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of("hr:read_write"), Set.of());
        String auditIdOne = ledgerService.beginAudit(
                commandForAudit("request-race-1-" + suffix, principal), principal);
        String auditIdTwo = ledgerService.beginAudit(
                commandForAudit("request-race-2-" + suffix, principal), principal);
        String idempotencyHash = hash("idem-race-" + suffix);
        String commandDigest = hash("command-race-" + suffix);
        String confirmationFingerprint = hash("token-race-" + suffix);
        ToolExecutionLedgerService.Reservation reservationOne =
                new ToolExecutionLedgerService.Reservation(
                        principal, "user_disable", "2.0.0", idempotencyHash,
                        commandDigest, confirmationFingerprint, auditIdOne);
        ToolExecutionLedgerService.Reservation reservationTwo =
                new ToolExecutionLedgerService.Reservation(
                        principal, "user_disable", "2.0.0", idempotencyHash,
                        commandDigest, confirmationFingerprint, auditIdTwo);
        CyclicBarrier startGate = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(
                    () -> reserveAfterBarrier(startGate, reservationOne));
            Future<String> second = executor.submit(
                    () -> reserveAfterBarrier(startGate, reservationTwo));

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        } finally {
            executor.shutdownNow();
        }

        long matchingRows = idempotencyRepository.findAll().stream()
                .filter(row -> principal.tenantId().equals(row.getTenantId()))
                .filter(row -> idempotencyHash.equals(row.getIdempotencyKeyHash()))
                .count();
        assertThat(matchingRows).isEqualTo(1);
    }

    @Test
    void migrationContainsOnlyOneWaySecretBindingsAndStickyUncertainState() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261028_77__tool_execution_gateway_ledger.sql"));

        assertThat(sql)
                .contains("idempotency_key_hash VARCHAR(64) NOT NULL")
                .contains("confirmation_fingerprint VARCHAR(64) NOT NULL")
                .contains("command_digest VARCHAR(64) NOT NULL")
                .contains("'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'IN_DOUBT'")
                .contains("uk_tei_replay_locator")
                .doesNotContain("proof_token VARCHAR")
                .doesNotContain("idempotency_key VARCHAR")
                .doesNotContain("business_payload")
                .doesNotContain("parameters_json");
    }

    private static ToolExecutionAuditEvent audit(String id, LocalDateTime now) {
        return ToolExecutionAuditEvent.start(
                id, hash("request-" + id), hash("correlation-" + id), hash("trace-" + id),
                "F-JPA", "FACTORY", PrincipalType.USER, "42", "user_disable", "2.0.0",
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE, now);
    }

    private static ToolExecutionCommand commandForAudit(
            String requestId,
            ExecutionPrincipal principal) {
        return new ToolExecutionCommand(
                requestId, "correlation-" + requestId, "trace-" + requestId,
                "user_disable", "2.0.0",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("userId", 7),
                principal, ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.of("idem-" + requestId), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(30));
    }

    private String reserveAfterBarrier(
            CyclicBarrier startGate,
            ToolExecutionLedgerService.Reservation reservation) throws Exception {
        startGate.await(10, TimeUnit.SECONDS);
        try {
            ledgerService.reserve(reservation);
            return "SUCCESS";
        } catch (DataIntegrityViolationException conflict) {
            return "CONFLICT";
        }
    }

    private static ToolExecutionIdempotencyRecord record(
            String id,
            String auditId,
            LocalDateTime now) {
        return ToolExecutionIdempotencyRecord.reserve(
                id, "F-JPA", "FACTORY", PrincipalType.USER, "42", "user_disable", "2.0.0",
                hash("idem"), hash("command"), hash("token"), auditId, now);
    }

    private static String hash(String value) {
        return ToolCommandDigest.persistentSecretFingerprint(value);
    }
}
