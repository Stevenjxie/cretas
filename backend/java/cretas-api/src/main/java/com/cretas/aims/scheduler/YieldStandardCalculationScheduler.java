package com.cretas.aims.scheduler;

import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.yield.YieldStandardCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D4: 按历史逐道报工数据自动推算工序标准出成率上下限与标准时薪.
 *
 * <p>只写入空字段, 已手填的标准值优先保留.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YieldStandardCalculationScheduler {

    private final FactoryRepository factoryRepository;
    private final YieldStandardCalculationService yieldStandardCalculationService;

    @Value("${yield-standard.auto-calculate.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${yield-standard.auto-calculate.cron:0 0 3 * * ?}")
    @SchedulerLock(
            name = "YieldStandardCalculationScheduler.autoCalculate",
            lockAtMostFor = "PT60M",
            lockAtLeastFor = "PT1M")
    public void autoCalculate() {
        if (!enabled) {
            log.debug("[YieldStandardCalculationScheduler] 已禁用 (yield-standard.auto-calculate.enabled=false)");
            return;
        }

        log.info("[YieldStandardCalculationScheduler] 开始自动推算工序标准值");
        long startMs = System.currentTimeMillis();

        List<String> factoryIds;
        try {
            factoryIds = factoryRepository.findAllActiveFactoryIds();
        } catch (Exception e) {
            log.error("[YieldStandardCalculationScheduler] 取 active factory ids 失败: {}", e.getMessage(), e);
            return;
        }

        int success = 0;
        int failed = 0;
        int updated = 0;
        int skippedInsufficient = 0;
        int skippedManual = 0;
        for (String factoryId : factoryIds) {
            try {
                YieldStandardCalculationService.Result result =
                        yieldStandardCalculationService.recalculateFactory(factoryId);
                success++;
                updated += result.getUpdated();
                skippedInsufficient += result.getSkippedInsufficientData();
                skippedManual += result.getSkippedManual();
                failed += result.getFailed();
                log.info("[YieldStandardCalculationScheduler] factory={} processed={} updated={} insufficient={} manual={} failed={}",
                        factoryId,
                        result.getProcessed(),
                        result.getUpdated(),
                        result.getSkippedInsufficientData(),
                        result.getSkippedManual(),
                        result.getFailed());
            } catch (Exception e) {
                failed++;
                log.warn("[YieldStandardCalculationScheduler] factory={} 推算失败: {}",
                        factoryId, e.getMessage(), e);
            }
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("[YieldStandardCalculationScheduler] 完成: totalFactories={} success={} updated={} insufficient={} manual={} failed={} 耗时={}ms",
                factoryIds.size(), success, updated, skippedInsufficient, skippedManual, failed, duration);
    }
}
