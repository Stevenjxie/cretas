package com.cretas.aims.dto.material;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * T159-B-codegen: 原料智能建议多字段响应 DTO.
 *
 * <p>由 GET /raw-material-types/suggest?name=&category= 返回.
 * 基于 {@code findSimilarByNameAndCategory} 找最相似的历史原料,
 * 将该原料的字段全量返回供前端表单级联预填.
 * 无匹配时所有字段为 null — 前端需容忍 null (不得假设有默认值).
 *
 * @since 2026-06-08 T159-B-codegen
 */
@Data
@Builder
public class MaterialSuggestDTO {

    /** 计量单位 (e.g. "kg", "g", "箱", "个") */
    private String unit;

    /** 类别 (e.g. "肉类", "包材") */
    private String category;

    /** 存储方式 (e.g. "FROZEN", "REFRIGERATED", "ROOM_TEMP") */
    private String storageType;

    /** 保质期 (天) */
    private Integer shelfLifeDays;

    /**
     * 装箱换算系数 (来自 material_packaging_hierarchy.level1_per_level2).
     * null 表示该历史原料未配置包装层级.
     */
    private BigDecimal level1PerLevel2;

    /**
     * 二级包装单位 (e.g. "箱").
     * null 表示该历史原料未配置包装层级.
     */
    private String level2Unit;

    /** Same-factory material used as an explainable source; never cross-tenant. */
    private String matchedMaterialName;

    /** HIGH/MEDIUM/LOW. LOW suggestions are never silently written. */
    private String unitConfidence;

    private String segmentL1Code;
    private String segmentL2Code;
    private String segmentL3Code;
    private String classificationConfidence;
    private String classificationReason;
    private List<MaterialTaxonomyCandidateDTO> classificationCandidates;
}
