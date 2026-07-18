package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-backed race test: exactly one concurrent request may acquire the execution lease. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({PreviewTokenServiceImpl.class, PreviewTokenServiceConcurrencyTest.JacksonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PreviewTokenServiceConcurrencyTest {

    @Autowired PreviewTokenService service;
    @Autowired IntentPreviewTokenRepository repository;

    @Test
    void concurrentConfirmationsHaveOneWinnerAndOneLoser() throws Exception {
        repository.deleteAll();
        var token = service.createBoundToken(new BoundTokenRequest(
                "F-CONCURRENT", 77L, "runner", "ORDER_CREATE", "Create order",
                "order_create", "1.0.0", ToolExecutionMode.EXECUTE,
                "ORDER", "O-77", "CREATE", Map.of("amount", 7),
                Map.of(), Map.of(), 300));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimResult> first = executor.submit(() -> raceClaim(token.getToken(), ready, start));
            Future<ClaimResult> second = executor.submit(() -> raceClaim(token.getToken(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ClaimResult firstResult = first.get(10, TimeUnit.SECONDS);
            ClaimResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstResult, secondResult))
                    .filteredOn(ClaimResult::isSuccess)
                    .hasSize(1);
            assertThat(java.util.List.of(firstResult, secondResult))
                    .filteredOn(result -> !result.isSuccess())
                    .singleElement()
                    .satisfies(result -> assertThat(result.getMessage())
                            .containsAnyOf("其他确认请求", "已被处理"));

            ClaimResult winner = firstResult.isSuccess() ? firstResult : secondResult;
            assertThat(service.resolveClaim(token.getToken(), winner.getClaimId(), true, "done")).isTrue();
            assertThat(repository.findByToken(token.getToken())).get()
                    .extracting(com.cretas.aims.entity.intent.IntentPreviewToken::getStatus)
                    .isEqualTo(TokenStatus.CONFIRMED);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            repository.deleteAll();
        }
    }

    private ClaimResult raceClaim(String token, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("race start timed out");
        }
        return service.claimToken(token, "F-CONCURRENT", 77L);
    }

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
