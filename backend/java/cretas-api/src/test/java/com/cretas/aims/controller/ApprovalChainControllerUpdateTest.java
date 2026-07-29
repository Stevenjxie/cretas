package com.cretas.aims.controller;

import com.cretas.aims.dto.approval.UpdateApprovalChainConfigRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ApprovalChainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Canvas-cutover guard for the legacy mutation endpoints of
 * {@link ApprovalChainController} (update / delete / enable-toggle).
 *
 * <p><b>History</b>: this class used to verify the Rule 17.1 partial wire→entity
 * mapper (Issue #384 batch 6 final) for {@code PUT /approval-chains/{id}}. That
 * behaviour was removed by {@code de9d9b9c97 "feat(oa): cut over approvals to
 * canvas runtime" (#1820)} — legacy configs became read-only and authoring moved
 * to the approval canvas runtime. The mapper assertions were tests of a deleted
 * feature, so they are replaced by cutover guards.
 *
 * <p>The one surviving mutation is {@code toggle(enabled=false)}: disabling a
 * stale legacy row is how a factory finishes migrating to the canvas, so it must
 * keep working while {@code toggle(enabled=true)} (re-arming legacy routing) is
 * refused like the other writes.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalChainControllerUpdateTest {

    @Mock ApprovalChainService approvalChainService;

    @InjectMocks ApprovalChainController controller;

    private static void assertLegacyReadOnly(BusinessException ex) {
        assertEquals(410, ex.getCode(), "legacy 写入路径必须是 410 Gone");
        assertEquals("OA_LEGACY_CONFIG_READ_ONLY", ex.getErrorCode());
        assertEquals("旧版审批配置已停止编辑", ex.getMessage());
        assertTrue(ex.getActionHint() != null && ex.getActionHint().contains("审批画布"),
                "必须给出去画布配置的 actionHint: " + ex.getActionHint());
    }

    @Test
    @DisplayName("updateConfig 已随画布切换下线 → 410 且不触达服务层")
    void updateConfig_legacyWritePathRemoved_throws410() {
        UpdateApprovalChainConfigRequest req = new UpdateApprovalChainConfigRequest();
        req.setName("更新后名称");
        req.setPriority(99);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateConfig("F001", "config-001", req));

        assertLegacyReadOnly(ex);
        verifyNoInteractions(approvalChainService);
    }

    @Test
    @DisplayName("deleteConfig 已随画布切换下线 → 410 且不触达服务层")
    void deleteConfig_legacyWritePathRemoved_throws410() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.deleteConfig("F001", "config-001"));

        assertLegacyReadOnly(ex);
        verifyNoInteractions(approvalChainService);
    }

    @Test
    @DisplayName("toggle(enabled=true) 会重新启用旧版链路 → 同样拒绝")
    void toggleEnabled_reArmingLegacyRouting_throws410() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.toggleEnabled("F001", "config-001", true));

        assertLegacyReadOnly(ex);
        verifyNoInteractions(approvalChainService);
    }

    @Test
    @DisplayName("toggle(enabled=false) 仍放行 — 停用旧配置是迁移到画布的收尾动作")
    void toggleEnabled_disablingLegacyConfig_stillAllowed() {
        ApprovalChainConfig disabled = new ApprovalChainConfig();
        disabled.setId("config-001");
        disabled.setEnabled(false);
        when(approvalChainService.toggleEnabled("F001", "config-001", false)).thenReturn(disabled);

        ApiResponse<ApprovalChainConfig> resp =
                controller.toggleEnabled("F001", "config-001", false);

        assertNotNull(resp);
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("旧版审批配置已停用", resp.getMessage());
        assertEquals(disabled, resp.getData());
        verify(approvalChainService).toggleEnabled("F001", "config-001", false);
    }
}
