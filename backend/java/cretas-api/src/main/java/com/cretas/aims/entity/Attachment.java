package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 通用附件实体 (多态: entity_type + entity_id)
 *
 * <p>支持 5 大业务场景的附件挂载: 客户跟踪 / 采购订单 / 质检 / 生产证据 / 财务凭证. 决策见
 * {@code 宏见竞品分析/01-客户档案/SCHEMA_DESIGN.md §2.3}: 多态关联不强外键, 文件实体仅存
 * OSS URL (不存 BLOB), 权限跟随 entity, 下载通过 OSS pre-signed URL 实现细粒度控制.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-ATT-1)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "attachments", indexes = {
        @Index(name = "idx_att_entity", columnList = "factory_id,entity_type,entity_id"),
        @Index(name = "idx_att_uploader", columnList = "factory_id,uploaded_by"),
        @Index(name = "idx_att_tag", columnList = "factory_id,business_tag"),
        @Index(name = "idx_att_hash", columnList = "factory_id,file_hash")
})
@Where(clause = "deleted_at IS NULL")
public class Attachment extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, length = 191)
    private String entityId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_url", nullable = false, length = 1000)
    private String fileUrl;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_type", nullable = false, length = 50)
    private String fileType;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_category", nullable = false, length = 32)
    private FileCategory fileCategory = FileCategory.OTHER;

    @Column(name = "file_storage", nullable = false, length = 20)
    private String fileStorage = "OSS";

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "business_tag", length = 50)
    private String businessTag;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "upload_source", nullable = false, length = 20)
    private String uploadSource = "WEB";

    /**
     * 关联实体类型 — 白名单约束 (DB CHECK constraint).
     *
     * <p>新增值时必须同步更新:
     * <ol>
     *   <li>本枚举</li>
     *   <li>Flyway migration 的 {@code chk_att_entity_type} CHECK constraint</li>
     * </ol>
     *
     * <p>Sprint 6 W3-A (2026-05-19): 加 {@link #SALES_ORDER} 和 {@link #INVENTORY}
     * 用于 inline link counter (4 list 行内 3-chip 显示). 详见
     * {@code docs/superpowers/specs/2026-05-19-sprint5-h1-attachment-linkcounter-spec.md §3.4}.
     */
    public enum EntityType {
        CUSTOMER,
        CUSTOMER_TRACKING,
        PURCHASE_ORDER,
        PURCHASE_RECEIPT,
        QUALITY_CHECK,
        PRODUCTION_BATCH,
        PAYMENT_VOUCHER,
        INVOICE,
        RD_SAMPLE,
        RECEIPT,
        RETURN_ORDER,
        SHIPMENT,
        WASTAGE_RECORD,
        GROUP_LEADER_REPORT,
        EXPENSE_REPORT,
        LEAVE_REQUEST,
        TIMECLOCK_PHOTO,
        /** Sprint 6 W3-A — 销售订单附件 (inline link counter 文件/图片/合同 3-chip). */
        SALES_ORDER,
        /** Sprint 6 W3-A — 库存批次附件 (入库单据 / 抄码单 / 质检证明). */
        INVENTORY,
        /** Sprint 6 W4-C — ECN 工程变更通知附件 (变更说明 / 影响分析报告 / 客户确认书). */
        ECN,
        /** Sprint 6 W2-C-2 — 通话记录录音附件 (CallRecord audio 文件挂载到附件中心). */
        CALL_RECORD,
        PRODUCTION_REPORT,
        GENERIC
    }

    /**
     * 文件分类. 用于列表 / 过滤 / 图标选择 / link counter 三分类.
     *
     * <p>Sprint 5 H-1 (2026-05-19): 加 {@link #CONTRACT} 用于销售单 list
     * inline link counter `文件(N) 图片(N) 合同(N)` 三分类 — 对齐 HJ link_type 设计
     * (Round 12 §B.6 X2). CONTRACT 跟 DOCUMENT 区别:
     * <ul>
     *   <li>{@link #DOCUMENT}: 通用 PDF/Word/Excel</li>
     *   <li>{@link #CONTRACT}: 合同 PDF (法律效力, 触发 vflag 业务流 — 销售合同 → 应收触发)</li>
     *   <li>{@link #VOUCHER}: 财务凭证扫描件 (跟 Voucher 实体绑定)</li>
     * </ul>
     *
     * <p>新增值时务必同步:
     * <ol>
     *   <li>本枚举</li>
     *   <li>Flyway migration 的 {@code chk_att_category} CHECK constraint</li>
     * </ol>
     */
    public enum FileCategory {
        PHOTO,
        VIDEO,
        DOCUMENT,
        VOUCHER,
        SIGNATURE,
        /** Sprint 5 H-1 — 合同 PDF, link counter 第 3 类. */
        CONTRACT,
        OTHER
    }
}
