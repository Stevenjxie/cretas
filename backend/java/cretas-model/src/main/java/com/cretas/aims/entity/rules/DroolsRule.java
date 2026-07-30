package com.cretas.aims.entity.rules;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * Drools 规则实体
 *
 * 存储 DRL 规则内容和决策表
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "drools_rules",
       indexes = {
           @Index(name = "idx_drools_rules_factory", columnList = "factory_id"),
           @Index(name = "idx_drools_rules_group", columnList = "factory_id, rule_group"),
           @Index(name = "idx_drools_rules_enabled", columnList = "factory_id, enabled")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_drools_rules_name",
                            columnNames = {"factory_id", "rule_group", "rule_name"})
       })
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class DroolsRule extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 规则组
     * 如: validation, workflow, costing, quality
     */
    @Column(name = "rule_group", length = 50, nullable = false)
    private String ruleGroup;

    /**
     * 规则名称
     */
    @Column(name = "rule_name", length = 100, nullable = false)
    private String ruleName;

    /**
     * 规则描述
     */
    @Column(name = "rule_description", columnDefinition = "TEXT")
    private String ruleDescription;

    /**
     * DRL 规则内容
     */
    @Column(name = "rule_content", columnDefinition = "TEXT", nullable = false)
    private String ruleContent;

    /**
     * Excel 决策表内容 (可选)
     */
    // @Lob on byte[] made Hibernate 6 bind the param as OID (large object),
    // but the PG column is bytea — any UPDATE failed with
    // "column is of type bytea but expression is of type oid". The DB columnDefinition
    // was also MySQL-specific (MEDIUMBLOB). Plain byte[] + columnDefinition=bytea
    // keeps Hibernate on the BYTEA JDBC path. Applies uniformly across 6 entities.
    @Column(name = "decision_table", columnDefinition = "bytea")
    private byte[] decisionTable;

    /**
     * 决策表类型 (XLS, XLSX, CSV)
     */
    @Column(name = "decision_table_type", length = 20)
    private String decisionTableType;

    /**
     * 规则版本号
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 是否启用
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    // Coalesce NULL → true to match @Builder.Default. Pre-migration rows
    // inserted without explicit enabled used to NPE on unboxing in
    // SchedulingAIServiceImpl (2026-04-16). V20260417_02 enforces NOT NULL
    // at the DB, but keep the getter defensive for older envs / replicas
    // that haven't replayed the migration yet.
    public Boolean getEnabled() {
        return enabled != null ? enabled : Boolean.TRUE;
    }

    /**
     * 执行优先级 (越大越先执行)
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 创建者用户ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 最后修改者用户ID
     */
    @Column(name = "updated_by")
    private Long updatedBy;
}
