package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.entity.config.AIIntentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1 读写分块 — 意图级权限门单测。
 *
 * <p>核心约束: required_permission 已声明时 fail-closed — userId 缺失 / 判定异常一律拒绝并
 * 回带权限码; 未声明时返回 LEGACY 标记 (调用方走 requiredRoles 旧逻辑, 不双查)。
 */
@DisplayName("IntentPermissionGate — module:action 权限码判定 (fail-closed)")
class IntentPermissionGateTest {

    private ToolRbacGuard rbacGuard;
    private IntentPermissionGate gate;

    @BeforeEach
    void setUp() {
        rbacGuard = mock(ToolRbacGuard.class);
        gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", rbacGuard);
    }

    private AIIntentConfig intentWithPermission(String code) {
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("MATERIAL_INBOUND_CREATE");
        intent.setRequiredPermission(code);
        return intent;
    }

    @Test
    @DisplayName("fail-closed: requiredPermission 已设 + userId=null → 拒绝且回带权限码, 不触发 RBAC 查询")
    void nullUserIdIsDeniedWithPermissionCode() {
        IntentPermissionGate.PermissionCheck check =
                gate.check(intentWithPermission("inventory:write"), null, "operator");

        assertThat(check.isDenied()).isTrue();
        assertThat(check.requiredPermission()).isEqualTo("inventory:write");
        verifyNoInteractions(rbacGuard);
    }

    @Test
    @DisplayName("requiredPermission 空/空白 → LEGACY 标记 (调用方走 requiredRoles 旧逻辑)")
    void blankPermissionFallsBackToLegacy() {
        assertThat(gate.check(intentWithPermission(null), 22L, "admin").isLegacy()).isTrue();
        assertThat(gate.check(intentWithPermission("  "), 22L, "admin").isLegacy()).isTrue();
        verifyNoInteractions(rbacGuard);
    }

    @Test
    @DisplayName("矩阵拒绝 → DENIED + 权限码; 矩阵放行 → ALLOWED")
    void matrixDecisionIsPropagated() {
        when(rbacGuard.hasAnyPermission(any(), eq("inventory:write"))).thenReturn(false);
        IntentPermissionGate.PermissionCheck denied =
                gate.check(intentWithPermission("inventory:write"), 22L, "operator");
        assertThat(denied.isDenied()).isTrue();
        assertThat(denied.requiredPermission()).isEqualTo("inventory:write");

        when(rbacGuard.hasAnyPermission(any(), eq("inventory:write"))).thenReturn(true);
        assertThat(gate.check(intentWithPermission("inventory:write"), 22L, "admin").isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("fail-closed: RBAC 判定抛异常 → 拒绝且回带权限码")
    void rbacExceptionIsDenied() {
        when(rbacGuard.hasAnyPermission(any(), eq("inventory:write")))
                .thenThrow(new IllegalStateException("db down"));

        IntentPermissionGate.PermissionCheck check =
                gate.check(intentWithPermission("inventory:write"), 22L, "admin");

        assertThat(check.isDenied()).isTrue();
        assertThat(check.requiredPermission()).isEqualTo("inventory:write");
    }

    @Test
    @DisplayName("intent=null → 放行 (无从设门, 由调用方处理)")
    void nullIntentIsAllowed() {
        assertThat(gate.check(null, 22L, "admin").isAllowed()).isTrue();
        verifyNoInteractions(rbacGuard);
    }

    @Test
    @DisplayName("P1-M: 逗号分隔权限码 = 任一即可, 与 ToolRbacEnforcer Set 语义对齐")
    void commaSeparatedCodesAreAnyOf() {
        when(rbacGuard.hasAnyPermission(any(), eq("sales:read_write"), eq("finance:read_write")))
                .thenReturn(true);
        IntentPermissionGate.PermissionCheck check = gate.check(
                intentWithPermission("sales:read_write, finance:read_write"), 22L, "finance_manager");
        assertThat(check.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("P1-M: 逗号码集判定拒绝时回带完整码串 (前端字典逐段渲染)")
    void commaSeparatedDenialCarriesFullCodeString() {
        when(rbacGuard.hasAnyPermission(any(), eq("sales:read_write"), eq("finance:read_write")))
                .thenReturn(false);
        IntentPermissionGate.PermissionCheck check = gate.check(
                intentWithPermission("sales:read_write, finance:read_write"), 22L, "viewer");
        assertThat(check.isDenied()).isTrue();
        assertThat(check.requiredPermission()).isEqualTo("sales:read_write, finance:read_write");
    }
}
