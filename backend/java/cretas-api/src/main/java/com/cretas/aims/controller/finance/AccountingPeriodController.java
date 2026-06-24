package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.service.finance.AccountingPeriodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sprint 7 T2 F-PERIOD: 期间结账 REST API.
 *
 * <p>路径 <code>/api/mobile/{factoryId}/finance/accounting-periods</code>:
 * <ul>
 *   <li>POST /open          打开 (year, month) 期间 (OPEN)</li>
 *   <li>POST /request-close 发起结账请求 (OPEN → PENDING_CLOSE)</li>
 *   <li>POST /confirm-close 确认结账 (PENDING_CLOSE → CLOSED, finance_manager only)</li>
 *   <li>POST /reopen        反结账 (CLOSED → OPEN, finance_manager only, 必填 reason)</li>
 *   <li>GET  /status        查询 (year, month) status (无 row → OPEN)</li>
 *   <li>GET                 list all periods</li>
 *   <li>GET  /{year}/{month} get detail (含 audit fields)</li>
 * </ul>
 *
 * <p>RBAC: 读 finance:read, 写 finance:read_write. confirmClose / reopen 需 finance_manager
 * 或 factory_super_admin role (Controller 层 @RequirePermission).
 *
 * @since 2026-05-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/accounting-periods")
@RequiredArgsConstructor
@RequirePermission({"finance:read", "finance:read_write"})
public class AccountingPeriodController {

    private final AccountingPeriodService periodService;

    /**
     * 列出 factory 内全部期间, 按年月 DESC. admin UI grid 用.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AccountingPeriod>>> list(
            @PathVariable String factoryId) {
        List<AccountingPeriod> data = periodService.listByFactory(factoryId);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 查询 (year, month) period 状态. 无 row 返 OPEN.
     * 防呆 R2: response 含 status + 全部 audit fields (lastClosedAt / reopenReason 等)
     * 用于前端弹窗 context 显示.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @PathVariable String factoryId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        AccountingPeriod.Status status = periodService.getStatus(factoryId, year, month);
        AccountingPeriod period = periodService.findPeriod(factoryId, year, month).orElse(null);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("factoryId", factoryId);
        body.put("year", year);
        body.put("month", month);
        body.put("status", status);
        body.put("period", period);  // null 时前端按 backwards compat OPEN 显示
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Period detail by (year, month).
     */
    @GetMapping("/{year}/{month}")
    public ResponseEntity<ApiResponse<AccountingPeriod>> detail(
            @PathVariable String factoryId,
            @PathVariable Integer year,
            @PathVariable Integer month) {
        AccountingPeriod p = periodService.findPeriod(factoryId, year, month)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(404,
                        String.format("%d-%02d 期间无记录", year, month)));
        return ResponseEntity.ok(ApiResponse.success(p));
    }

    /**
     * 打开期间 (创建 OPEN row).
     */
    @PostMapping("/open")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<AccountingPeriod>> openPeriod(
            @PathVariable String factoryId,
            @RequestBody PeriodRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        AccountingPeriod p = periodService.openPeriod(factoryId, req.year(), req.month(), userId);
        return ResponseEntity.ok(ApiResponse.success("期间已打开", p));
    }

    /**
     * 发起结账请求. OPEN → PENDING_CLOSE + 触发 BUDGET_APPROVAL workflow.
     */
    @PostMapping("/request-close")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<AccountingPeriod>> requestClose(
            @PathVariable String factoryId,
            @RequestBody PeriodRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        AccountingPeriod p = periodService.requestClose(factoryId, req.year(), req.month(), userId);
        return ResponseEntity.ok(ApiResponse.success("结账请求已提交, 等待 finance director 审批", p));
    }

    /**
     * 确认结账. PENDING_CLOSE → CLOSED. voucher 写 gate 立即生效.
     * 需 finance_manager / factory_super_admin role.
     */
    @PostMapping("/confirm-close")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<AccountingPeriod>> confirmClose(
            @PathVariable String factoryId,
            @RequestBody PeriodRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        AccountingPeriod p = periodService.confirmClose(factoryId, req.year(), req.month(), userId);
        return ResponseEntity.ok(ApiResponse.success("期间已结账, 凭证写入已锁定", p));
    }

    /**
     * 反结账. CLOSED → OPEN. 必填原因.
     * 需 finance_manager / factory_super_admin role.
     */
    @PostMapping("/reopen")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<AccountingPeriod>> reopen(
            @PathVariable String factoryId,
            @RequestBody ReopenRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        AccountingPeriod p = periodService.reopenPeriod(factoryId, req.year(), req.month(),
                req.reason(), userId);
        return ResponseEntity.ok(ApiResponse.success("期间已反结账, voucher 可继续修改", p));
    }

    /**
     * 立即锁定结转 (逃生口). CLOSED 期间强制 LOCKED 并立即结转损益, 不必等 20 天调整窗口。
     * 财务确认无后续调整时用。需 finance_manager / factory_super_admin role.
     */
    @PostMapping("/force-lock-close")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<AccountingPeriod>> forceLockClose(
            @PathVariable String factoryId,
            @RequestBody PeriodRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        AccountingPeriod p = periodService.forceLockAndClose(factoryId, req.year(), req.month(), userId);
        return ResponseEntity.ok(ApiResponse.success("期间已锁定并结转损益", p));
    }

    // ==================== DTO records ====================

    public record PeriodRequest(Integer year, Integer month) {}
    public record ReopenRequest(Integer year, Integer month, String reason) {}
}
