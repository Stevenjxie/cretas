package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 字段能力映射实体
 * 将调研问卷字段映射到系统实体字段、表单schema和分析维度
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-09
 */
@Data
@Entity
@Table(name = "field_capability_mapping",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"survey_section", "survey_row_index"})
       },
       indexes = {
           @Index(name = "idx_mapping_entity_type", columnList = "entity_type"),
           @Index(name = "idx_mapping_section", columnList = "survey_section"),
           @Index(name = "idx_mapping_dimension", columnList = "analysis_dimension")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldCapabilityMapping implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    /**
     * 调研问卷章节
     */
    @Column(name = "survey_section", nullable = false, length = 50)
    private String surveySection;

    /**
     * 调研问卷行索引
     */
    @Column(name = "survey_row_index", nullable = false)
    private Integer surveyRowIndex;

    /**
     * 调研字段名称
     */
    @Column(name = "survey_field_name", nullable = false, length = 255)
    private String surveyFieldName;

    /**
     * 对应实体类型
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * 对应实体字段
     */
    @Column(name = "entity_field", nullable = false, length = 100)
    private String entityField;

    /**
     * 表单schema键
     */
    @Column(name = "form_schema_key", length = 100)
    private String formSchemaKey;

    /**
     * 分析维度
     */
    @Column(name = "analysis_dimension", length = 100)
    private String analysisDimension;

    /**
     * 是否为实体必需字段
     */
    @Builder.Default
    @Column(name = "is_required_for_entity")
    private Boolean isRequiredForEntity = false;

    /**
     * Survey HTML section ID (e.g. "s4-3", "s5-2") — maps to client_requirement_feedbacks.section
     */
    @Column(name = "survey_section_html", length = 50)
    private String surveySectionHtml;

    /**
     * Survey HTML row index — maps to client_requirement_feedbacks.row_index
     */
    @Column(name = "survey_row_index_html")
    private Integer surveyRowIndexHtml;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
