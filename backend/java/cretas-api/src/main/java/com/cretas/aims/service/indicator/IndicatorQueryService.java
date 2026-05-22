package com.cretas.aims.service.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 指标查询服务 — 食品行业指标中心 Phase 1 Sprint 1 Day 4
 *
 * <p>策略分发: 按 {@link Indicator#getComputeStrategy()} 字段 (REALTIME / PRECOMPUTED / CACHED)
 * 选择不同计算路径。REALTIME 永远调 Python；PRECOMPUTED 直接返 lastValue；CACHED 在 TTL 内复用
 * lastValue，过期后 fetch Python 并落 {@code IndicatorVersion} 快照。
 *
 * <p>Python 端点真实接入推迟到 Phase 2B (本 Day 4 stub 返 {@link java.math.BigDecimal#ZERO})。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 */
public interface IndicatorQueryService {

    /**
     * 按指标 code 计算当前值 — 策略分发入口.
     *
     * <p>四分支:
     * <ul>
     *   <li>REALTIME    → 每次调用都 fetch Python (no cache, no version save)</li>
     *   <li>PRECOMPUTED → 直接返 {@code ind.lastValue} (Phase 2C scheduler 维护)</li>
     *   <li>CACHED      → TTL 内复用，过期 fetch Python + 落 version</li>
     *   <li>其他        → {@link com.cretas.aims.exception.BusinessException}</li>
     * </ul>
     *
     * @param code        指标编码 (e.g. {@code RESTAURANT_DISH_MARGIN})
     * @param factoryId   工厂 ID
     * @param periodStart 业务时间窗起
     * @param periodEnd   业务时间窗止
     * @return 计算结果 + 缓存命中标识 + 来源标签
     * @throws com.cretas.aims.exception.BusinessException 指标不存在 / 未知策略
     */
    IndicatorValueResult computeForCode(String code, String factoryId,
                                        LocalDate periodStart, LocalDate periodEnd);

    /**
     * 按分类列出工厂启用指标 (Day 5 Controller 用).
     *
     * @param factoryId 工厂 ID
     * @param category  分类 (FACTORY / RESTAURANT / QUALITY)
     * @return 启用指标列表 (按 displayOrder 升序)
     */
    List<Indicator> listByCategory(String factoryId, String category);

    /**
     * 按 ID 单条获取 (多租户安全).
     *
     * @param id        指标 ID
     * @param factoryId 工厂 ID
     * @return 指标定义
     * @throws com.cretas.aims.exception.BusinessException 不存在 / 跨租户
     */
    Indicator getById(String id, String factoryId);
}
