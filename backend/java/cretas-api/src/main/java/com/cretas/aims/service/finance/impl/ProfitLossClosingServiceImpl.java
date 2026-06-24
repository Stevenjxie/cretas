package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.service.finance.ProfitLossClosingService;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结转损益 (P1): 月末损益类 6xxx → 本年利润 4103。见 spec §1.1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitLossClosingServiceImpl implements ProfitLossClosingService {

    private final VoucherService voucherService;
    private final VoucherEntryRepository voucherEntryRepo;
    private final AccountRepository accountRepo;

    private static final String RETAINED_EARNINGS_CODE = "4103";
    private static final String RETAINED_EARNINGS_NAME = "本年利润";

    @Override
    @Transactional
    public void closePeriod(String factoryId, int year, int month, Long userId) {
        LocalDate start = YearMonth.of(year, month).atDay(1);
        LocalDate end = YearMonth.of(year, month).atEndOfMonth();

        List<SubjectAggregateRow> rows = voucherEntryRepo.aggregateBySubjectPosted(factoryId, start, end);
        Map<String, Account> byCode = new HashMap<>();
        for (Account a : accountRepo.findVisibleToFactory(factoryId)) {
            byCode.merge(a.getCode(), a, (ex, in) -> ex.getFactoryId() != null ? ex : in);
        }

        List<VoucherEntrySpec> entries = new ArrayList<>();
        BigDecimal retained = BigDecimal.ZERO; // 4103 净额: 正=贷(盈利), 负=借(亏损)

        for (SubjectAggregateRow r : rows) {
            String code = r.getSubjectCode();
            if (code == null) continue;
            Account acc = byCode.get(code);
            // 结转 6xxx 损益类 (收入 60-63 / 营业成本 64 / 期间费用 66-68)。
            // ⚠️ 必须含 COST 类别 (主营业务成本 6401 在 #1106 后是 COST), 但仅 6xxx —
            //   5xxx 生产成本/制造费用 也是 COST 类别却是存货资本化(WIP), 用 code 前缀 6 排除之。
            boolean isPnl = code.startsWith("6")
                    && (acc == null
                        || acc.getCategory() == AccountCategory.REVENUE
                        || acc.getCategory() == AccountCategory.COST
                        || acc.getCategory() == AccountCategory.EXPENSE);
            if (!isPnl) continue;

            BigDecimal debit = r.getTotalDebit() != null ? r.getTotalDebit() : BigDecimal.ZERO;
            BigDecimal credit = r.getTotalCredit() != null ? r.getTotalCredit() : BigDecimal.ZERO;
            BigDecimal net = credit.subtract(debit).setScale(2, RoundingMode.HALF_UP); // 贷-借
            if (net.signum() == 0) continue;
            String name = (acc != null) ? acc.getName() : r.getSubjectName();

            if (net.signum() > 0) {
                // 净贷 (收入): 借 该科目 net 结平 / 4103 贷 net
                entries.add(new VoucherEntrySpec(code, name, net, null, "结转 " + name));
                retained = retained.add(net);
            } else {
                // 净借 (成本费用): 贷 该科目 |net| 结平 / 4103 借 |net|
                BigDecimal abs = net.abs();
                entries.add(new VoucherEntrySpec(code, name, null, abs, "结转 " + name));
                retained = retained.subtract(abs);
            }
        }

        if (entries.isEmpty()) {
            log.info("[PLClosing] {}-{}-{} 无损益发生额, no-op", factoryId, year, month);
            return;
        }

        // 4103 汇总一行: retained>0 → 4103 贷; retained<0 → 4103 借
        if (retained.signum() > 0) {
            entries.add(new VoucherEntrySpec(RETAINED_EARNINGS_CODE, RETAINED_EARNINGS_NAME, null, retained, "结转本年利润"));
        } else {
            entries.add(new VoucherEntrySpec(RETAINED_EARNINGS_CODE, RETAINED_EARNINGS_NAME, retained.abs(), null, "结转本年利润(亏损)"));
        }

        int revision = currentRevision(factoryId, year, month);
        String sourceId = String.format("closing-%s-%d-%d-monthly-r%d", factoryId, year, month, revision);
        voucherService.createManual(factoryId, VoucherType.PL_CLOSING, end, entries,
                "PL_CLOSING", sourceId, String.format("%d-%02d 结转损益", year, month), userId);
        log.info("[PLClosing] {}-{}-{} 结转完成: 4103 净额={} (rev{})", factoryId, year, month, retained, revision);
    }

    @Override
    @Transactional
    public void reversePeriodClosing(String factoryId, int year, int month, Long userId) {
        // 在 Task 7 实现
        throw new UnsupportedOperationException("Task 7");
    }

    /** rev = 该期历史结转批次数 (含已 REVERSED), 用于 source_business_id 避免撞键。Task 7 完善。 */
    private int currentRevision(String factoryId, int year, int month) {
        return 0; // P1 Task 6: 首次结转 r0; Task 7 加 reopen 后用真实计数
    }
}
