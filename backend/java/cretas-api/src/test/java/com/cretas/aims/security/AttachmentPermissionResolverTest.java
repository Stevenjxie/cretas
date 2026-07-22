package com.cretas.aims.security;

import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RBAC unit tests for {@link AttachmentPermissionResolver} — Sprint1-Fix-K2.
 *
 * <p>Covers the four high-risk cases PR_AUDIT flagged on #658:
 * <ol>
 *   <li>warehouse_manager attempting PAYMENT_VOUCHER read → 403 (no finance perm)</li>
 *   <li>finance_manager attempting PAYMENT_VOUCHER read → allowed</li>
 *   <li>warehouse_manager attempting QUALITY_CHECK read → 403 (no quality perm)</li>
 *   <li>factory_super_admin → always bypass</li>
 * </ol>
 * Plus upload guardrails (content-type whitelist + 10MB size cap).
 */
@DisplayName("AttachmentPermissionResolver RBAC 单测")
@ExtendWith(MockitoExtension.class)
class AttachmentPermissionResolverTest {

    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;

    private AttachmentPermissionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AttachmentPermissionResolver(permissionService, userRepository);
    }

    private User userWithRole(Long id, FactoryUserRole role) {
        User u = new User();
        u.setId(id);
        u.setRoleCode(role.name());
        return u;
    }

    // ==================== Entity-type RBAC ====================

    @Test
    @DisplayName("❌ warehouse_manager 调 PAYMENT_VOUCHER read → 403 (无 finance:read 权限)")
    void warehouseManager_paymentVoucher_read_forbidden() {
        User warehouseMgr = userWithRole(101L, FactoryUserRole.warehouse_manager);
        when(permissionService.hasAnyPermission(eq(warehouseMgr), any(String[].class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(warehouseMgr, EntityType.PAYMENT_VOUCHER));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("PAYMENT_VOUCHER"), "错误信息应包含 entityType");
    }

    @Test
    @DisplayName("✅ finance_manager 调 PAYMENT_VOUCHER read → 通过 (有 finance:read 权限)")
    void financeManager_paymentVoucher_read_allowed() {
        User financeMgr = userWithRole(102L, FactoryUserRole.finance_manager);
        when(permissionService.hasAnyPermission(eq(financeMgr), any(String[].class))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireRead(financeMgr, EntityType.PAYMENT_VOUCHER));
    }

    @Test
    @DisplayName("❌ warehouse_manager 调 QUALITY_CHECK read → 403 (无 quality:read 权限)")
    void warehouseManager_qualityCheck_read_forbidden() {
        User warehouseMgr = userWithRole(101L, FactoryUserRole.warehouse_manager);
        when(permissionService.hasAnyPermission(eq(warehouseMgr), any(String[].class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(warehouseMgr, EntityType.QUALITY_CHECK));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("✅ factory_super_admin 总是 bypass — PAYMENT_VOUCHER / QUALITY_CHECK / 所有 entityType")
    void factorySuperAdmin_bypasses_allChecks() {
        User admin = userWithRole(1L, FactoryUserRole.factory_super_admin);
        assertDoesNotThrow(() -> resolver.requireRead(admin, EntityType.PAYMENT_VOUCHER));
        assertDoesNotThrow(() -> resolver.requireRead(admin, EntityType.QUALITY_CHECK));
        assertDoesNotThrow(() -> resolver.requireWrite(admin, EntityType.PAYMENT_VOUCHER));
        // 不应该 call hasAnyPermission — admin 直接 bypass
        verify(permissionService, never()).hasAnyPermission(any(), any(String[].class));
    }

    @Test
    @DisplayName("✅ platform_admin 总是 bypass")
    void platformAdmin_bypasses_allChecks() {
        User admin = userWithRole(0L, FactoryUserRole.platform_admin);
        assertDoesNotThrow(() -> resolver.requireRead(admin, EntityType.PAYMENT_VOUCHER));
        verify(permissionService, never()).hasAnyPermission(any(), any(String[].class));
    }

    @Test
    @DisplayName("❌ 未登录 (user=null) → 401")
    void nullUser_throws401() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(null, EntityType.PURCHASE_ORDER));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("❌ entityType=null → 400")
    void nullEntityType_throws400() {
        User user = userWithRole(101L, FactoryUserRole.warehouse_manager);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(user, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("✅ requireWrite 仅 read_write 权限通过 (read 单独不够)")
    void requireWrite_demandsReadWrite() {
        User salesMgr = userWithRole(103L, FactoryUserRole.sales_manager);
        // Mock: 单独 sales:read 不够 — resolver 应只问 sales:read_write
        when(permissionService.hasAnyPermission(eq(salesMgr), eq(new String[]{"sales:read_write"}))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireWrite(salesMgr, EntityType.CUSTOMER));
        verify(permissionService).hasAnyPermission(eq(salesMgr), eq(new String[]{"sales:read_write"}));
    }

    @Test
    @DisplayName("✅ 采购收货附件写入按仓储职责授权")
    void purchaseReceipt_write_usesWarehousePermissions() {
        User warehouseWorker = userWithRole(104L, FactoryUserRole.warehouse_worker);
        String[] expected = {"warehouse:read_write", "inventory:write"};
        when(permissionService.hasAnyPermission(eq(warehouseWorker), eq(expected))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireWrite(warehouseWorker, EntityType.PURCHASE_RECEIPT));
        verify(permissionService).hasAnyPermission(eq(warehouseWorker), eq(expected));
    }

    @Test
    @DisplayName("✅ 采购人员只能只读查看采购收货凭证")
    void purchaseReceipt_read_preservesProcurementTraceability() {
        User procurementManager = userWithRole(105L, FactoryUserRole.procurement_manager);
        String[] expected = {"warehouse:read", "warehouse:read_write", "procurement:read", "procurement:read_write"};
        when(permissionService.hasAnyPermission(eq(procurementManager), eq(expected))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireRead(procurementManager, EntityType.PURCHASE_RECEIPT));
        verify(permissionService).hasAnyPermission(eq(procurementManager), eq(expected));
    }

    @Test
    @DisplayName("✅ 客户来料凭证写入与收货动作使用同一仓储权限")
    void customerSuppliedReceipt_write_usesWarehousePermissions() {
        User warehouseWorker = userWithRole(106L, FactoryUserRole.warehouse_worker);
        String[] expected = {"warehouse:read_write", "inventory:write"};
        when(permissionService.hasAnyPermission(eq(warehouseWorker), eq(expected))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireWrite(
                warehouseWorker, EntityType.CUSTOMER_SUPPLIED_RECEIPT));
        verify(permissionService).hasAnyPermission(eq(warehouseWorker), eq(expected));
    }

    @Test
    @DisplayName("❌ 仅采购权限不能查看客户来料凭证")
    void customerSuppliedReceipt_read_doesNotLeakToProcurementOnlyRole() {
        User procurementManager = userWithRole(107L, FactoryUserRole.procurement_manager);
        String[] expected = {"warehouse:read", "warehouse:read_write", "sales:read", "sales:read_write"};
        when(permissionService.hasAnyPermission(eq(procurementManager), eq(expected))).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> resolver.requireRead(procurementManager, EntityType.CUSTOMER_SUPPLIED_RECEIPT));
        assertEquals(403, exception.getCode());
        verify(permissionService).hasAnyPermission(eq(procurementManager), eq(expected));
    }

    // ==================== Upload guardrails ====================

    @Test
    @DisplayName("❌ 上传 .exe (application/octet-stream) → 400 not in whitelist")
    void uploadGuardrail_rejectsExecutable() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.validateUploadRequest("application/octet-stream", 1024L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不支持的文件类型"));
    }

    @Test
    @DisplayName("❌ 上传 20MB 文件 → 400 exceeds 10MB cap")
    void uploadGuardrail_rejectsOversize() {
        long twentyMb = 20L * 1024L * 1024L;
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.validateUploadRequest("application/pdf", twentyMb));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("10MB"));
    }

    @Test
    @DisplayName("✅ 上传 pdf / jpeg / png / xlsx / docx / mp4 在 whitelist 内")
    void uploadGuardrail_acceptsWhitelisted() {
        assertDoesNotThrow(() -> resolver.validateUploadRequest("application/pdf", 1024L));
        assertDoesNotThrow(() -> resolver.validateUploadRequest("image/jpeg", 1024L));
        assertDoesNotThrow(() -> resolver.validateUploadRequest("image/png", null));
        assertDoesNotThrow(() -> resolver.validateUploadRequest(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null));
        assertDoesNotThrow(() -> resolver.validateUploadRequest(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null));
        assertDoesNotThrow(() -> resolver.validateUploadRequest("video/mp4", 5L * 1024L * 1024L));
    }

    @Test
    @DisplayName("❌ Content-Type 为空 → 400")
    void uploadGuardrail_rejectsBlankContentType() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.validateUploadRequest("", null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("✅ Content-Type 大写 → 正常 (whitelist 比对小写)")
    void uploadGuardrail_caseInsensitive() {
        assertDoesNotThrow(() -> resolver.validateUploadRequest("Application/PDF", null));
    }

    // ==================== resolveCurrentUser ====================

    @Test
    @DisplayName("✅ resolveCurrentUser 从 request.userId 解析 User")
    void resolveCurrentUser_fromRequestAttribute() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        User u = userWithRole(42L, FactoryUserRole.procurement_manager);
        when(req.getAttribute("userId")).thenReturn(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        User result = resolver.resolveCurrentUser(req);
        assertSame(u, result);
    }

    @Test
    @DisplayName("✅ resolveCurrentUser 在 request=null → 返 null (closed-by-default)")
    void resolveCurrentUser_nullRequest_returnsNull() {
        assertNull(resolver.resolveCurrentUser(null));
    }

    @Test
    @DisplayName("✅ resolveCurrentUser 在 userId 属性缺失 → 返 null")
    void resolveCurrentUser_missingAttribute_returnsNull() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("userId")).thenReturn(null);
        assertNull(resolver.resolveCurrentUser(req));
    }
}
