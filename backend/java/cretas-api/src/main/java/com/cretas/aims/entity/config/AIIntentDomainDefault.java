package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * AI意图域默认配置实体
 *
 * 用于当关键词匹配失败但域检测成功时返回默认意图:
 * - 每个域可配置主/次默认意图
 * - 支持工厂级覆盖平台级配置
 * - 优先查找工厂级，没有则使用平台级
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-10
 */
@Entity
@Table(name = "ai_domain_default_intents",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "domain_name"}),
       indexes = {
           @Index(name = "idx_domain_default_factory_id", columnList = "factory_id"),
           @Index(name = "idx_domain_default_domain_name", columnList = "domain_name"),
           @Index(name = "idx_domain_default_is_active", columnList = "is_active")
       })
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class AIIntentDomainDefault extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID（用于工厂级配置隔离）
     * - null: 平台级配置（所有工厂共享）
     * - 具体工厂ID: 工厂级配置（覆盖平台级）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 语义域名称
     * 如: SCALE, ALERT, MATERIAL, PROCESSING, REPORT, EQUIPMENT, SHIPMENT, QUALITY, CUSTOMER, SUPPLIER, USER, TRACE
     */
    @Column(name = "domain_name", nullable = false, length = 30)
    private String domainName;

    /**
     * 主默认意图代码
     * 当域检测成功但关键词匹配失败时，优先返回此意图
     */
    @Column(name = "primary_intent_code", nullable = false, length = 50)
    private String primaryIntentCode;

    /**
     * 次默认意图代码（可选）
     * 当主意图不适用时的备选方案
     */
    @Column(name = "secondary_intent_code", length = 50)
    private String secondaryIntentCode;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;
}
