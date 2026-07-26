package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.ApprovalChainConfigRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalChainServiceCanvasCutoverTest {

    private static final String FACTORY_ID = "F006";
    private static final DecisionType TYPE = DecisionType.PURCHASE_ORDER_APPROVAL;

    @Mock
    private ApprovalChainConfigRepository legacyRepository;
    @Mock
    private ApprovalWorkflowService workflowService;

    private ApprovalChainServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApprovalChainServiceImpl(
                legacyRepository, new ObjectMapper(), workflowService);
    }

    @Test
    void activeCanvasIsTheOnlyApprovalRuntimeSource() {
        when(workflowService.getActiveByDecisionType(FACTORY_ID, TYPE))
                .thenReturn(Optional.of(new ApprovalWorkflow()));

        assertTrue(service.requiresApproval(FACTORY_ID, TYPE, Map.of()));
        verifyNoInteractions(legacyRepository);
    }

    @Test
    void noCanvasAndNoLegacyMeansNoApproval() {
        when(workflowService.getActiveByDecisionType(FACTORY_ID, TYPE))
                .thenReturn(Optional.empty());
        when(legacyRepository.findByFactoryIdAndDecisionTypeAndEnabledTrueOrderByApprovalLevel(
                FACTORY_ID, TYPE)).thenReturn(List.of());

        assertFalse(service.requiresApproval(FACTORY_ID, TYPE, Map.of()));
    }

    @Test
    void legacyOnlyConfigurationMustBeMigratedInsteadOfSilentlyReleased() {
        when(workflowService.getActiveByDecisionType(FACTORY_ID, TYPE))
                .thenReturn(Optional.empty());
        when(legacyRepository.findByFactoryIdAndDecisionTypeAndEnabledTrueOrderByApprovalLevel(
                FACTORY_ID, TYPE)).thenReturn(List.of(new ApprovalChainConfig()));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.requiresApproval(FACTORY_ID, TYPE, Map.of()));

        assertEquals(409, error.getCode());
        assertEquals("OA_LEGACY_CONFIG_MIGRATION_REQUIRED", error.getErrorCode());
    }
}
