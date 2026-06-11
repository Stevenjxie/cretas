package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * W9 红线 (AI-RBAC 系统性收口): 中央 {@link ToolRbacEnforcer} 单元测试。
 *
 * <p>验证: 整类敏感写工具 (customer_delete / order_delete / finance_invoice_approve /
 * transfer_approve / user_role_assign / period_confirm_close ...) 经 AI 路径被中央鉴权,
 * 低权角色拒绝 / 对应权限角色放行 / 只读工具不受影响 / 未映射写工具放行 (保 W0 行为) /
 * fail-closed (缺 userId 拒绝)。
 */
@DisplayName("W9 红线: 中央 ToolRbacEnforcer")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolRbacEnforcerTest {

    private static final String FACTORY = "F006";
    private static final long OPERATOR_UID = 9001L;
    private static final long ADMIN_UID = 9002L;

    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;

    private ToolRbacEnforcer enforcer;

    @BeforeEach
    void setUp() {
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", userRepository);
        ReflectionTestUtils.setField(guard, "permissionService", permissionService);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        User operator = new User();
        operator.setId(OPERATOR_UID);
        operator.setFactoryId(FACTORY);
        operator.setRoleCode("operator");
        User admin = new User();
        admin.setId(ADMIN_UID);
        admin.setFactoryId(FACTORY);
        admin.setRoleCode("factory_super_admin");
        when(userRepository.findById(OPERATOR_UID)).thenReturn(java.util.Optional.of(operator));
        when(userRepository.findById(ADMIN_UID)).thenReturn(java.util.Optional.of(admin));
    }

    private static Map<String, Object> ctx(long userId, String role) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", userId);
        c.put("userRole", role);
        return c;
    }

    /** Minimal stub tool with configurable name + optional declared permissions. */
    private static ToolExecutor tool(String name, Set<String> declaredPerms) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public Set<String> getRequiredPermissions() {
                return declaredPerms != null ? declaredPerms : Set.of();
            }
        };
    }

    private static ToolExecutor tool(String name) {
        return tool(name, null);
    }

    // ===== mapped sensitive write tools: low-priv denied =====

    @Test
    @DisplayName("operator 经 AI 删客户 → 无 sales/finance:read_write → 拒绝")
    void operatorDeniedCustomerDelete() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        ToolRbacEnforcer.Decision d = enforcer.check(tool("customer_delete"), ctx(OPERATOR_UID, "operator"));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getMessage()).contains("没有权限");
    }

    @Test
    @DisplayName("operator 经 AI 删订单 → 拒绝")
    void operatorDeniedOrderDelete() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("order_delete"), ctx(OPERATOR_UID, "operator"))).isFalse();
    }

    @Test
    @DisplayName("operator 经 AI 财审发票 → 拒绝")
    void operatorDeniedInvoiceApprove() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("finance_invoice_approve"), ctx(OPERATOR_UID, "operator"))).isFalse();
    }

    @Test
    @DisplayName("operator 经 AI 审批转账 → 拒绝")
    void operatorDeniedTransferApprove() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("transfer_approve"), ctx(OPERATOR_UID, "operator"))).isFalse();
    }

    @Test
    @DisplayName("operator 经 AI 分配角色 → 拒绝 (需 system:read_write)")
    void operatorDeniedUserRoleAssign() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("user_role_assign"), ctx(OPERATOR_UID, "operator"))).isFalse();
    }

    @Test
    @DisplayName("operator 经 AI 关账期 → 拒绝 (需 finance:read_write)")
    void operatorDeniedPeriodClose() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("period_confirm_close"), ctx(OPERATOR_UID, "operator"))).isFalse();
    }

    // ===== mapped sensitive write tools: privileged allowed =====

    @Test
    @DisplayName("管理员有权限 → 删客户放行")
    void adminAllowedCustomerDelete() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(true);
        assertThat(enforcer.isAllowed(tool("customer_delete"), ctx(ADMIN_UID, "factory_super_admin"))).isTrue();
    }

    @Test
    @DisplayName("管理员有权限 → 财审发票放行")
    void adminAllowedInvoiceApprove() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(true);
        assertThat(enforcer.isAllowed(tool("finance_invoice_approve"), ctx(ADMIN_UID, "factory_super_admin"))).isTrue();
    }

    // ===== read tools NOT affected =====

    @Test
    @DisplayName("只读工具 (customer_query) 无权限要求 → 放行 (即使无权限)")
    void readToolNotGated() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("customer_query"), ctx(OPERATOR_UID, "operator"))).isTrue();
        assertThat(enforcer.isAllowed(tool("inventory_list"), ctx(OPERATOR_UID, "operator"))).isTrue();
        assertThat(enforcer.isAllowed(tool("order_detail"), ctx(OPERATOR_UID, "operator"))).isTrue();
    }

    // ===== benign generator write-suffix tools NOT over-blocked (no mapping) =====

    @Test
    @DisplayName("良性生成类 (financial_chart_generate / trace_generate) 未映射 → 放行, 不误伤")
    void benignGeneratorsNotMapped() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        assertThat(enforcer.isAllowed(tool("financial_chart_generate"), ctx(OPERATOR_UID, "operator"))).isTrue();
        assertThat(enforcer.isAllowed(tool("trace_generate"), ctx(OPERATOR_UID, "operator"))).isTrue();
        assertThat(enforcer.isAllowed(tool("nutrition_label_generate"), ctx(OPERATOR_UID, "operator"))).isTrue();
    }

    // ===== tool-declared override takes priority over map =====

    @Test
    @DisplayName("工具自声明 getRequiredPermissions() 优先于内置映射")
    void declaredOverrideTakesPriority() {
        ToolExecutor declared = tool("some_new_tool", Set.of("quality:read_write"));
        when(permissionService.hasAnyPermission(any(), eq("quality:read_write"))).thenReturn(false);
        assertThat(enforcer.resolveRequiredPermissions(declared)).containsExactly("quality:read_write");
        assertThat(enforcer.isAllowed(declared, ctx(OPERATOR_UID, "operator"))).isFalse();
    }

    // ===== fail-closed =====

    @Test
    @DisplayName("缺 userId → fail-closed 拒绝 (映射工具)")
    void missingUserIdFailClosed() {
        Map<String, Object> noUid = new HashMap<>();
        noUid.put("factoryId", FACTORY);
        noUid.put("userRole", "operator");
        assertThat(enforcer.isAllowed(tool("customer_delete"), noUid)).isFalse();
    }

    @Test
    @DisplayName("null context → fail-closed 拒绝 (映射工具)")
    void nullContextFailClosed() {
        assertThat(enforcer.isAllowed(tool("order_delete"), null)).isFalse();
    }

    @Test
    @DisplayName("null tool → 放行 (无操作)")
    void nullToolAllowed() {
        assertThat(enforcer.isAllowed(null, ctx(OPERATOR_UID, "operator"))).isTrue();
    }
}
