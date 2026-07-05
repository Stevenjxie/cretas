package com.cretas.aims.service.impl;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.quality.QualityDispositionInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the 质检处置→库存 bridge is invoked at the correct point inside
 * {@link QualityDispositionRuleServiceImpl#executeDisposition}:
 * <ul>
 *   <li>Direct execution (no special approval) → {@code applyDisposition} IS called (flip happens).</li>
 *   <li>Approval-initiated branch (needs special approval) → {@code applyDisposition} is NOT called
 *       (an un-approved RELEASE must not un-quarantine).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QualityDispositionRuleServiceInventoryHookTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private QualityDispositionRuleServiceImpl service;

    @Mock
    private ApprovalChainService approvalChainService;
    @Mock
    private DecisionAuditService decisionAuditService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QualityDispositionInventoryService dispositionInventoryService;

    private QualityInspection inspection;

    @BeforeEach
    void setUp() {
        // dispositionInventoryService is an @Autowired(required=false) field; @InjectMocks uses the
        // @RequiredArgsConstructor (final fields only) and does NOT field-inject it → wire manually.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "dispositionInventoryService", dispositionInventoryService);

        inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId(FACTORY_ID);
        inspection.setProductionBatchId(100L);

        User executor = mock(User.class);
        lenient().when(executor.getFullName()).thenReturn("张质检");
        lenient().when(executor.getRole()).thenReturn("quality_manager");
        when(userRepository.findById(9L)).thenReturn(Optional.of(executor));
    }

    @Test
    @DisplayName("HOOK-01: 直接执行放行 (无特批) → 桥接 applyDisposition 被调用")
    void directReleaseInvokesBridge() {
        // requiresSpecialApproval → false (走直接执行分支)
        when(approvalChainService.requiresApproval(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(false);
        when(decisionAuditService.logRuleExecution(anyString(), anyString(), anyString(), anyMap(), anyMap(),
                anyList(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(DecisionAuditLog.builder().id("al-1").build());

        service.executeDisposition(FACTORY_ID, inspection, DispositionAction.RELEASE, 9L, "质检合格放行");

        verify(dispositionInventoryService, times(1)).applyDisposition(inspection, DispositionAction.RELEASE);
    }

    @Test
    @DisplayName("HOOK-02: 需特批 (approval-initiated) → 桥接不被调用 (未批不释放隔离)")
    void approvalInitiatedDoesNotInvokeBridge() {
        // requiresSpecialApproval → true (走 approval-initiated 分支, 只记审计, 不执行库存翻转)
        when(approvalChainService.requiresApproval(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(true);
        when(approvalChainService.findMatchingConfig(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(Optional.empty());
        when(decisionAuditService.logForceInsertWithRuleConfig(anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyBoolean(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(DecisionAuditLog.builder().id("al-2").build());

        service.executeDisposition(FACTORY_ID, inspection, DispositionAction.RELEASE, 9L, "低合格率放行申请");

        verify(dispositionInventoryService, never()).applyDisposition(any(), any());
    }
}
