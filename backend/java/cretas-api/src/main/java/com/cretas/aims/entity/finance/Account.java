package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * 会计科目 (Chart of Accounts) — Sprint 7 T1 (F-VOUCHER-2 Phase B).
 *
 * <p>承接 中国 GAAP 标准科目表 (1001 库存现金 / 1122 应收账款 / 5001 主营业务收入 等).
 * 跟 {@link VoucherEntry#getSubjectCode()} 配对使用 — entry 自由文本科目仍保留 (旧凭证
 * 数据后向兼容), 新建科目走 Account 表 (Sprint 7 T3 报表三表 必须基于 Account.category +
 * balanceType 计算资产/负债/收入/费用 4 大类汇总).
 *
 * <p>层级: parent_id 形成 4 层树 (一级 1001 → 二级 1001.01 → 三级 1001.01.001 → 四级
 * 1001.01.001.A). level=1 是顶层 (无 parent).
 *
 * <p>factory_id scope: factory 内 unique(code). 系统级 标准科目 factoryId 为 NULL —
 * 任何 factory 都可见 (seed 通过 V20260701_02 写入). factory 自定义科目 必须显式
 * factoryId 非空.
 *
 * <p>软删除沿用 BaseEntity. 已被 voucher_entry 引用的科目应禁用 (active=false), 不应
 * 物理删除 (避免引用悬空).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "accounts",
        uniqueConstraints = {
                // factory 内 code 唯一. NULL factoryId 表示系统级标准科目, 走单独 unique index
                // (DB 层 V20260701_02 用 partial unique index 处理 NULL semantics).
                @UniqueConstraint(name = "uk_account_factory_code", columnNames = {"factory_id", "code"})
        },
        indexes = {
                @Index(name = "idx_account_factory", columnList = "factory_id"),
                @Index(name = "idx_account_code", columnList = "code"),
                @Index(name = "idx_account_parent", columnList = "parent_id"),
                @Index(name = "idx_account_category", columnList = "category"),
                @Index(name = "idx_account_active", columnList = "active")
        })
@Where(clause = "deleted_at IS NULL")
public class Account extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /**
     * 工厂 ID. NULL 表示系统级标准科目 (中国 GAAP), 所有 factory 共享.
     * 非 NULL 表示 factory 自定义科目, 只该 factory 可见.
     */
    @Column(name = "factory_id", length = 191)
    private String factoryId;

    /** 科目编码 (e.g. "1001" 现金 / "1122" 应收账款 / "5001" 主营业务收入). factory 内唯一. */
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    /** 科目名称 (e.g. "库存现金" / "应收账款" / "主营业务收入"). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 父科目 ID (null = 顶级科目, level=1). */
    @Column(name = "parent_id", length = 191)
    private String parentId;

    /** 科目层级 (1-4, 1=顶级一级科目, 4=最深四级明细). */
    @Column(name = "level", nullable = false)
    @Builder.Default
    private Integer level = 1;

    /** 科目大类 (中国 GAAP 6 类). */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private AccountCategory category;

    /** 余额方向 (DEBIT_NORMAL / CREDIT_NORMAL). */
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false, length = 16)
    private AccountBalanceType balanceType;

    /** 描述 / 备注 (可选). */
    @Column(name = "description", length = 500)
    private String description;

    /** 是否启用 — false 时不可用于新凭证分录, 但保留历史引用. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * 显示排序权重 (asc). 同级科目按此排序, 默认 0 (按 code 字典序).
     * 财务部门可手工调整以匹配自家习惯 (e.g. 把"现金"放最前).
     */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
