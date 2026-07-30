package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商食品资质 — Sprint 9 P2.C 食品行业法定 (P1).
 *
 * <p>食品安全法第 51 条 + 供应商资质审核要求, Cretas 食品垂直差异化:
 * vs HJ 仅有 {@code Supplier.lastAuditDate} 字段无主动预警 + 锁单,
 * Cretas 通过 {@link SupplierQualification} 实现 30/7/0 day 三级预警 + PO BLOCKING.
 *
 * <p>资质类型 (qualificationType):
 * <ul>
 *   <li>{@code SC_LICENSE} — 食品生产许可证 (国家市场监管总局)</li>
 *   <li>{@code IMPORT_CERT} — 进口商资质 (跨境食材)</li>
 *   <li>{@code HACCP_CERT} — HACCP 认证证书 (有效期 3 年)</li>
 *   <li>{@code BUSINESS_LICENSE} — 营业执照</li>
 *   <li>{@code ISO22000} — ISO 22000 食品安全管理体系</li>
 *   <li>{@code FSSC22000} — FSSC 22000 食品安全管理体系</li>
 *   <li>{@code THIRD_PARTY_TEST} — 第三方检测报告 (有效期 6 个月)</li>
 *   <li>{@code OTHER} — 其他</li>
 * </ul>
 *
 * <p>状态 (status):
 * <ul>
 *   <li>{@code VALID} — 有效, 过期日 > 30 天</li>
 *   <li>{@code EXPIRING} — 即将过期, 过期日 <= 30 天</li>
 *   <li>{@code EXPIRED} — 已过期 (Scheduler 自动迁移)</li>
 *   <li>{@code REVOKED} — 主动吊销</li>
 * </ul>
 *
 * <p>预警逻辑 (per {@code SupplierQualificationExpiryScheduler} 每日 8 am):
 * <ul>
 *   <li>30 天内过期 → 🟡 提前预警 + status=EXPIRING</li>
 *   <li>7 天内过期 → 🔴 紧急预警 (高优先级通知)</li>
 *   <li>已过期 → ⛔ status=EXPIRED + 紧急通知 + PO BLOCKING</li>
 * </ul>
 *
 * <p>factory_id scope: factory 内 unique(supplier_id, qualification_type, certificate_number).
 * 同一供应商同一类资质可有多份历史证 (e.g. 续发 SC 证), certificate_number 区分.
 *
 * <p>软删除沿用 BaseEntity. 已过期或吊销的资质应保留 (审计), 不应物理删除.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "supplier_qualifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supplier_qual_factory_supplier_type_cert",
                        columnNames = {"factory_id", "supplier_id", "qualification_type", "certificate_number"})
        },
        indexes = {
                @Index(name = "idx_supplier_qualifications_supplier",
                        columnList = "factory_id,supplier_id,status"),
                @Index(name = "idx_supplier_qualifications_expiry",
                        columnList = "factory_id,expiry_date,status")
        })
@Where(clause = "deleted_at IS NULL")
public class SupplierQualification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 供应商 ID — 关联 {@code suppliers.id} (VARCHAR(191) UUID). */
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    /** 资质类型 — 见 class javadoc enum 列表. */
    @Column(name = "qualification_type", nullable = false, length = 50)
    private String qualificationType;

    /** 证书编号 (factory + supplier + type 内唯一). */
    @Column(name = "certificate_number", nullable = false, length = 200)
    private String certificateNumber;

    /** 颁发机构 (e.g. "国家市场监督管理总局"). */
    @Column(name = "issuing_authority", nullable = false, length = 200)
    private String issuingAuthority;

    /** 颁发日期. */
    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    /** 过期日期 — 关键: 主动预警 driver. */
    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    /** 证书扫描件 OSS URL (可选). */
    @Column(name = "attachment_oss_url", length = 500)
    private String attachmentOssUrl;

    /** 状态: VALID / EXPIRING / EXPIRED / REVOKED. 默认 VALID. */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "VALID";

    /** 上次复核时间 (人工 audit 时间). */
    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    /** 上次复核人 (user_id). */
    @Column(name = "last_reviewed_by_user_id")
    private Long lastReviewedByUserId;

    /** 备注. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
