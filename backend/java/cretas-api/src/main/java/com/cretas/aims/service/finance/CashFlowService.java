package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
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
            if (v.getStatus() != null
                    && "VOID".equals(v.getStatus().name())) continue;
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

        // 期初/期末现金余额 — 简化: 不计算 (需 BalanceSheet 跨期, 留 TODO)
        BigDecimal beginningCash = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal endingCash = beginningCash.add(netIncrease).setScale(2, RoundingMode.HALF_UP);

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
                .build();
    }

    /**
     * 按 code 前缀决定活动分类:
     * - 14xx/15xx/19xx → INVESTING (固定资产/无形资产/长期投资)
     * - 22xx + 4xxx → FINANCING (长期负债 + 权益)
     * - default → OPERATING (5xxx 收入 / 6xxx 成本 / 应收应付等)
     */
    private String classifyActivity(String code) {
        if (code == null || code.length() < 2) return "OPERATING";
        String prefix2 = code.substring(0, 2);
        // 投资类: 14xx 长期股权 / 15xx 投资性资产 / 16xx 固定资产 / 17xx 无形资产 / 19xx 其他长期
        if (prefix2.equals("14") || prefix2.equals("15") || prefix2.equals("16")
                || prefix2.equals("17") || prefix2.equals("18") || prefix2.equals("19")) {
            return "INVESTING";
        }
        // 筹资类: 22xx 长期借款 / 25xx 长期应付 / 4xxx 权益 (实收资本/资本公积/留存收益)
        if (prefix2.equals("22") || prefix2.equals("23") || prefix2.equals("24")
                || prefix2.equals("25") || code.startsWith("4")) {
            return "FINANCING";
        }
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
}
