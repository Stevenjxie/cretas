package com.cretas.aims.entity.enums;

/**
 * Sprint4-H Q-PROCESS-1: 工序质检不良类型枚举.
 *
 * <p>覆盖食品加工常见缺陷类别 — 外观/重量/成分/微生物/包装/异物.
 * 用于 QualityDefect.defectType, AIChat tool 过滤, 报表统计.
 */
public enum DefectType {
    APPEARANCE("外观缺陷", "色泽不正/形状不规则/破损/变形"),
    WEIGHT("重量不达标", "净含量低于/高于规格"),
    COMPOSITION("成分异常", "蛋白/脂肪/水分等指标超标"),
    MICROBIAL("微生物超标", "菌落总数/致病菌/霉菌超标"),
    PACKAGING("包装缺陷", "封口不严/标签缺失/印刷错误/破损"),
    FOREIGN_OBJECT("异物", "金属/塑料/毛发/玻璃等异物"),
    SENSORY("感官异常", "气味/口感/质地异常"),
    SHELF_LIFE("保质期问题", "临期/过期/储存条件异常"),
    OTHER("其他", "未分类缺陷");

    private final String displayName;
    private final String description;

    DefectType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
