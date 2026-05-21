package com.cretas.aims.scheduler;

import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 9 P2.C — 供应商食品资质过期预警 Scheduler.
 *
 * <p>每日 08:00 (服务器时区) 执行, 跨工厂扫描全部 SupplierQualification:
 * <ol>
 *   <li>已过期 (expiry_date < today) 且 status IN ('VALID', 'EXPIRING') →
 *       UPDATE status = 'EXPIRED' + 紧急通知 QUALITY_MANAGER + PROCUREMENT_MANAGER 角色</li>
 *   <li>7 天内将过期 (today <= expiry_date <= today + 7) 且 status = 'VALID' →
 *       UPDATE status = 'EXPIRING' + 紧急通知 (高优先级)</li>
 *   <li>30 天内将过期 (today + 7 < expiry_date <= today + 30) 且 status = 'VALID' →
 *       UPDATE status = 'EXPIRING' + 普通通知 (提前预警)</li>
 * </ol>
 *
 * <p>幂等性: status 转换是 idempotent — 已 EXPIRED 不会再降级回 EXPIRING.
 * 重复执行同一日只发一次通知 (state-machine 单向).
 *
 * <p>ShedLock per {@link com.cretas.aims.config.ShedLockConfig}: blue-green 双 JVM
 * 同步 fire 时只其中一个跑, 另一个 silently skip. lockAtMostFor=10min 安全 (大量
 * factory 时也跑不超 5min).
 *
 * <p>通知接收角色: 默认 {@code QUALITY_MANAGER}. 可在 future iteration 加上
 * PROCUREMENT_MANAGER (受 PO 影响).
 *
 * @since 2026-05-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierQualificationExpiryScheduler {

    private static final String NOTIFY_ROLE = "QUALITY_MANAGER";

    private final SupplierQualificationRepository qualificationRepository;
    private final FactoryRepository factoryRepository;
    private final NotificationService notificationService;

    @Value("${supplier.qualification-scheduler.enabled:true}")
    private boolean enabled;

    /**
     * 每日 08:00 执行. cron 顺序: 秒 分 时 日 月 周.
     * {@code 0 0 8 * * ?} = 0 秒 0 分 8 时 任意日 任意月 任意周.
     */
    @Scheduled(cron = "${supplier.qualification-scheduler.cron:0 0 8 * * ?}")
    @SchedulerLock(
            name = "SupplierQualificationExpiryScheduler.dailyExpiryCheck",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT1M")
    public void dailyExpiryCheck() {
        if (!enabled) {
            log.debug("[SupplierQualificationExpiryScheduler] 已禁用 (enabled=false)");
            return;
        }

        log.info("[SupplierQualificationExpiryScheduler] 开始每日资质过期扫描");
        long startMs = System.currentTimeMillis();

        int expiredCount = 0;
        int urgentCount = 0;
        int warningCount = 0;

        try {
            LocalDate today = LocalDate.now();

            // 1. 已过期 (expiry < today, status 仍 VALID/EXPIRING)
            List<SupplierQualification> expired = qualificationRepository
                    .findAllExpiredByStatusIn(List.of("VALID", "EXPIRING"), today);
            for (SupplierQualification q : expired) {
                String prev = q.getStatus();
                q.setStatus("EXPIRED");
                qualificationRepository.save(q);
                expiredCount++;
                notifyExpired(q, prev);
            }

            // 2. 7 天内将过期 (today <= expiry <= today + 7, status = VALID)
            LocalDate sevenDaysOut = today.plusDays(7);
            List<SupplierQualification> urgent = qualificationRepository
                    .findAllValidExpiringBetween(today, sevenDaysOut);
            // 同时也 catch 当天已经 EXPIRING 的 (重发紧急通知)
            for (SupplierQualification q : urgent) {
                String prev = q.getStatus();
                if (!"EXPIRING".equals(prev)) {
                    q.setStatus("EXPIRING");
                    qualificationRepository.save(q);
                }
                urgentCount++;
                notifyUrgent(q);
            }

            // 3. 30 天内将过期 (8 <= days <= 30, status = VALID)
            LocalDate eightDaysOut = today.plusDays(8);
            LocalDate thirtyDaysOut = today.plusDays(30);
            List<SupplierQualification> warning = qualificationRepository
                    .findAllValidExpiringBetween(eightDaysOut, thirtyDaysOut);
            Set<Long> alreadyUrgent = new HashSet<>();
            for (SupplierQualification q : urgent) {
                alreadyUrgent.add(q.getId());
            }
            for (SupplierQualification q : warning) {
                if (alreadyUrgent.contains(q.getId())) continue;
                String prev = q.getStatus();
                if (!"EXPIRING".equals(prev)) {
                    q.setStatus("EXPIRING");
                    qualificationRepository.save(q);
                }
                warningCount++;
                notifyWarning(q);
            }
        } catch (Exception e) {
            log.error("[SupplierQualificationExpiryScheduler] 扫描异常: {}", e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("[SupplierQualificationExpiryScheduler] 扫描完成, 耗时 {}ms, "
                        + "已过期 {} 项 ⛔, 7天内 {} 项 🔴, 30天内 {} 项 🟡",
                duration, expiredCount, urgentCount, warningCount);
    }

    private void notifyExpired(SupplierQualification q, String previousStatus) {
        String title = String.format("⛔ 供应商资质已过期 — %s (%s)",
                q.getQualificationType(), q.getCertificateNumber());
        String body = String.format(
                "供应商资质过期警告 (factory=%s, supplier=%s)\n"
                        + "类型: %s\n证号: %s\n过期日: %s\n之前状态: %s → 已自动迁移 EXPIRED\n\n"
                        + "⛔ 此供应商相关采购单建议立即审查. 续期或选择替代供应商.",
                q.getFactoryId(), q.getSupplierId(), q.getQualificationType(),
                q.getCertificateNumber(), q.getExpiryDate(), previousStatus);
        try {
            notificationService.notifyRole(q.getFactoryId(), NOTIFY_ROLE, title, body);
        } catch (Exception e) {
            log.warn("[SupplierQualificationExpiryScheduler] 通知失败 (id={}): {}",
                    q.getId(), e.getMessage());
        }
    }

    private void notifyUrgent(SupplierQualification q) {
        long days = LocalDate.now().until(q.getExpiryDate()).getDays();
        String title = String.format("🔴 紧急: 供应商资质 %d 天内过期 — %s",
                days, q.getQualificationType());
        String body = String.format(
                "供应商资质 7 天内将过期 (factory=%s, supplier=%s)\n"
                        + "类型: %s\n证号: %s\n过期日: %s (剩 %d 天)\n\n"
                        + "🔴 紧急: 立即联系供应商续期, 否则采购单会被 BLOCKING.",
                q.getFactoryId(), q.getSupplierId(), q.getQualificationType(),
                q.getCertificateNumber(), q.getExpiryDate(), days);
        try {
            notificationService.notifyRole(q.getFactoryId(), NOTIFY_ROLE, title, body);
        } catch (Exception e) {
            log.warn("[SupplierQualificationExpiryScheduler] 通知失败 (id={}): {}",
                    q.getId(), e.getMessage());
        }
    }

    private void notifyWarning(SupplierQualification q) {
        long days = LocalDate.now().until(q.getExpiryDate()).getDays();
        String title = String.format("🟡 供应商资质 %d 天内过期 — %s",
                days, q.getQualificationType());
        String body = String.format(
                "供应商资质 30 天内将过期 (factory=%s, supplier=%s)\n"
                        + "类型: %s\n证号: %s\n过期日: %s (剩 %d 天)\n\n"
                        + "🟡 提前预警: 建议联系供应商安排续期手续.",
                q.getFactoryId(), q.getSupplierId(), q.getQualificationType(),
                q.getCertificateNumber(), q.getExpiryDate(), days);
        try {
            notificationService.notifyRole(q.getFactoryId(), NOTIFY_ROLE, title, body);
        } catch (Exception e) {
            log.warn("[SupplierQualificationExpiryScheduler] 通知失败 (id={}): {}",
                    q.getId(), e.getMessage());
        }
    }
}
