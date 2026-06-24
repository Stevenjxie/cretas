package com.cretas.aims.scheduler;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.AccountingPeriodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Sprint 7 T2 F-PERIOD: 自动结账 scheduler.
 *
 * <p>每月 1 号 02:00 (服务器时区) 触发, 对所有 active factory 上月 (period.year/month
 * = lastMonth) 的 OPEN 状态 period 发起结账请求 (OPEN → PENDING_CLOSE).
 *
 * <p><b>不自动 confirmClose</b>: 仅触发审批工作流, 等 finance director 手工 confirmClose
 * 推进到 CLOSED 状态. 这是合规要求 (期间结账是 finance director 的责任, 不能完全 cron 化).
 *
 * <p>幂等性: {@link AccountingPeriodService#requestClose} 内部检查 status — 已 PENDING_CLOSE
 * / CLOSED 的不会重复触发. 没 row 的会自动 openPeriod 再 requestClose.
 *
 * <p>ShedLock 保证 Blue-green 双 JVM 同步 fire 时只其中一个真正跑.
 *
 * @since 2026-05-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingPeriodScheduler {

    private final FactoryRepository factoryRepository;
    private final AccountingPeriodService accountingPeriodService;
    private final AccountingPeriodRepository periodRepo;

    @Value("${accounting-period.auto-close.enabled:true}")
    private boolean enabled;

    /**
     * 每月 1 号 02:00 发起上月结账请求. cron 秒 分 时 日 月 周 → 0 0 2 1 * ?
     *
     * <p>periodMonth = 上月 (e.g. 2026-06-01 02:00 执行 → 处理 2026-05).
     */
    @Scheduled(cron = "${accounting-period.auto-close.cron:0 0 2 1 * ?}")
    @SchedulerLock(
            name = "AccountingPeriodScheduler.autoRequestClose",
            lockAtMostFor = "PT60M",
            lockAtLeastFor = "PT1M")
    public void autoRequestClose() {
        if (!enabled) {
            log.debug("[AccountingPeriodScheduler] 已禁用 (accounting-period.auto-close.enabled=false)");
            return;
        }

        YearMonth target = YearMonth.from(LocalDate.now()).minusMonths(1);
        int year = target.getYear();
        int month = target.getMonthValue();
        log.info("[AccountingPeriodScheduler] 开始月度自动结账请求, period={}-{}", year, month);
        long startMs = System.currentTimeMillis();

        List<String> factoryIds;
        try {
            factoryIds = factoryRepository.findAllActiveFactoryIds();
        } catch (Exception e) {
            log.error("[AccountingPeriodScheduler] 取 active factory ids 失败: {}", e.getMessage(), e);
            return;
        }

        int success = 0;
        int skipped = 0;
        int failed = 0;
        for (String factoryId : factoryIds) {
            try {
                AccountingPeriod.Status status = accountingPeriodService.getStatus(factoryId, year, month);
                if (status == AccountingPeriod.Status.OPEN) {
                    // null userId — 系统触发, audit 显示 null = 自动任务
                    accountingPeriodService.requestClose(factoryId, year, month, null);
                    success++;
                    log.info("[AccountingPeriodScheduler] factory={} {}-{} → PENDING_CLOSE",
                            factoryId, year, month);
                } else {
                    skipped++;
                    log.debug("[AccountingPeriodScheduler] factory={} {}-{} status={}, skip",
                            factoryId, year, month, status);
                }
            } catch (Exception e) {
                failed++;
                log.warn("[AccountingPeriodScheduler] factory={} {}-{} 触发失败: {}",
                        factoryId, year, month, e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("[AccountingPeriodScheduler] 完成: total={} success={} skipped={} failed={} 耗时={}ms",
                factoryIds.size(), success, skipped, failed, duration);
    }

    /**
     * 结转损益 finalize (方案A): 每日 03:00 扫描 CLOSED 且调整窗口已 LOCKED 且未结转
     * (closing_posted_at IS NULL) 的期间, 自动过结转损益凭证。窗口锁定后无业务凭证可入,
     * 故结转一次成型不陈旧 (spec §2.2)。系统触发 userId=null。
     */
    @Scheduled(cron = "${accounting-period.finalize.cron:0 0 3 * * ?}")
    @SchedulerLock(
            name = "AccountingPeriodScheduler.finalizeLockedPeriods",
            lockAtMostFor = "PT60M",
            lockAtLeastFor = "PT1M")
    public void finalizeLockedPeriods() {
        if (!enabled) return;
        List<String> factoryIds;
        try {
            factoryIds = factoryRepository.findAllActiveFactoryIds();
        } catch (Exception e) {
            log.error("[Finalize] 取 active factory 失败: {}", e.getMessage(), e);
            return;
        }
        int closed = 0;
        for (String factoryId : factoryIds) {
            List<AccountingPeriod> periods =
                    periodRepo.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, AccountingPeriod.Status.CLOSED);
            for (AccountingPeriod p : periods) {
                if (p.getClosingPostedAt() != null) continue; // 已结转
                if (p.getAdjustWindowState() != AccountingPeriod.AdjustWindowState.LOCKED) continue; // 窗口未过
                try {
                    // forceLockAndClose 是 per-period 原子事务 (closePeriod + 标记 closingPostedAt 同事务),
                    // 经 service proxy 调用 → 每期独立事务, 一期失败不污染其它期 (避免 batch @Transactional
                    // 遇 DataIntegrity 整批 rollback-only)。系统触发 userId=null。
                    accountingPeriodService.forceLockAndClose(factoryId, p.getYear(), p.getMonth(), null);
                    closed++;
                } catch (DataIntegrityViolationException dup) {
                    // 并发 (manual force-lock 同刻撞): 输方整事务回滚, 赢方已提交凭证+closingPostedAt → 终态正确, 仅 log。
                    log.warn("[Finalize] {}-{}-{} 唯一约束撞 (已被并发结转), 幂等跳过", factoryId, p.getYear(), p.getMonth());
                } catch (Exception e) {
                    log.error("[Finalize] {}-{}-{} 结转失败: {}", factoryId, p.getYear(), p.getMonth(), e.getMessage(), e);
                }
            }
        }
        if (closed > 0) log.info("[Finalize] 本轮结转 {} 个期间", closed);
    }
}
