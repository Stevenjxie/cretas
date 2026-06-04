package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.MonthCloseReconciliationDTO;
import com.cretas.aims.dto.finance.MonthCloseResultDTO;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.finance.MonthCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Wave2 月结自动闭环 REST API.
 *
 * <p>兑现邓总 "30号数据完结, 1-3号出报表, 留20天调整窗口"。路径
 * <code>/api/mobile/{factoryId}/finance/month-close</code>:
 * <ul>
 *   <li>GET  /preview?year=&month=  月结对账预览 (防呆 R1 预先显示边界)</li>
 *   <li>POST /execute               一键月结 (对账→P&L→快照→CLOSED+20天窗口, finance:read_write)</li>
 *   <li>GET  /board                 月结看板 (各月状态/结账时间/调整窗口截止)</li>
 * </ul>
 *
 * <p>RBAC: 读 finance:read / finance:read_write; execute 需 finance:read_write.
 *
 * @since 2026-06-04 Wave2
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/month-close")
@RequiredArgsConstructor
@RequirePermission({"finance:read", "finance:read_write"})
public class MonthCloseController {

    private final MonthCloseService monthCloseService;
    private final AccountingPeriodService periodService;

    /**
     * 月结对账预览. 不写库. 防呆 R1: 用户点"一键月结"前先看对账状态.
     */
    @GetMapping("/preview")
    public ResponseEntity<ApiResponse<MonthCloseReconciliationDTO>> preview(
            @PathVariable String factoryId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        MonthCloseReconciliationDTO data = monthCloseService.previewClose(factoryId, year, month);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 执行一键月结. 对账校验通过 → 生成 P&L → 快照锁定 → CLOSED + 20天调整窗口.
     * 对账未通过 → 400 明确报错 (禁降级, 不静默结账). 已 CLOSED → 409 (幂等防重复).
     */
    @PostMapping("/execute")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<MonthCloseResultDTO>> execute(
            @PathVariable String factoryId,
            @RequestBody MonthCloseRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        MonthCloseResultDTO data = monthCloseService.executeClose(
                factoryId, req.year(), req.month(), userId);
        return ResponseEntity.ok(ApiResponse.success(data.getMessage(), data));
    }

    /**
     * 月结看板 — 列出 factory 全部期间 (含 Wave2 调整窗口 / 快照字段), 按年月 DESC.
     */
    @GetMapping("/board")
    public ResponseEntity<ApiResponse<List<AccountingPeriod>>> board(
            @PathVariable String factoryId) {
        List<AccountingPeriod> data = periodService.listByFactory(factoryId);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ==================== DTO record ====================

    public record MonthCloseRequest(Integer year, Integer month) {}
}
