package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.alerts.restaurant.RestaurantHealthAlertBridgeService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 餐饮经营体检 → 标准告警 定时扫描器 — 2026-07-11 (餐饮经营体检预警推送).
 *
 * <p>为什么用 scheduled sweep 而非纯 event-driven: 体检报告的数据粒度是"月"
 * (health-check-report period=month), 没有一个自然的业务写操作时点能触发实时
 * 评估 (不像 INVENTORY_LOW 有"入库/出库"事件可挂). 定时轮询是唯一合理触发方式.
 *
 * <p>频率选择: 每 6 小时一次 (非 15 分钟) — 体检数据源本身按月更新, 6 小时内
 * 数据几乎不变, 更高频率纯粹浪费 Python 服务调用; 但仍保证"当天配置了规则/首次
 * 上线"时几小时内就能拿到第一批 standing alert, 不用等到明天. 另配一个 on-demand
 * endpoint (见 {@code RestaurantHealthAlertController}) 供手动立即触发/测试
 * (sweepFactory 幂等, 两条触发路径可自由并存不冲突).
 *
 * @since 2026-07-11
 */
@Slf4j
@Component
public class AlertRestaurantHealthCheckScheduler {

    @Autowired
    private FactoryRepository factoryRepository;

    @Autowired
    private RestaurantHealthAlertBridgeService bridgeService;

    @PostConstruct
    void init() {
        log.info("{} registered — 餐饮门店经营体检 standing alert 扫描, 每 6 小时一次 (ShedLock 防多实例)",
                getClass().getSimpleName());
    }

    /** 每 6 小时, 启动 5 分钟后首次跑 (给应用完全起来留缓冲). */
    @Scheduled(fixedRate = 6L * 60L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
    @SchedulerLock(name = "AlertRestaurantHealthCheckScheduler.evaluate",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void evaluate() {
        log.info("[AlertRestaurantHealthCheckScheduler] 开始扫描全部餐饮门店");

        List<Factory> restaurants;
        try {
            restaurants = factoryRepository.findByTypeAndIsActiveTrue(FactoryType.RESTAURANT);
        } catch (Exception e) {
            log.error("[AlertRestaurantHealthCheckScheduler] 查询餐饮门店列表失败", e);
            return;
        }

        log.info("[AlertRestaurantHealthCheckScheduler] {} 个活跃餐饮门店", restaurants.size());

        int ok = 0;
        int failed = 0;
        for (Factory f : restaurants) {
            try {
                Map<String, Object> r = bridgeService.sweepFactory(f.getId());
                if (Boolean.TRUE.equals(r.get("available"))) {
                    ok++;
                } else {
                    failed++;
                    log.warn("[AlertRestaurantHealthCheckScheduler] factoryId={} 本轮不可用: {}",
                            f.getId(), r.get("reason"));
                }
            } catch (Exception e) {
                failed++;
                log.error("[AlertRestaurantHealthCheckScheduler] factoryId={} 扫描异常", f.getId(), e);
            }
        }

        log.info("[AlertRestaurantHealthCheckScheduler] 扫描完成: ok={}, failed={}, total={}",
                ok, failed, restaurants.size());
    }
}
