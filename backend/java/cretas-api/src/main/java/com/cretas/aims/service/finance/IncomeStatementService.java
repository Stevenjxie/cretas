package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 7 T3 利润表 (Income Statement / P&L) 生成服务.
 *
 * <p>期间内 [startYear-startMonth 月初, endYear-endMonth 月底] 累计发生额. 算法:
 * <ol>
 *   <li>查 voucher entries 按 subjectCode 聚合 (期间过滤)</li>
 *   <li>按 Account.category 分桶:
 *     <ul>
 *       <li>REVENUE (CREDIT_NORMAL): amount = credit - debit (期间发生收入)</li>
 *       <li>COST (DEBIT_NORMAL): amount = debit - credit (期间发生成本)</li>
 *       <li>EXPENSE (DEBIT_NORMAL): amount = debit - credit (期间发生费用)</li>
 *     </ul>
 *   </li>
 *   <li>所得税识别: EXPENSE 中 accountName 含 "所得税" 的科目, 独立拆出</li>
 *   <li>毛利 = totalRevenue - totalCost</li>
 *   <li>营业利润 = 毛利 - totalExpense</li>
 *   <li>净利润 = 营业利润 - incomeTax</li>
 * </ol>
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncomeStatementService {

    private final VoucherEntryRepository voucherEntryRepo;
    private final VoucherRepository voucherRepo;
    private final AccountRepository accountRepo;

    public IncomeStatementDTO generate(String factoryId,
                                       Integer startYear, Integer startMonth,
                                       Integer endYear, Integer endMonth) {
        validateInput(factoryId, startYear, startMonth, endYear, endMonth);

        LocalDate startDate = YearMonth.of(startYear, startMonth).atDay(1);
        LocalDate endDate = YearMonth.of(endYear, endMonth).atEndOfMonth();
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate after endDate: " + startDate + " > " + endDate);
        }

        log.info("[IncomeStatement] generate factoryId={}, range={} to {}", factoryId, startDate, endDate);

        // Step 1: 聚合期间 entries by subjectCode
        // F006 财务审计 Bug 6 (2026-07-04): 官方报表切到 POSTED-only 口径 (排除 DRAFT 未审草稿);
        // 同时排除结转产物 (PL_CLOSING/COST_CARRYOVER 红冲镜像) — 否则已结账期间的利润表会显示
        // 营业收入=0/成本=0 (被结转分录清零), 见 aggregateBySubjectExcludingDraftAndClosing javadoc。
        List<SubjectAggregateRow> aggregates = voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(
                factoryId, startDate, endDate);

        // Step 2: 预取 Account 索引
        List<Account> allAccounts = accountRepo.findVisibleToFactory(factoryId);
        Map<String, Account> accountByCode = new HashMap<>();
        for (Account a : allAccounts) {
            accountByCode.merge(a.getCode(), a, (existing, incoming) ->
                    existing.getFactoryId() != null ? existing : incoming);
        }

        // Step 3: 分桶 REVENUE / COST / EXPENSE
        List<BalanceSheetDTO.LineItem> revenues = new ArrayList<>();
        List<BalanceSheetDTO.LineItem> costs = new ArrayList<>();
        List<BalanceSheetDTO.LineItem> expenses = new ArrayList<>();
        BigDecimal incomeTax = BigDecimal.ZERO;

        for (SubjectAggregateRow row : aggregates) {
            Account account = accountByCode.get(row.getSubjectCode());
            String name = (account != null) ? account.getName() : row.getSubjectName();
            String code = row.getSubjectCode();

            AccountCategory cat = (account != null) ? account.getCategory() : inferCategoryByCode(code);
            if (cat == null) continue;

            if (cat == AccountCategory.REVENUE) {
                // CREDIT_NORMAL: credit - debit
                revenues.add(BalanceSheetDTO.LineItem.builder()
                        .accountCode(code)
                        .accountName(name)
                        .amount(safeSubtract(row.getTotalCredit(), row.getTotalDebit()))
                        .build());
            } else if (cat == AccountCategory.COST) {
                // DEBIT_NORMAL: debit - credit
                costs.add(BalanceSheetDTO.LineItem.builder()
                        .accountCode(code)
                        .accountName(name)
                        .amount(safeSubtract(row.getTotalDebit(), row.getTotalCredit()))
                        .build());
            } else if (cat == AccountCategory.EXPENSE) {
                BigDecimal amount = safeSubtract(row.getTotalDebit(), row.getTotalCredit());
                BalanceSheetDTO.LineItem item = BalanceSheetDTO.LineItem.builder()
                        .accountCode(code)
                        .accountName(name)
                        .amount(amount)
                        .build();
                // 所得税识别 — accountName 含 "所得税"
                if (name != null && name.contains("所得税")) {
                    incomeTax = incomeTax.add(amount);
                }
                expenses.add(item);
            }
            // ASSET/LIABILITY/EQUITY skip — 归资产负债表
        }

        Comparator<BalanceSheetDTO.LineItem> byCode = Comparator.comparing(BalanceSheetDTO.LineItem::getAccountCode);
        revenues.sort(byCode);
        costs.sort(byCode);
        expenses.sort(byCode);

        BigDecimal totalRevenue = sumLineItems(revenues);
        BigDecimal totalCost = sumLineItems(costs);
        BigDecimal totalExpense = sumLineItems(expenses);
        BigDecimal grossProfit = totalRevenue.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
        BigDecimal operatingProfit = grossProfit.subtract(totalExpense).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netProfit = operatingProfit.subtract(incomeTax).setScale(2, RoundingMode.HALF_UP);

        boolean costDataAvailable = !costs.isEmpty();
        IncomeStatementDTO.GrossMarginStatus grossMarginStatus;
        String grossMarginStatusMessage;
        if (totalRevenue.signum() <= 0) {
            grossMarginStatus = IncomeStatementDTO.GrossMarginStatus.NO_REVENUE;
            grossMarginStatusMessage = "期间营业收入为零或缺失，毛利率不可计算";
        } else if (!costDataAvailable) {
            grossMarginStatus = IncomeStatementDTO.GrossMarginStatus.MISSING_COST_DATA;
            grossMarginStatusMessage = "期间没有已过账的营业成本分录，不能把缺失成本当作 0 计算毛利率";
        } else {
            grossMarginStatus = IncomeStatementDTO.GrossMarginStatus.CALCULABLE;
            grossMarginStatusMessage = "毛利率可按已过账营业收入和营业成本计算";
        }

        // 待过账 (DRAFT) 凭证数 — 见 pendingDraftVoucherCount javadoc。
        long pendingDraftCount = voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                factoryId, VoucherStatus.DRAFT, startDate, endDate);

        return IncomeStatementDTO.builder()
                .costDataAvailable(costDataAvailable)
                .grossMarginStatus(grossMarginStatus)
                .grossMarginStatusMessage(grossMarginStatusMessage)
                .factoryId(factoryId)
                .startYear(startYear)
                .startMonth(startMonth)
                .endYear(endYear)
                .endMonth(endMonth)
                .revenues(revenues)
                .costs(costs)
                .expenses(expenses)
                .totalRevenue(totalRevenue)
                .totalCost(totalCost)
                .grossProfit(grossProfit)
                .totalExpense(totalExpense)
                .operatingProfit(operatingProfit)
                .incomeTax(incomeTax.setScale(2, RoundingMode.HALF_UP))
                .netProfit(netProfit)
                .generatedAt(LocalDateTime.now().toString())
                .pendingDraftVoucherCount(pendingDraftCount)
                .build();
    }

    private void validateInput(String factoryId, Integer startYear, Integer startMonth,
                               Integer endYear, Integer endMonth) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId is required");
        }
        if (startYear == null || startMonth == null || endYear == null || endMonth == null) {
            throw new IllegalArgumentException("startYear/startMonth/endYear/endMonth required");
        }
        if (startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) {
            throw new IllegalArgumentException("month must be 1-12");
        }
    }

    /**
     * Account-less legacy entry fallback: by code prefix.
     * 5/6xxx → REVENUE/COST/EXPENSE; cannot reliably distinguish, default 看 subjectName.
     */
    private AccountCategory inferCategoryByCode(String code) {
        if (code == null || code.isEmpty()) return null;
        char first = code.charAt(0);
        if (first == '5') return AccountCategory.REVENUE;  // 5xxx 收入类
        if (first == '6') return AccountCategory.COST;      // 6xxx 成本类 (or EXPENSE — ambiguous)
        return null;  // 1/2/3/4 由 BalanceSheet 处理
    }

    private BigDecimal safeSubtract(BigDecimal a, BigDecimal b) {
        BigDecimal x = a != null ? a : BigDecimal.ZERO;
        BigDecimal y = b != null ? b : BigDecimal.ZERO;
        return x.subtract(y).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumLineItems(List<BalanceSheetDTO.LineItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BalanceSheetDTO.LineItem item : items) {
            if (item.getAmount() != null) {
                sum = sum.add(item.getAmount());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }
}
