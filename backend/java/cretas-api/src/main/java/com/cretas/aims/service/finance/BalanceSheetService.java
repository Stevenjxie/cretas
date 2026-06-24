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

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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
        // 未结转损益 (REVENUE/COST/EXPENSE 净额) 累计 → 期末作为"未分配利润"权益合成行.
        // 不滚入权益则任何有收入但未结账的工厂资产负债表永远不平 (assets 因收入增加,
        // 权益却为 0). 统一符号 (credit - debit): 收入贷方为正, 成本/费用借方为负.
        BigDecimal retainedEarnings = BigDecimal.ZERO;

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
                } else if (prefix.startsWith("5") || prefix.startsWith("6")) {
                    // REVENUE/COST/EXPENSE legacy entry — 滚入未分配利润 (credit - debit)
                    retainedEarnings = retainedEarnings.add(
                            safeSubtract(row.getTotalCredit(), row.getTotalDebit()));
                }
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
            } else if (cat == AccountCategory.REVENUE
                    || cat == AccountCategory.COST
                    || cat == AccountCategory.EXPENSE) {
                // 未结转损益滚入未分配利润. 统一 credit - debit:
                //   REVENUE (贷方正常): credit - debit = +收入
                //   COST / EXPENSE (借方正常): credit - debit = -成本/费用
                // 结账后 (借收入/贷本年利润) 收入科目净额归零 → 此处自动为 0, 不重复计.
                retainedEarnings = retainedEarnings.add(
                        safeSubtract(row.getTotalCredit(), row.getTotalDebit()));
            }
        }

        // 未结转损益作为"未分配利润"并入权益. 4103(本年利润)/4104(利润分配) 是系统级
        // EQUITY 科目 (V20260701_02 seed). 若该工厂已手工记账 4104, 把未结转净额并入
        // 该既有行 (避免出现两条 4104); 否则新增合成 4104"未分配利润"行.
        // 注: 本系统月结 (MonthCloseServiceImpl) 不自动过结转损益凭证, 故未结转损益常驻
        // 5/6xxx 科目, 本行是其进入资产负债表的唯一途径. 由复式记账恒等式 (Σ借=Σ贷),
        // 配平凭证下 资产 ≡ 负债 + (已记账权益 + 未分配利润) 恒成立 (与本行用何 code 无关,
        // 仅影响展示是否合并).
        if (retainedEarnings.signum() != 0) {
            BigDecimal retained = retainedEarnings.setScale(2, RoundingMode.HALF_UP);
            BalanceSheetDTO.LineItem existing4104 = equityItems.stream()
                    .filter(e -> "4104".equals(e.getAccountCode()))
                    .findFirst().orElse(null);
            if (existing4104 != null) {
                existing4104.setAmount(existing4104.getAmount().add(retained));
            } else {
                equityItems.add(BalanceSheetDTO.LineItem.builder()
                        .accountCode("4104")
                        .accountName("未分配利润")
                        .amount(retained)
                        .build());
            }
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

    // -------------------------------------------------------------------------
    // Excel 导出
    // -------------------------------------------------------------------------

    /**
     * 导出资产负债表为 xlsx, 写入 {@code out}.
     *
     * @return 文件名 (含时间戳, 用于 Content-Disposition)
     */
    public String exportBalanceSheet(String factoryId, Integer year, Integer month, OutputStream out) {
        BalanceSheetDTO dto = generate(factoryId, year, month);
        List<List<Object>> rows = buildBalanceSheetRows(dto);
        writeRawRows(out, rows, "资产负债表");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("资产负债表_%s_%d%02d_%s.xlsx", factoryId, year, month, ts);
    }

    /** 构建 xlsx 行: header = row[0], 资产/负债/权益 按段落分组. */
    private List<List<Object>> buildBalanceSheetRows(BalanceSheetDTO dto) {
        List<List<Object>> rows = new ArrayList<>();
        // header
        rows.add(List.of("科目编码", "科目名称", "期末余额(元)", "类别"));

        for (BalanceSheetDTO.LineItem item : nvlList(dto.getAssets())) {
            rows.add(List.of(
                    nvlStr(item.getAccountCode()),
                    nvlStr(item.getAccountName()),
                    amountText(item.getAmount()),
                    "资产"));
        }
        // 资产合计
        rows.add(List.of("", "资产合计", amountText(dto.getTotalAssets()), ""));

        for (BalanceSheetDTO.LineItem item : nvlList(dto.getLiabilities())) {
            rows.add(List.of(
                    nvlStr(item.getAccountCode()),
                    nvlStr(item.getAccountName()),
                    amountText(item.getAmount()),
                    "负债"));
        }
        // 负债合计
        rows.add(List.of("", "负债合计", amountText(dto.getTotalLiabilities()), ""));

        for (BalanceSheetDTO.LineItem item : nvlList(dto.getEquity())) {
            rows.add(List.of(
                    nvlStr(item.getAccountCode()),
                    nvlStr(item.getAccountName()),
                    amountText(item.getAmount()),
                    "所有者权益"));
        }
        // 权益合计 + 总计
        rows.add(List.of("", "所有者权益合计", amountText(dto.getTotalEquity()), ""));
        rows.add(List.of("", "负债+所有者权益合计", amountText(dto.getTotalLiabilitiesAndEquity()), ""));
        rows.add(List.of("", "资产总计", amountText(dto.getTotalAssets()), ""));

        return rows;
    }

    // -------------------------------------------------------------------------
    // EasyExcel 工具方法
    // -------------------------------------------------------------------------

    private void writeRawRows(OutputStream out, List<List<Object>> rows, String sheetName) {
        // Even with no data rows, we still write the header
        List<Object> header = rows.get(0);
        List<List<Object>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();

        List<List<String>> head = new ArrayList<>();
        for (Object h : header) {
            head.add(List.of(h.toString()));
        }

        ExcelWriter writer = EasyExcel.write(out).head(head).build();
        WriteSheet sheet = EasyExcel.writerSheet(sheetName).build();
        writer.write(dataRows, sheet);
        writer.finish();
    }

    private BigDecimal scale2(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String amountText(BigDecimal v) {
        return scale2(v).toPlainString();
    }

    private String nvlStr(Object v) {
        return v == null ? "" : v.toString();
    }

    private <T> List<T> nvlList(List<T> list) {
        return list == null ? List.of() : list;
    }

    // -------------------------------------------------------------------------
    // 原有私有方法
    // -------------------------------------------------------------------------

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
