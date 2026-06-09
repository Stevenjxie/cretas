package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * SP11: per-factory 凭证导出列字段名映射配置.
 * 客户可自定义金蝶/用友各版本的列名, 适配不同版本差异.
 *
 * <p>一个工厂对同一目标系统只能有一个有效配置 (UNIQUE factory_id + target_system).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "voucher_export_configs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "target_system"}),
        indexes = @Index(name = "idx_vec_factory", columnList = "factory_id"))
@Where(clause = "deleted_at IS NULL")
public class VoucherExportConfig extends BaseEntity {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "target_system", nullable = false, length = 32)
    @Builder.Default
    private VoucherTargetSystem targetSystem = VoucherTargetSystem.KINGDEE;

    // 列名映射 — 客户可改成本地金蝶/用友列名
    @Column(name = "col_voucher_no", length = 64)
    @Builder.Default private String colVoucherNo   = "凭证字号";

    @Column(name = "col_date", length = 64)
    @Builder.Default private String colDate        = "日期";

    @Column(name = "col_summary", length = 64)
    @Builder.Default private String colSummary     = "摘要";

    @Column(name = "col_subject_code", length = 64)
    @Builder.Default private String colSubjectCode = "科目编码";

    @Column(name = "col_subject_name", length = 64)
    @Builder.Default private String colSubjectName = "科目名称";

    @Column(name = "col_debit", length = 64)
    @Builder.Default private String colDebit       = "借方金额";

    @Column(name = "col_credit", length = 64)
    @Builder.Default private String colCredit      = "贷方金额";

    @Column(name = "col_auxiliary", length = 64)
    @Builder.Default private String colAuxiliary   = "辅助核算";

    @Column(name = "col_currency", length = 64)
    @Builder.Default private String colCurrency    = "币别";

    @Column(name = "is_active", nullable = false)
    @Builder.Default private Boolean isActive = true;
}
