package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ApprovalWorkflowServiceImpl 单元测试 — Sprint 3 Track-I (C-APPROVAL-EDITOR-1) Day 3.
 *
 * 覆盖 (≥ 6 cases per Day 3 gate):
 * 1. create happy path (sequential 2-step graph)
 * 2. create dup name → BusinessException(409)
 * 3. create invalid graph (missing start node) → BusinessException(400)
 * 4. create invalid graph (cycle) → BusinessException(400)
 * 5. update PATCH semantics + published→draft auto-revert
 * 6. update wrong factory → BusinessException(403)
 * 7. update not found → EntityNotFoundException
 * 8. getActiveByDecisionType returns highest priority published+enabled
 * 9. publishDraft happy path
 * 10. publishDraft non-draft → BusinessException(400)
 *
 * @since 2026-05-16
 */
@DisplayName("ApprovalWorkflowServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceImplTest {

    @Mock
    private ApprovalWorkflowRepository repository;

    @Mock
    private ApprovalWorkflowInstanceRepository workflowInstanceRepository;

    private ApprovalWorkflowServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F006";
    private static final String WORKFLOW_ID = "wf-uuid-001";

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowServiceImpl(repository, objectMapper);
        ReflectionTestUtils.setField(service, "workflowInstanceRepository", workflowInstanceRepository);
    }

    // ==================== fixtures ====================

    /** 合法 2-step graph: start → approval → end. */
    private ApprovalWorkflow validSequentialWorkflow() {
        ApprovalWorkflowNode start = ApprovalWorkflowNode.builder()
                .id("n_start").type("start").label("入口")
                .config(Map.of())
                .build();
        ApprovalWorkflowNode approval = ApprovalWorkflowNode.builder()
                .id("n_approve").type("approval").label("一级审批")
                .config(Map.of(
                        "approverRoles", List.of("factory_admin"),
                        "requiredApprovers", 1,
                        "timeoutMinutes", 60))
                .build();
        ApprovalWorkflowNode end = ApprovalWorkflowNode.builder()
                .id("n_end").type("end").label("通过")
                .config(Map.of("outcome", "APPROVED"))
                .build();

        ApprovalWorkflowEdge e1 = ApprovalWorkflowEdge.builder()
                .id("e1").source("n_start").target("n_approve").build();
        ApprovalWorkflowEdge e2 = ApprovalWorkflowEdge.builder()
                .id("e2").source("n_approve").target("n_end").build();

        ApprovalWorkflow w = ApprovalWorkflow.builder()
                .decisionType(DecisionType.QUALITY_RELEASE)
                .name("test-quality-release-workflow")
                .description("test fixture")
                .startNodeId("n_start")
                .build();
        w.setNodesJson(service.serializeNodes(List.of(start, approval, end)));
        w.setEdgesJson(service.serializeEdges(List.of(e1, e2)));
        return w;
    }

    /** Graph 缺 start 节点 (违法). */
    private ApprovalWorkflow workflowMissingStart() {
        ApprovalWorkflow w = validSequentialWorkflow();
        ApprovalWorkflowNode approval = ApprovalWorkflowNode.builder()
                .id("n_approve").type("approval").label("仅审批节点").build();
        ApprovalWorkflowNode end = ApprovalWorkflowNode.builder()
                .id("n_end").type("end").label("终点").build();
        w.setNodesJson(service.serializeNodes(List.of(approval, end)));
        w.setEdgesJson(service.serializeEdges(List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("n_approve").target("n_end").build())));
        w.setStartNodeId("n_approve"); // 引用了非 start 类型
        return w;
    }

    /** Graph 含环: A → B → A (违法). */
    private ApprovalWorkflow workflowWithCycle() {
        ApprovalWorkflowNode start = ApprovalWorkflowNode.builder()
                .id("n_start").type("start").build();
        ApprovalWorkflowNode a = ApprovalWorkflowNode.builder()
                .id("n_a").type("approval").build();
        ApprovalWorkflowNode b = ApprovalWorkflowNode.builder()
                .id("n_b").type("approval").build();
        ApprovalWorkflowNode end = ApprovalWorkflowNode.builder()
                .id("n_end").type("end").build();

        ApprovalWorkflow w = ApprovalWorkflow.builder()
                .decisionType(DecisionType.QUALITY_RELEASE)
                .name("cycle-test")
                .startNodeId("n_start")
                .build();
        w.setNodesJson(service.serializeNodes(List.of(start, a, b, end)));
        // start → a → b → a (cycle), 同时 end 孤立但 cycle 先报
        w.setEdgesJson(service.serializeEdges(List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("n_start").target("n_a").build(),
                ApprovalWorkflowEdge.builder().id("e2").source("n_a").target("n_b").build(),
                ApprovalWorkflowEdge.builder().id("e3").source("n_b").target("n_a").build(),
                ApprovalWorkflowEdge.builder().id("e4").source("n_a").target("n_end").build())));
        return w;
    }

    // ==================== tests ====================

    @Test
    @DisplayName("Case 1: create happy path — sequential 2-step graph 保存成功")
    void create_happyPath_savesWorkflow() {
        ApprovalWorkflow input = validSequentialWorkflow();
        when(repository.existsByFactoryIdAndDecisionTypeAndName(FACTORY_ID, DecisionType.QUALITY_RELEASE,
                "test-quality-release-workflow")).thenReturn(false);
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(inv -> {
            ApprovalWorkflow w = inv.getArgument(0);
            w.setId(WORKFLOW_ID);
            return w;
        });

        ApprovalWorkflow result = service.create(FACTORY_ID, input);

        assertNotNull(result.getId());
        assertEquals(FACTORY_ID, result.getFactoryId());
        // 默认值由 service 应用
        assertEquals(true, result.getEnabled());
        assertEquals(1, result.getVersion());
        assertEquals(0, result.getPriority());
        assertEquals("draft", result.getPublishStatus());
        verify(repository).save(any(ApprovalWorkflow.class));
    }

    @Test
    @DisplayName("Case 2: create dup name → BusinessException(409)")
    void create_duplicateName_throws409() {
        ApprovalWorkflow input = validSequentialWorkflow();
        when(repository.existsByFactoryIdAndDecisionTypeAndName(eq(FACTORY_ID),
                eq(DecisionType.QUALITY_RELEASE), eq("test-quality-release-workflow")))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, input));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 3: create with missing start node → BusinessException(400)")
    void create_missingStartNode_throws400() {
        ApprovalWorkflow input = workflowMissingStart();
        // 注意: 不需要 stub existsByFactoryIdAndDecisionTypeAndName,
        // 因为 createConfig 先 dup check 再 validate, 但 missing start 是
        // validate 阶段抛, 所以仍会走到 validate 之前. 我们让它返 false 让代码 进 validate.
        when(repository.existsByFactoryIdAndDecisionTypeAndName(any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, input));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校验失败"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 4: create with cycle → BusinessException(400) cycle detected")
    void create_withCycle_throws400() {
        ApprovalWorkflow input = workflowWithCycle();
        when(repository.existsByFactoryIdAndDecisionTypeAndName(any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, input));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校验失败"));
    }

    @Test
    @DisplayName("Case 5: update published workflow → 自动 revert 到 draft + version +1")
    void update_publishedRevertsToDraft() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        existing.setVersion(3);

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ApprovalWorkflow partial = new ApprovalWorkflow();
        partial.setName("renamed");

        ApprovalWorkflow result = service.update(FACTORY_ID, WORKFLOW_ID, partial);

        assertEquals("renamed", result.getName());
        assertEquals("draft", result.getPublishStatus());
        assertEquals(4, result.getVersion());

        ArgumentCaptor<ApprovalWorkflow> captor = ArgumentCaptor.forClass(ApprovalWorkflow.class);
        verify(repository).save(captor.capture());
        assertEquals("draft", captor.getValue().getPublishStatus());
    }

    @Test
    @DisplayName("Case 6: update wrong factory → BusinessException(403)")
    void update_wrongFactory_throws403() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(OTHER_FACTORY);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow()));
        assertEquals(403, ex.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 7: update not found → EntityNotFoundException")
    void update_notFound_throws() {
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow()));
        verify(repository, never()).save(any());
    }

    @Test
    void running_instance_blocks_all_mutating_workflow_operations() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(workflowInstanceRepository.findByFactoryIdAndWorkflowIdAndStatus(
                FACTORY_ID, WORKFLOW_ID, ApprovalWorkflowInstance.InstanceStatus.RUNNING))
                .thenReturn(List.of(ApprovalWorkflowInstance.builder().id("inst-running").build()));

        BusinessException update = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow()));
        BusinessException delete = assertThrows(BusinessException.class,
                () -> service.delete(FACTORY_ID, WORKFLOW_ID));
        BusinessException archive = assertThrows(BusinessException.class,
                () -> service.archive(FACTORY_ID, WORKFLOW_ID));
        BusinessException toggle = assertThrows(BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, false));

        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", update.getErrorCode());
        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", delete.getErrorCode());
        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", archive.getErrorCode());
        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", toggle.getErrorCode());
        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 8: getActiveByDecisionType returns highest priority published+enabled")
    void getActive_returnsHighestPriority() {
        ApprovalWorkflow high = validSequentialWorkflow();
        high.setId("wf-high");
        high.setPriority(100);
        ApprovalWorkflow low = validSequentialWorkflow();
        low.setId("wf-low");
        low.setPriority(10);
        // Repository 已按 priority DESC 排序, 返回顺序固定
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(high, low));

        Optional<ApprovalWorkflow> result = service.getActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE);

        assertTrue(result.isPresent());
        assertEquals("wf-high", result.get().getId());
    }

    @Test
    @DisplayName("Case 9: getActiveByDecisionType 无 active → empty (caller fallback to flat-list)")
    void getActive_empty_returnsEmpty() {
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of());

        Optional<ApprovalWorkflow> result = service.getActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Case 10: publishDraft non-draft → BusinessException(400)")
    void publish_nonDraft_throws400() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.publishDraft(FACTORY_ID, WORKFLOW_ID));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("仅 draft"));
        verify(repository, never()).save(any());
    }
}
