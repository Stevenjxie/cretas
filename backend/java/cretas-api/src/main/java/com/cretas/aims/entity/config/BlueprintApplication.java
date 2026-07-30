package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 蓝图应用记录实体
 * 记录蓝图被应用到工厂的历史
 */
@Entity
@Table(name = "blueprint_applications")
@Data
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BlueprintApplication extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 蓝图ID
     */
    @Column(name = "blueprint_id", nullable = false, length = 36)
    private String blueprintId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 应用人用户ID
     */
    @Column(name = "applied_by")
    private Long appliedBy;

    /**
     * 应用时间
     */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * 状态: PENDING, IN_PROGRESS, COMPLETED, FAILED
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ApplicationStatus status = ApplicationStatus.COMPLETED;

    /**
     * 应用结果摘要
     */
    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (appliedAt == null) {
            appliedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ApplicationStatus.PENDING;
        }
    }

    /**
     * 应用状态枚举
     */
    public enum ApplicationStatus {
        PENDING,      // 待处理
        IN_PROGRESS,  // 进行中
        COMPLETED,    // 已完成
        FAILED        // 失败
    }
}
