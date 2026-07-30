package com.cretas.aims.entity.canvas;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Canvas-Thresholds 工厂级阈值参数 (Phase A P0-1).
 *
 * <p>一行 = 一个 (factoryId, thresholdKey) 配置。 通过 {@code ThresholdResolverService}
 * 读取, 默认 fallback 到 service 类中保留的 hard-coded 常量。
 *
 * <p>{@code factoryId} 语义:
 * <ul>
 *   <li>{@code "*"} → global default (跨工厂; 见 {@code GLOBAL_FALLBACK_FACTORY_ID})</li>
 *   <li>其他 → per-factory override (优先级最高)</li>
 * </ul>
 *
 * <p>唯一约束 (partial unique index, see V20260823_01__thresholds_hub.sql):
 * {@code (factory_id, threshold_key) WHERE deleted_at IS NULL}
 *
 * <p>值类型: {@code threshold_value} 列以 VARCHAR 存储, 通过 {@link #valueType} 决定解析:
 * <ul>
 *   <li>{@link ThresholdValueType#INTEGER} → {@link #asInteger()}</li>
 *   <li>{@link ThresholdValueType#DECIMAL} → {@link #asBigDecimal()}</li>
 *   <li>{@link ThresholdValueType#DOUBLE}  → {@link #asDouble()}</li>
 *   <li>{@link ThresholdValueType#STRING}  → {@link #getThresholdValue()}</li>
 * </ul>
 *
 * @since Canvas Phase A (2026-05-21)
 */
@Entity
@Table(name = "factory_thresholds", indexes = {
        @Index(name = "idx_factory_thresholds_factory_category",
               columnList = "factory_id,category"),
        @Index(name = "idx_factory_thresholds_key", columnList = "threshold_key")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FactoryThreshold extends BaseEntity {

    /** Sentinel factoryId for global defaults (used when no per-factory row exists). */
    public static final String GLOBAL_FALLBACK_FACTORY_ID = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * 工厂 ID; {@value #GLOBAL_FALLBACK_FACTORY_ID} = 全局默认 fallback.
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 阈值键, e.g. "inventory.turnover.red", "iot.cold_chain.temp_max".
     * 命名规则: {category}.{subdomain}.{name} (lowercase + underscore).
     * 全局唯一通过 partial unique index (factory_id, threshold_key) 保证。
     */
    @Column(name = "threshold_key", length = 100, nullable = false)
    private String thresholdKey;

    /**
     * 阈值分类, 用于 UI sub-tab 分组 + 批量 reset.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private ThresholdCategory category;

    /**
     * 值类型 (决定如何解析 {@link #thresholdValue} 字符串).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 20, nullable = false)
    private ThresholdValueType valueType;

    /**
     * 阈值字符串形式 (运行时按 valueType 解析).
     * 范围检查由 service 层根据 thresholdKey 决定。
     */
    @Column(name = "threshold_value", length = 100, nullable = false)
    private String thresholdValue;

    /**
     * Service 层中保留的 hard-coded 默认值字符串形式 (UI 显示 + reset-to-default 用).
     */
    @Column(name = "default_value", length = 100, nullable = false)
    private String defaultValue;

    /**
     * 人类可读描述 (UI 显示).
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 单位 (UI 显示), e.g. "次/年", "%", "°C", "天".
     */
    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * 数值下限 (UI 校验; NULL = 无下限).
     */
    @Column(name = "min_value", length = 100)
    private String minValue;

    /**
     * 数值上限 (UI 校验; NULL = 无上限).
     */
    @Column(name = "max_value", length = 100)
    private String maxValue;

    /**
     * 是否启用; false = service 直接走 hard-coded fallback.
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * AUD-4 P1 JPA 乐观锁 version (mirror ScheduledTask pattern). 由 Flyway 默认 0 NOT NULL。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ==================== 类型转换便利方法 ====================

    /**
     * 按 INTEGER 类型解析阈值; throw if thresholdValue is null/blank/non-numeric.
     */
    public Integer asInteger() {
        requireValue();
        return Integer.valueOf(thresholdValue.trim());
    }

    /**
     * 按 DECIMAL 类型解析阈值.
     */
    public BigDecimal asBigDecimal() {
        requireValue();
        return new BigDecimal(thresholdValue.trim());
    }

    /**
     * 按 DOUBLE 类型解析阈值.
     */
    public Double asDouble() {
        requireValue();
        return Double.valueOf(thresholdValue.trim());
    }

    private void requireValue() {
        if (thresholdValue == null || thresholdValue.isBlank()) {
            throw new IllegalStateException(
                    "thresholdValue is null/blank for key=" + thresholdKey
                    + " (factoryId=" + factoryId + ")");
        }
    }
}
