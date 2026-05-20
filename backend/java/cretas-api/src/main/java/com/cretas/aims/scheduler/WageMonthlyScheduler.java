package com.cretas.aims.scheduler;

import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.WageCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Sprint 6 Track W4-B: 月底自动 trigger 工资计算.
 *
 * <p>每月 1 号 01:00 执行 (上月 wage 数据 finalized 后), 对所有 active 工厂的所有员工
 * 跑 {@link WageCalculationService#calculateMonthlyForFactory} → batch 生成
 * {@link com.cretas.aims.entity.WageCalculation} 记录.
 *
 * <p>幂等性: {@code WageCalculationService.calculateMonthly} 内部 upsert (factoryId, employeeId, periodMonth) →
 * 重复执行不重复创建 calc 记录 (同月再 trigger 仅 update existing).
 *
 * <p>ShedLock per {@link com.cretas.aims.config.ShedLockConfig}: blue-green 双 JVM 同步 fire
 * 时只其中一个跑, 另一个 silently skip.
 *
 * <p>TODO Sprint 6 W4-A 协同: 待 {@code VoucherGenerator} 支持 WAGE_MONTHLY business type 后,
 * 在 calculateMonthlyForFactory 完成后调用 {@code voucherService.batchCreateForFactory(factoryId, "WAGE_MONTHLY")}
 * 自动生成工资凭证. 当前 wave 2 wired W4-A in flight (sprint6/W4-A-auxiliary-full-wiring),
 * 先 ship calc, voucher 留 follow-up.
 *
 * @since 2026-05-19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WageMonthlyScheduler {

    private final FactoryRepository factoryRepository;

    // optional injection — 测试环境无 wage 模块时可缺.
    @Autowired(required = false)
    private WageCalculationService wageCalculationService;

    @Value("${wage.monthly-scheduler.enabled:true}")
    private boolean enabled;

    /**
     * 每月 1 号 01:00 (服务器时区) 执行上月工资批量计算.
     *
     * <p>cron 顺序: 秒 分 时 日 月 周. {@code 0 0 1 1 * ?} = 0 秒 0 分 1 时 1 日 任意月 任意周.
     *
     * <p>periodMonth 取上月 (e.g. 2026-06-01 凌晨跑 → 计算 2026-05).
     */
    @Scheduled(cron = "${wage.monthly-scheduler.cron:0 0 1 1 * ?}")
    @SchedulerLock(
            name = "WageMonthlyScheduler.runMonthly",
            lockAtMostFor = "PT60M",
            lockAtLeastFor = "PT1M")
    public void runMonthly() {
        if (!enabled) {
            log.debug("[WageMonthlyScheduler] 已禁用 (wage.monthly-scheduler.enabled=false)");
            return;
        }
        if (wageCalculationService == null) {
            log.warn("[WageMonthlyScheduler] WageCalculationService 未注入, 跳过");
            return;
        }

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        log.info("[WageMonthlyScheduler] 开始月底自动工资计算, month={}", lastMonth);
        long startMs = System.currentTimeMillis();

        try {
            executeBatchForAllFactories(lastMonth);
        } catch (Exception e) {
            log.error("[WageMonthlyScheduler] 月底批量计算异常: {}", e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("[WageMonthlyScheduler] 月底自动工资计算完成, 耗时 {}ms", duration);
    }

    /**
     * 手动触发批量计算 (admin UI / 测试用). 不带 ShedLock — 由 caller 控制并发.
     */
    public Map<String, Object> manualTrigger(YearMonth month) {
        if (wageCalculationService == null) {
            throw new IllegalStateException("WageCalculationService 未注入");
        }
        log.info("[WageMonthlyScheduler] 手动触发月度计算: month={}", month);
        return executeBatchForAllFactories(month);
    }

    private Map<String, Object> executeBatchForAllFactories(YearMonth month) {
        List<String> factoryIds = factoryRepository.findAllActiveFactoryIds();
        log.info("[WageMonthlyScheduler] 发现 {} 个活跃工厂, month={}", factoryIds.size(), month);

        int factoryCount = 0;
        int totalSuccess = 0;
        int totalFailed = 0;
        java.util.Map<String, Object> aggregated = new java.util.LinkedHashMap<>();

        for (String factoryId : factoryIds) {
            try {
                Map<String, Object> result = wageCalculationService.calculateMonthlyForFactory(factoryId, month);
                Object succObj = result.get("successCount");
                Object failObj = result.get("failedCount");
                if (succObj instanceof Integer s) totalSuccess += s;
                if (failObj instanceof Integer f) totalFailed += f;
                factoryCount++;
            } catch (Exception e) {
                log.error("[WageMonthlyScheduler] factory={} 计算失败: {}", factoryId, e.getMessage(), e);
                totalFailed++;
            }
        }

        aggregated.put("month", month.toString());
        aggregated.put("factoryCount", factoryCount);
        aggregated.put("totalSuccess", totalSuccess);
        aggregated.put("totalFailed", totalFailed);
        log.info("[WageMonthlyScheduler] 汇总: month={}, factories={}, success={}, failed={}",
                month, factoryCount, totalSuccess, totalFailed);
        return aggregated;
    }
}
