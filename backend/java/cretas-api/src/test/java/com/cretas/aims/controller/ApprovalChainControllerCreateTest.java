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
 * Canvas-cutover guard for {@link ApprovalChainController#createConfig}.
 *
 * <p><b>History</b>: this class used to verify the Rule 17.1 wire→entity mapper
 * (Issue #384 batch 6 final) by asserting that {@code POST /approval-chains}
 * mapped {@link CreateApprovalChainConfigRequest} onto the entity and forwarded
 * it to {@code ApprovalChainService.createConfig}. That behaviour was removed by
 * {@code de9d9b9c97 "feat(oa): cut over approvals to canvas runtime" (#1820)}:
 * legacy approval-chain configs are now <b>read-only</b> and all authoring moved
 * to the approval canvas runtime (系统设置 → 审批业务). The endpoint is kept only
 * so old clients get an actionable 410 instead of a 404.
 *
 * <p>The mapper assertions were therefore not "failing tests" but tests of a
 * deleted feature. They are replaced by a cutover guard: re-opening the legacy
 * write path (accidentally or by revert) must fail this test.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalChainControllerCreateTest {

    @Mock ApprovalChainService approvalChainService;

    @InjectMocks ApprovalChainController controller;

    private static CreateApprovalChainConfigRequest validRequest() {
        CreateApprovalChainConfigRequest req = new CreateApprovalChainConfigRequest();
        req.setDecisionType(DecisionType.QUALITY_RELEASE);
        req.setName("一级审批 - 质量放行");
        req.setApprovalLevel(1);
        req.setApproverRoles("[\"factory_super_admin\"]");
        return req;
    }

    @Test
    @DisplayName("createConfig 已随画布切换下线 → 410 OA_LEGACY_CONFIG_READ_ONLY")
    void createConfig_legacyWritePathRemoved_throws410() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createConfig("F001", validRequest()));

        assertEquals(410, ex.getCode(), "legacy 写入路径必须是 410 Gone, 不是 400/404");
        assertEquals("OA_LEGACY_CONFIG_READ_ONLY", ex.getErrorCode(),
                "前端按 errorCode 分支引导到审批画布");
        assertEquals("旧版审批配置已停止编辑", ex.getMessage());
        assertTrue(ex.getActionHint() != null && ex.getActionHint().contains("审批画布"),
                "必须给出去画布配置的 actionHint: " + ex.getActionHint());
    }

    @Test
    @DisplayName("拒绝发生在服务层之前 — 旧版配置绝不被写库")
    void createConfig_neverReachesService() {
        assertThrows(BusinessException.class,
                () -> controller.createConfig("F001", validRequest()));

        // The whole point of the cutover: no legacy row may be created, so the
        // controller must fail closed *before* touching the service.
        verifyNoInteractions(approvalChainService);
    }

    @Test
    @DisplayName("合法 body 也一样拒绝 — 不是校验失败, 是能力下线")
    void createConfig_fullyPopulatedBody_stillRejected() {
        CreateApprovalChainConfigRequest req = validRequest();
        req.setDecisionType(DecisionType.FORCE_INSERT);
        req.setDescription("跨工序强制插单需要二级审批");
        req.setTriggerCondition("{\"impactLevel\":\"HIGH\"}");
        req.setApprovalLevel(2);
        req.setRequiredApprovers(2);
        req.setApproverUserIds("[101,102]");
        req.setTimeoutMinutes(120);
        req.setPriority(10);
        req.setEnabled(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createConfig("F002", req));
        assertEquals(410, ex.getCode());
        verifyNoInteractions(approvalChainService);
    }
}
