package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
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
import java.util.*;

/**
 * Sprint 7 T3 现金流量表 (Cash Flow Statement) — 直接法.
 *
 * <p>算法:
 * <ol>
 *   <li>找现金账户 (subjectCode IN ('1001','1002','1012')) 在期间内 voucher entries</li>
 *   <li>对每张含现金的 voucher, 遍历对手 entries (非现金), 按对手科目 code 前缀分类</li>
 *   <li>累计 inflow (cash debit) / outflow (cash credit) by counterpart code</li>
 *   <li>简化: code 起始位决定活动类别:
 *     <ul>
 *       <li>5/6xxx → 经营活动 (operating) — REVENUE / COST / EXPENSE</li>
 *       <li>14xx/15xx/19xx → 投资活动 — 长期资产</li>
 *       <li>22xx/4xxx → 筹资活动 — 长期负债 / 权益</li>
 *       <li>其他 (1xxx-13xx 流动资产, 21xx 流动负债, 应收/应付) → 默认归经营活动</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * @since 2026-05-20 Sprint 7 T3 (Phase C)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashFlowService {

    private final VoucherRepository voucherRepo;
    private final VoucherEntryRepository voucherEntryRepo;
    private final AccountRepository accountRepo;

    /** 标准现金/银行存款/其他货币资金科目 code (per 中国 GAAP). */
    private static final List<String> CASH_CODES = List.of("1001", "1002", "1012");

    /**
     * 资产负债表口径共用累计起点 (1970-01-01 Unix epoch) — 与 {@link BalanceSheetService}
     * (及 {@code VoucherExportServiceImpl}) 保持一致, 用于把现金科目累计余额算到"远古".
     */
    private static final LocalDate EPOCH_START = LocalDate.of(1970, 1, 1);

    public CashFlowDTO generate(String factoryId,
                                Integer startYear, Integer startMonth,
                                Integer endYear, Integer endMonth) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId is required");
        }
        if (startYear == null || startMonth == null || endYear == null || endMonth == null) {
            throw new IllegalArgumentException("year/month required");
        }
        if (startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) {
            throw new IllegalArgumentException("month must be 1-12");
        }

        LocalDate startDate = YearMonth.of(startYear, startMonth).atDay(1);
        LocalDate endDate = YearMonth.of(endYear, endMonth).atEndOfMonth();
        log.info("[CashFlow] generate factoryId={}, range={} to {}", factoryId, startDate, endDate);

        // Account 索引 — 优先 factory 自定义
        List<Account> allAccounts = accountRepo.findVisibleToFactory(factoryId);
        Map<String, Account> accountByCode = new HashMap<>();
        for (Account a : allAccounts) {
            accountByCode.merge(a.getCode(), a, (existing, incoming) ->
                    existing.getFactoryId() != null ? existing : incoming);
        }

        // 拉期间内含现金的 vouchers (entry-driven)
        List<Voucher> cashVouchers = voucherRepo.findByFactoryIdAndDateRange(factoryId, startDate, endDate);

        // 期间累计 inflow/outflow by counterpart code
        // key = counterpartCode, value = {inflow, outflow}
        Map<String, BigDecimal[]> aggregateByCounterpart = new HashMap<>();
        Map<String, String> nameByCode = new HashMap<>();

        for (Voucher v : cashVouchers) {
            // F006 财务审计 Bug 6 follow-up (2026-07-04, #1228 CashFlow sibling): 现金流量表
            // 官方报表口径统一 POSTED-only — 排除 VOID (作废) 和 DRAFT (未财审草稿, 人工审核制
            // 下不算"已发生"现金流动), 但 REVERSED (已过账后红字冲销) 必须保留计入 —— 原凭证的
            // 现金流仍算发生过, 配合红字冲销镜像凭证 (同为 POSTED) 才能正确互相抵消归零, 与
            // BalanceSheetService/aggregateBySubjectExcludingDraft 同一原则 (见其 javadoc)。
            if (v.getStatus() == VoucherStatus.VOID || v.getStatus() == VoucherStatus.DRAFT) continue;
            // 一张 voucher 内: 找现金 entries + 非现金 entries
            List<VoucherEntry> entries = v.getEntries();
            if (entries == null || entries.isEmpty()) continue;

            List<VoucherEntry> cashEntries = new ArrayList<>();
            List<VoucherEntry> nonCashEntries = new ArrayList<>();
            for (VoucherEntry e : entries) {
                if (e.getSubjectCode() != null && CASH_CODES.contains(e.getSubjectCode())) {
                    cashEntries.add(e);
                } else {
                    nonCashEntries.add(e);
                }
            }
            if (cashEntries.isEmpty()) continue;

            // 计算本 voucher 的现金净流向 (debit - credit)
            BigDecimal voucherCashDebit = BigDecimal.ZERO;
            BigDecimal voucherCashCredit = BigDecimal.ZERO;
            for (VoucherEntry ce : cashEntries) {
                voucherCashDebit = voucherCashDebit.add(nullsafe(ce.getDebit()));
                voucherCashCredit = voucherCashCredit.add(nullsafe(ce.getCredit()));
            }
            // 直接法: 把 voucher 内非现金 entries 视为现金流的"来源/去向"
            // 简化处理 — 把非现金 entries 的总借/总贷 按比例分摊
            // 实务上一笔 voucher 通常 1 现金 entry + 1 对手 entry, 这里允许多对手
            BigDecimal nonCashDebitTotal = BigDecimal.ZERO;
            BigDecimal nonCashCreditTotal = BigDecimal.ZERO;
            for (VoucherEntry nc : nonCashEntries) {
                nonCashDebitTotal = nonCashDebitTotal.add(nullsafe(nc.getDebit()));
                nonCashCreditTotal = nonCashCreditTotal.add(nullsafe(nc.getCredit()));
            }

            for (VoucherEntry nc : nonCashEntries) {
                if (nc.getSubjectCode() == null) continue;
                BigDecimal entryDebit = nullsafe(nc.getDebit());
                BigDecimal entryCredit = nullsafe(nc.getCredit());

                // inflow attribution: 现金借方 (流入), 来源是非现金 entries 的贷方 → 按对手贷方占比分摊
                BigDecimal inflowShare = BigDecimal.ZERO;
                if (voucherCashDebit.signum() > 0 && nonCashCreditTotal.signum() > 0) {
                    inflowShare = voucherCashDebit
                            .multiply(entryCredit)
                            .divide(nonCashCreditTotal, 4, RoundingMode.HALF_UP);
                }
                // outflow attribution: 现金贷方 (流出), 去向是非现金 entries 的借方 → 按对手借方占比分摊
                BigDecimal outflowShare = BigDecimal.ZERO;
                if (voucherCashCredit.signum() > 0 && nonCashDebitTotal.signum() > 0) {
                    outflowShare = voucherCashCredit
                            .multiply(entryDebit)
                            .divide(nonCashDebitTotal, 4, RoundingMode.HALF_UP);
                }

                if (inflowShare.signum() > 0 || outflowShare.signum() > 0) {
                    BigDecimal[] cur = aggregateByCounterpart.computeIfAbsent(
                            nc.getSubjectCode(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    cur[0] = cur[0].add(inflowShare);
                    cur[1] = cur[1].add(outflowShare);
                    nameByCode.putIfAbsent(nc.getSubjectCode(),
                            nc.getSubjectName() != null ? nc.getSubjectName() :
                                    (accountByCode.get(nc.getSubjectCode()) != null
                                            ? accountByCode.get(nc.getSubjectCode()).getName() : nc.getSubjectCode()));
                }
            }
        }

        // 按 code 前缀分活动桶
        List<CashFlowDTO.Activity> operating = new ArrayList<>();
        List<CashFlowDTO.Activity> investing = new ArrayList<>();
        List<CashFlowDTO.Activity> financing = new ArrayList<>();

        for (Map.Entry<String, BigDecimal[]> entry : aggregateByCounterpart.entrySet()) {
            String code = entry.getKey();
            BigDecimal inflow = entry.getValue()[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal outflow = entry.getValue()[1].setScale(2, RoundingMode.HALF_UP);
            BigDecimal netAmount = inflow.subtract(outflow).setScale(2, RoundingMode.HALF_UP);
            CashFlowDTO.Activity activity = CashFlowDTO.Activity.builder()
                    .counterpartCode(code)
                    .counterpartName(nameByCode.getOrDefault(code, code))
                    .inflow(inflow)
                    .outflow(outflow)
                    .netAmount(netAmount)
                    .build();
            String category = classifyActivity(code);
            switch (category) {
                case "INVESTING": investing.add(activity); break;
                case "FINANCING": financing.add(activity); break;
                default: operating.add(activity);
            }
        }

        Comparator<CashFlowDTO.Activity> byCode = Comparator.comparing(CashFlowDTO.Activity::getCounterpartCode);
        operating.sort(byCode);
        investing.sort(byCode);
        financing.sort(byCode);

        BigDecimal operatingNet = sumNet(operating);
        BigDecimal investingNet = sumNet(investing);
        BigDecimal financingNet = sumNet(financing);
        BigDecimal netIncrease = operatingNet.add(investingNet).add(financingNet)
                .setScale(2, RoundingMode.HALF_UP);

        // 期初现金余额 = 现金科目 (CASH_CODES) 在 [EPOCH_START, startDate-1] 的累计余额
        // (debit - credit), 复用 BalanceSheetService 同款聚合方法
        // (voucherEntryRepo.aggregateBySubjectExcludingDraft) 保证与资产负债表现金科目余额口径
        // 完全一致 (排 VOID/DRAFT, 保留 REVERSED — 与本方法上面的 voucher 过滤条件同一原则).
        // 历史 bug: 曾硬编码 0, 导致任何不从数据起点开始的区间现金流量表期末余额低报.
        BigDecimal beginningCash = computeCashBalance(factoryId, startDate.minusDays(1));
        BigDecimal endingCash = beginningCash.add(netIncrease).setScale(2, RoundingMode.HALF_UP);

        // F006 财务审计 Bug 6 follow-up (2026-07-04): 待过账 (DRAFT) 凭证数 — 现金流量表已切
        // POSTED-only 口径, 用此字段告知财务人员"报表数字看着少"的原因 (fool-proof Rule 5),
        // 而非悄悄漏算不说明. 与 BalanceSheetDTO/IncomeStatementDTO.pendingDraftVoucherCount 同一原则.
        long pendingDraftCount = voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                factoryId, VoucherStatus.DRAFT, startDate, endDate);

        return CashFlowDTO.builder()
                .factoryId(factoryId)
                .startYear(startYear)
                .startMonth(startMonth)
                .endYear(endYear)
                .endMonth(endMonth)
                .operatingActivities(operating)
                .investingActivities(investing)
                .financingActivities(financing)
                .operatingNetCashFlow(operatingNet)
                .investingNetCashFlow(investingNet)
                .financingNetCashFlow(financingNet)
                .netIncreaseInCash(netIncrease)
                .beginningCash(beginningCash)
                .endingCash(endingCash)
                .generatedAt(LocalDateTime.now().toString())
                .pendingDraftVoucherCount(pendingDraftCount)
                .build();
    }

    // -------------------------------------------------------------------------
    // Excel 导出
    // -------------------------------------------------------------------------

    /**
     * 导出现金流量表为 xlsx, 写入 {@code out}.
     *
     * @return 文件名 (含时间戳, 用于 Content-Disposition)
     */
    public String exportCashFlow(String factoryId,
                                 Integer startYear, Integer startMonth,
                                 Integer endYear, Integer endMonth,
                                 OutputStream out) {
        CashFlowDTO dto = generate(factoryId, startYear, startMonth, endYear, endMonth);
        List<List<Object>> rows = buildCashFlowRows(dto);
        writeRawRows(out, rows, "现金流量表");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return String.format("现金流量表_%s_%d%02d_%d%02d_%s.xlsx",
                factoryId, startYear, startMonth, endYear, endMonth, ts);
    }

    /** 构建 xlsx 行: header = row[0], 三大活动分段列出. */
    private List<List<Object>> buildCashFlowRows(CashFlowDTO dto) {
        List<List<Object>> rows = new ArrayList<>();
        // header
        rows.add(List.of("活动类别", "科目编码", "科目名称", "流入(元)", "流出(元)", "净额(元)"));

        addActivitySection(rows, "经营活动", dto.getOperatingActivities(), dto.getOperatingNetCashFlow());
        addActivitySection(rows, "投资活动", dto.getInvestingActivities(), dto.getInvestingNetCashFlow());
        addActivitySection(rows, "筹资活动", dto.getFinancingActivities(), dto.getFinancingNetCashFlow());

        // 汇总行
        rows.add(List.of("", "", "现金净增加额",
                amountText(dto.getNetIncreaseInCash().max(BigDecimal.ZERO)),
                amountText(dto.getNetIncreaseInCash().min(BigDecimal.ZERO).abs()),
                amountText(dto.getNetIncreaseInCash())));
        rows.add(List.of("", "", "期初现金余额", "", "", amountText(dto.getBeginningCash())));
        rows.add(List.of("", "", "期末现金余额", "", "", amountText(dto.getEndingCash())));

        // F006 财务审计 Bug 6 follow-up (2026-07-04): 待过账提示 footer 行 — 与
        // BalanceSheetService/IncomeStatementService 导出的 footer 一致, 告知财务人员
        // 本表仅含已过账 (POSTED) 凭证, 避免"数字看着少却不知道为什么".
        long pending = dto.getPendingDraftVoucherCount() != null ? dto.getPendingDraftVoucherCount() : 0L;
        if (pending > 0) {
            rows.add(List.of("", "", "", "", "", ""));
            rows.add(List.of("", "", "注: 本表仅含已过账(POSTED)凭证, 另有 " + pending + " 张凭证待过账(未计入本表)", "", "", ""));
        }

        return rows;
    }

    private void addActivitySection(List<List<Object>> rows,
                                    String sectionName,
                                    List<CashFlowDTO.Activity> activities,
                                    BigDecimal sectionNet) {
        if (activities == null || activities.isEmpty()) {
            rows.add(List.of(sectionName, "", "（无相关活动）", "0.00", "0.00", "0.00"));
        } else {
            for (CashFlowDTO.Activity a : activities) {
                rows.add(List.of(
                        sectionName,
                        nvlStr(a.getCounterpartCode()),
                        nvlStr(a.getCounterpartName()),
                        amountText(a.getInflow()),
                        amountText(a.getOutflow()),
                        amountText(a.getNetAmount())));
            }
        }
        // section subtotal
        rows.add(List.of("", "", sectionName + " 合计", "", "", amountText(sectionNet)));
    }

    // -------------------------------------------------------------------------
    // EasyExcel 工具方法
    // -------------------------------------------------------------------------

    private void writeRawRows(OutputStream out, List<List<Object>> rows, String sheetName) {
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

    // -------------------------------------------------------------------------
    // 原有私有方法
    // -------------------------------------------------------------------------

    /** 投资活动对手科目前缀: 15xx 长期投资 / 16xx 固定资产 / 17xx 无形资产 / 18xx 长期待摊. */
    private static final Set<String> INVESTING_PREFIX2 = Set.of("15", "16", "17", "18");
    /** 投资活动具体科目: 交易性金融资产 / 应收股利 / 应收利息 (投资收益往来). */
    private static final Set<String> INVESTING_CODES = Set.of("1101", "1131", "1132");
    /** 筹资活动具体科目: 短期借款 / 应付利息 / 应付股利 / 长期借款 / 应付债券 / 长期应付款. */
    private static final Set<String> FINANCING_CODES = Set.of("2001", "2231", "2232", "2501", "2502", "2701");

    /**
     * 按对手科目 code 决定现金流量表活动分类 (经营 / 投资 / 筹资).
     *
     * <p>历史 bug (已修): 旧逻辑 14-19 一刀切归投资、22-25 一刀切归筹资, 把 <b>存货 (14xx)</b>
     * 错归投资、<b>应付账款/票据/预收/薪酬/税费 (22xx 流动往来)</b> 错归筹资, 且漏了
     * <b>短期借款 (2001) / 长期应付款 (2701)</b> 应属筹资. 现按真实科目语义分类:
     * <ul>
     *   <li>筹资: 借款/债券/应付利息股利/长期应付 ({@link #FINANCING_CODES}) + 权益 (4xxx)</li>
     *   <li>投资: 长期资产 15-18 + 短期投资/投资收益往来 ({@link #INVESTING_CODES})</li>
     *   <li>经营: 其余 (现金/应收预付/存货/应付往来/税费/薪酬/收入/成本/费用)</li>
     * </ul>
     * 子科目 (如 2221.01) 按主科目前缀归类.
     */
    String classifyActivity(String code) {
        if (code == null || code.length() < 2) return "OPERATING";
        String mainCode = code.contains(".") ? code.substring(0, code.indexOf('.')) : code;
        String prefix2 = mainCode.length() >= 2 ? mainCode.substring(0, 2) : mainCode;
        // 筹资活动: 借款/债券/利息股利/长期应付 + 权益 (4xxx)
        if (FINANCING_CODES.contains(mainCode) || mainCode.startsWith("4")) {
            return "FINANCING";
        }
        // 投资活动: 长期资产 15-18 + 短期投资/投资收益往来
        if (INVESTING_PREFIX2.contains(prefix2) || INVESTING_CODES.contains(mainCode)) {
            return "INVESTING";
        }
        // 经营活动 (默认): 现金/应收预付/存货/应付往来/税费/薪酬/收入/成本/费用
        return "OPERATING";
    }

    private BigDecimal sumNet(List<CashFlowDTO.Activity> activities) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CashFlowDTO.Activity a : activities) {
            if (a.getNetAmount() != null) {
                sum = sum.add(a.getNetAmount());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullsafe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * 现金科目 ({@link #CASH_CODES}) 在 [{@link #EPOCH_START}, asOfDate] 的累计余额
     * (debit - credit), 复用 {@code voucherEntryRepo.aggregateBySubjectExcludingDraft} — 与
     * {@link BalanceSheetService#generate} 计算资产科目余额 (debit - credit) 完全同款聚合方法
     * (排 VOID/DRAFT, 保留 REVERSED, 见其 javadoc), 确保期初现金余额跟资产负债表现金科目余额
     * 口径一致, 也与本类现金流量表活动聚合的 voucher 过滤条件 (line ~99 排 VOID/DRAFT 保留
     * REVERSED) 同一原则.
     *
     * @param asOfDate 累计截止日 (含); 若早于 EPOCH_START (理论上不会发生) 返回 0
     */
    private BigDecimal computeCashBalance(String factoryId, LocalDate asOfDate) {
        if (asOfDate.isBefore(EPOCH_START)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        List<SubjectAggregateRow> aggregates =
                voucherEntryRepo.aggregateBySubjectExcludingDraft(factoryId, EPOCH_START, asOfDate);
        BigDecimal balance = BigDecimal.ZERO;
        for (SubjectAggregateRow row : aggregates) {
            if (row.getSubjectCode() != null && CASH_CODES.contains(row.getSubjectCode())) {
                balance = balance.add(nullsafe(row.getTotalDebit())).subtract(nullsafe(row.getTotalCredit()));
            }
        }
        return balance.setScale(2, RoundingMode.HALF_UP);
    }
}
