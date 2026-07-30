package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * 供应商银行账户（多值）。
 *
 * <p>🔴 {@code is_primary = TRUE} 的那条镜像回 {@code suppliers.bank_name / bank_account},
 * 而 {@code PaymentRequestServiceImpl} 把供应商主数据当作**收款账户的权威来源**
 * (付款单自身的值只做兜底)。也就是说「哪条是主账户」直接决定出纳打款打到哪张卡 ——
 * 这是本次多值化风险最高的一处, 主账户切换必须同步刷新镜像列, 不能只写子表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "supplier_bank_accounts",
       indexes = {
           @Index(name = "idx_supplier_bank_accounts_supplier",
                  columnList = "factory_id, supplier_id, sort_order")
       })
@Where(clause = "deleted_at IS NULL")
public class SupplierBankAccount extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 多租户隔离键 —— 所有查询必须带上。 */
    @Column(name = "factory_id", nullable = false, length = 255)
    private String factoryId;

    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    /** 户名。对公账户通常等于供应商全称, 回填时即以此为默认值。 */
    @Column(name = "account_name", nullable = false, length = 200)
    private String accountName;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    /** 支行/网点。 */
    @Column(name = "branch_name", length = 200)
    private String branchName;

    /**
     * ⚠️ 刻意**不**加 {@code @PriceSensitive}: 既有的 {@code suppliers.bank_account}
     * 对所有角色可见, 这里加脱敏会让「同一个账号在主档看得到、在联系人页看不到」,
     * 是静默的行为变更。要不要把银行账号纳入脱敏是独立的安全决策, 应整体一次做
     * (含 suppliers.bank_account 与导出 DTO), 不在本次多值化里夹带。
     */
    @Column(name = "account_number", nullable = false, length = 64)
    private String accountNumber;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "CNY";

    /** 主账户 —— 出纳付款单默认打这张卡。 */
    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
