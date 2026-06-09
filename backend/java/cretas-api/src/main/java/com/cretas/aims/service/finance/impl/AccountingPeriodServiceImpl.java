package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.PeriodClosedException;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.inventory.InventoryLedgerSnapshotService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 7 T2 F-PERIOD impl.
 *
 * <p>WorkflowEngineService 为可选注入 — 测试 / 早期 factory 没配 BUDGET workflow 时
 * fail-open: requestClose 直接进 PENDING_CLOSE state, 不抛错也不报错.
 *
 * <p>SP11 D1 月结钩子: confirmClose 成功后触发 {@link InventoryLedgerSnapshotService#createForPeriod}
 * 创建期末快照, fail-soft (快照失败不回滚结账).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingPeriodServiceImpl implements AccountingPeriodService {

    private final AccountingPeriodRepository repo;

    /** 工作流可选 — 没配 BUDGET workflow 时 requestClose 仍能 ship, 走人工 confirm. */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngineService;

    /**
     * SP11 D1: 期末快照 (可选注入, 旧工厂/测试环境无此 bean 时 fail-open).
     * fail-soft: 快照失败只 warn, 不回滚结账主事务.
     */
    @Autowired(required = false)
    private InventoryLedgerSnapshotService inventoryLedgerSnapshotService;

    @Override
    @Transactional
    public AccountingPeriod openPeriod(String factoryId, Integer year, Integer month, Long userId) {
        validateInput(factoryId, year, month);

        Optional<AccountingPeriod> existing = repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(
                factoryId, year, month);

        if (existing.isPresent()) {
            AccountingPeriod p = existing.get();
            if (p.getStatus() == AccountingPeriod.Status.OPEN) {
                log.debug("[AccountingPeriod] openPeriod 幂等命中: {}/{}/{}", factoryId, year, month);
                return p;
            }
            // 已有 row 但不是 OPEN — 应该走 reopenPeriod, 不能 openPeriod
            throw new BusinessException(409,
                    String.format("%d-%02d 期间已存在 (状态=%s), 请使用反结账", year, month, p.getStatus()))
                    .withHint(String.format("/finance/accounting-period?year=%d&month=%d", year, month));
        }

        AccountingPeriod p = AccountingPeriod.builder()
                .factoryId(factoryId)
                .year(year)
                .month(month)
                .status(AccountingPeriod.Status.OPEN)
                .openedAt(LocalDateTime.now())
                .openedBy(userId)
                .build();
        AccountingPeriod saved = repo.save(p);
        log.info("[AccountingPeriod] OPEN {}-{}-{} by user={}", factoryId, year, month, userId);
        return saved;
    }

    @Override
    @Transactional
    public AccountingPeriod requestClose(String factoryId, Integer year, Integer month, Long userId) {
        validateInput(factoryId, year, month);
        AccountingPeriod p = repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month)
                .orElseGet(() -> {
                    // 兼容: 没有 row 时也允许 requestClose, 自动创建 OPEN row 再 transition
                    log.info("[AccountingPeriod] requestClose 自动创建 row: {}/{}/{}", factoryId, year, month);
                    return openPeriod(factoryId, year, month, userId);
                });

        if (p.getStatus() != AccountingPeriod.Status.OPEN) {
            throw new BusinessException(400,
                    String.format("%d-%02d 期间状态=%s, 仅 OPEN 可发起结账", year, month, p.getStatus()));
        }

        // 触发 BUDGET_APPROVAL workflow (可选 — 没 wire 也不阻塞)
        String workflowInstanceId = tryStartWorkflow(factoryId, p, userId);

        p.setStatus(AccountingPeriod.Status.PENDING_CLOSE);
        if (workflowInstanceId != null) {
            p.setApprovalWorkflowInstanceId(workflowInstanceId);
        }
        AccountingPeriod saved = repo.save(p);
        log.info("[AccountingPeriod] PENDING_CLOSE {}-{}-{} by user={} workflow={}",
                factoryId, year, month, userId, workflowInstanceId);
        return saved;
    }

    @Override
    @Transactional
    public AccountingPeriod confirmClose(String factoryId, Integer year, Integer month, Long userId) {
        validateInput(factoryId, year, month);
        AccountingPeriod p = repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month)
                .orElseThrow(() -> new BusinessException(404,
                        String.format("%d-%02d 期间不存在", year, month)));

        if (p.getStatus() == AccountingPeriod.Status.CLOSED) {
            log.debug("[AccountingPeriod] confirmClose 幂等命中 (already CLOSED): {}/{}/{}",
                    factoryId, year, month);
            return p;
        }

        if (p.getStatus() != AccountingPeriod.Status.PENDING_CLOSE
                && p.getStatus() != AccountingPeriod.Status.OPEN) {
            throw new BusinessException(400,
                    String.format("%d-%02d 期间状态=%s, 不能 confirmClose", year, month, p.getStatus()));
        }

        p.setStatus(AccountingPeriod.Status.CLOSED);
        p.setClosedAt(LocalDateTime.now());
        p.setClosedBy(userId);
        AccountingPeriod saved = repo.save(p);
        log.info("[AccountingPeriod] CLOSED {}-{}-{} by user={}", factoryId, year, month, userId);

        // SP11 D1: 月结后创建期末快照 (fail-soft — 快照失败不回滚结账事务)
        createInventorySnapshotFailSoft(factoryId, saved.getId());

        return saved;
    }

    @Override
    @Transactional
    public AccountingPeriod reopenPeriod(String factoryId, Integer year, Integer month,
                                        String reason, Long userId) {
        validateInput(factoryId, year, month);
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException(400, "反结账必须填写原因");
        }

        AccountingPeriod p = repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month)
                .orElseThrow(() -> new BusinessException(404,
                        String.format("%d-%02d 期间不存在, 无法反结账", year, month)));

        if (p.getStatus() != AccountingPeriod.Status.CLOSED) {
            throw new BusinessException(400,
                    String.format("%d-%02d 期间状态=%s, 仅 CLOSED 可反结账", year, month, p.getStatus()));
        }

        p.setStatus(AccountingPeriod.Status.OPEN);
        p.setReopenedAt(LocalDateTime.now());
        p.setReopenedBy(userId);
        p.setReopenReason(reason.trim());
        AccountingPeriod saved = repo.save(p);
        log.warn("[AccountingPeriod] REOPENED {}-{}-{} by user={} reason='{}'",
                factoryId, year, month, userId, reason);
        return saved;
    }

    @Override
    public AccountingPeriod.Status getStatus(String factoryId, Integer year, Integer month) {
        validateInput(factoryId, year, month);
        return repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month)
                .map(AccountingPeriod::getStatus)
                .orElse(AccountingPeriod.Status.OPEN);  // backwards compat: no row → OPEN
    }

    @Override
    public Optional<AccountingPeriod> findPeriod(String factoryId, Integer year, Integer month) {
        validateInput(factoryId, year, month);
        return repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month);
    }

    @Override
    public List<AccountingPeriod> listByFactory(String factoryId) {
        if (factoryId == null) {
            throw new BusinessException(400, "factoryId 不可为空");
        }
        return repo.findByFactoryIdAndDeletedAtIsNullOrderByYearDescMonthDesc(factoryId);
    }

    @Override
    public void assertOpen(String factoryId, Integer year, Integer month) {
        if (factoryId == null || year == null || month == null) {
            // 防御性: 调用方未提供完整 (factory, year, month) 时, 静默 pass (旧 voucher
            // 数据没准带不全; backwards compat 优先于严格性).
            return;
        }
        Optional<AccountingPeriod> periodOpt =
                repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(factoryId, year, month);
        if (periodOpt.isEmpty()) {
            return;  // backwards compat: 无 row 默认 OPEN, voucher 可写
        }
        AccountingPeriod period = periodOpt.get();
        if (period.getStatus() != AccountingPeriod.Status.CLOSED) {
            // OPEN / PENDING_CLOSE — voucher 写允许 (PENDING_CLOSE 是审批中, 审批未通过仍需 voucher edit)
            return;
        }

        // Wave2 月结: CLOSED 时检查 20 天调整窗口 (兑现邓总 "留20天调整窗口").
        // adjust_deadline 内 → voucher 仍可调整 (silent pass); 窗口已过 / 无 deadline → 硬锁.
        AccountingPeriod.AdjustWindowState windowState = period.getAdjustWindowState();
        if (windowState == AccountingPeriod.AdjustWindowState.OPEN_WINDOW) {
            log.debug("[AccountingPeriod] {}-{}-{} CLOSED 但调整窗口内 (截止={}), voucher 允许调整",
                    factoryId, year, month, period.getAdjustDeadline());
            return;
        }
        // LOCKED — 窗口已过 (或旧 CLOSED 行无 deadline) → 硬锁
        throw new PeriodClosedException(factoryId, year, month);
    }

    // ==================== private helpers ====================

    /**
     * SP11 D1: fail-soft wrapper for inventory snapshot creation.
     * <p>快照失败 (DB error / 数据不一致) 只 warn, 不回滚已成功的结账事务.
     * 避免因快照 bug 导致所有月结操作失败 (结账比快照更重要).
     */
    private void createInventorySnapshotFailSoft(String factoryId, String accountingPeriodId) {
        if (inventoryLedgerSnapshotService == null) {
            log.debug("[AccountingPeriod] InventoryLedgerSnapshotService 未注入, 跳过快照");
            return;
        }
        try {
            inventoryLedgerSnapshotService.createForPeriod(factoryId, accountingPeriodId);
            log.info("[SP11] D1 inventory snapshot created: factoryId={} periodId={}",
                    factoryId, accountingPeriodId);
        } catch (Exception e) {
            log.warn("[SP11] D1 inventory snapshot FAILED (fail-soft, 结账不受影响): factoryId={} periodId={} error={}",
                    factoryId, accountingPeriodId, e.getMessage(), e);
        }
    }

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

    /**
     * 启动 BUDGET_APPROVAL workflow. Workflow 未配置 (or 服务未注入) 时 fail-open 返 null.
     *
     * <p>fail-open 设计: 期间结账是合规级业务, 不能因 workflow 没配就阻塞. requestClose
     * 仍前进到 PENDING_CLOSE, 由 finance director 手工 confirmClose 推进.
     */
    private String tryStartWorkflow(String factoryId, AccountingPeriod period, Long userId) {
        if (workflowEngineService == null) {
            log.debug("[AccountingPeriod] WorkflowEngineService 未注入, 跳过审批触发");
            return null;
        }
        try {
            if (!workflowEngineService.hasActiveWorkflow(factoryId, "BUDGET")) {
                log.info("[AccountingPeriod] factory={} 无 BUDGET_APPROVAL workflow, 直接 PENDING_CLOSE",
                        factoryId);
                return null;
            }
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("entityType", "ACCOUNTING_PERIOD");
            ctx.put("year", period.getYear());
            ctx.put("month", period.getMonth());
            ctx.put("periodId", period.getId());
            ApprovalWorkflowInstance instance = workflowEngineService.startWorkflow(
                    factoryId, "BUDGET", period.getId(), ctx, userId);
            return instance != null ? instance.getId() : null;
        } catch (Exception e) {
            log.warn("[AccountingPeriod] BUDGET_APPROVAL workflow 启动失败 factory={} period={}-{}: {}",
                    factoryId, period.getYear(), period.getMonth(), e.getMessage());
            return null;
        }
    }
}
