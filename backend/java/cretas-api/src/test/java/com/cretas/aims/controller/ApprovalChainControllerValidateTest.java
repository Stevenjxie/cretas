package com.cretas.aims.controller;

import com.cretas.aims.dto.approval.CreateApprovalChainConfigRequest;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ApprovalChainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Canvas-cutover guard for {@link ApprovalChainController#validateConfig}.
 *
 * <p><b>History</b>: this class used to verify that {@code POST
 * /approval-chains/validate} mapped the DTO and forwarded a dry-run validation to
 * {@code ApprovalChainService.validateConfig}. {@code de9d9b9c97 "feat(oa): cut
 * over approvals to canvas runtime" (#1820)} retired the whole legacy authoring
 * surface — including the dry-run validator, which only ever existed to preflight
 * a legacy create. Validation now lives in the approval canvas runtime.
 *
 * <p>Kept as a cutover guard so a revert of the read-only gate is caught.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalChainControllerValidateTest {

    @Mock ApprovalChainService approvalChainService;

    @InjectMocks ApprovalChainController controller;

    private static CreateApprovalChainConfigRequest request() {
        CreateApprovalChainConfigRequest req = new CreateApprovalChainConfigRequest();
        req.setDecisionType(DecisionType.QUALITY_RELEASE);
        req.setName("一级审批 - 质量放行");
        req.setApprovalLevel(1);
        req.setApproverRoles("[\"factory_super_admin\"]");
        return req;
    }

    @Test
    @DisplayName("validateConfig 已随画布切换下线 → 410 OA_LEGACY_CONFIG_READ_ONLY")
    void validateConfig_legacyDryRunRemoved_throws410() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.validateConfig("F001", request()));

        assertEquals(410, ex.getCode());
        assertEquals("OA_LEGACY_CONFIG_READ_ONLY", ex.getErrorCode());
        assertEquals("旧版审批配置已停止编辑", ex.getMessage());
        assertTrue(ex.getActionHint() != null && ex.getActionHint().contains("审批画布"),
                "必须给出去画布配置的 actionHint: " + ex.getActionHint());
    }

    @Test
    @DisplayName("非法 body 也返回同一个 410 — 不再区分校验结果")
    void validateConfig_invalidBody_sameCutoverError() {
        CreateApprovalChainConfigRequest req = request();
        req.setDecisionType(DecisionType.SUPPLIER_APPROVAL);
        req.setApproverRoles("not-a-json-array");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.validateConfig("F001", req));

        assertEquals(410, ex.getCode(),
                "校验能力整体下线, 不应退回旧的 isValid=false 200 语义");
        verifyNoInteractions(approvalChainService);
    }
}
