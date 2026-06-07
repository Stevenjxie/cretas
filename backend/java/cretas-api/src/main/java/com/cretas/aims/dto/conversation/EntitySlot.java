package com.cretas.aims.dto.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实体槽位 DTO
 *
 * 表示对话上下文中的一个实体引用，用于指代消解
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntitySlot {

    /**
     * 槽位类型枚举
     */
    public enum SlotType {
        /** 批次 */
        BATCH,
        /** 供应商 */
        SUPPLIER,
        /** 门店 */
        STORE,
        /** 客户 */
        CUSTOMER,
        /** 产品 */
        PRODUCT,
        /** 物料类型 */
        MATERIAL_TYPE,
        /** 时间范围 */
        TIME_RANGE,
        /** 仓库 */
        WAREHOUSE,
        /** 设备 */
        EQUIPMENT,
        /** 员工 */
        EMPLOYEE,
        /** 订单 */
        ORDER,
        /** 菜品 */
        DISH
    }

    /**
     * 槽位类型
     */
    private SlotType type;

    /**
     * 实体ID
     */
    private String id;

    /**
     * 实体名称
     */
    private String name;

    /**
     * 显示值 (用于向用户展示)
     */
    private String displayValue;

    /**
     * 元数据 (存储额外信息)
     */
    private Map<String, Object> metadata;

    /**
     * 首次提及时间
     */
    private LocalDateTime mentionedAt;

    /**
     * 提及次数
     */
    private int mentionCount;

    /**
     * 置信度 (0-1)
     */
    private double confidence;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 创建批次槽位
     */
    public static EntitySlot batch(String id, String batchNumber) {
        return EntitySlot.builder()
                .type(SlotType.BATCH)
                .id(id)
                .name(batchNumber)
                .displayValue("批次 " + batchNumber)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建供应商槽位
     */
    public static EntitySlot supplier(String id, String name) {
        return EntitySlot.builder()
                .type(SlotType.SUPPLIER)
                .id(id)
                .name(name)
                .displayValue("供应商 " + name)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建门店槽位
     */
    public static EntitySlot store(String id, String name) {
        return EntitySlot.builder()
                .type(SlotType.STORE)
                .id(id)
                .name(name)
                .displayValue("门店 " + name)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建菜品槽位
     */
    public static EntitySlot dish(String id, String name) {
        return EntitySlot.builder()
                .type(SlotType.DISH)
                .id(id)
                .name(name)
                .displayValue("菜品 " + name)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建客户槽位
     */
    public static EntitySlot customer(String id, String name) {
        return EntitySlot.builder()
                .type(SlotType.CUSTOMER)
                .id(id)
                .name(name)
                .displayValue("客户 " + name)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建产品槽位
     */
    public static EntitySlot product(String id, String name) {
        return EntitySlot.builder()
                .type(SlotType.PRODUCT)
                .id(id)
                .name(name)
                .displayValue("产品 " + name)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建时间范围槽位
     */
    public static EntitySlot timeRange(String rangeDescription, LocalDateTime start, LocalDateTime end) {
        return EntitySlot.builder()
                .type(SlotType.TIME_RANGE)
                .id(rangeDescription)
                .name(rangeDescription)
                .displayValue(rangeDescription)
                .metadata(Map.of("start", start.toString(), "end", end.toString()))
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建仓库槽位
     */
    public static EntitySlot warehouse(String id, String name) {
        return EntitySlot.builder()
                .type(SlotType.WAREHOUSE)
                .id(id)
                .name(name)
                .displayValue("仓库 " + name)
                .mentionedAt(LocalDateTime.now())
                .mentionCount(1)
                .confidence(1.0)
                .build();
    }

    /**
     * 增加提及次数
     */
    public void incrementMentionCount() {
        this.mentionCount++;
    }
}
