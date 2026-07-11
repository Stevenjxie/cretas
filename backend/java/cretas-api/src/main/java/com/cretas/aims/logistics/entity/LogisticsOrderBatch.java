package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 每日订单导入批次 — 一次上传/预检/提交对应一行。
 *
 * <p>幂等键 {@code (factory_id, business_date, source_fingerprint)} 唯一 (spec §2
 * 决策 7, DB {@code uq_lob_idempotent}) — 重复上传同一文件应返回既有批次, service 层负责
 * 查询该唯一键并短路, 不在此实体重复表达业务逻辑。
 *
 * <p>⚠️ Flyway 侧 {@code uq_lob_idempotent} 是**部分索引** ({@code WHERE deleted_at IS NULL}) —
 * JPA {@code @UniqueConstraint} 无法表达"部分" (总是全表), 下面声明的约束语义略严于生产 PG
 * (软删除行也计入唯一性, 生产 PG 允许软删后重复)。声明它主要为了 H2 test schema 也能真实
 * 拒绝重复幂等键 (见 {@code LogisticsOrderBatchRepositoryTest#duplicateIdempotencyKeyRejected})；
 * Hibernate {@code ddl-auto=validate} 默认不校验 unique constraint/index 定义, 不影响生产启动。
 *
 * <p>Maps to table {@code logistics_order_batches} (V20261028_01)。
 */
@Entity
@Table(name = "logistics_order_batches",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lob_idempotent_jpa",
                        columnNames = {"factory_id", "business_date", "source_fingerprint"})
        },
        indexes = {
                @Index(name = "idx_lob_factory_date", columnList = "factory_id, business_date")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsOrderBatch extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = OrderBatchStatus.PREVIEWED;
        }
        if (totalRows == null) {
            totalRows = 0;
        }
        if (validRows == null) {
            validRows = 0;
        }
        if (errorRows == null) {
            errorRows = 0;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "batch_number", length = 64, nullable = false)
    private String batchNumber;

    @Column(name = "source_filename", length = 512)
    private String sourceFilename;

    /** 上传文件内容指纹 (e.g. sha256) — 幂等键组成部分, 见类注释。 */
    @Column(name = "source_fingerprint", length = 128, nullable = false)
    private String sourceFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private OrderBatchStatus status;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows;

    @Column(name = "valid_rows", nullable = false)
    private Integer validRows;

    @Column(name = "error_rows", nullable = false)
    private Integer errorRows;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
