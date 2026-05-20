package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.service.finance.BalanceSheetService;
import com.cretas.aims.service.finance.CashFlowService;
import com.cretas.aims.service.finance.IncomeStatementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprint 7 T3 报表三表 (Balance Sheet / Income Statement / Cash Flow) REST API.
 *
 * <p>路径 <code>/api/mobile/{factoryId}/finance/report</code>:
 * <ul>
 *   <li>GET /balance-sheet?year=2026&month=5
 *       — 截至 month 月底的资产负债表</li>
 *   <li>GET /income-statement?startYear=2026&startMonth=1&endYear=2026&endMonth=5
 *       — 期间累计利润表 (1-5 月)</li>
 *   <li>GET /cash-flow?startYear=2026&startMonth=1&endYear=2026&endMonth=5
 *       — 期间累计现金流量表 (直接法)</li>
 * </ul>
 *
 * <p>RBAC: finance:read / finance:read_write (跟 VoucherController 对齐). 价格敏感字段
 * 由 GlobalPriceSensitiveAdvice 在 BigDecimal 上自动 mask, 三表 DTO 用 BigDecimal 直接
 * 反映 voucher_entry 金额, 因此非 finance role 看不到任何金额数字.
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/report")
@RequiredArgsConstructor
@RequirePermission({"finance:read", "finance:read_write"})
public class FinanceReportController {

    private final BalanceSheetService balanceSheetService;
    private final IncomeStatementService incomeStatementService;
    private final CashFlowService cashFlowService;

    /**
     * 资产负债表 (截至 year-month 月底余额).
     */
    @GetMapping("/balance-sheet")
    public ResponseEntity<ApiResponse<BalanceSheetDTO>> balanceSheet(
            @PathVariable String factoryId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        BalanceSheetDTO dto = balanceSheetService.generate(factoryId, year, month);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * 利润表 (期间累计).
     */
    @GetMapping("/income-statement")
    public ResponseEntity<ApiResponse<IncomeStatementDTO>> incomeStatement(
            @PathVariable String factoryId,
            @RequestParam Integer startYear,
            @RequestParam Integer startMonth,
            @RequestParam Integer endYear,
            @RequestParam Integer endMonth) {
        IncomeStatementDTO dto = incomeStatementService.generate(
                factoryId, startYear, startMonth, endYear, endMonth);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * 现金流量表 (直接法, 期间累计).
     */
    @GetMapping("/cash-flow")
    public ResponseEntity<ApiResponse<CashFlowDTO>> cashFlow(
            @PathVariable String factoryId,
            @RequestParam Integer startYear,
            @RequestParam Integer startMonth,
            @RequestParam Integer endYear,
            @RequestParam Integer endMonth) {
        CashFlowDTO dto = cashFlowService.generate(
                factoryId, startYear, startMonth, endYear, endMonth);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }
}
