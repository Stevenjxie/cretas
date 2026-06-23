package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 工厂成本参数 (SP-C) — 替代 SP-B1 ¥26/工时硬编码。
 *
 * <p>每工厂一行 (UNIQUE factory_id)。{@code laborHourlyRate} 为 null 时,
 * {@code ClerkProcessEntryServiceImpl.resolveLaborRate} 回退到
 * {@code LABOR_RATE_DEFAULT}(¥26) 并附 warning。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "factory_cost_settings")
@Where(clause = "deleted_at IS NULL")
public class FactoryCostSettings extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** ¥/工时; null = 未配置，由调用方回退到 LABOR_RATE_DEFAULT */
    @Column(name = "labor_hourly_rate", precision = 12, scale = 2)
    private BigDecimal laborHourlyRate;
}
