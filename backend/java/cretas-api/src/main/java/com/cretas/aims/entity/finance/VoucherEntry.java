package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 凭证分录 (借/贷一行). 多条分录借贷必平: sum(debit) == sum(credit).
 *
 * 会计科目硬编码示例:
 *   1122 应收账款 / 6001 主营业务收入 / 5401 主营业务成本 / 2202 应付账款 等
 *
 * F-VOUCHER-TPL-1 (P1 后续) 引入会计科目模板, 现在留 TODO.
 */
@Data
@EqualsAndHashCode(callSuper = true, exclude = {"voucher"})
@ToString(exclude = {"voucher"})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "voucher_entries",
        indexes = {
                @Index(name = "idx_ve_voucher", columnList = "voucher_id"),
                @Index(name = "idx_ve_subject", columnList = "subject_code"),
                @Index(name = "idx_ve_auxiliary", columnList = "auxiliary_type, auxiliary_entity_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class VoucherEntry extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private Voucher voucher;

    /** 行号 (1-based, 同一 voucher 内唯一) */
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    /** 会计科目编码 (e.g. "1122") */
    @Column(name = "subject_code", nullable = false, length = 32)
    private String subjectCode;

    /** 会计科目名称 (e.g. "应收账款") */
    @Column(name = "subject_name", nullable = false, length = 100)
    private String subjectName;

    /** 分录描述 */
    @Column(name = "description", length = 500)
    private String description;

    /** 借方金额 (与 credit 互斥; 一方非零另一方零) */
    @PriceSensitive
    @Column(name = "debit", precision = 15, scale = 2)
    private BigDecimal debit;

    /** 贷方金额 */
    @PriceSensitive
    @Column(name = "credit", precision = 15, scale = 2)
    private BigDecimal credit;

    /** 辅助核算自由文本 (legacy, 保留 backward compat — 推荐用 auxiliaryType+auxiliaryEntityId 结构化字段) */
    @Column(name = "cost_center", length = 100)
    private String costCenter;

    /**
     * Sprint 5 F-2 辅助核算 7 类 (R-HJ Round 13 §2 实测):
     * CUSTOMER / SUPPLIER / DEPT / EMPLOYEE / PROJECT / INVENTORY / OUTSOURCER.
     * 与 auxiliaryEntityId 成对出现 (要么都 null, 要么都非 null).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auxiliary_type", length = 16)
    private AuxiliaryType auxiliaryType;

    /**
     * 辅助核算实体 PK (polymorphic FK by convention).
     * 字符串以兼容 UUID / Long 两种 PK 类型 (Customer UUID String, Employee Long, etc).
     * DB 层无 FK 约束 (因 7 类目标表不同), 应用层在 VoucherService 校验. 见 R-HJ Round 13 §2.
     */
    @Column(name = "auxiliary_entity_id", length = 191)
    private String auxiliaryEntityId;
}
