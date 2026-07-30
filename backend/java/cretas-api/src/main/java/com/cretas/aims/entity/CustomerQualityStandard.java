package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 客户质量标准 entity — Sprint 9 P1.1 Fix 1 (CustomerQualityStandard 独立化).
 *
 * <p>历史背景: Sprint 8 P4c {@code CustomerQualityStandardTool} (2026-05-20 ship) 受限于
 * Customer entity 无 {@code qualityStandards} 字段, 仅能从 {@code Customer.notes} 关键词
 * (质量/标准/COA/检测) 提取 + 工厂默认 5 项 fallback. Sprint 9 P1.1 立独立 entity 替代,
 * Tool 优先查 Repository, 0 result 退回 R5 fallback (保留 backwards-compat).
 *
 * <p>关联:
 * <ul>
 *   <li>{@code customerId} → {@link Customer#getId()} (UUID String, length 36+, 不强制 FK
 *       constraint 因 PG-level cascade 复杂; soft-delete 由应用层 service 保证)</li>
 *   <li>{@code factoryId} → {@link Factory#getId()} (工厂隔离)</li>
 * </ul>
 *
 * <p>唯一约束 {@code (factory_id, customer_id, standard_code)} 防同 customer 重复登记.
 * 索引 {@code (factory_id, customer_id, active)} 加速 Tool 主路径查询.
 *
 * <p>软删除: 继承 BaseEntity, {@code @Where} 自动过滤 deleted_at.
 *
 * @author Cretas Team
 * @since 2026-05-21 (Sprint 9 P1.1)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "customer_quality_standards",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_customer_quality_standard_code",
                        columnNames = {"factory_id", "customer_id", "standard_code"})
        },
        indexes = {
                @Index(name = "idx_customer_quality_standards_lookup",
                        columnList = "factory_id,customer_id,active")
        })
@Where(clause = "deleted_at IS NULL")
public class CustomerQualityStandard extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID (隔离). */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** Customer.id (UUID String 形式). */
    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    /** 客户标准编码 (factory + customer 内唯一), e.g. "CQS-A01", "MICRO-001". */
    @Column(name = "standard_code", nullable = false, length = 50)
    private String standardCode;

    /** 标准名称 (中文), e.g. "微生物总数 ≤ 10^4". */
    @Column(name = "standard_name", nullable = false, length = 200)
    private String standardName;

    /**
     * 标准类别 (CHECK 约束: BIOLOGICAL/CHEMICAL/PHYSICAL/SENSORY/PACKAGING).
     * 用 String 而非 enum 给 Flyway seed 灵活性, 同时 DB CHECK 防止脏数据.
     */
    @Column(name = "category", nullable = false, length = 30)
    private String category;

    /** 详细描述 (optional). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 限量值 (optional, 部分定性标准如"感官无异味"无 limit). */
    @Column(name = "limit_value", precision = 19, scale = 4)
    private BigDecimal limitValue;

    /** 单位, e.g. "CFU/g", "mg/kg", "°C". */
    @Column(name = "unit", length = 30)
    private String unit;

    /** 是否启用 (废止 / 暂停时 false). */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
