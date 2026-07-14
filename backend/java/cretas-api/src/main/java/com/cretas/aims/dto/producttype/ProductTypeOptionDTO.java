package com.cretas.aims.dto.producttype;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 产品类型「选项」精简 DTO —— 仅供下拉选择器 / SKU picker 使用。
 *
 * <p>背景: {@link ProductTypeDTO} 是 47 字段重 DTO, 每条还要解析 4 个 JSON 字段
 * (processingSteps / skillRequirements / equipmentIds / qualityCheckIds)。产品-工序配置页
 * 顶部产品选择器 + workflow 编辑器 SKU picker 各拉一次全量 (F006 382 个产品 → 422KB / ~3s ×2),
 * 但它们只需 id/name/code/unit/specification/productCategory 几个标量字段。
 *
 * <p>本 DTO 通过 JPQL 构造器投影直接 SELECT 需要的列 (不 hydrate 实体、不解析 JSON), 配合
 * {@code @Cacheable} → 加载从 ~3s 降到几百毫秒且二次命中缓存。字段顺序必须与
 * {@code ProductTypeRepository#findOptionsByFactoryId} 的 {@code SELECT new} 一致。
 *
 * @author Cretas Team
 * @since 2026-07-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductTypeOptionDTO {
    private String id;
    private String code;
    private String name;
    private String unit;
    private String specification;
    private String productCategory;
    private Boolean isActive;
    /** 温区 (2026-07-14 追加, 供成品/SKU 管理页动态筛选下拉用) */
    private String temperatureZone;
}
