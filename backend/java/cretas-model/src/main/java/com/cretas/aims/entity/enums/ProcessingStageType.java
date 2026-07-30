package com.cretas.aims.entity.enums;

/**
 * 加工环节类型枚举
 * 定义食品加工各个环节的标准类型
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-23
 */
public enum ProcessingStageType {
    // ==================== 前处理环节 ====================
    RECEIVING("接收", "原料接收验收"),
    THAWING("解冻", "半解冻处理"),
    TRIMMING("修整", "去尾/修整处理"),
    PREPARATION("准备", "前处理准备"),
    SORTING("分拣", "原料分拣"),

    // ==================== 主加工环节 ====================
    CUTTING("切割", "切割处理"),
    SLICING("切片", "机械切片"),
    DICING("切丁", "切丁处理"),
    MINCING("绞碎", "绞碎处理"),

    // ==================== 清洗环节 ====================
    WASHING("清洗", "清洗处理"),
    DRAINING("沥干", "沥干处理"),

    // ==================== 调味环节 ====================
    MARINATING("腌制", "腌制/上浆"),
    SEASONING("调味", "调味处理"),

    // ==================== 热处理环节 ====================
    COOKING("烹饪", "烹饪/蒸煮"),
    FRYING("油炸", "油炸处理"),
    BAKING("烘烤", "烘烤处理"),
    STEAMING("蒸制", "蒸制处理"),

    // ==================== 冷处理环节 ====================
    COOLING("冷却", "冷却处理"),
    FREEZING("速冻", "IQF速冻"),
    CHILLING("冷藏", "冷藏处理"),

    // ==================== 包装环节 ====================
    PACKAGING("包装", "成品包装"),
    LABELING("贴标", "标签打印"),
    BOXING("装箱", "装箱处理"),

    // ==================== 质检环节 ====================
    INSPECTION("检验", "质量检验"),
    QUALITY_CHECK("品控", "品控检查"),
    METAL_DETECTION("金属检测", "金属探测"),
    WEIGHT_CHECK("称重检测", "重量检测"),

    // ==================== 清洁环节 ====================
    CLEANING("清洁", "设备清洁"),
    LINE_CHANGE("换线", "产线切换"),

    // ==================== 其他 ====================
    OTHER("其他", "其他环节");

    private final String name;
    private final String description;

    ProcessingStageType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据名称查找环节类型
     */
    public static ProcessingStageType fromName(String name) {
        for (ProcessingStageType type : values()) {
            if (type.name.equals(name)) {
                return type;
            }
        }
        return OTHER;
    }
}
