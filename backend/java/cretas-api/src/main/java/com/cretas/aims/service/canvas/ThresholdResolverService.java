package com.cretas.aims.service.canvas;

import com.cretas.aims.entity.canvas.FactoryThreshold;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Canvas-Thresholds 解析服务 (Phase A P0-1).
 *
 * <p>统一阈值读取入口 — 6 个 service 类 (InventoryHealthAnalysisServiceImpl /
 * IotDataServiceImpl / CustomerCreditCheckServiceImpl / RecursiveBomExpansionService /
 * ShortageAnalysisServiceImpl / ComplexityRouterImpl) 都通过此接口读取自己的阈值。
 *
 * <p>解析优先级:
 * <ol>
 *   <li>per-factory row (factoryId, thresholdKey) with enabled=true</li>
 *   <li>global default row ({@code "*"}, thresholdKey) with enabled=true</li>
 *   <li>调用方提供的 fallback 默认值 (hard-coded constant)</li>
 * </ol>
 *
 * <p>所有 getter 在 DB miss / cache miss 时不抛异常, 返回 fallback default。
 *
 * @since Canvas Phase A (2026-05-21)
 */
public interface ThresholdResolverService {

    /**
     * 读取整数阈值; 未找到时返回 {@code defaultValue}.
     */
    int getInteger(String factoryId, String thresholdKey, int defaultValue);

    /**
     * 读取 BigDecimal 阈值; 未找到时返回 {@code defaultValue}.
     */
    BigDecimal getBigDecimal(String factoryId, String thresholdKey, BigDecimal defaultValue);

    /**
     * 读取双精度浮点阈值; 未找到时返回 {@code defaultValue}.
     */
    double getDouble(String factoryId, String thresholdKey, double defaultValue);

    /**
     * 读取字符串阈值; 未找到时返回 {@code defaultValue}.
     */
    String getString(String factoryId, String thresholdKey, String defaultValue);

    /**
     * 读取阈值实体 (UI / 测试用; 业务 service 优先用 typed getter).
     */
    Optional<FactoryThreshold> resolve(String factoryId, String thresholdKey);

    /**
     * 失效指定 (factoryId, thresholdKey) 的缓存条目.
     * 通常由 Controller mutation endpoint (POST/PUT/DELETE) 在写入后调用。
     */
    void invalidate(String factoryId, String thresholdKey);

    /**
     * 失效指定 factoryId 的所有缓存条目 (批量重置 / 工厂被删除时).
     */
    void invalidateFactory(String factoryId);

    /**
     * 清空所有缓存 (测试用).
     */
    void invalidateAll();
}
