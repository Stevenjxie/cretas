package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.workflow.OaActionIdempotencyLedger;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workflow.OaActionIdempotencyLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class OaActionIdempotencyServiceTest {

    @Mock private OaActionIdempotencyLedgerRepository repository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private OaActionIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new OaActionIdempotencyService(repository, entityManager, new ObjectMapper());
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(null);
    }

    @Test
    void first_success_is_persisted_as_completed() {
        when(repository.findByFactoryIdAndInstanceIdAndIdempotencyKey("F006", "inst-1", "key-1"))
                .thenReturn(Optional.empty());
        OaActionIdempotencyService.ActionContext context = context("key-1", "通过");

        Map<String, Object> result = service.execute(context, () -> Map.of("workflowStatus", "APPROVED"));

        assertEquals("APPROVED", result.get("workflowStatus"));
        InOrder ordering = inOrder(entityManager, query, repository);
        ordering.verify(entityManager).createNativeQuery(anyString());
        ordering.verify(query).setParameter(anyString(), any());
        ordering.verify(query).getSingleResult();
        ordering.verify(repository).findByFactoryIdAndInstanceIdAndIdempotencyKey(
                "F006", "inst-1", "key-1");
        ArgumentCaptor<OaActionIdempotencyLedger> captor =
                ArgumentCaptor.forClass(OaActionIdempotencyLedger.class);
        verify(repository).saveAndFlush(captor.capture());
        verify(repository).save(captor.capture());
        assertEquals("COMPLETED", captor.getAllValues().get(1).getCompletionState());
        assertEquals("APPROVED", captor.getAllValues().get(1).getResultJson().get("workflowStatus"));
    }

    @Test
    void same_key_same_payload_returns_stored_result_without_reexecuting() {
        OaActionIdempotencyService.ActionContext context = context("key-2", "通过");
        OaActionIdempotencyLedger completed = completedLedger(context, "APPROVED");
        when(repository.findByFactoryIdAndInstanceIdAndIdempotencyKey("F006", "inst-1", "key-2"))
                .thenReturn(Optional.of(completed));
        AtomicInteger executions = new AtomicInteger();

        Map<String, Object> result = service.execute(context, () -> {
            executions.incrementAndGet();
            return Map.of();
        });

        assertEquals(0, executions.get());
        assertEquals("APPROVED", result.get("workflowStatus"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void same_key_different_payload_is_rejected() {
        OaActionIdempotencyService.ActionContext original = context("key-3", "通过");
        OaActionIdempotencyLedger completed = completedLedger(original, "APPROVED");
        when(repository.findByFactoryIdAndInstanceIdAndIdempotencyKey("F006", "inst-1", "key-3"))
                .thenReturn(Optional.of(completed));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.execute(context("key-3", "不同备注"), Map::of));

        assertEquals("OA_IDEMPOTENCY_KEY_REUSED", error.getErrorCode());
    }

    @Test
    void failed_action_never_marks_ledger_completed() {
        when(repository.findByFactoryIdAndInstanceIdAndIdempotencyKey("F006", "inst-1", "key-4"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.execute(context("key-4", "通过"), () -> {
                    throw new IllegalStateException("domain failure");
                }));

        verify(repository).saveAndFlush(any(OaActionIdempotencyLedger.class));
        verify(repository, never()).save(any(OaActionIdempotencyLedger.class));
    }

    private OaActionIdempotencyService.ActionContext context(String key, String notes) {
        return new OaActionIdempotencyService.ActionContext(
                "F006", "inst-1", key, "finance", "APPROVE", 108L,
                "finance_manager", notes);
    }

    private OaActionIdempotencyLedger completedLedger(
            OaActionIdempotencyService.ActionContext context, String status) {
        OaActionIdempotencyLedger ledger = OaActionIdempotencyLedger.builder()
                .id("ledger-1")
                .factoryId(context.factoryId())
                .instanceId(context.instanceId())
                .idempotencyKey(context.idempotencyKey())
                .expectedNodeId(context.expectedNodeId())
                .action(context.action())
                .operatorId(context.operatorId())
                .operatorRole(context.operatorRole())
                .completionState("COMPLETED")
                .resultJson(Map.of("workflowStatus", status))
                .build();
        String fingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", context);
        ledger.setRequestFingerprint(fingerprint);
        return ledger;
    }
}
