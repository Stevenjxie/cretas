package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * SOP (Standard Operating Procedure) 配置实体
 *
 * 用于定义标准操作流程，包括:
 * - 工序步骤及顺序
 * - 各步骤的规则验证
 * - 拍照证据要求
 * - 技能等级要求
 *
 * Sprint 2 任务:
 * - S2-2: 创建 SopConfig 实体
 * - S2-3: 关联规则组 (ruleGroupId)
 * - S2-4: 拍照证据配置 (photoConfigJson)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "sop_configs",
       indexes = {
           @Index(name = "idx_sop_factory", columnList = "factory_id"),
           @Index(name = "idx_sop_entity_type", columnList = "entity_type"),
           @Index(name = "idx_sop_product_type", columnList = "product_type_id"),
           @Index(name = "idx_sop_rule_group", columnList = "rule_group_id"),
           @Index(name = "idx_sop_active", columnList = "is_active")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_sop_factory_code",
                            columnNames = {"factory_id", "code"})
       })
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class SopConfig extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /**
     * 工厂ID - 多租户隔离
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * SOP 名称
     * 如: "带鱼加工标准流程", "虾仁包装SOP"
     */
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /**
     * SOP 编码
     * 如: "SOP-FISH-001", "SOP-SHRIMP-PKG"
     */
    @Column(name = "code", length = 50, nullable = false)
    private String code;

    /**
     * SOP 描述
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 关联实体类型
     * 如: PRODUCTION_BATCH, MATERIAL_BATCH, QUALITY_CHECK
     */
    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    /**
     * 关联产品类型ID (可选)
     * 为空则适用于所有产品类型
     */
    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    // ==================== Sprint 2 S2-3: 规则组关联 ====================

    /**
     * 关联的规则组ID
     * 对应 drools_rules.rule_group
     * 该 SOP 执行时会触发该规则组下的所有规则
     */
    @Column(name = "rule_group_id", length = 50)
    private String ruleGroupId;

    // ==================== 步骤配置 ====================

    /**
     * SOP 步骤配置 (JSON 数组)
     * 格式:
     * [
     *   {
     *     "stageType": "RECEIVING",
     *     "orderIndex": 1,
     *     "name": "原料接收",
     *     "requiredSkillLevel": 2,
     *     "photoRequired": true,
     *     "timeLimitMinutes": 30,
     *     "validationRuleIds": ["rule-check-temp"],
     *     "notes": "检查温度需在-18°C以下"
     *   },
     *   ...
     * ]
     */
    @Column(name = "steps_json", columnDefinition = "JSON")
    private String stepsJson;

    /**
     * 验证规则配置 (JSON 对象)
     * 格式:
     * {
     *   "onStart": ["rule-check-material"],
     *   "onComplete": ["rule-check-output"],
     *   "crossStep": ["rule-check-temp-chain"]
     * }
     */
    @Column(name = "validation_rules_json", columnDefinition = "JSON")
    private String validationRulesJson;

    // ==================== Sprint 2 S2-4: 拍照证据配置 ====================

    /**
     * 拍照配置 (JSON 对象)
     * 格式:
     * {
     *   "required": true,
     *   "stages": ["RECEIVING", "PACKAGING"],
     *   "minPhotosPerStage": 1,
     *   "maxPhotosPerStage": 5
     * }
     */
    @Column(name = "photo_config_json", columnDefinition = "JSON")
    private String photoConfigJson;

    // ==================== 版本与状态 ====================

    /**
     * 版本号
     * 每次更新递增
     */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /**
     * 是否启用
     */
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * 创建者用户ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    // ==================== 辅助方法 ====================

    /**
     * 递增版本号
     */
    public void incrementVersion() {
        this.version = (this.version == null ? 0 : this.version) + 1;
    }
}
