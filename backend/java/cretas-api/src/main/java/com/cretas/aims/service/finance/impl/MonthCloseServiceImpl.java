package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.finance.MonthCloseReconciliationDTO;
import com.cretas.aims.dto.finance.MonthCloseResultDTO;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.IncomeStatementService;
import com.cretas.aims.service.finance.MonthCloseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wave2 月结自动闭环编排实现.
 *
 * <p>编排已有 service: {@link ArApService} (对账) + {@link IncomeStatementService} (P&L) +
 * {@link AccountingPeriodRepository} (状态机持久化). 不重复造业务逻辑.
 *
 * @since 2026-06-04 Wave2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthCloseServiceImpl implements MonthCloseService {

    private final AccountingPeriodRepository periodRepo;
    private final AccountingPeriodService periodService;
    private final ArApService arApService;
    private final IncomeStatementService incomeStatementService;
    private final ObjectMapper objectMapper;

    private static final String STATUS_PASS = "PASS";
    private static final String STATUS_WARNING = "WARNING";
    private static final String SEV_BLOCKING = "BLOCKING";
    private static final String SEV_WARNING = "WARNING";
    private static final String SEV_INFO = "INFO";

    @Override
    public MonthCloseReconciliationDTO previewClose(String factoryId, Integer year, Integer month) {
        validateInput(factoryId, year, month);

        List<MonthCloseReconciliationDTO.CheckItem> checks = new ArrayList<>();
        boolean canClose = true;

        // Check 1 (BLOCKING): 期间是否已 CLOSED — 防重复结账 (防呆 Rule 4)
        Optional<AccountingPeriod> existing = periodService.findPeriod(factoryId, year, month);
        boolean alreadyClosed = existing
                .map(p -> p.getStatus() == AccountingPeriod.Status.CLOSED)
                .orElse(false);
        checks.add(MonthCloseReconciliationDTO.CheckItem.builder()
                .name("期间状态")
                .passed(!alreadyClosed)
                .severity(SEV_BLOCKING)
                .detail(alreadyClosed
                        ? String.format("%d-%02d 期间已结账, 不能重复结账 (如需重做请先反结账)", year, month)
                        : String.format("%d-%02d 期间未结账, 可执行月结", year, month))
                .value(existing.map(p -> p.getStatus().name()).orElse("OPEN"))
                .build());
        if (alreadyClosed) {
            canClose = false;
        }

        // Check 2 (WARNING): 未审批的应收/应付调整 — 非阻塞, 提示财务先处理
        long pendingAdjustments = countPendingAdjustments(factoryId);
        boolean noPending = pendingAdjustments == 0;
        checks.add(MonthCloseReconciliationDTO.CheckItem.builder()
                .name("未审批的应收/应付调整")
                .passed(noPending)
                .severity(SEV_WARNING)
                .detail(noPending
                        ? "无待审批调整"
                        : String.format("有 %d 笔应收/应付调整待审批, 建议结账前处理 (不阻塞结账)", pendingAdjustments))
                .value(pendingAdjustments)
                .build());

        // Check 3 (INFO): P&L 可生成 — 是否有收入/成本数据
        IncomeStatementDTO pl = safeGenerateIncomeStatement(factoryId, year, month);
        boolean hasData = pl != null
                && (isNonZero(pl.getTotalRevenue()) || isNonZero(pl.getTotalCost()));
        checks.add(MonthCloseReconciliationDTO.CheckItem.builder()
                .name("利润表数据")
                .passed(true)  // INFO — 即使无数据也允许结账 (空账期也能关账)
                .severity(SEV_INFO)
                .detail(hasData
                        ? String.format("本月营业收入 %s, 净利润 %s",
                            nz(pl.getTotalRevenue()), nz(pl.getNetProfit()))
                        : "本月暂无收入/成本凭证数据, 利润表为空")
                .value(pl != null ? pl.getNetProfit() : null)
                .build());

        // 综合结论: 任一 WARNING check 失败 → WARNING; 否则 PASS
        boolean anyWarning = checks.stream()
                .anyMatch(c -> SEV_WARNING.equals(c.getSeverity()) && !c.isPassed());
        String reconciliationStatus = anyWarning ? STATUS_WARNING : STATUS_PASS;

        String summary = buildSummary(checks, canClose, reconciliationStatus);

        return MonthCloseReconciliationDTO.builder()
                .factoryId(factoryId)
                .year(year)
                .month(month)
                .canClose(canClose)
                .reconciliationStatus(reconciliationStatus)
                .checks(checks)
                .summary(summary)
                .build();
    }

    @Override
    @Transactional
    public MonthCloseResultDTO executeClose(String factoryId, Integer year, Integer month, Long userId) {
        validateInput(factoryId, year, month);

        // Step 1: 幂等 — 已 CLOSED → 409 (防呆 Rule 4 防重复结账)
        AccountingPeriod period = periodRepo
                .findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month)
                .orElseGet(() -> periodService.openPeriod(factoryId, year, month, userId));

        if (period.getStatus() == AccountingPeriod.Status.CLOSED) {
            throw new BusinessException(409,
                    String.format("%d-%02d 期间已结账, 不能重复结账", year, month))
                    .withCode("PERIOD_ALREADY_CLOSED")
                    .withHint(String.format("/finance/accounting-period?year=%d&month=%d", year, month));
        }

        // Step 2: 对账校验 — BLOCKING 失败 → 400 明确报错 (禁降级假数据, 不静默结账)
        MonthCloseReconciliationDTO reconciliation = previewClose(factoryId, year, month);
        if (!reconciliation.isCanClose()) {
            String blockingDetail = reconciliation.getChecks().stream()
                    .filter(c -> SEV_BLOCKING.equals(c.getSeverity()) && !c.isPassed())
                    .map(MonthCloseReconciliationDTO.CheckItem::getDetail)
                    .findFirst()
                    .orElse("对账校验未通过");
            throw new BusinessException(400,
                    String.format("%d-%02d 月结对账未通过: %s", year, month, blockingDetail))
                    .withCode("RECONCILIATION_BLOCKED")
                    .withSeverity("BLOCKING");
        }

        // Step 3: 生成 P&L (单月)
        IncomeStatementDTO pl = incomeStatementService.generate(factoryId, year, month, year, month);

        // Step 4 + 5: 快照锁定 + 置 CLOSED + 20天调整窗口
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime adjustDeadline = now.plusDays(ADJUST_WINDOW_DAYS);

        period.setStatus(AccountingPeriod.Status.CLOSED);
        period.setClosedAt(now);
        period.setClosedBy(userId);
        period.setAdjustDeadline(adjustDeadline);
        period.setReportReadyAt(now);
        period.setReconciliationStatus(reconciliation.getReconciliationStatus());
        period.setReconciliationSummary(truncate(reconciliation.getSummary(), 2000));
        period.setTotalRevenueSnapshot(pl.getTotalRevenue());
        period.setNetProfitSnapshot(pl.getNetProfit());
        period.setIncomeStatementSnapshot(serializeSnapshot(pl));

        AccountingPeriod saved = periodRepo.save(period);
        log.info("[MonthClose] CLOSED {}-{}-{} by user={} 净利={} 调整窗口截止={} 对账={}",
                factoryId, year, month, userId, pl.getNetProfit(), adjustDeadline,
                reconciliation.getReconciliationStatus());

        String msg = String.format("%d-%02d 月结完成: 报表已生成 (净利润 %s), 调整窗口至 %s",
                year, month, nz(pl.getNetProfit()), adjustDeadline.toLocalDate());

        return MonthCloseResultDTO.builder()
                .period(saved)
                .incomeStatement(pl)
                .adjustDeadline(adjustDeadline)
                .reportReadyAt(now)
                .reconciliationStatus(reconciliation.getReconciliationStatus())
                .message(msg)
                .build();
    }

    // ==================== private helpers ====================

    private void validateInput(String factoryId, Integer year, Integer month) {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new BusinessException(400, "factoryId 不可为空");
        }
        if (year == null || year < 2000 || year > 2200) {
            throw new BusinessException(400, "year 非法: " + year);
        }
        if (month == null || month < 1 || month > 12) {
            throw new BusinessException(400, "month 必须 1-12: " + month);
        }
    }

    /** 统计未审批的应收/应付调整笔数. 失败 fail-open 返 0 (对账是 best-effort 提示, 不阻塞). */
    private long countPendingAdjustments(String factoryId) {
        try {
            PageResponse<ArApTransaction> page = arApService.getPendingAdjustments(factoryId, 1, 1);
            Long total = page.getTotalElements();
            return total != null ? total : 0L;
        } catch (Exception e) {
            log.warn("[MonthClose] 统计待审批调整失败 factory={}: {}", factoryId, e.getMessage());
            return 0L;
        }
    }

    /** 生成 P&L. 失败 fail-open 返 null (preview 是只读预览, 不能因 P&L 报错就崩). */
    private IncomeStatementDTO safeGenerateIncomeStatement(String factoryId, Integer year, Integer month) {
        try {
            return incomeStatementService.generate(factoryId, year, month, year, month);
        } catch (Exception e) {
            log.warn("[MonthClose] preview 生成利润表失败 factory={} {}-{}: {}",
                    factoryId, year, month, e.getMessage());
            return null;
        }
    }

    private String serializeSnapshot(IncomeStatementDTO pl) {
        try {
            return objectMapper.writeValueAsString(pl);
        } catch (Exception e) {
            log.warn("[MonthClose] 利润表快照序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildSummary(List<MonthCloseReconciliationDTO.CheckItem> checks,
                                boolean canClose, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("对账结论=").append(status)
                .append(", 可结账=").append(canClose ? "是" : "否").append("; ");
        for (MonthCloseReconciliationDTO.CheckItem c : checks) {
            sb.append("[").append(c.isPassed() ? "✓" : "✗").append("]")
                    .append(c.getName()).append(": ").append(c.getDetail()).append("; ");
        }
        return sb.toString();
    }

    private boolean isNonZero(java.math.BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    private String nz(java.math.BigDecimal v) {
        return v != null ? v.toPlainString() : "0";
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
