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
 * 蓝图版本历史实体
 * 记录蓝图的版本变更历史
 *
 * Sprint 3 任务: S3-7 蓝图版本管理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "blueprint_version_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BlueprintVersionHistory extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 关联的蓝图ID
     */
    @Column(name = "blueprint_id", nullable = false, length = 36)
    private String blueprintId;

    /**
     * 版本号
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * 变更类型: CREATE, UPDATE, PUBLISH, DEPRECATE
     */
    @Column(name = "change_type", length = 20)
    private String changeType;

    /**
     * 变更说明
     */
    @Column(name = "change_description", columnDefinition = "TEXT")
    private String changeDescription;

    /**
     * 版本快照 (完整蓝图数据JSON)
     */
    @Column(name = "snapshot_data", columnDefinition = "JSON")
    private String snapshotData;

    /**
     * 变更内容摘要 (与上一版本的差异)
     */
    @Column(name = "change_summary", columnDefinition = "JSON")
    private String changeSummary;

    /**
     * 是否为发布版本
     */
    @Column(name = "is_published")
    private Boolean isPublished;

    /**
     * 发布时间
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * 创建人
     */
    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (isPublished == null) {
            isPublished = false;
        }
    }
}
