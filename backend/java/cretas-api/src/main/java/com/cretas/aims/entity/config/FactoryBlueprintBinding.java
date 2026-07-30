package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 工厂蓝图绑定实体
 * 记录工厂与蓝图的绑定关系和版本信息
 *
 * Sprint 3 任务: S3-7 蓝图版本管理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "factory_blueprint_bindings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class FactoryBlueprintBinding extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 36)
    private String factoryId;

    /**
     * 蓝图ID
     */
    @Column(name = "blueprint_id", nullable = false, length = 36)
    private String blueprintId;

    /**
     * 当前应用的蓝图版本
     */
    @Column(name = "applied_version", nullable = false)
    private Integer appliedVersion;

    /**
     * 最新可用版本 (蓝图当前版本)
     */
    @Column(name = "latest_version")
    private Integer latestVersion;

    /**
     * 是否自动更新
     */
    @Column(name = "auto_update")
    private Boolean autoUpdate;

    /**
     * 更新策略: MANUAL, AUTO_MINOR, AUTO_ALL
     */
    @Column(name = "update_policy", length = 20)
    private String updatePolicy;

    /**
     * 上次应用时间
     */
    @Column(name = "last_applied_at")
    private LocalDateTime lastAppliedAt;

    /**
     * 上次检查更新时间
     */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    /**
     * 待处理的更新版本 (有新版本可用时设置)
     */
    @Column(name = "pending_version")
    private Integer pendingVersion;

    /**
     * 更新通知状态: NONE, PENDING, NOTIFIED, DISMISSED
     */
    @Column(name = "notification_status", length = 20)
    private String notificationStatus;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (autoUpdate == null) {
            autoUpdate = false;
        }
        if (updatePolicy == null) {
            updatePolicy = "MANUAL";
        }
        if (notificationStatus == null) {
            notificationStatus = "NONE";
        }
    }
}
