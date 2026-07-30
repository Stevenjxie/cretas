package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.annotations.Where;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 计量单位配置实体
 *
 * 用于配置化管理计量单位:
 * - 支持单位换算系数
 * - 支持工厂级别的自定义单位
 * - 支持单位分类 (重量、体积、数量等)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "unit_of_measurements",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "unit_code"}),
       indexes = {
           @Index(name = "idx_unit_factory", columnList = "factory_id"),
           @Index(name = "idx_unit_category", columnList = "category"),
           @Index(name = "idx_unit_is_active", columnList = "is_active")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class UnitOfMeasurement extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     * "*" 表示全局默认配置
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 单位代码
     * 如: kg, g, ton, box, bag, pcs
     */
    @Column(name = "unit_code", nullable = false, length = 20)
    private String unitCode;

    /**
     * 单位名称 (显示用)
     * 如: 公斤, 克, 吨, 箱, 袋, 件
     */
    @Column(name = "unit_name", nullable = false, length = 100)
    private String unitName;

    /**
     * 单位符号 (简写)
     * 如: kg, g, t, 箱, 袋
     */
    @Column(name = "unit_symbol", length = 20)
    private String unitSymbol;

    /**
     * 基础单位 (同分类下的换算基准)
     * 如: 重量类的基础单位是 kg
     */
    @Column(name = "base_unit", nullable = false, length = 20)
    private String baseUnit;

    /**
     * 转换系数 (相对于基础单位)
     * 如: g 的系数是 0.001 (1g = 0.001kg)
     *     ton 的系数是 1000 (1ton = 1000kg)
     */
    @Column(name = "conversion_factor", precision = 15, scale = 6)
    private BigDecimal conversionFactor;

    /**
     * 单位分类
     * WEIGHT - 重量 (kg, g, ton)
     * VOLUME - 体积 (L, mL)
     * COUNT - 数量 (pcs, box, bag)
     * LENGTH - 长度 (m, cm)
     * TEMPERATURE - 温度 (℃, ℉)
     */
    @Column(name = "category", length = 50)
    private String category;

    /** Business fields where this unit may be selected. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage_scopes_json", columnDefinition = "jsonb")
    private List<String> usageScopesJson;

    /**
     * Conversion family. Automatic conversion is only allowed inside this
     * family or through an explicit product packaging conversion.
     */
    @Column(name = "conversion_family", length = 50)
    private String conversionFamily;

    /**
     * 小数位数 (显示精度)
     */
    @Column(name = "decimal_places")
    @Builder.Default
    private Integer decimalPlaces = 2;

    /**
     * 是否为基础单位
     */
    @Column(name = "is_base_unit")
    @Builder.Default
    private Boolean isBaseUnit = false;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 是否为系统内置
     */
    @Column(name = "is_system")
    @Builder.Default
    private Boolean isSystem = true;

    /**
     * 排序顺序
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 工厂级单位别名，用于将输入归一到 unitCode；不表示包装换算关系。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aliases_json", columnDefinition = "jsonb")
    private List<String> aliasesJson;

    /**
     * 判断是否为全局配置
     */
    public boolean isGlobalConfig() {
        return "*".equals(factoryId);
    }

    /**
     * 将值从当前单位转换为基础单位
     * @param value 当前单位的值
     * @return 基础单位的值
     */
    public BigDecimal toBaseUnit(BigDecimal value) {
        if (value == null || conversionFactor == null) {
            return value;
        }
        return value.multiply(conversionFactor);
    }

    /**
     * 将值从基础单位转换为当前单位
     * @param baseValue 基础单位的值
     * @return 当前单位的值
     */
    public BigDecimal fromBaseUnit(BigDecimal baseValue) {
        if (baseValue == null || conversionFactor == null || conversionFactor.compareTo(BigDecimal.ZERO) == 0) {
            return baseValue;
        }
        return baseValue.divide(conversionFactor, decimalPlaces, java.math.RoundingMode.HALF_UP);
    }
}
