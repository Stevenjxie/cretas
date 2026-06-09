package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.SettlementType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * SP11: 采购结算属性 → 会计科目借贷映射.
 *
 * <p>6 种结算属性(PREPAID/CREDIT_FIRST/NO_INVOICE/MONTHLY/CREDIT_PERIOD/IMMEDIATE)
 * 各自对应借贷科目. factory_id='__default__' 为系统种子默认模板.
 *
 * <p>若工厂无 per-factory 配置, service 层从 __default__ copy 一份, 使首次查询透明.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "voucher_subject_mappings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "settlement_type", "business_type"}),
        indexes = {
                @Index(name = "idx_vsm_factory", columnList = "factory_id"),
                @Index(name = "idx_vsm_settlement", columnList = "factory_id, settlement_type")
        })
@Where(clause = "deleted_at IS NULL")
public class VoucherSubjectMapping extends BaseEntity {

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
    @Column(name = "settlement_type", nullable = false, length = 32)
    private SettlementType settlementType;

    /** null = 通用; "PURCHASE" / "SALES" 区分业务类型 */
    @Column(name = "business_type", length = 32)
    private String businessType;

    @Column(name = "debit_subject_code", nullable = false, length = 32)
    private String debitSubjectCode;

    @Column(name = "debit_subject_name", length = 64)
    private String debitSubjectName;

    @Column(name = "credit_subject_code", nullable = false, length = 32)
    private String creditSubjectCode;

    @Column(name = "credit_subject_name", length = 64)
    private String creditSubjectName;

    @Column(name = "remark", length = 255)
    private String remark;
}
