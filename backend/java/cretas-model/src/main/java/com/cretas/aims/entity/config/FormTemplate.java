package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.Builder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 表单模板实体
 * 用于存储自定义 Formily Schema，支持动态表单配置
 *
 * 使用场景:
 * - 不同工厂可以自定义表单字段
 * - AI 配置助手生成的自定义字段存储于此
 * - 前端加载时与代码默认 Schema 合并渲染
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Entity
@Table(name = "form_templates", indexes = {
    @Index(name = "idx_factory_entity", columnList = "factory_id, entity_type"),
    @Index(name = "idx_entity_type", columnList = "entity_type"),
    @Index(name = "idx_is_active", columnList = "is_active")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class FormTemplate extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 工厂ID - 多租户隔离
     * 为 null 时表示系统级模板（对所有工厂生效）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 模板名称
     * 如: "原料入库表单-定制版", "质检表单-添加色泽评分"
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 实体类型 - 对应哪个业务表单
     * 如: QUALITY_CHECK, MATERIAL_BATCH, PROCESSING_BATCH, SHIPMENT
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * Formily Schema JSON
     * 存储完整的 Formily Schema 结构
     * 包含 type, properties, x-reactions 等
     */
    @Column(name = "schema_json", columnDefinition = "TEXT")
    private String schemaJson;

    /**
     * UI Schema JSON (可选)
     * 存储额外的 UI 配置，如布局、样式等
     */
    @Column(name = "ui_schema_json", columnDefinition = "TEXT")
    private String uiSchemaJson;

    /**
     * 模板描述
     * 说明此模板的用途和修改内容
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 版本号
     * 每次更新递增，用于前端缓存失效判断
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /**
     * 是否启用
     * false 时前端不加载此模板
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 创建者用户ID
     * 如果是 AI 自动创建，记录触发的用户
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 创建来源
     * MANUAL - 手动创建
     * AI_ASSISTANT - AI 助手生成
     * IMPORT - 批量导入
     */
    @Column(name = "source", length = 20)
    @Builder.Default
    private String source = "MANUAL";

    /**
     * 来源模板包ID
     * 当 source = IMPORT 时，记录导入的行业模板包ID
     */
    @Column(name = "source_package_id", length = 50)
    private String sourcePackageId;

    /**
     * 递增版本号
     */
    public void incrementVersion() {
        this.version = (this.version == null ? 0 : this.version) + 1;
    }
}
