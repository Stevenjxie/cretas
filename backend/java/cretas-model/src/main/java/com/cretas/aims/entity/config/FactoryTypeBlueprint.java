package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 工厂类型蓝图实体
 * 用于定义不同类型工厂的标准配置模板
 */
@Entity
@Table(name = "factory_type_blueprints")
@Data
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class FactoryTypeBlueprint extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 蓝图名称，如"水产加工厂"、"肉类加工厂"
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 蓝图描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 行业类型
     */
    @Column(name = "industry_type", length = 50)
    private String industryType;

    /**
     * 默认配置JSON (存储为字符串)
     * 包含：dailyCapacity, shiftCount, qualityStandards, temperatureControl等
     */
    @Column(name = "default_config", columnDefinition = "JSON")
    private String defaultConfig;

    /**
     * 表单模板配置 (存储为字符串)
     * 数组格式：[{name, type, fields}]
     */
    @Column(name = "form_templates", columnDefinition = "JSON")
    private String formTemplates;

    /**
     * 规则模板配置 (存储为字符串)
     * 数组格式：[{name, type, threshold}]
     */
    @Column(name = "rule_templates", columnDefinition = "JSON")
    private String ruleTemplates;

    /**
     * 产品类型模板 (存储为字符串)
     * 数组格式：[{name, category, processSteps}]
     */
    @Column(name = "product_type_templates", columnDefinition = "JSON")
    private String productTypeTemplates;

    /**
     * 部门模板 (存储为字符串)
     * 数组格式：[{name, type}]
     */
    @Column(name = "department_templates", columnDefinition = "JSON")
    private String departmentTemplates;

    /**
     * 是否激活
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * 版本号
     */
    @Column(nullable = false)
    private Integer version = 1;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();  // 设置 BaseEntity 的 createdAt/updatedAt
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (version == null) {
            version = 1;
        }
    }
}
