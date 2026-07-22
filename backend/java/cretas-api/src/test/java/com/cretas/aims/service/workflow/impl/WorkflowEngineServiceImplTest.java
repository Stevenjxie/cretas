package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowEngineServiceImpl} — Phase 1 B.4 DAG executor.
 *
 * <p>Covers acceptance criteria scenarios:
 * <ol>
 *   <li>{@link #happy_path_2_step_approval} — start → approve(node1) → approve(node2) → APPROVED</li>
 *   <li>{@link #reject_terminates} — start → reject → REJECTED + currentNodeIds empty</li>
 *   <li>{@link #parallel_join_all} — start → parallel → 2 branches → join(ALL) → end → APPROVED</li>
 *   <li>{@link #cancel_writes_history} — cancel writes CANCEL history + status=CANCELLED</li>
 * </ol>
 *
 * @since 2026-05-18 (B.4)
 */
@DisplayName("WorkflowEngineServiceImpl B.4 DAG executor")
@ExtendWith(MockitoExtension.class)
class WorkflowEngineServiceImplTest {

    @Mock private ApprovalWorkflowInstanceRepository instanceRepository;
    @Mock private ApprovalHistoryRepository historyRepository;
    @Mock private ApprovalWorkflowService workflowService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();
    private WorkflowEngineServiceImpl engine;

    private static final String FACTORY_ID = "F001";
    private static final String WORKFLOW_ID = "wf-test-001";
    private static final Long INITIATOR_ID = 100L;
    private static final Long APPROVER_ID = 200L;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngineServiceImpl(
                instanceRepository,
                historyRepository,
                workflowService,
                spelEvaluator,
                /* redisTemplate = */ null);

        // Default: history rows empty (each test may override via specific .thenReturn).
        lenient().when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                anyString(), anyString())).thenReturn(List.of());

        // history save returns the input (with synthetic id) — caller doesn't use return value here,
        // but we do want it to not NPE.
        lenient().when(historyRepository.save(any(ApprovalHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // instance save returns the input — engine reads back the saved instance.
        lenient().when(instanceRepository.save(any(ApprovalWorkflowInstance.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // workflowService delegate to real JSON serde so tests build POJOs.
        lenient().when(workflowService.deserializeNodes(anyString()))
                .thenAnswer(inv -> deserialize(inv.getArgument(0), ApprovalWorkflowNode.class));
        lenient().when(workflowService.deserializeEdges(anyString()))
                .thenAnswer(inv -> deserialize(inv.getArgument(0), ApprovalWorkflowEdge.class));
        lenient().when(workflowService.serializeNodes(any()))
                .thenAnswer(inv -> serialize(inv.getArgument(0)));
        lenient().when(workflowService.serializeEdges(any()))
                .thenAnswer(inv -> serialize(inv.getArgument(0)));
    }

    // ==================== fixtures ====================

    private ApprovalWorkflowNode node(String id, String type, Map<String, Object> config) {
        return ApprovalWorkflowNode.builder()
                .id(id).type(type).label(id)
                .config(config == null ? Map.of() : config)
                .build();
    }

    private ApprovalWorkflowEdge edge(String id, String source, String target,
                                       String condition, Integer priority, String label) {
        return ApprovalWorkflowEdge.builder()
                .id(id).source(source).target(target)
                .condition(condition)
                .priority(priority == null ? 0 : priority)
                .label(label)
                .build();
    }

    private ApprovalWorkflow buildWorkflow(List<ApprovalWorkflowNode> nodes,
                                            List<ApprovalWorkflowEdge> edges,
                                            String startNodeId) {
        return buildWorkflow(DecisionType.PURCHASE_ORDER_APPROVAL, nodes, edges, startNodeId);
    }

    private ApprovalWorkflow buildWorkflow(DecisionType decisionType,
                                            List<ApprovalWorkflowNode> nodes,
                                            List<ApprovalWorkflowEdge> edges,
                                            String startNodeId) {
        ApprovalWorkflow w = ApprovalWorkflow.builder()
                .decisionType(decisionType)
                .name("test-" + UUID.randomUUID())
                .startNodeId(startNodeId)
                .build();
        w.setId(WORKFLOW_ID);
        w.setFactoryId(FACTORY_ID);
        try {
            w.setNodesJson(objectMapper.writeValueAsString(nodes));
            w.setEdgesJson(objectMapper.writeValueAsString(edges));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        // Mock service lookups.
        lenient().when(workflowService.getActiveByDecisionType(FACTORY_ID, decisionType))
                .thenReturn(Optional.of(w));
        lenient().when(workflowService.getById(FACTORY_ID, WORKFLOW_ID)).thenReturn(Optional.of(w));
        return w;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List deserialize(String json, Class<?> elementType) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String serialize(List<?> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** Wire instanceRepository so that findById returns the *currently-saved* instance. */
    private void wireInstanceLookup() {
        // capture the most recent save.
        ApprovalWorkflowInstance[] saved = new ApprovalWorkflowInstance[1];
        lenient().when(instanceRepository.save(any(ApprovalWorkflowInstance.class)))
                .thenAnswer(inv -> {
                    saved[0] = inv.getArgument(0);
                    return saved[0];
                });
        lenient().when(instanceRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(saved[0]));
    }

    private Map<String, Object> approvalCfg() {
        return Map.of("approverRoles", List.of("factory_admin"), "requiredApprovers", 1);
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("F006 sales threshold: <=5000 auto-completes OA, >5000 creates finance task")
    void sales_amount_threshold_routes_inside_persisted_oa() {
        buildWorkflow(
                DecisionType.SALES_ORDER_APPROVAL,
                List.of(
                        node("start", "start", null),
                        node("external", "condition", null),
                        node("amount", "condition", null),
                        node("finance", "approval", Map.of(
                                "approverRoles", List.of("finance_manager"),
                                "requiredApprovers", 1)),
                        node("end_auto", "end", Map.of("outcome", "APPROVED")),
                        node("end_approved", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "external", null, 0, null),
                        edge("e2", "external", "end_auto", "#externalOrder == true", 0, "外部渠道"),
                        edge("e3", "external", "amount", null, 99, "DEFAULT"),
                        edge("e4", "amount", "finance", "#amount > 5000", 0, "超过5000元"),
                        edge("e5", "amount", "end_auto", null, 99, "DEFAULT"),
                        edge("e6", "finance", "end_approved", null, 0, "通过")),
                "start");

        ApprovalWorkflowInstance lowAmount = engine.startWorkflow(
                FACTORY_ID, "SALES_ORDER", "SO-LOW", Map.of("amount", 5000, "externalOrder", false), INITIATOR_ID);
        ApprovalWorkflowInstance highAmount = engine.startWorkflow(
                FACTORY_ID, "SALES_ORDER", "SO-HIGH", Map.of("amount", 5000.01, "externalOrder", false), INITIATOR_ID);
        ApprovalWorkflowInstance externalHighAmount = engine.startWorkflow(
                FACTORY_ID,
                "SALES_ORDER",
                "SO-EXTERNAL-HIGH",
                Map.of("amount", 5000.01, "externalOrder", true),
                INITIATOR_ID);

        assertEquals(InstanceStatus.APPROVED, lowAmount.getStatus());
        assertTrue(lowAmount.getCurrentNodeIds().isEmpty());
        assertEquals(InstanceStatus.RUNNING, highAmount.getStatus());
        assertEquals(List.of("finance"), highAmount.getCurrentNodeIds());
        assertEquals(InstanceStatus.APPROVED, externalHighAmount.getStatus());
        assertTrue(externalHighAmount.getCurrentNodeIds().isEmpty());
    }

    @Test
    @DisplayName("happy_path_2_step_approval: start → approve(node1) → approve(node2) → APPROVED")
    void happy_path_2_step_approval() {
        wireInstanceLookup();
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("approve2", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "approve2", null, 0, null),
                        edge("e3", "approve2", "end_ok", null, 0, null)),
                "start");

        ApprovalWorkflowInstance inst = engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", "PO-001", Map.of("amount", 5000), INITIATOR_ID);

        assertEquals(InstanceStatus.RUNNING, inst.getStatus());
        assertEquals(List.of("approve1"), inst.getCurrentNodeIds());

        // First approve → node2 should be active.
        ApprovalWorkflowInstance afterFirst = engine.transitionNode(
                inst.getId(), APPROVER_ID, "factory_admin", HistoryAction.APPROVE, "step1 ok");
        assertEquals(InstanceStatus.RUNNING, afterFirst.getStatus());
        assertEquals(List.of("approve2"), afterFirst.getCurrentNodeIds());

        // Second approve → APPROVED terminal.
        ApprovalWorkflowInstance afterSecond = engine.transitionNode(
                inst.getId(), APPROVER_ID, "factory_admin", HistoryAction.APPROVE, "step2 ok");
        assertEquals(InstanceStatus.APPROVED, afterSecond.getStatus());
        assertNotNull(afterSecond.getCompletedAt());
        assertTrue(afterSecond.getCurrentNodeIds().isEmpty());

        // Verify history rows: START + 2 APPROVE + 1 AUTO_TRANSITION (end node).
        ArgumentCaptor<ApprovalHistory> historyCaptor = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository, atLeast(4)).save(historyCaptor.capture());
        List<HistoryAction> actions = historyCaptor.getAllValues().stream()
                .map(ApprovalHistory::getAction)
                .toList();
        assertTrue(actions.contains(HistoryAction.START));
        assertEquals(2, actions.stream().filter(a -> a == HistoryAction.APPROVE).count());
        assertTrue(actions.contains(HistoryAction.AUTO_TRANSITION),
                "end node should write AUTO_TRANSITION history");
    }

    @Test
    @DisplayName("reject_terminates: start → reject → REJECTED, currentNodeIds=[]")
    void reject_terminates() {
        wireInstanceLookup();
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "end_ok", null, 0, null)),
                "start");

        ApprovalWorkflowInstance inst = engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", "PO-002", Map.of(), INITIATOR_ID);
        assertEquals(List.of("approve1"), inst.getCurrentNodeIds());

        ApprovalWorkflowInstance afterReject = engine.transitionNode(
                inst.getId(), APPROVER_ID, "factory_admin", HistoryAction.REJECT, "not ok");

        assertEquals(InstanceStatus.REJECTED, afterReject.getStatus());
        assertTrue(afterReject.getCurrentNodeIds().isEmpty());
        assertNotNull(afterReject.getCompletedAt());

        // Verify REJECT history written.
        ArgumentCaptor<ApprovalHistory> cap = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository, atLeast(2)).save(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(h -> h.getAction() == HistoryAction.REJECT),
                "REJECT history row should be persisted");
    }

    @Test
    @DisplayName("parallel_join_all: start → parallel → 2 branches → join(ALL) → end → APPROVED")
    void parallel_join_all() {
        wireInstanceLookup();
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("fan", "parallel", null),
                        node("branch_a", "approval", approvalCfg()),
                        node("branch_b", "approval", approvalCfg()),
                        node("merge", "join", Map.of("mode", "ALL")),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "fan", null, 0, null),
                        edge("e2", "fan", "branch_a", null, 0, null),
                        edge("e3", "fan", "branch_b", null, 1, null),
                        edge("e4", "branch_a", "merge", null, 0, null),
                        edge("e5", "branch_b", "merge", null, 0, null),
                        edge("e6", "merge", "end_ok", null, 0, null)),
                "start");

        ApprovalWorkflowInstance inst = engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", "PO-003", Map.of(), INITIATOR_ID);

        // Both branches should be active after parallel fan-out.
        assertEquals(InstanceStatus.RUNNING, inst.getStatus());
        assertEquals(2, inst.getCurrentNodeIds().size());
        assertTrue(inst.getCurrentNodeIds().contains("branch_a"));
        assertTrue(inst.getCurrentNodeIds().contains("branch_b"));

        // After first approve, only the other branch should still be active (join not yet satisfied).
        // We need historyRepository to surface our APPROVE for branch_a so join's ALL check works.
        // Use a stateful answer for findByFactoryIdAndInstanceIdOrderByCreatedAtAsc.
        List<ApprovalHistory> historyBuffer = new ArrayList<>();
        when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(anyString(), anyString()))
                .thenReturn(historyBuffer);
        when(historyRepository.save(any(ApprovalHistory.class))).thenAnswer(inv -> {
            ApprovalHistory h = inv.getArgument(0);
            historyBuffer.add(h);
            return h;
        });

        // First branch transitions — currentNodeIds should drop branch_a (the "from" node).
        // The currentNodeIds is List, transitionNode takes head as fromNode. With branch_a at index 0,
        // approve removes branch_a; join only has 1 of 2 upstream completed → not yet satisfied.
        ApprovalWorkflowInstance after1 = engine.transitionNode(
                inst.getId(), APPROVER_ID, "factory_admin", HistoryAction.APPROVE, "a ok");

        // branch_a should be removed; branch_b should still be active.
        assertEquals(InstanceStatus.RUNNING, after1.getStatus());
        assertTrue(after1.getCurrentNodeIds().contains("branch_b"),
                "branch_b should still be active after branch_a approves");
        assertFalse(after1.getCurrentNodeIds().contains("branch_a"),
                "branch_a should be removed after approval");

        // Second branch approve → join satisfied (ALL), advance to end_ok → APPROVED.
        ApprovalWorkflowInstance after2 = engine.transitionNode(
                inst.getId(), APPROVER_ID, "factory_admin", HistoryAction.APPROVE, "b ok");

        assertEquals(InstanceStatus.APPROVED, after2.getStatus());
        assertTrue(after2.getCurrentNodeIds().isEmpty());
        assertNotNull(after2.getCompletedAt());
    }

    @Test
    @DisplayName("cancel_writes_history: cancel → CANCELLED + CANCEL history row")
    void cancel_writes_history() {
        wireInstanceLookup();
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "end_ok", null, 0, null)),
                "start");

        ApprovalWorkflowInstance inst = engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", "PO-004", Map.of(), INITIATOR_ID);
        assertEquals(InstanceStatus.RUNNING, inst.getStatus());

        ApprovalWorkflowInstance cancelled = engine.cancel(inst.getId(), 999L, "user changed mind");

        assertEquals(InstanceStatus.CANCELLED, cancelled.getStatus());
        assertTrue(cancelled.getCurrentNodeIds().isEmpty());
        assertNotNull(cancelled.getCompletedAt());

        // Verify CANCEL history row written.
        ArgumentCaptor<ApprovalHistory> cap = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository, atLeast(2)).save(cap.capture());
        ApprovalHistory cancelRow = cap.getAllValues().stream()
                .filter(h -> h.getAction() == HistoryAction.CANCEL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("CANCEL history row not found"));
        assertEquals("user changed mind", cancelRow.getNotes());
        assertEquals(999L, cancelRow.getActorId());
        assertEquals(FACTORY_ID, cancelRow.getFactoryId());
        assertEquals(inst.getId(), cancelRow.getInstanceId());

        // Re-cancel should throw — already terminal.
        BusinessException ex = assertThrows(BusinessException.class,
                () -> engine.cancel(inst.getId(), 999L, "again"));
        assertTrue(ex.getMessage().contains("CANCELLED") || ex.getMessage().contains("已结束"));
    }

    // ==================== Phase 1 P1 follow-up — issue #11 / #12 ====================

    @Test
    @DisplayName("concurrent_approve_throws_409: 第二个 reviewer 触发 OptimisticLockingFailure → BusinessException 409")
    void concurrent_approve_throws_409() {
        wireInstanceLookup();
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "end_ok", null, 0, null)),
                "start");

        ApprovalWorkflowInstance inst = engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", "PO-CONCURRENT", Map.of(), INITIATOR_ID);
        assertEquals(InstanceStatus.RUNNING, inst.getStatus());

        // Simulate second concurrent UPDATE — first save succeeds (wireInstanceLookup),
        // second save throws OptimisticLockingFailure (Hibernate would do this on
        // stale @Version mismatch). Stub the *next* save call to blow up.
        reset(instanceRepository);
        when(instanceRepository.findById(anyString())).thenReturn(Optional.of(inst));
        when(instanceRepository.save(any(ApprovalWorkflowInstance.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        ApprovalWorkflowInstance.class, inst.getId()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engine.transitionNode(inst.getId(), APPROVER_ID, "factory_admin",
                        HistoryAction.APPROVE, "concurrent approve"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("并发审批冲突") || ex.getMessage().contains("刷新"),
                "expect 409 message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("concurrent_cancel_throws_409: 并发 cancel 触发 OptimisticLockingFailure → BusinessException 409")
    void concurrent_cancel_throws_409() {
        wireInstanceLookup();
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "end_ok", null, 0, null)),
                "start");

        ApprovalWorkflowInstance inst = engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", "PO-CONCURRENT-CANCEL", Map.of(), INITIATOR_ID);

        // Reset + stub second save to throw OptimisticLockingFailure
        reset(instanceRepository);
        when(instanceRepository.findById(anyString())).thenReturn(Optional.of(inst));
        when(instanceRepository.save(any(ApprovalWorkflowInstance.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        ApprovalWorkflowInstance.class, inst.getId()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engine.cancel(inst.getId(), 999L, "concurrent cancel"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("并发审批冲突") || ex.getMessage().contains("刷新"),
                "expect 409 message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Redis cache is written only after the surrounding transaction commits")
    @SuppressWarnings("unchecked")
    void redis_cache_waits_for_transaction_commit() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService, spelEvaluator, redis);
        buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "end_ok", null, 0, null)),
                "start");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            ApprovalWorkflowInstance instance = engine.startWorkflow(
                    FACTORY_ID, "PURCHASE_ORDER", "PO-COMMIT-CACHE", Map.of(), INITIATOR_ID);

            verifyNoInteractions(values);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            verify(values).set(anyString(), same(instance));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("startWorkflow_unique_violation_throws_409: partial unique index 冲突 → BusinessException 409")
    void startWorkflow_unique_violation_throws_409() {
        // 模拟 partial-unique-index uq_aw_instances_active 命中:
        // INSERT 时 Postgres 23505 violation → Spring 转 DataIntegrityViolationException.
        ApprovalWorkflow w = buildWorkflow(
                List.of(
                        node("start", "start", null),
                        node("approve1", "approval", approvalCfg()),
                        node("end_ok", "end", Map.of("outcome", "APPROVED"))),
                List.of(
                        edge("e1", "start", "approve1", null, 0, null),
                        edge("e2", "approve1", "end_ok", null, 0, null)),
                "start");

        when(instanceRepository.save(any(ApprovalWorkflowInstance.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_aw_instances_active\""));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engine.startWorkflow(FACTORY_ID, "PURCHASE_ORDER", "PO-DUP",
                        Map.of(), INITIATOR_ID));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("审批流程已存在") || ex.getMessage().contains("不能重复启动"),
                "expect 409 message, got: " + ex.getMessage());
    }
}
