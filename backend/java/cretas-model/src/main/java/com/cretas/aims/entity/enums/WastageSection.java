package com.cretas.aims.entity.enums;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 餐饮损耗档口枚举 (Wave2 损耗按人/档口责任制)。
 *
 * <p>固定枚举值，前端 dropdown 录入（防呆 Rule 3：约束选择而非自由文本）。
 * DB 存稳定英文码 ({@link #name()})，UI / 报表层用 {@link #getLabel()} 中文。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
public enum WastageSection {

    SEAFOOD("海鲜"),
    COLD_DISH("冷菜"),
    HOT_DISH("热菜"),
    FRONT_HOUSE("前厅"),
    OTHER("其他");

    private final String label;

    WastageSection(String label) {
        this.label = label;
    }

    /** 中文显示名 */
    public String getLabel() {
        return label;
    }

    /** 全部合法 code 列表（schema / 参数校验用） */
    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toList());
    }

    /**
     * 解析 code（大小写不敏感）。非法值返回 null（调用方决定是否拒绝）。
     */
    public static WastageSection fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        try {
            return WastageSection.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * code → 中文 label，未知 code 原样返回（DTO 回填用，容忍历史脏数据）。
     */
    public static String labelOf(String code) {
        WastageSection s = fromCode(code);
        return s != null ? s.label : (code == null ? "未指定" : code);
    }
}
