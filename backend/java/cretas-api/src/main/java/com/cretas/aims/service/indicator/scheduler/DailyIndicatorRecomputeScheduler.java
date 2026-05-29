package com.cretas.aims.service.indicator.scheduler;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 真业务 indicator 每日重算调度器 — Sprint 12 Phase B step 3 (Issue #263 main).
 *
 * <p>每日 02:00 Asia/Shanghai (低流量窗) 遍历 {@link IndicatorComputationStrategyRegistry}
 * 注册的所有 REAL_BUSINESS strategy codes, 在所有启用的 (factory, code) 对上调
 * {@link IndicatorQueryService#computeForCode} 触发 fresh 计算. 每次成功调用会:
 * <ol>
 *   <li>strategy.compute() 跑 SQL aggregate 真接业务表</li>
 *   <li>更新 indicator.lastValue + lastComputedAt (CACHED 分支)</li>
 *   <li>append indicator_versions 一行 (compute_source='REAL_BUSINESS:&lt;code&gt;')</li>
 * </ol>
 *
 * <p>设计选择:
 * <ul>
 *   <li><b>02:00 时点</b>: sales/inventory 已稳定, 老板早上看 dashboard (08:00-09:00) 时 cache 暖</li>
 *   <li><b>per-(factory, code) try/catch</b>: 一个失败不影响其他 — 同 governance 模式</li>
 *   <li><b>periodStart/End 传 null</b>: 让 strategy 用自己的 default (MTD / 本月 / instant snapshot)</li>
 *   <li><b>不重叠运行</b>: Spring 默认 single-thread scheduler, @Scheduled 已串行化</li>
 * </ul>
 *
 * <p>禁用方式: {@code cretas.indicator.daily-recompute.enabled=false} (测试环境 / 故障 rollback 用).
 *
 * @author Cretas Team
 * @since 2026-05-29 (Sprint 12 Phase B step 3)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyIndicatorRecomputeScheduler {

    private final IndicatorComputationStrategyRegistry strategyRegistry;
    private final IndicatorQueryService indicatorQueryService;
    private final IndicatorRepository indicatorRepository;

    @Value("${cretas.indicator.daily-recompute.enabled:true}")
    private boolean enabled;

    @Value("${cretas.indicator.recompute-on-startup:true}")
    private boolean recomputeOnStartup;

    /**
     * 每日 02:00 Asia/Shanghai 重算所有 REAL_BUSINESS indicators.
     *
     * <p>Cron expression: {@code "0 0 2 * * *"} = "秒 分 时 日 月 星期" — 每天 02:00:00.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
    public void recomputeAll() {
        runRecomputeOnce("daily-cron");
    }

    /**
     * Spring 启动完成立即跑一次 — fresh deploy 后 lastValue 立即填 (不等 02:00),
     * dashboard 即时看到 real-business 数字 (而不是 "—" placeholder).
     *
     * <p>禁用方式: {@code cretas.indicator.recompute-on-startup=false} (prod 海量 indicators 时考虑).
     * 当前 Sprint 12 Phase B step 1+2 共 4 个 codes × ~1-5 factories = ≤20 个 SQL aggregates, 启动慢度可忽略.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!recomputeOnStartup) {
            log.debug("DailyIndicatorRecomputeScheduler startup hook disabled via cretas.indicator.recompute-on-startup=false");
            return;
        }
        log.info("DailyIndicatorRecomputeScheduler: Spring ready — triggering initial recompute (cretas.indicator.recompute-on-startup=true)");
        runRecomputeOnce("startup");
    }

    /**
     * 暴露给手动 trigger / startup hook (per spec 后续可加 ApplicationReadyEvent listener
     * 让 fresh deploy 立刻填 lastValue 不等到第二天 02:00). 当前只供 cron 调.
     *
     * @return success count, failed count
     */
    public int[] runRecomputeOnce(String triggerLabel) {
        if (!enabled) {
            log.debug("DailyIndicatorRecomputeScheduler disabled via cretas.indicator.daily-recompute.enabled=false");
            return new int[]{0, 0};
        }

        List<String> codes = strategyRegistry.allCodes();
        if (codes.isEmpty()) {
            log.info("DailyIndicatorRecomputeScheduler [{}]: no REAL_BUSINESS strategies registered — skip", triggerLabel);
            return new int[]{0, 0};
        }

        List<Indicator> indicators = indicatorRepository
                .findByCodeInAndIsActiveTrueAndDeletedAtIsNull(codes);
        if (indicators.isEmpty()) {
            log.info("DailyIndicatorRecomputeScheduler [{}]: {} strategy codes registered but no active indicators bind them — skip",
                    triggerLabel, codes.size());
            return new int[]{0, 0};
        }

        int success = 0;
        int failed = 0;
        long startMs = System.currentTimeMillis();

        for (Indicator ind : indicators) {
            try {
                // periodStart/End=null → strategy 用 default (MTD / current month / instant)
                indicatorQueryService.computeForCode(ind.getCode(), ind.getFactoryId(), null, null);
                success++;
            } catch (Exception ex) {
                failed++;
                log.warn("DailyIndicatorRecomputeScheduler [{}] failed: code={}, factoryId={}, err={}",
                        triggerLabel, ind.getCode(), ind.getFactoryId(), ex.getMessage());
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("DailyIndicatorRecomputeScheduler [{}] done: {}/{} success, {} failed, {} codes × {} (factory,code) pairs in {}ms",
                triggerLabel, success, indicators.size(), failed, codes.size(), indicators.size(), elapsedMs);

        return new int[]{success, failed};
    }
}
