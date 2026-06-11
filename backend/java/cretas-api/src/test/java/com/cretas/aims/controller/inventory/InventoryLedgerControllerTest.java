package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.InventoryLedgerDTO;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP11 W8 残部: InventoryLedgerController 单元测试.
 *
 * <p>覆盖两个关键合规点:
 * <ol>
 *   <li><b>R2 脱敏</b>: 非财务角色调用台账查询 → 返回数据 (Controller 本身不过滤 JSON 列,
 *       @PriceSensitive 由 PriceFieldResponseAdvice 在序列化层处理); 导出 xlsx 时
 *       resolveCanViewPrices 返回 false, Service 以 includePrices=false 调用 → 不含金额列.</li>
 *   <li><b>幂等性</b>: 同参数连续两次导出 → Service 以相同参数被调用两次且均成功,
 *       响应一致 (无状态变更, 导出纯读).</li>
 * </ol>
 *
 * <p>使用 MockitoExtension 直接实例化 Controller, 不起 Spring 容器.
 * Service 层 mock 返回确定性数据, 测试专注于 Controller 层职责:
 * includePrices 解析逻辑 + 幂等调用保证.
 *
 * @since SP11 W8残部 2026-06-11
 */
@DisplayName("InventoryLedgerController: R2脱敏 + 幂等导出")
@ExtendWith(MockitoExtension.class)
class InventoryLedgerControllerTest {

    private static final String FACTORY_ID = "F-CTRL-TEST";
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END   = LocalDate.of(2026, 5, 31);

    @Mock private InventoryLedgerService ledgerService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private InventoryLedgerController controller;

    @BeforeEach
    void setUp() {
        controller = new InventoryLedgerController(ledgerService, permissionService, userRepository);
    }

    // ========================= Helper builders =========================

    private InventoryLedgerLineDTO buildLine(BigDecimal closingQty, BigDecimal closingAmount) {
        return InventoryLedgerLineDTO.builder()
                .materialTypeId("MAT-001")
                .materialName("猪舌")
                .unit("kg")
                .openingQty(new BigDecimal("50.000000"))
                .inboundQty(new BigDecimal("100.000000"))
                .closingQty(closingQty)
                .closingAmount(closingAmount)
                .build();
    }

    private User buildUser(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    /** 设置 response.getOutputStream() 返回一个可写的 ByteArrayOutputStream */
    private ByteArrayOutputStream stubResponseOutputStream() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            @Override public void write(int b) { baos.write(b); }
            @Override public void write(byte[] b, int off, int len) { baos.write(b, off, len); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener wl) {}
        });
        return baos;
    }

    // ========================= Tests: getLedger =========================

    @Test
    @DisplayName("C1: getLedger — 返回 success=true, lines 含 Service 数据")
    void getLedger_returnsServiceData() {
        InventoryLedgerLineDTO line = buildLine(
                new BigDecimal("150.000000"), new BigDecimal("9000.00"));
        when(ledgerService.getLedger(eq(FACTORY_ID), eq(START), eq(END), isNull()))
                .thenReturn(List.of(line));

        ResponseEntity<ApiResponse<InventoryLedgerDTO>> resp =
                controller.getLedger(FACTORY_ID, START, END, null);

        assertNotNull(resp, "响应不应为 null");
        assertEquals(200, resp.getStatusCode().value(), "HTTP 状态应为 200");
        ApiResponse<InventoryLedgerDTO> body = resp.getBody();
        assertNotNull(body, "响应体不应为 null");
        assertTrue(body.getSuccess(), "success 应为 true");
        assertNotNull(body.getData(), "data 不应为 null");
        assertEquals(FACTORY_ID, body.getData().getFactoryId());
        assertEquals(1, body.getData().getLines().size(), "应返回 1 行数据");
        assertEquals(new BigDecimal("150.000000"),
                body.getData().getLines().get(0).getClosingQty());
    }

    @Test
    @DisplayName("C2: getLedger — Service 返回空列表时 lines 不为 null 且为空")
    void getLedger_emptyLines_notNull() {
        when(ledgerService.getLedger(anyString(), any(), any(), any()))
                .thenReturn(List.of());

        ResponseEntity<ApiResponse<InventoryLedgerDTO>> resp =
                controller.getLedger(FACTORY_ID, START, END, null);

        assertNotNull(resp.getBody().getData().getLines(), "空台账 lines 不应为 null");
        assertTrue(resp.getBody().getData().getLines().isEmpty(), "空台账 lines 应为空列表");
    }

    // ========================= Tests: R2 脱敏 (resolveCanViewPrices) =========================

    @Test
    @DisplayName("C3 R2: userId 缺失 (request.getAttribute=null) → resolveCanViewPrices=false → Service 以 includePrices=false 调用")
    void exportLedger_noUserId_includesPricesFalse() throws Exception {
        when(request.getAttribute("userId")).thenReturn(null);
        when(ledgerService.exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class)))
                .thenReturn("台账_2026-05.xlsx");
        stubResponseOutputStream();

        controller.exportLedger(FACTORY_ID, START, END, null, request, response);

        verify(ledgerService).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class));
    }

    @Test
    @DisplayName("C4 R2: userId 存在但用户不存在 (DB miss) → resolveCanViewPrices=false → includePrices=false")
    void exportLedger_userNotFound_includesPricesFalse() throws Exception {
        when(request.getAttribute("userId")).thenReturn(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        when(ledgerService.exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class)))
                .thenReturn("台账_2026-05.xlsx");
        stubResponseOutputStream();

        controller.exportLedger(FACTORY_ID, START, END, null, request, response);

        verify(ledgerService).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class));
        // permissionService 不应被调用 (用户不存在, fail-CLOSED 短路)
        verifyNoInteractions(permissionService);
    }

    @Test
    @DisplayName("C5 R2: 用户有双权限 (price:view + finance:read_write) → resolveCanViewPrices=true → includePrices=true")
    void exportLedger_financeUser_includesPricesTrue() throws Exception {
        User financeUser = buildUser(10L, "finance_mgr");
        when(request.getAttribute("userId")).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(financeUser));
        when(permissionService.hasPermission(financeUser, "procurement:price:view")).thenReturn(true);
        when(permissionService.hasPermission(financeUser, "finance:read_write")).thenReturn(true);
        when(ledgerService.exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(true), any(OutputStream.class)))
                .thenReturn("台账_含金额_2026-05.xlsx");
        stubResponseOutputStream();

        controller.exportLedger(FACTORY_ID, START, END, null, request, response);

        verify(ledgerService).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(true), any(OutputStream.class));
    }

    @Test
    @DisplayName("C6 R2: 用户只有 price:view 但缺 finance:read_write → resolveCanViewPrices=false → includePrices=false (双门验证)")
    void exportLedger_priceViewOnlyNoPrices() throws Exception {
        User warehouseUser = buildUser(20L, "warehouse_worker");
        when(request.getAttribute("userId")).thenReturn(20L);
        when(userRepository.findById(20L)).thenReturn(Optional.of(warehouseUser));
        when(permissionService.hasPermission(warehouseUser, "procurement:price:view")).thenReturn(true);
        when(permissionService.hasPermission(warehouseUser, "finance:read_write")).thenReturn(false);
        when(ledgerService.exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class)))
                .thenReturn("台账_无金额_2026-05.xlsx");
        stubResponseOutputStream();

        controller.exportLedger(FACTORY_ID, START, END, null, request, response);

        verify(ledgerService).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class));
    }

    @Test
    @DisplayName("C7 R2: userId 为 Integer 类型 (而非 Long) → 正确转 Long, 不抛 ClassCastException")
    void exportLedger_userIdAsInteger_noClassCast() throws Exception {
        User user = buildUser(30L, "ops_user");
        // JwtAuthInterceptor 有时以 Integer 存 userId
        when(request.getAttribute("userId")).thenReturn(30);  // Integer 而非 Long
        when(userRepository.findById(30L)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(eq(user), anyString())).thenReturn(false);
        when(ledgerService.exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class)))
                .thenReturn("台账_2026-05.xlsx");
        stubResponseOutputStream();

        // 不应抛 ClassCastException
        assertDoesNotThrow(() ->
                controller.exportLedger(FACTORY_ID, START, END, null, request, response));
        verify(ledgerService).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class));
    }

    @Test
    @DisplayName("C8 R2: permissionService 抛异常 → fail-CLOSED (includePrices=false), 不向上传播")
    void exportLedger_permissionServiceThrows_failClosed() throws Exception {
        User user = buildUser(50L, "admin_user");
        when(request.getAttribute("userId")).thenReturn(50L);
        when(userRepository.findById(50L)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(eq(user), anyString()))
                .thenThrow(new RuntimeException("权限服务暂不可用"));
        when(ledgerService.exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class)))
                .thenReturn("台账_2026-05.xlsx");
        stubResponseOutputStream();

        // fail-CLOSED: 异常不应传播到 caller, 以 includePrices=false 继续
        assertDoesNotThrow(() ->
                controller.exportLedger(FACTORY_ID, START, END, null, request, response));
        verify(ledgerService).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class));
    }

    // ========================= Tests: 幂等性 =========================

    @Test
    @DisplayName("C9 幂等: 同参数两次 getLedger → Service 被调用两次, 两次结果结构相同")
    void getLedger_calledTwiceWithSameParams_idempotent() {
        InventoryLedgerLineDTO line = buildLine(
                new BigDecimal("150.000000"), new BigDecimal("9000.00"));
        when(ledgerService.getLedger(eq(FACTORY_ID), eq(START), eq(END), isNull()))
                .thenReturn(List.of(line));

        ResponseEntity<ApiResponse<InventoryLedgerDTO>> resp1 =
                controller.getLedger(FACTORY_ID, START, END, null);
        ResponseEntity<ApiResponse<InventoryLedgerDTO>> resp2 =
                controller.getLedger(FACTORY_ID, START, END, null);

        // Service 被调用两次 (导出是纯读, 无状态变更)
        verify(ledgerService, times(2))
                .getLedger(eq(FACTORY_ID), eq(START), eq(END), isNull());

        // 两次响应结构一致
        assertEquals(resp1.getStatusCode(), resp2.getStatusCode(), "两次 HTTP 状态应一致");
        assertEquals(resp1.getBody().getSuccess(), resp2.getBody().getSuccess(),
                "两次 success 应一致");
        assertEquals(resp1.getBody().getData().getLines().size(),
                resp2.getBody().getData().getLines().size(), "两次 lines.size 应一致");
    }

    @Test
    @DisplayName("C10 幂等: 同参数两次 exportLedger → Service 被调用两次, 无状态副作用")
    void exportLedger_calledTwiceSameParams_idempotent() throws Exception {
        when(request.getAttribute("userId")).thenReturn(null);
        when(ledgerService.exportInventoryLedger(
                anyString(), any(), any(), any(), anyBoolean(), any(OutputStream.class)))
                .thenReturn("台账_2026-05.xlsx");
        stubResponseOutputStream();

        controller.exportLedger(FACTORY_ID, START, END, null, request, response);
        // 第二次调用 (response mock 已用, 重新 stub OutputStream)
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            @Override public void write(int b) { baos2.write(b); }
            @Override public void write(byte[] b, int off, int len) { baos2.write(b, off, len); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener wl) {}
        });
        controller.exportLedger(FACTORY_ID, START, END, null, request, response);

        // Service 被调用两次, 无异常 — 幂等保证
        verify(ledgerService, times(2)).exportInventoryLedger(
                eq(FACTORY_ID), eq(START), eq(END), isNull(), eq(false), any(OutputStream.class));
    }
}
