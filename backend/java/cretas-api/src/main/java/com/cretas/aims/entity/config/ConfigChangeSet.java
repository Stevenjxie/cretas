package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 配置变更集实体
 * 统一追踪所有配置变更，支持差异预览、审批、回滚
 *
 * 使用场景:
 * - 表单模板修改 (FormTemplate)
 * - Drools 规则变更
 * - 审批链配置变更
 * - 其他需要版本控制的配置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Entity
@Table(name = "config_change_sets", indexes = {
    @Index(name = "idx_config_type_id", columnList = "config_type, config_id"),
    @Index(name = "idx_factory_id", columnList = "factory_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_created_by", columnList = "created_by")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ConfigChangeSet extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID - 多租户隔离
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 配置类型
     */
    @Column(name = "config_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ConfigType configType;

    /**
     * 原配置ID
     */
    @Column(name = "config_id", nullable = false, length = 36)
    private String configId;

    /**
     * 配置名称 (冗余存储，便于查询展示)
     */
    @Column(name = "config_name", length = 100)
    private String configName;

    /**
     * 变更前版本号
     */
    @Column(name = "from_version")
    private Integer fromVersion;

    /**
     * 变更后版本号
     */
    @Column(name = "to_version")
    private Integer toVersion;

    /**
     * 变更前配置快照 (JSON)
     */
    @Column(name = "before_snapshot", columnDefinition = "TEXT")
    private String beforeSnapshot;

    /**
     * 变更后配置快照 (JSON)
     */
    @Column(name = "after_snapshot", columnDefinition = "TEXT")
    private String afterSnapshot;

    /**
     * 差异内容 (JSON格式，前端可直接渲染)
     * 格式: { "added": [...], "removed": [...], "modified": [...] }
     */
    @Column(name = "diff_json", columnDefinition = "TEXT")
    private String diffJson;

    /**
     * 变更摘要 (人类可读的变更描述)
     */
    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    /**
     * 变更状态
     */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ChangeStatus status;

    /**
     * 创建者用户ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 创建者用户名
     */
    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    /**
     * 审批者用户ID
     */
    @Column(name = "approved_by")
    private Long approvedBy;

    /**
     * 审批者用户名
     */
    @Column(name = "approved_by_name", length = 100)
    private String approvedByName;

    /**
     * 审批时间
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * 审批/拒绝备注
     */
    @Column(name = "approval_comment", columnDefinition = "TEXT")
    private String approvalComment;

    /**
     * 应用时间 (审批通过后实际生效的时间)
     */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * 回滚时间
     */
    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    /**
     * 回滚者用户ID
     */
    @Column(name = "rolled_back_by")
    private Long rolledBackBy;

    /**
     * 回滚原因
     */
    @Column(name = "rollback_reason", length = 500)
    private String rollbackReason;

    /**
     * 是否可回滚
     * 部分变更可能因为后续变更而无法回滚
     */
    @Column(name = "is_rollbackable")
    @Builder.Default
    private Boolean isRollbackable = true;

    // ========== 枚举类型 ==========

    /**
     * 配置类型枚举
     */
    public enum ConfigType {
        FORM_TEMPLATE,      // 表单模板
        DROOLS_RULE,        // Drools 规则
        APPROVAL_CHAIN,     // 审批链配置
        QUALITY_RULE,       // 质检规则
        CONVERSION_RATE,    // 转换率配置
        FACTORY_CAPACITY,   // 工厂产能配置
        OTHER               // 其他配置
    }

    /**
     * 变更状态枚举
     */
    public enum ChangeStatus {
        PENDING,        // 待审批
        APPROVED,       // 已批准 (等待应用)
        APPLIED,        // 已应用 (生效中)
        REJECTED,       // 已拒绝
        ROLLED_BACK,    // 已回滚
        EXPIRED         // 已过期 (未审批超时)
    }

    // ========== 业务方法 ==========

    /**
     * 审批通过
     */
    public void approve(Long approverId, String approverName, String comment) {
        this.status = ChangeStatus.APPROVED;
        this.approvedBy = approverId;
        this.approvedByName = approverName;
        this.approvedAt = LocalDateTime.now();
        this.approvalComment = comment;
    }

    /**
     * 拒绝变更
     */
    public void reject(Long approverId, String approverName, String comment) {
        this.status = ChangeStatus.REJECTED;
        this.approvedBy = approverId;
        this.approvedByName = approverName;
        this.approvedAt = LocalDateTime.now();
        this.approvalComment = comment;
    }

    /**
     * 应用变更
     */
    public void apply() {
        this.status = ChangeStatus.APPLIED;
        this.appliedAt = LocalDateTime.now();
    }

    /**
     * 回滚变更
     */
    public void rollback(Long userId, String reason) {
        this.status = ChangeStatus.ROLLED_BACK;
        this.rolledBackAt = LocalDateTime.now();
        this.rolledBackBy = userId;
        this.rollbackReason = reason;
    }

    /**
     * 检查是否可以审批
     */
    public boolean canApprove() {
        return this.status == ChangeStatus.PENDING;
    }

    /**
     * 检查是否可以回滚
     */
    public boolean canRollback() {
        return this.status == ChangeStatus.APPLIED && Boolean.TRUE.equals(this.isRollbackable);
    }
}
