package com.cretas.aims.scheduler;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Sprint 9 P2.E — SSOP 每日清洁任务自动创建 Scheduler.
 *
 * <p>每日 06:00 (服务器时区) 执行, 跨工厂扫描 frequency=DAILY 且 active=true 的
 * {@link SsopProcedure} 模板, 当日没有 record 则创建 SCHEDULED record (按 shift=MORNING
 * 默认, 后续可由 operator 手动复制到其他 shift).
 *
 * <p>幂等性: {@code existsByProcedureIdAndExecutionDate} check — 同 procedureId
 * 同日期已存在 record 则跳过 (避免 scheduler 重复 fire 或人工预创建后冲突).
 *
 * <p>ShedLock per {@code ShedLockConfig}: blue-green 双 JVM 同步 fire 时只一个跑,
 * 另一个 silently skip. lockAtMostFor=10min 安全 (大量 procedures 时也跑不超 5min).
 *
 * <p>关于 SHIFT/WEEKLY/MONTHLY frequency:
 * <ul>
 *   <li>SHIFT: 暂不自动创建, 由 operator 手动创建每班次 record</li>
 *   <li>WEEKLY: 暂不自动创建 (复杂度: 需配置 dayOfWeek)</li>
 *   <li>MONTHLY: 暂不自动创建 (复杂度: 需配置 dayOfMonth)</li>
 * </ul>
 *
 * @since 2026-05-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsopDailyScheduleScheduler {

    /** Scheduler 默认创建 SCHEDULED record 的班次. */
    private static final String DEFAULT_SHIFT = "MORNING";

    private final SsopProcedureRepository procedureRepository;
    private final SsopExecutionRecordRepository recordRepository;
    private final FactoryRepository factoryRepository;

    @Value("${ssop.daily-schedule-scheduler.enabled:true}")
    private boolean enabled;

    /**
     * 每日 06:00 执行. cron 顺序: 秒 分 时 日 月 周.
     * {@code 0 0 6 * * ?} = 0 秒 0 分 6 时 任意日 任意月 任意周.
     */
    @Scheduled(cron = "${ssop.daily-schedule-scheduler.cron:0 0 6 * * ?}")
    @SchedulerLock(
            name = "SsopDailyScheduleScheduler.dailyTaskCreation",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT1M")
    public void dailyTaskCreation() {
        if (!enabled) {
            log.debug("[SsopDailyScheduleScheduler] 已禁用 (enabled=false)");
            return;
        }

        log.info("[SsopDailyScheduleScheduler] 开始每日 SSOP 清洁任务创建扫描");
        long startMs = System.currentTimeMillis();

        int createdCount = 0;
        int skippedCount = 0;
        int factoryCount = 0;

        try {
            LocalDate today = LocalDate.now();

            // 跨工厂扫描
            List<Factory> factories = factoryRepository.findAll();
            for (Factory factory : factories) {
                String factoryId = factory.getId();
                List<SsopProcedure> dailyProcedures = procedureRepository
                        .findByFactoryIdAndFrequencyAndActiveTrue(factoryId, "DAILY");
                if (dailyProcedures.isEmpty()) continue;

                factoryCount++;
                for (SsopProcedure proc : dailyProcedures) {
                    // 幂等 check
                    if (recordRepository.existsByProcedureIdAndExecutionDate(
                            proc.getId(), today)) {
                        skippedCount++;
                        continue;
                    }

                    SsopExecutionRecord rec = SsopExecutionRecord.builder()
                            .factoryId(factoryId)
                            .procedureId(proc.getId())
                            .executionDate(today)
                            .shift(DEFAULT_SHIFT)
                            .status("SCHEDULED")
                            .build();
                    try {
                        recordRepository.save(rec);
                        createdCount++;
                    } catch (Exception e) {
                        log.warn("[SsopDailyScheduleScheduler] 创建失败 factory={} procedureId={}: {}",
                                factoryId, proc.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[SsopDailyScheduleScheduler] 扫描异常: {}", e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("[SsopDailyScheduleScheduler] 扫描完成, 耗时 {}ms, " +
                        "覆盖 {} 工厂, 创建 {} 项, 跳过 (已存在) {} 项",
                duration, factoryCount, createdCount, skippedCount);
    }
}
