package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Sprint 5 Track A personal view queries
 * ({@link WorkflowEngineServiceImpl#findCreatedBy} +
 *  {@link WorkflowEngineServiceImpl#findParticipatedBy}).
 *
 * <p>Verifies:
 * <ol>
 *   <li>service delegates to repository with right (factoryId, userId, pageable) args</li>
 *   <li>null guards reject missing factoryId / userId / pageable</li>
 *   <li>empty page is propagated faithfully (no NPE on dto build)</li>
 * </ol>
 *
 * @since 2026-05-19 (Sprint 5 Track A)
 */
@DisplayName("WorkflowEngineServiceImpl Sprint 5 personal view queries")
@ExtendWith(MockitoExtension.class)
class WorkflowEngineServiceImplPersonalViewTest {

    @Mock private ApprovalWorkflowInstanceRepository instanceRepository;
    @Mock private ApprovalHistoryRepository historyRepository;
    @Mock private ApprovalWorkflowService workflowService;

    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();
    private WorkflowEngineServiceImpl engine;

    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService,
                spelEvaluator, /* redisTemplate = */ null);
    }

    // ==================== findCreatedBy ====================

    @Test
    @DisplayName("findCreatedBy: delegates to repo with correct args + returns page")
    void findCreatedBy_delegates_and_returns_page() {
        Pageable pageable = PageRequest.of(0, 20);
        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id("inst-1")
                .factoryId(FACTORY_ID)
                .workflowId("wf-1")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("PO-001")
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>())
                .initiatedBy(USER_ID)
                .initiatedAt(LocalDateTime.now())
                .build();
        Page<ApprovalWorkflowInstance> expected = new PageImpl<>(List.of(inst), pageable, 1);
        when(instanceRepository.findByFactoryIdAndInitiatedByOrderByInitiatedAtDesc(
                FACTORY_ID, USER_ID, pageable)).thenReturn(expected);

        Page<ApprovalWorkflowInstance> result = engine.findCreatedBy(FACTORY_ID, USER_ID, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("inst-1", result.getContent().get(0).getId());
        assertEquals(USER_ID, result.getContent().get(0).getInitiatedBy());

        ArgumentCaptor<Long> userCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> factoryCap = ArgumentCaptor.forClass(String.class);
        verify(instanceRepository).findByFactoryIdAndInitiatedByOrderByInitiatedAtDesc(
                factoryCap.capture(), userCap.capture(), any(Pageable.class));
        assertEquals(FACTORY_ID, factoryCap.getValue());
        assertEquals(USER_ID, userCap.getValue());
    }

    @Test
    @DisplayName("findCreatedBy: returns empty page when user has no initiated instances")
    void findCreatedBy_empty_page() {
        Pageable pageable = PageRequest.of(0, 20);
        when(instanceRepository.findByFactoryIdAndInitiatedByOrderByInitiatedAtDesc(
                FACTORY_ID, USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<ApprovalWorkflowInstance> result = engine.findCreatedBy(FACTORY_ID, USER_ID, pageable);

        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    @DisplayName("findCreatedBy: null factoryId throws NullPointerException")
    void findCreatedBy_null_factoryId_throws() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThrows(NullPointerException.class,
                () -> engine.findCreatedBy(null, USER_ID, pageable));
        verifyNoInteractions(instanceRepository);
    }

    @Test
    @DisplayName("findCreatedBy: null userId throws NullPointerException")
    void findCreatedBy_null_userId_throws() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThrows(NullPointerException.class,
                () -> engine.findCreatedBy(FACTORY_ID, null, pageable));
        verifyNoInteractions(instanceRepository);
    }

    @Test
    @DisplayName("findCreatedBy: null pageable throws NullPointerException")
    void findCreatedBy_null_pageable_throws() {
        assertThrows(NullPointerException.class,
                () -> engine.findCreatedBy(FACTORY_ID, USER_ID, null));
        verifyNoInteractions(instanceRepository);
    }

    // ==================== findParticipatedBy ====================

    @Test
    @DisplayName("findParticipatedBy: delegates to repo with correct args + returns page")
    void findParticipatedBy_delegates_and_returns_page() {
        Pageable pageable = PageRequest.of(0, 20);
        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id("inst-2")
                .factoryId(FACTORY_ID)
                .workflowId("wf-1")
                .moduleCode("SALES_ORDER")
                .businessEntityId("SO-002")
                .status(InstanceStatus.APPROVED)
                .currentNodeIds(new ArrayList<>())
                .initiatedBy(999L)  // 不是 USER_ID — 表示别人发起, 我参与
                .initiatedAt(LocalDateTime.now())
                .build();
        Page<ApprovalWorkflowInstance> expected = new PageImpl<>(List.of(inst), pageable, 1);
        when(instanceRepository.findParticipatedBy(FACTORY_ID, USER_ID, pageable))
                .thenReturn(expected);

        Page<ApprovalWorkflowInstance> result =
                engine.findParticipatedBy(FACTORY_ID, USER_ID, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("inst-2", result.getContent().get(0).getId());
        // verify isolation: initiatedBy not equal to user (this user is a participant only)
        assertNotEquals(USER_ID, result.getContent().get(0).getInitiatedBy());

        verify(instanceRepository).findParticipatedBy(FACTORY_ID, USER_ID, pageable);
    }

    @Test
    @DisplayName("findParticipatedBy: null guards reject missing args")
    void findParticipatedBy_null_guards() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThrows(NullPointerException.class,
                () -> engine.findParticipatedBy(null, USER_ID, pageable));
        assertThrows(NullPointerException.class,
                () -> engine.findParticipatedBy(FACTORY_ID, null, pageable));
        assertThrows(NullPointerException.class,
                () -> engine.findParticipatedBy(FACTORY_ID, USER_ID, null));
        verifyNoInteractions(instanceRepository);
    }

    @Test
    @DisplayName("findActedBy: excludes initiator-only records by delegating to actor query")
    void findActedBy_delegates_to_actor_query() {
        Pageable pageable = PageRequest.of(0, 20);
        ApprovalWorkflowInstance acted = ApprovalWorkflowInstance.builder()
                .id("inst-acted")
                .factoryId(FACTORY_ID)
                .workflowId("wf-acted")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("PO-ACTED")
                .status(InstanceStatus.APPROVED)
                .currentNodeIds(List.of())
                .initiatedBy(999L)
                .build();
        when(instanceRepository.findActedBy(FACTORY_ID, USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(acted), pageable, 1));

        Page<ApprovalWorkflowInstance> result =
                engine.findActedBy(FACTORY_ID, USER_ID, pageable);

        assertEquals(List.of("inst-acted"),
                result.getContent().stream().map(ApprovalWorkflowInstance::getId).toList());
        verify(instanceRepository).findActedBy(FACTORY_ID, USER_ID, pageable);
    }

    @Test
    @DisplayName("findCopiedTo: matches persisted notify transition by role and keeps factory scope")
    void findCopiedTo_matches_notify_role() {
        Pageable pageable = PageRequest.of(0, 20);
        ApprovalHistory notify = ApprovalHistory.builder()
                .factoryId(FACTORY_ID)
                .instanceId("inst-copied")
                .nodeId("notify-finance")
                .action(ApprovalHistory.HistoryAction.AUTO_TRANSITION)
                .notes("notify dispatched: IN_APP=SENT")
                .build();
        when(historyRepository.findByFactoryIdAndActionOrderByCreatedAtDesc(
                FACTORY_ID, ApprovalHistory.HistoryAction.AUTO_TRANSITION))
                .thenReturn(List.of(notify));

        ApprovalWorkflowInstance copied = ApprovalWorkflowInstance.builder()
                .id("inst-copied")
                .factoryId(FACTORY_ID)
                .workflowId("wf-copied")
                .moduleCode("SALES_ORDER")
                .businessEntityId("SO-001")
                .status(InstanceStatus.APPROVED)
                .currentNodeIds(List.of())
                .initiatedAt(LocalDateTime.now())
                .build();
        when(instanceRepository.findByFactoryIdAndIdIn(
                FACTORY_ID, List.of("inst-copied"))).thenReturn(List.of(copied));

        ApprovalWorkflow workflow = ApprovalWorkflow.builder()
                .id("wf-copied")
                .factoryId(FACTORY_ID)
                .nodesJson("[]")
                .build();
        when(workflowService.getById(FACTORY_ID, "wf-copied"))
                .thenReturn(Optional.of(workflow));
        when(workflowService.deserializeNodes("[]"))
                .thenReturn(List.of(ApprovalWorkflowNode.builder()
                        .id("notify-finance")
                        .type("notify")
                        .config(Map.of("notifyRoles", List.of("finance_manager")))
                        .build()));

        Page<ApprovalWorkflowInstance> result = engine.findCopiedTo(
                FACTORY_ID, USER_ID, "finance_manager", pageable);
        Page<ApprovalWorkflowInstance> unrelated = engine.findCopiedTo(
                FACTORY_ID, USER_ID, "quality_manager", pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("inst-copied", result.getContent().get(0).getId());
        assertTrue(unrelated.isEmpty());
        verify(instanceRepository, times(2)).findByFactoryIdAndIdIn(
                FACTORY_ID, List.of("inst-copied"));
    }
    // ==================== Sprint 6 W1-B: listAllRunning (admin view) ====================

    @Test
    @DisplayName("listAllRunning: delegates to repo with RUNNING status + returns page")
    void listAllRunning_delegates_and_returns_page() {
        Pageable pageable = PageRequest.of(0, 20);
        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id("inst-admin-1")
                .factoryId(FACTORY_ID)
                .workflowId("wf-1")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("PO-101")
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>())
                .initiatedBy(42L)
                .initiatedAt(LocalDateTime.now())
                .build();
        Page<ApprovalWorkflowInstance> expected = new PageImpl<>(List.of(inst), pageable, 1);
        when(instanceRepository.findByFactoryIdAndStatusOrderByInitiatedAtDesc(
                FACTORY_ID, InstanceStatus.RUNNING, pageable))
                .thenReturn(expected);

        Page<ApprovalWorkflowInstance> result = engine.listAllRunning(FACTORY_ID, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("inst-admin-1", result.getContent().get(0).getId());
        assertEquals(InstanceStatus.RUNNING, result.getContent().get(0).getStatus());

        verify(instanceRepository).findByFactoryIdAndStatusOrderByInitiatedAtDesc(
                FACTORY_ID, InstanceStatus.RUNNING, pageable);
    }

    @Test
    @DisplayName("listAllRunning: returns empty page when factory has no RUNNING instances")
    void listAllRunning_empty_page() {
        Pageable pageable = PageRequest.of(0, 20);
        when(instanceRepository.findByFactoryIdAndStatusOrderByInitiatedAtDesc(
                FACTORY_ID, InstanceStatus.RUNNING, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<ApprovalWorkflowInstance> result = engine.listAllRunning(FACTORY_ID, pageable);

        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    @DisplayName("listAllRunning: null factoryId throws NullPointerException")
    void listAllRunning_null_factoryId_throws() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThrows(NullPointerException.class,
                () -> engine.listAllRunning(null, pageable));
        verifyNoInteractions(instanceRepository);
    }

    @Test
    @DisplayName("listAllRunning: null pageable throws NullPointerException")
    void listAllRunning_null_pageable_throws() {
        assertThrows(NullPointerException.class,
                () -> engine.listAllRunning(FACTORY_ID, null));
        verifyNoInteractions(instanceRepository);
    }
}
