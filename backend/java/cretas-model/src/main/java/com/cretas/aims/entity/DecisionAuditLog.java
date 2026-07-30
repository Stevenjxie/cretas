package com.cretas.aims.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 决策审计日志实体
 * 记录所有关键业务决策，支持回放和审计追踪
 */
@Entity
@Table(name = "decision_audit_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DecisionAuditLog {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    // 决策标识
    @Column(name = "decision_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private DecisionType decisionType;

    @Column(name = "decision_code", length = 100)
    private String decisionCode;

    // 关联实体
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 36)
    private String entityId;

    @Column(name = "factory_id", nullable = false, length = 20)
    private String factoryId;

    // 决策上下文 (JSON字段)
    @Column(name = "input_context", columnDefinition = "JSON")
    private String inputContext;

    @Column(name = "output_result", columnDefinition = "JSON")
    private String outputResult;

    @Column(name = "rules_applied", columnDefinition = "JSON")
    private String rulesApplied;

    // 规则版本追踪
    @Column(name = "rule_config_id", length = 36)
    private String ruleConfigId;

    @Column(name = "rule_config_version")
    private Integer ruleConfigVersion;

    @Column(name = "rule_config_name", length = 100)
    private String ruleConfigName;

    // 决策详情
    @Column(name = "decision_made", length = 255)
    private String decisionMade;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "confidence", precision = 5, scale = 2)
    private BigDecimal confidence;

    // 状态变更
    @Column(name = "previous_state", length = 50)
    private String previousState;

    @Column(name = "new_state", length = 50)
    private String newState;

    // 执行信息
    @Column(name = "executor_id")
    private Long executorId;

    @Column(name = "executor_name", length = 100)
    private String executorName;

    @Column(name = "executor_role", length = 50)
    private String executorRole;

    @Column(name = "execution_mode", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ExecutionMode executionMode = ExecutionMode.AUTOMATIC;

    // 审批信息
    @Column(name = "requires_approval")
    @Builder.Default
    private Boolean requiresApproval = false;

    @Column(name = "approval_status", length = 20)
    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "approver_name", length = 100)
    private String approverName;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approval_comment", columnDefinition = "TEXT")
    private String approvalComment;

    // 回放支持
    @Column(name = "is_replayable")
    @Builder.Default
    private Boolean isReplayable = true;

    @Column(name = "replay_data", columnDefinition = "JSON")
    private String replayData;

    @Column(name = "checksum", length = 64)
    private String checksum;

    // 审计字段
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // 枚举类型
    public enum DecisionType {
        RULE_EXECUTION,      // 规则引擎执行
        STATE_TRANSITION,    // 状态机转换
        FORCE_INSERT,        // 强制插单
        APPROVAL,            // 审批决策
        AI_ANALYSIS,         // AI分析决策
        MANUAL_OVERRIDE      // 手动覆盖
    }

    public enum ExecutionMode {
        AUTOMATIC,   // 自动执行
        MANUAL,      // 手动执行
        OVERRIDE     // 覆盖执行
    }

    public enum ApprovalStatus {
        PENDING,     // 待审批
        APPROVED,    // 已批准
        REJECTED     // 已拒绝
    }
}
