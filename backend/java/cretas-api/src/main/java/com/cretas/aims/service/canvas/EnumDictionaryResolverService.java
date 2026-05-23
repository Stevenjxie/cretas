package com.cretas.aims.service.canvas;

import com.cretas.aims.entity.canvas.EnumDictionary;

import java.util.List;

/**
 * Canvas-Phase C 枚举字典解析服务 (Resolver).
 *
 * <p>统一 dropdown 值读取入口 — 前端 select / 后端 enum 校验都通过此接口。
 *
 * <p>解析优先级:
 * <ol>
 *   <li>per-factory rows (factoryId, category) with enabled=true</li>
 *   <li>fallback to global ("*", category) 当 per-factory 为空</li>
 * </ol>
 *
 * <p>注意: per-factory 一旦有任何 enabled row 即不再 fallback 到 global; 视为 "工厂已自定义".
 *
 * @since Canvas Phase C (2026-05-22)
 */
public interface EnumDictionaryResolverService {

    /**
     * 读取 (factoryId, category) 对应的所有 enabled 枚举值, 按 displayOrder + code 排序.
     *
     * @param factoryId 工厂 ID; null/blank → 直接 fallback global
     * @param category  类别 code, e.g. "CANCEL_REASON"
     * @return 枚举值列表 (可能为空 list, 但不为 null)
     */
    List<EnumDictionary> getEnumValues(String factoryId, String category);

    /**
     * 失效 (factoryId, category) 缓存条目, 通常由 Controller 写后调用。
     */
    void invalidate(String factoryId, String category);

    /**
     * 失效全部缓存 (测试用 + 工厂被删除时).
     */
    void invalidateAll();
}
