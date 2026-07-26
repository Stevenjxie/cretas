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
import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @DisplayName("Case 5: update published workflow → reject and require clone")
    void update_publishedWorkflowIsImmutable() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        existing.setVersion(3);

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        ApprovalWorkflow partial = new ApprovalWorkflow();
        partial.setName("renamed");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, partial));

        assertEquals(409, exception.getCode());
        assertEquals("OA_WORKFLOW_IMMUTABLE", exception.getErrorCode());
        assertEquals("published", existing.getPublishStatus());
        assertEquals(3, existing.getVersion());
        verify(repository, never()).save(any());
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
    void runningInstanceBlocksDefinitionMutationButAllowsDisableAndArchive() {
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
        when(repository.save(existing)).thenReturn(existing);
        ApprovalWorkflow archived = service.archive(FACTORY_ID, WORKFLOW_ID);
        ApprovalWorkflow disabled = service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, false);

        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", update.getErrorCode());
        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", delete.getErrorCode());
        assertEquals("archived", archived.getPublishStatus());
        assertFalse(disabled.getEnabled());
        verify(repository, never()).delete(any());
        verify(repository, times(2)).save(existing);
    }

    @Test
    @DisplayName("Case 8: getActiveByDecisionType returns the unique published+enabled version")
    void getActiveReturnsUniqueVersion() {
        ApprovalWorkflow high = validSequentialWorkflow();
        high.setId("wf-high");
        high.setPriority(100);
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(high));

        Optional<ApprovalWorkflow> result = service.getActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE);

        assertTrue(result.isPresent());
        assertEquals("wf-high", result.get().getId());
    }

    @Test
    void multipleActiveWorkflowsFailClosed() {
        ApprovalWorkflow first = validSequentialWorkflow();
        ApprovalWorkflow second = validSequentialWorkflow();
        when(repository.findActiveByDecisionType(
                FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL))
                .thenReturn(List.of(first, second));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.getActiveByDecisionType(
                        FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL));

        assertEquals("OA_MULTIPLE_ACTIVE_WORKFLOWS", error.getErrorCode());
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
    @DisplayName("克隆已发布版本会创建独立递增草稿且不修改源版本")
    void cloneAsDraft_createsIndependentIncrementedDraft() {
        ApprovalWorkflow source = validSequentialWorkflow();
        source.setId(WORKFLOW_ID);
        source.setFactoryId(FACTORY_ID);
        source.setPublishStatus("published");
        source.setEnabled(true);
        source.setVersion(3);
        source.setPriority(20);
        when(repository.findByFactoryIdAndIdForUpdate(FACTORY_ID, WORKFLOW_ID))
                .thenReturn(Optional.of(source));
        when(repository.findByFactoryIdAndDecisionTypeOrderByPriorityDesc(
                FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(source));
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(invocation -> {
            ApprovalWorkflow saved = invocation.getArgument(0);
            saved.setId("wf-draft-v4");
            return saved;
        });

        ApprovalWorkflow draft = service.cloneAsDraft(FACTORY_ID, WORKFLOW_ID);

        assertEquals("wf-draft-v4", draft.getId());
        assertEquals(4, draft.getVersion());
        assertEquals("draft", draft.getPublishStatus());
        assertFalse(draft.getEnabled());
        assertEquals(source.getNodesJson(), draft.getNodesJson());
        assertEquals("published", source.getPublishStatus());
        assertTrue(source.getEnabled());
    }

    @Test
    @DisplayName("已有同业务草稿时克隆幂等返回该草稿")
    void cloneAsDraft_returnsExistingDraftIdempotently() {
        ApprovalWorkflow source = validSequentialWorkflow();
        source.setId(WORKFLOW_ID);
        source.setFactoryId(FACTORY_ID);
        source.setPublishStatus("published");
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId("existing-draft");
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setVersion(4);
        when(repository.findByFactoryIdAndIdForUpdate(FACTORY_ID, WORKFLOW_ID))
                .thenReturn(Optional.of(source));
        when(repository.findByFactoryIdAndDecisionTypeOrderByPriorityDesc(
                FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(draft, source));

        ApprovalWorkflow result = service.cloneAsDraft(FACTORY_ID, WORKFLOW_ID);

        assertSame(draft, result);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("发布草稿会启用新版本供新实例选择")
    void publishDraft_enablesPublishedVersion() {
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow published = service.publishDraft(FACTORY_ID, WORKFLOW_ID);

        assertEquals("published", published.getPublishStatus());
        assertTrue(published.getEnabled());
    }

    @Test
    void publishingNewVersionAtomicallyDisablesPreviousActiveVersion() {
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        ApprovalWorkflow previous = validSequentialWorkflow();
        previous.setId("wf-active-v1");
        previous.setFactoryId(FACTORY_ID);
        previous.setPublishStatus("published");
        previous.setEnabled(true);

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));
        when(repository.findActiveByDecisionType(
                FACTORY_ID, draft.getDecisionType())).thenReturn(List.of(previous));
        when(repository.save(any(ApprovalWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow published = service.publishDraft(FACTORY_ID, WORKFLOW_ID);

        assertFalse(previous.getEnabled());
        assertTrue(published.getEnabled());
        assertEquals("published", published.getPublishStatus());
        verify(repository).save(argThat(workflow ->
                "wf-active-v1".equals(workflow.getId())
                        && Boolean.FALSE.equals(workflow.getEnabled())));
        verify(repository).save(argThat(workflow ->
                WORKFLOW_ID.equals(workflow.getId())
                        && "published".equals(workflow.getPublishStatus())
                        && Boolean.TRUE.equals(workflow.getEnabled())));
    }

    @Test
    @DisplayName("未接入业务只能保存草稿，不能发布或重新启用")
    void unwiredBusinessCannotBePublishedOrEnabled() {
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        ReflectionTestUtils.setField(service, "decisionTypeMetadataRegistry", registry);

        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setDecisionType(DecisionType.LEAVE_APPROVAL);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));

        BusinessException publishError = assertThrows(
                BusinessException.class,
                () -> service.publishDraft(FACTORY_ID, WORKFLOW_ID));
        assertEquals("OA_BUSINESS_NOT_WIRED", publishError.getErrorCode());

        draft.setPublishStatus("published");
        BusinessException enableError = assertThrows(
                BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, true));
        assertEquals("OA_BUSINESS_NOT_WIRED", enableError.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void onlyPublishedUniqueWorkflowCanBeEnabled() {
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));

        BusinessException draftError = assertThrows(
                BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, true));
        assertEquals("OA_WORKFLOW_NOT_PUBLISHED", draftError.getErrorCode());

        draft.setPublishStatus("published");
        ApprovalWorkflow other = validSequentialWorkflow();
        other.setId("wf-other-active");
        other.setEnabled(true);
        other.setPublishStatus("published");
        when(repository.findActiveByDecisionType(
                FACTORY_ID, draft.getDecisionType())).thenReturn(List.of(other));

        BusinessException activeError = assertThrows(
                BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, true));
        assertEquals("OA_ACTIVE_WORKFLOW_EXISTS", activeError.getErrorCode());
        verify(repository, never()).save(any());
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
