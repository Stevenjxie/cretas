package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SP11: 凭证导出历史记录 (导出日志审计 + Rule-4 幂等去重).
 *
 * <p>同一工厂同一 exportType+period 5 分钟内重复提交 → 返回已有记录的 id.
 * 防止用户连续点导出产生多份相同文件.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "voucher_export_records",
        indexes = {
                @Index(name = "idx_ver_factory_type", columnList = "factory_id, export_type"),
                @Index(name = "idx_ver_period", columnList = "factory_id, period_start, period_end")
        })
@Where(clause = "deleted_at IS NULL")
public class VoucherExportRecord extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** SEQUENTIAL_LEDGER / SUBJECT_BALANCE / INVENTORY_LEDGER */
    @Column(name = "export_type", nullable = false, length = 32)
    private String exportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_system", length = 32)
    private VoucherTargetSystem targetSystem;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "row_count", nullable = false)
    @Builder.Default
    private Integer rowCount = 0;

    @Column(name = "file_name", length = 255)
    private String fileName;

    /** SHA-256 hex of exported file bytes (for dedup verification) */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "exported_by", nullable = false)
    private Long exportedBy;
}
