package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据完整性快照实体
 * 按工厂、实体类型和日期记录字段完整度和整体完整度
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-09
 */
@Data
@Entity
@Table(name = "data_completeness_snapshots",
       indexes = {
           @Index(name = "idx_snapshot_factory", columnList = "factory_id"),
           @Index(name = "idx_snapshot_date", columnList = "snapshot_date"),
           @Index(name = "idx_snapshot_entity_type", columnList = "entity_type")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataCompletenessSnapshot implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 快照日期
     */
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /**
     * 实体类型
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * 总记录数
     */
    @Builder.Default
    @Column(name = "total_records")
    private Integer totalRecords = 0;

    /**
     * 字段完整度（JSON格式）
     */
    @Column(name = "field_completeness", columnDefinition = "jsonb")
    private String fieldCompleteness;

    /**
     * 整体完整度百分比
     */
    @Builder.Default
    @Column(name = "overall_completeness")
    private Double overallCompleteness = 0.0;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
