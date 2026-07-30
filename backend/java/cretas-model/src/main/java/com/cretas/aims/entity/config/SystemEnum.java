package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 系统枚举配置实体
 *
 * 用于将硬编码枚举迁移到数据库配置:
 * - 支持动态添加/修改枚举值
 * - 支持工厂级别的枚举覆盖
 * - 支持枚举元数据扩展 (如权限级别、部门等)
 *
 * 设计原则:
 * - 全局配置 factory_id = '*'
 * - 工厂级配置优先于全局配置
 * - 保留Java枚举类作为默认值和类型安全
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "system_enums",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "enum_group", "enum_code"}),
       indexes = {
           @Index(name = "idx_enum_factory_group", columnList = "factory_id, enum_group"),
           @Index(name = "idx_enum_group", columnList = "enum_group"),
           @Index(name = "idx_enum_is_active", columnList = "is_active")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class SystemEnum extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     * "*" 表示全局默认配置
     * 具体工厂ID表示工厂级覆盖
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 枚举组
     * 如: PROCESSING_STAGE, QUALITY_STATUS, FACTORY_USER_ROLE, MATERIAL_BATCH_STATUS
     */
    @Column(name = "enum_group", nullable = false, length = 50)
    private String enumGroup;

    /**
     * 枚举代码 (对应Java枚举的name)
     * 如: RECEIVING, SLICING, factory_super_admin
     */
    @Column(name = "enum_code", nullable = false, length = 50)
    private String enumCode;

    /**
     * 显示标签 (中文名)
     * 如: 接收, 切片, 工厂总监
     */
    @Column(name = "enum_label", nullable = false, length = 100)
    private String enumLabel;

    /**
     * 枚举描述
     * 详细说明该枚举值的用途
     */
    @Column(name = "enum_description", length = 200)
    private String enumDescription;

    /**
     * 枚举值 (可选的附加值)
     * 如: 权重、优先级等数值
     */
    @Column(name = "enum_value", length = 100)
    private String enumValue;

    /**
     * 排序顺序
     * 用于前端下拉列表排序
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 是否为系统内置 (不可删除)
     */
    @Column(name = "is_system")
    @Builder.Default
    private Boolean isSystem = true;

    /**
     * 扩展元数据 (JSON格式)
     * 存储枚举的额外属性，如:
     * - 角色枚举: {"level": 10, "department": "production", "permissions": ["read", "write"]}
     * - 状态枚举: {"allowedTransitions": ["IN_PROGRESS", "CANCELLED"], "isFinal": false}
     */
    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    /**
     * 父枚举代码 (用于层级枚举)
     * 如: 子分类枚举指向父分类
     */
    @Column(name = "parent_code", length = 50)
    private String parentCode;

    /**
     * 图标 (用于前端展示)
     */
    @Column(name = "icon", length = 50)
    private String icon;

    /**
     * 颜色代码 (用于状态展示)
     */
    @Column(name = "color", length = 20)
    private String color;

    /**
     * 判断是否为全局配置
     */
    public boolean isGlobalConfig() {
        return "*".equals(factoryId);
    }

    /**
     * 获取枚举值的整数形式 (如果是数字)
     */
    public Integer getEnumValueAsInt() {
        if (enumValue == null || enumValue.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(enumValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
