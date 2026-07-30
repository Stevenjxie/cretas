package com.cretas.aims.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 生产小结 (interim settlement) 记录 — BY_STOCK 模式幂等基础。
 *
 * <p>每次文员点击"小结"按钮产生一行，session_seq 单调递增。
 * UNIQUE (factory_id, production_plan_id, session_seq) 保证幂等:
 * 重复点击同一次小结直接 409，不会重复扣减原料 (Task 3 使用)。
 *
 * <p>Task 2 范围: 仅地基 (实体 + 表 + repository)，不改任何业务行为。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "production_interim_settlement",
    indexes = {
        @Index(name = "idx_interim_plan", columnList = "factory_id, production_plan_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_interim_plan_seq",
            columnNames = {"factory_id", "production_plan_id", "session_seq"})
    })
@Where(clause = "deleted_at IS NULL")
public class ProductionInterimSettlement extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_plan_id", nullable = false, length = 50)
    private String productionPlanId;

    /** 单调递增序号，从 1 开始。同 (factory, plan, seq) 唯一 = 幂等键。 */
    @Column(name = "session_seq", nullable = false)
    private Integer sessionSeq;

    @Column(name = "posted_at", nullable = false)
    private LocalDateTime postedAt;

    /** 操作人 user id，nullable (系统自动小结场景)。 */
    @Column(name = "posted_by")
    private Long postedBy;

    /**
     * 本次小结摘要 (JSON)。格式由 Task 3 写入方决定；此处仅持久化容器。
     * 同用 {@code @Type(JsonType.class)} + {@code columnDefinition="jsonb"}，
     * 镜像 {@link SemiFinishedInventory#getMaterialBatchRefs()} 的 jsonb 映射范本。
     */
    @Type(JsonType.class)
    @Column(name = "summary", columnDefinition = "jsonb")
    private Map<String, Object> summary;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (postedAt == null) {
            postedAt = LocalDateTime.now();
        }
    }
}
