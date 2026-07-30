package com.cretas.aims.entity.config;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI意图配置历史记录实体
 *
 * 用于记录意图配置的变更历史，支持审计和回滚：
 * - 保存每个版本的完整配置快照
 * - 记录变更人、时间、原因
 * - 支持按版本查询和回滚
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-04
 */
@Entity
@Table(name = "ai_intent_config_history",
       indexes = {
           @Index(name = "idx_config_version", columnList = "intent_config_id, version_number"),
           @Index(name = "idx_factory_intent", columnList = "factory_id, intent_code"),
           @Index(name = "idx_changed_at", columnList = "changed_at")
       },
       uniqueConstraints = @UniqueConstraint(columnNames = {"intent_config_id", "version_number"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIIntentConfigHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的意图配置ID
     */
    @Column(name = "intent_config_id", nullable = false, length = 36)
    private String intentConfigId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 意图代码
     */
    @Column(name = "intent_code", nullable = false, length = 50)
    private String intentCode;

    /**
     * 版本号
     */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * 完整配置快照 (JSON)
     */
    @Column(name = "snapshot", columnDefinition = "JSON", nullable = false)
    private String snapshot;

    /**
     * 修改人ID
     */
    @Column(name = "changed_by")
    private Long changedBy;

    /**
     * 修改人姓名
     */
    @Column(name = "changed_by_name", length = 100)
    private String changedByName;

    /**
     * 修改时间
     */
    @Column(name = "changed_at")
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();

    /**
     * 修改原因/备注
     */
    @Column(name = "change_reason", length = 500)
    private String changeReason;

    /**
     * 变更类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 20)
    @Builder.Default
    private ChangeType changeType = ChangeType.UPDATE;

    /**
     * 变更的字段列表 (JSON)
     */
    @Column(name = "changed_fields", columnDefinition = "JSON")
    private String changedFields;

    /**
     * 变更类型枚举
     */
    public enum ChangeType {
        CREATE,    // 创建
        UPDATE,    // 更新
        ROLLBACK,  // 回滚
        DELETE     // 删除
    }
}
