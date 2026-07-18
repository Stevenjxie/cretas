package com.cretas.aims.repository.intent;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and behavior gate for atomic confirmation JPQL. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class IntentPreviewTokenRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired IntentPreviewTokenRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void atomicClaimAndLeaseResolutionQueriesEnforceEveryPredicate() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        IntentPreviewToken token = persistBound("token-atomic", now.plusMinutes(5));

        assertThat(repository.claimForExecution(
                token.getToken(), "OTHER", token.getTenantId(), token.getUserId(),
                token.getCommandDigest(), token.getParametersHash(), token.getToolName(),
                token.getDescriptorVersion(), token.getExecutionMode(), "claim-wrong", now, now,
                TokenStatus.PENDING, TokenStatus.EXECUTING)).isZero();

        assertThat(repository.claimForExecution(
                token.getToken(), token.getFactoryId(), token.getTenantId(), token.getUserId(),
                token.getCommandDigest(), token.getParametersHash(), token.getToolName(),
                token.getDescriptorVersion(), token.getExecutionMode(), "claim-owner", now, now,
                TokenStatus.PENDING, TokenStatus.EXECUTING)).isEqualTo(1);
        assertThat(repository.claimForExecution(
                token.getToken(), token.getFactoryId(), token.getTenantId(), token.getUserId(),
                token.getCommandDigest(), token.getParametersHash(), token.getToolName(),
                token.getDescriptorVersion(), token.getExecutionMode(), "claim-second", now, now,
                TokenStatus.PENDING, TokenStatus.EXECUTING)).isZero();

        assertThat(repository.findByTokenAndClaimIdAndStatus(
                token.getToken(), "claim-owner", TokenStatus.EXECUTING))
                .isPresent();
        assertThat(repository.resolveClaim(
                token.getToken(), "stale-owner", TokenStatus.EXECUTING,
                TokenStatus.FAILED, now, "stale")).isZero();
        assertThat(repository.resolveClaim(
                token.getToken(), "claim-owner", TokenStatus.EXECUTING,
                TokenStatus.CONFIRMED, now, "done")).isEqualTo(1);
        assertThat(repository.resolveClaim(
                token.getToken(), "claim-owner", TokenStatus.EXECUTING,
                TokenStatus.FAILED, now, "late overwrite")).isZero();

        assertThat(repository.findByToken(token.getToken()))
                .get()
                .extracting(IntentPreviewToken::getStatus)
                .isEqualTo(TokenStatus.CONFIRMED);
    }

    @Test
    void expiryAndCancellationQueriesAreIdentityBoundAndConditional() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        IntentPreviewToken expired = persistBound("token-expired", now.minusSeconds(1));
        IntentPreviewToken cancellable = persistBound("token-cancel", now.plusMinutes(5));

        assertThat(repository.expirePendingToken(
                expired.getToken(), expired.getFactoryId(), expired.getUserId(), now,
                TokenStatus.PENDING, TokenStatus.EXPIRED)).isEqualTo(1);
        assertThat(repository.cancelPendingToken(
                cancellable.getToken(), "OTHER", cancellable.getUserId(), "wrong factory", now,
                TokenStatus.PENDING, TokenStatus.CANCELLED)).isZero();
        assertThat(repository.cancelPendingToken(
                cancellable.getToken(), cancellable.getFactoryId(), cancellable.getUserId(),
                "cancelled", now, TokenStatus.PENDING, TokenStatus.CANCELLED)).isEqualTo(1);

        assertThat(repository.findByToken(expired.getToken())).get()
                .extracting(IntentPreviewToken::getStatus).isEqualTo(TokenStatus.EXPIRED);
        assertThat(repository.findByToken(cancellable.getToken())).get()
                .extracting(IntentPreviewToken::getStatus).isEqualTo(TokenStatus.CANCELLED);
    }

    @Test
    void migrationSupportsFreshDatabaseAndRemovesLegacyStatusConstraintSafely() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261028_75__intent_preview_token_atomic_confirmation.sql"));

        assertThat(sql.indexOf("CREATE TABLE IF NOT EXISTS intent_preview_tokens"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(sql.indexOf("ALTER TABLE intent_preview_tokens"));
        assertThat(sql)
                .contains("conrelid = 'intent_preview_tokens'::regclass")
                .contains("DROP TRIGGER IF EXISTS intent_preview_tokens_update_timestamp ON intent_preview_tokens")
                .contains("pg_get_constraintdef(oid) NOT LIKE '%EXECUTING%'")
                .contains("DROP CONSTRAINT %I")
                .contains("status IN ('PENDING', 'EXECUTING', 'CONFIRMED', 'FAILED', 'CANCELLED', 'EXPIRED')");
    }

    private IntentPreviewToken persistBound(String tokenValue, LocalDateTime expiresAt) throws Exception {
        JsonNode parameters = objectMapper.readTree("{\"amount\":1.00,\"enabled\":true}");
        String parametersHash = ToolCommandDigest.parametersHash(parameters);
        String commandDigest = ToolCommandDigest.commandDigest(
                "F-JPA-CONFIRM", 42L, "order_create", "1.2.3",
                ToolExecutionMode.EXECUTE, parameters);
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(tokenValue)
                .factoryId("F-JPA-CONFIRM")
                .tenantId("F-JPA-CONFIRM")
                .userId(42L)
                .intentCode("ORDER_CREATE")
                .toolName("order_create")
                .descriptorVersion("1.2.3")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash(parametersHash)
                .commandDigest(commandDigest)
                .previewData(objectMapper.writeValueAsString(parameters))
                .status(TokenStatus.PENDING)
                .expiresAt(expiresAt)
                .build();
        entityManager.persistAndFlush(token);
        entityManager.clear();
        return token;
    }
}
