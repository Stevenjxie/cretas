package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
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
 * Sprint 7 T3 资产负债表 (Balance Sheet) 生成服务.
 *
 * <p>截至期末 (year, month 月底) 的余额快照. 算法:
 * <ol>
 *   <li>取 [1970-01-01, lastDayOfMonth] 期间所有 voucher entries 按 subjectCode 聚合</li>
 *   <li>每个聚合行按 subjectCode 查 Account (factory + 系统级 union), 拿到 category + balanceType</li>
 *   <li>DEBIT_NORMAL 科目 (ASSET): amount = debit - credit; 仅 ASSET 类纳入资产部分</li>
 *   <li>CREDIT_NORMAL 科目 (LIABILITY/EQUITY/REVENUE): amount = credit - debit; LIABILITY / EQUITY 分别归类</li>
 *   <li>REVENUE / COST / EXPENSE 不纳入资产负债表 (归利润表)</li>
 *   <li>负 amount 的科目 (e.g. 错误录入) 仍按原符号显示 + balanceCheck 会捕获</li>
 * </ol>
 *
 * <p>balanceCheck = (|totalAssets - (totalLiabilities + totalEquity)| <= 0.01).
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceSheetService {

    private final VoucherEntryRepository voucherEntryRepo;
    private final AccountRepository accountRepo;
    private final AccountingPeriodService accountingPeriodService;

    /** 资产负债表起始日 — 1970-01-01 (Unix epoch) 作为"远古", 取期初到期末累计. */
    private static final LocalDate EPOCH_START = LocalDate.of(1970, 1, 1);

    /**
     * 生成资产负债表 (截至 year-month 月底).
     */
    public BalanceSheetDTO generate(String factoryId, Integer year, Integer month) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId is required");
        }
        if (year == null || month == null || month < 1 || month > 12) {
            throw new IllegalArgumentException("year + month (1-12) required");
        }

        LocalDate endDate = YearMonth.of(year, month).atEndOfMonth();
        log.info("[BalanceSheet] generate factoryId={}, year={}, month={}, endDate={}",
                factoryId, year, month, endDate);

        // Step 1: 聚合 subjectCode → totalDebit/totalCredit
        List<SubjectAggregateRow> aggregates = voucherEntryRepo.aggregateBySubject(
                factoryId, EPOCH_START, endDate);

        // Step 2: 一次性预取 factory 全部可见 Account, 建 code → Account 索引
        List<Account> allAccounts = accountRepo.findVisibleToFactory(factoryId);
        Map<String, Account> accountByCode = new HashMap<>();
        for (Account a : allAccounts) {
            // factory 自定义优先 (覆盖系统级 if 同 code)
            accountByCode.merge(a.getCode(), a, (existing, incoming) ->
                    existing.getFactoryId() != null ? existing : incoming);
        }

        // Step 3: 分桶
        List<BalanceSheetDTO.LineItem> assets = new ArrayList<>();
        List<BalanceSheetDTO.LineItem> liabilities = new ArrayList<>();
        List<BalanceSheetDTO.LineItem> equityItems = new ArrayList<>();

        for (SubjectAggregateRow row : aggregates) {
            Account account = accountByCode.get(row.getSubjectCode());
            if (account == null) {
                // 没绑 Account 的 legacy entry — 按 subjectCode 前缀简单分类 fallback
                BalanceSheetDTO.LineItem item = buildLineItem(row, /*useDebitMinusCredit=*/true);
                if (row.getSubjectCode() == null) continue;
                String prefix = row.getSubjectCode();
                if (prefix.startsWith("1")) {
                    assets.add(item);
                } else if (prefix.startsWith("2")) {
                    liabilities.add(buildLineItem(row, false));
                } else if (prefix.startsWith("3") || prefix.startsWith("4")) {
                    equityItems.add(buildLineItem(row, false));
                }
                // 5/6xxx (REVENUE/COST/EXPENSE) skip — 归利润表
                continue;
            }

            AccountCategory cat = account.getCategory();
            AccountBalanceType bt = account.getBalanceType();

            if (cat == AccountCategory.ASSET) {
                // DEBIT_NORMAL: debit - credit
                assets.add(BalanceSheetDTO.LineItem.builder()
                        .accountCode(account.getCode())
                        .accountName(account.getName())
                        .amount(safeSubtract(row.getTotalDebit(), row.getTotalCredit()))
                        .build());
            } else if (cat == AccountCategory.LIABILITY) {
                // CREDIT_NORMAL: credit - debit
                liabilities.add(BalanceSheetDTO.LineItem.builder()
                        .accountCode(account.getCode())
                        .accountName(account.getName())
                        .amount(safeSubtract(row.getTotalCredit(), row.getTotalDebit()))
                        .build());
            } else if (cat == AccountCategory.EQUITY) {
                equityItems.add(BalanceSheetDTO.LineItem.builder()
                        .accountCode(account.getCode())
                        .accountName(account.getName())
                        .amount(safeSubtract(row.getTotalCredit(), row.getTotalDebit()))
                        .build());
            }
            // REVENUE / COST / EXPENSE → 归利润表, skip 资产负债表
        }

        // Step 4: 按 accountCode 排序
        Comparator<BalanceSheetDTO.LineItem> byCode = Comparator.comparing(BalanceSheetDTO.LineItem::getAccountCode);
        assets.sort(byCode);
        liabilities.sort(byCode);
        equityItems.sort(byCode);

        // Step 5: 合计
        BigDecimal totalAssets = sumLineItems(assets);
        BigDecimal totalLiabilities = sumLineItems(liabilities);
        BigDecimal totalEquity = sumLineItems(equityItems);
        BigDecimal totalLiabilitiesAndEquity = totalLiabilities.add(totalEquity);

        // Step 6: 平衡校验 (|diff| <= 0.01)
        BigDecimal diff = totalAssets.subtract(totalLiabilitiesAndEquity).abs();
        boolean balanced = diff.compareTo(new BigDecimal("0.01")) <= 0;

        // Step 7: 期间结账状态 (Rule 5 dead-end — UI 显示 "未结账" 警告)
        AccountingPeriod.Status periodStatus = accountingPeriodService.getStatus(factoryId, year, month);

        return BalanceSheetDTO.builder()
                .factoryId(factoryId)
                .year(year)
                .month(month)
                .periodStatus(periodStatus)
                .assets(assets)
                .liabilities(liabilities)
                .equity(equityItems)
                .totalAssets(totalAssets)
                .totalLiabilities(totalLiabilities)
                .totalEquity(totalEquity)
                .totalLiabilitiesAndEquity(totalLiabilitiesAndEquity)
                .balanceCheck(balanced)
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    private BalanceSheetDTO.LineItem buildLineItem(SubjectAggregateRow row, boolean useDebitMinusCredit) {
        BigDecimal amount = useDebitMinusCredit
                ? safeSubtract(row.getTotalDebit(), row.getTotalCredit())
                : safeSubtract(row.getTotalCredit(), row.getTotalDebit());
        return BalanceSheetDTO.LineItem.builder()
                .accountCode(row.getSubjectCode())
                .accountName(row.getSubjectName())
                .amount(amount)
                .build();
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
