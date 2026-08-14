package com.cretas.aims.event.listener;

import com.cretas.aims.event.RecipeSavedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 餐饮配方保存事件监听器 (#57) — 触发 Python gold 缓存
 * {@code agg_restaurant_product_cost} 对该菜品重算。
 *
 * <p><b>事务隔离 (REQUIRES_NEW + @Async)</b>: 配方 create/update 主事务在 controller
 * 内 commit, 之后才异步处理本事件。重算逻辑跑在<b>独立新事务</b>里, 即便重算失败也
 * <b>绝不</b>污染/doom 配方保存事务 (heed 项目 doomed-transaction 铁律: Spring REQUIRED
 * 内层抛异常会标记父事务 rollback-only, fail-soft catch 也救不回 → 必须 REQUIRES_NEW
 * 真隔离)。@Async 让 listener 在独立线程跑, 不阻塞 HTTP 响应。
 *
 * <p><b>当前状态 (stub)</b>: Python 重算的内部 HTTP 调用尚未接线 (需复用 PythonSmartBIClient
 * + 内部鉴权头 X-Internal-Secret / X-Factory-Id, 见 restaurant_cost_card.py + cron ETL)。
 * 本 listener 先落地 event-publish + 监听骨架 + fail-soft 边界, 记 TODO。重算暂由
 * 既有的手动 ETL 端点
 * (POST /api/smartbi/restaurant-ops/etl) 覆盖, 缓存最长滞后一周; staleness 列
 * (last_recipe_updated_at) 已就位供未来精确判过期。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57)
 */
@Slf4j
@Component
public class RecipeSavedEventListener {

    /**
     * REQUIRES_NEW: 独立事务, 与配方保存事务完全隔离。@Async: 独立线程, 不阻塞响应。
     */
    @EventListener
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleRecipeSaved(RecipeSavedEvent event) {
        try {
            log.info("[配方成本重算] 收到配方保存事件: {}", event);
            recomputeProductCost(event.getFactoryId(), event.getProductTypeId());
        } catch (Exception e) {
            // fail-soft: 重算失败绝不影响配方保存 (主事务已 commit), 仅记日志。
            log.warn("[配方成本重算 — best-effort, 不阻塞] factoryId={}, productTypeId={}: {}",
                    event.getFactoryId(), event.getProductTypeId(), e.getMessage());
        }
    }

    /**
     * 触发 Python 端 agg_restaurant_product_cost 该菜品重算。
     *
     * <p>TODO(#57 follow-up): 接线 PythonSmartBIClient 调
     * {@code POST /api/smartbi/restaurant-ops/etl} (或新增窄 recompute 端点) 带
     * 内部鉴权头, 把 last_recipe_updated_at 写为 event.savedAt。当前仅 log,
     * ⚠️ 2026-08-14 订正: 原文写「缓存由 weekly materializer 兜底刷新」——
     * **全仓没有 weekly materializer**（已 grep，只有 restaurant_cost_card.py
     * 读 last_recipe_updated_at 判陈旧）。真正在刷的是 Python 侧那个进程内
     * ETL 常驻循环（restaurant_ops_etl Stage 3d，每轮重算 food_cost）。
     */
    private void recomputeProductCost(String factoryId, String productTypeId) {
        log.info("[配方成本重算 — STUB] 暂未接线 Python 重算 (由 Python 侧 ETL 常驻循环兜底): "
                + "factoryId={}, productTypeId={}", factoryId, productTypeId);
        // 实接线示例 (待 follow-up):
        //   pythonSmartBIClient.recomputeProductCost(factoryId, productTypeId, event.getSavedAt());
    }
}
