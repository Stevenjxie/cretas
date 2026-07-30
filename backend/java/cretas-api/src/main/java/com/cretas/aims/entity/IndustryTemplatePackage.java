package com.cretas.aims.entity;

import lombok.*;

import jakarta.persistence.*;
import java.util.Map;
import org.hibernate.annotations.Where;

/**
 * 行业模板包实体
 *
 * 用于存储预定义的行业配置模板，工厂可以快速导入这些模板
 * 来初始化自己的表单配置，实现快速上线。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "industry_template_packages",
       indexes = {
           @Index(name = "idx_industry_code", columnList = "industry_code"),
           @Index(name = "idx_is_default", columnList = "is_default")
       })
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class IndustryTemplatePackage extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 行业代码
     * 例如: seafood_processing, prepared_food, meat_processing
     */
    @Column(name = "industry_code", nullable = false, length = 50)
    private String industryCode;

    /**
     * 行业中文名
     * 例如: 水产加工, 预制菜加工, 肉类加工
     */
    @Column(name = "industry_name", nullable = false, length = 100)
    private String industryName;

    /**
     * 行业模板描述
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 模板JSON配置
     * 结构: {
     *   "QUALITY_CHECK": { "properties": { ... } },
     *   "MATERIAL_BATCH": { "properties": { ... } },
     *   "PRODUCTION_BATCH": { "properties": { ... } }
     * }
     */
    @Column(name = "templates_json", columnDefinition = "JSON")
    private String templatesJson;

    /**
     * 模板版本
     */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /**
     * 是否为默认模板
     * 新建工厂时，如果未选择模板，可以使用默认模板
     */
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    /**
     * 获取模板配置Map（用于API返回）
     * 注意：实际解析由Service层处理
     */
    @Transient
    public Map<String, Object> getTemplatesAsMap() {
        // 由 Service 层解析 JSON
        return null;
    }
}
