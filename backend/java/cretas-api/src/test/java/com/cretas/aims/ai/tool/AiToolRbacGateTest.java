package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.dataop.BatchDeleteConfirmTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchDeleteTool;
import com.cretas.aims.ai.tool.impl.material.MaterialUpdateTool;
import com.cretas.aims.ai.tool.impl.returnorder.ReturnOrderApproveTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5 红线 (AI-RBAC): AI tool 执行链不得绕过 controller @RequirePermission。
 *
 * <p>Fable 系统性审计: HTTP 面 RBAC 由 controller 注解收口, 但 AI tool 直调 service 绕过它 —
 * 敏感写工具 (删批次/改入库量/财审退货) 在 AI 路径无鉴权。本测试验证修复后:
 * <ul>
 *   <li>仓管/操作员经 AI 删批次 → 拒绝 (service 角色守卫 / RBAC gate)</li>
 *   <li>管理员经 AI 删批次 → 放行</li>
 *   <li>非财务经 AI 财审退货 → 拒绝</li>
 *   <li>财务经 AI 财审退货 → 放行</li>
 * </ul>
 *
 * <p>核心: tool 从 context 取调用者真实角色 (userId/userRole 来自 JWT, 经意图执行入口透传),
 * 传给带守卫的 service 重载 / 经 ToolRbacGuard 用与 controller 同源的 PermissionService 判定。
 */
@DisplayName("W5 红线: AI tool 执行链 RBAC 守卫")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiToolRbacGateTest {

    private static final String FACTORY = "F006";
    private static final long OPERATOR_UID = 9001L;
    private static final long ADMIN_UID = 9002L;
    private static final long FINANCE_UID = 9003L;
    private static final long SALES_UID = 9004L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Map<String, Object> ctx(long userId, String userRole) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", userId);
        c.put("userRole", userRole);
        return c;
    }

    private static ToolCall call(String name, String argsJson) {
        return ToolCall.of("tc-1", name, argsJson);
    }

    // ---------- MaterialBatchDeleteTool ----------

    @Nested
    @DisplayName("MaterialBatchDeleteTool: 仓管经 AI 删批次 = 同 HTTP 面 403")
    class MaterialBatchDelete {

        @Mock private MaterialBatchService materialBatchService;
        private MaterialBatchDeleteTool tool;

        @BeforeEach
        void setUp() {
            tool = new MaterialBatchDeleteTool();
            ReflectionTestUtils.setField(tool, "materialBatchService", materialBatchService);
            ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        }

        @Test
        @DisplayName("透传真实角色给带守卫的 service 重载 (而非 null)")
        void passesRealCallerRole() throws Exception {
            tool.execute(call("material_batch_delete", "{\"batchId\":\"MB-1\"}"),
                    ctx(OPERATOR_UID, "operator"));
            // 必须调用 3-arg (带 callerRole) 重载, 且 callerRole = 真实角色
            verify(materialBatchService).deleteMaterialBatch(eq(FACTORY), eq("MB-1"), eq("operator"));
            // 绝不调用 2-arg (callerRole=null) 无守卫重载
            verify(materialBatchService, never()).deleteMaterialBatch(eq(FACTORY), eq("MB-1"));
        }

        @Test
        @DisplayName("service 403 (仓管无权) → tool 返回错误, 不静默成功")
        void deniedSurfacedAsError() throws Exception {
            org.mockito.Mockito.doThrow(new BusinessException(403, "仓管员无权删除批次 (无单据移除库存)"))
                    .when(materialBatchService).deleteMaterialBatch(eq(FACTORY), eq("MB-1"), eq("operator"));
            String resp = tool.execute(call("material_batch_delete", "{\"batchId\":\"MB-1\"}"),
                    ctx(OPERATOR_UID, "operator"));
            // 不静默成功: 抛出的 403 被工具转成 success:false 错误 (脱敏后紧凑 JSON)
            assertThat(resp.replace(" ", "")).contains("\"success\":false");
        }

        @Test
        @DisplayName("管理员经 AI 删批次 → 放行 (callerRole=factory_super_admin)")
        void adminAllowed() throws Exception {
            tool.execute(call("material_batch_delete", "{\"batchId\":\"MB-1\"}"),
                    ctx(ADMIN_UID, "factory_super_admin"));
            verify(materialBatchService).deleteMaterialBatch(eq(FACTORY), eq("MB-1"), eq("factory_super_admin"));
        }
    }

    // ---------- MaterialUpdateTool ----------

    @Nested
    @DisplayName("MaterialUpdateTool: 透传真实角色")
    class MaterialUpdate {

        @Mock private MaterialBatchService materialBatchService;
        private MaterialUpdateTool tool;

        @BeforeEach
        void setUp() {
            tool = new MaterialUpdateTool();
            ReflectionTestUtils.setField(tool, "materialBatchService", materialBatchService);
            ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
            MaterialBatchDTO dto = new MaterialBatchDTO();
            dto.setBatchNumber("BN-1");
            when(materialBatchService.updateMaterialBatch(any(), any(), any(), any())).thenReturn(dto);
        }

        @Test
        @DisplayName("调用带 callerRole 的 4-arg 重载 (而非 3-arg null)")
        void passesRealCallerRole() throws Exception {
            tool.execute(call("material_update", "{\"batchId\":\"MB-1\",\"storageLocation\":\"A-1\"}"),
                    ctx(OPERATOR_UID, "operator"));
            verify(materialBatchService).updateMaterialBatch(eq(FACTORY), eq("MB-1"),
                    any(UpdateMaterialBatchRequest.class), eq("operator"));
            verify(materialBatchService, never()).updateMaterialBatch(eq(FACTORY), eq("MB-1"),
                    any(UpdateMaterialBatchRequest.class));
        }
    }

    // ---------- BatchDeleteConfirmTool ----------

    @Nested
    @DisplayName("BatchDeleteConfirmTool: 上游 RBAC gate")
    class BatchDeleteConfirm {

        @Mock private ProcessingService processingService;
        @Mock private MaterialBatchService materialBatchService;
        @Mock private UserRepository userRepository;
        @Mock private PermissionService permissionService;
        private BatchDeleteConfirmTool tool;

        private User user(long id, FactoryUserRole role) {
            User u = new User();
            u.setId(id);
            u.setFactoryId(FACTORY);
            u.setRoleCode(role.name());
            return u;
        }

        @BeforeEach
        void setUp() {
            tool = new BatchDeleteConfirmTool();
            ToolRbacGuard guard = new ToolRbacGuard();
            ReflectionTestUtils.setField(guard, "userRepository", userRepository);
            ReflectionTestUtils.setField(guard, "permissionService", permissionService);
            ReflectionTestUtils.setField(tool, "processingService", processingService);
            ReflectionTestUtils.setField(tool, "materialBatchService", materialBatchService);
            ReflectionTestUtils.setField(tool, "rbacGuard", guard);
            ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        }

        @Test
        @DisplayName("operator 经 AI 批量删原料批次 → 无 warehouse:read_write → 拒绝, 不删")
        void operatorDeniedMaterialBatch() throws Exception {
            when(userRepository.findById(OPERATOR_UID)).thenReturn(Optional.of(user(OPERATOR_UID, FactoryUserRole.operator)));
            when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);

            String resp = tool.execute(
                    call("batch_delete_confirm",
                            "{\"entityType\":\"MATERIAL_BATCH\",\"ids\":[\"MB-1\"],\"confirmed\":true}"),
                    ctx(OPERATOR_UID, "operator"));

            assertThat(resp).contains("权限不足");
            verify(materialBatchService, never()).deleteMaterialBatch(any(), any(), any());
            verify(materialBatchService, never()).deleteMaterialBatch(any(), any());
        }

        @Test
        @DisplayName("warehouse_manager → 有 warehouse:read_write → 放行, 走带 callerRole 的删除")
        void managerAllowedMaterialBatch() throws Exception {
            when(userRepository.findById(ADMIN_UID)).thenReturn(Optional.of(user(ADMIN_UID, FactoryUserRole.warehouse_manager)));
            when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(true);

            tool.execute(
                    call("batch_delete_confirm",
                            "{\"entityType\":\"MATERIAL_BATCH\",\"ids\":[\"MB-1\"],\"confirmed\":true}"),
                    ctx(ADMIN_UID, "warehouse_manager"));

            verify(materialBatchService).deleteMaterialBatch(eq(FACTORY), eq("MB-1"), eq("warehouse_manager"));
        }

        @Test
        @DisplayName("operator 经 AI 批量取消生产批次 → 无 production:read_write → 拒绝, 不取消")
        void operatorDeniedProductionBatch() throws Exception {
            when(userRepository.findById(OPERATOR_UID)).thenReturn(Optional.of(user(OPERATOR_UID, FactoryUserRole.operator)));
            when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);

            String resp = tool.execute(
                    call("batch_delete_confirm",
                            "{\"entityType\":\"PRODUCTION_BATCH\",\"ids\":[\"PB-1\"],\"confirmed\":true}"),
                    ctx(OPERATOR_UID, "operator"));

            assertThat(resp).contains("权限不足");
            verify(processingService, never()).cancelProduction(any(), any(), any());
        }
    }

    // ---------- ReturnOrderApproveTool ----------

    @Nested
    @DisplayName("ReturnOrderApproveTool: finance-approve 需 finance:read_write")
    class ReturnOrderApprove {

        @Mock private ReturnOrderService returnOrderService;
        @Mock private UserRepository userRepository;
        @Mock private PermissionService permissionService;
        private ReturnOrderApproveTool tool;

        private User user(long id, FactoryUserRole role) {
            User u = new User();
            u.setId(id);
            u.setFactoryId(FACTORY);
            u.setRoleCode(role.name());
            return u;
        }

        @BeforeEach
        void setUp() {
            tool = new ReturnOrderApproveTool();
            ToolRbacGuard guard = new ToolRbacGuard();
            ReflectionTestUtils.setField(guard, "userRepository", userRepository);
            ReflectionTestUtils.setField(guard, "permissionService", permissionService);
            ReflectionTestUtils.setField(tool, "returnOrderService", returnOrderService);
            ReflectionTestUtils.setField(tool, "rbacGuard", guard);
            ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        }

        private ReturnOrder ro() {
            ReturnOrder r = new ReturnOrder();
            r.setId("RO-1");
            r.setReturnNumber("RTN-001");
            r.setStatus(ReturnOrderStatus.FINANCE_APPROVED);
            return r;
        }

        @Test
        @DisplayName("非财务 (sales_manager) 经 AI 财审退货 → 拒绝, 不调 service")
        void nonFinanceDeniedFinanceApprove() throws Exception {
            when(userRepository.findById(SALES_UID)).thenReturn(Optional.of(user(SALES_UID, FactoryUserRole.sales_manager)));
            // finance:read_write → false (sales 没有)
            when(permissionService.hasAnyPermission(any(), eq("finance:read_write"))).thenReturn(false);

            String resp = tool.execute(
                    call("return_order_approve", "{\"returnOrderId\":\"RO-1\",\"action\":\"finance-approve\"}"),
                    ctx(SALES_UID, "sales_manager"));

            assertThat(resp).contains("没有权限");
            verify(returnOrderService, never()).financeApproveReturnOrder(any(), any(), any());
        }

        @Test
        @DisplayName("财务经 AI 财审退货 → 放行")
        void financeAllowedFinanceApprove() throws Exception {
            when(userRepository.findById(FINANCE_UID)).thenReturn(Optional.of(user(FINANCE_UID, FactoryUserRole.finance_manager)));
            when(permissionService.hasAnyPermission(any(), eq("finance:read_write"))).thenReturn(true);
            when(returnOrderService.financeApproveReturnOrder(eq(FACTORY), eq("RO-1"), any())).thenReturn(ro());

            tool.execute(
                    call("return_order_approve", "{\"returnOrderId\":\"RO-1\",\"action\":\"finance-approve\"}"),
                    ctx(FINANCE_UID, "finance_manager"));

            verify(returnOrderService, times(1)).financeApproveReturnOrder(eq(FACTORY), eq("RO-1"), any());
        }

        @Test
        @DisplayName("非业务角色经 AI 完成退货 → 拒绝 (需 sales/procurement:read_write)")
        void nonBizDeniedComplete() throws Exception {
            User viewer = user(SALES_UID, FactoryUserRole.viewer);
            when(userRepository.findById(SALES_UID)).thenReturn(Optional.of(viewer));
            when(permissionService.hasAnyPermission(any(), eq("sales:read_write"), eq("procurement:read_write")))
                    .thenReturn(false);

            String resp = tool.execute(
                    call("return_order_approve", "{\"returnOrderId\":\"RO-1\",\"action\":\"complete\"}"),
                    ctx(SALES_UID, "viewer"));

            assertThat(resp).contains("没有权限");
            verify(returnOrderService, never()).completeReturnOrder(any(), any());
        }
    }
}
