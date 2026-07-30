package com.cretas.aims.entity.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 表单模板版本历史实体
 *
 * 用于存储每次模板更新时的快照，支持版本回滚
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "form_template_versions",
       indexes = {
           @Index(name = "idx_template_id", columnList = "template_id"),
           @Index(name = "idx_factory_entity_version", columnList = "factory_id, entity_type, version"),
           @Index(name = "idx_created_at", columnList = "created_at")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormTemplateVersion {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 关联的模板ID
     */
    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 实体类型
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * 模板名称
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * Schema JSON
     */
    @Column(name = "schema_json", columnDefinition = "TEXT")
    private String schemaJson;

    /**
     * UI Schema JSON
     */
    @Column(name = "ui_schema_json", columnDefinition = "TEXT")
    private String uiSchemaJson;

    /**
     * 描述
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 版本号
     */
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * 来源
     */
    @Column(name = "source", length = 20)
    private String source;

    /**
     * 创建者ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 版本创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 变更摘要
     */
    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    /**
     * 从当前模板创建版本快照
     */
    public static FormTemplateVersion fromTemplate(FormTemplate template, String changeSummary) {
        return FormTemplateVersion.builder()
                .id(java.util.UUID.randomUUID().toString())
                .templateId(template.getId())
                .factoryId(template.getFactoryId())
                .entityType(template.getEntityType())
                .name(template.getName())
                .schemaJson(template.getSchemaJson())
                .uiSchemaJson(template.getUiSchemaJson())
                .description(template.getDescription())
                .version(template.getVersion())
                .source(template.getSource())
                .createdBy(template.getCreatedBy())
                .createdAt(LocalDateTime.now())
                .changeSummary(changeSummary)
                .build();
    }
}
