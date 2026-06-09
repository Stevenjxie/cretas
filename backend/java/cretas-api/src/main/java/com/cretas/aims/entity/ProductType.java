package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Where;
/**
 * 产品类型实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "createdBy", "conversions", "productionPlans"})
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "product_types",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "code"})
       },
       indexes = {
           @Index(name = "idx_product_factory", columnList = "factory_id"),
           @Index(name = "idx_product_is_active", columnList = "is_active"),
           @Index(name = "idx_product_template", columnList = "template_id"),
           @Index(name = "idx_product_customer", columnList = "customer_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class ProductType extends BaseEntity {
    @Id
    @Column(name = "id", length = 100)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "code", nullable = false, length = 50)
    private String code;
    @Column(name = "name", nullable = false)
    private String name;
    @Column(name = "category", length = 50)
    private String category;
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;
    @PriceSensitive
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;
    @Column(name = "production_time_minutes")
    private Integer productionTimeMinutes;
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    // ==================== Sprint 2 S2-1: Form Template Association ====================

    /**
     * Associated form template ID for this product type
     * Allows different product types to have specialized input forms
     */
    @Column(name = "form_template_id", length = 100)
    private String formTemplateId;

    // ==================== Sprint 2 S2-5: SOP Configuration Association ====================

    /**
     * Default SOP configuration ID for this product type
     * Links to SopConfig entity for standardized operating procedures
     */
    @Column(name = "default_sop_config_id", length = 50)
    private String defaultSopConfigId;

    // ==================== Phase 5: SKU Configuration Fields ====================

    /**
     * Standard work hours to produce one unit
     * Used by scheduling system for capacity planning
     */
    @Column(name = "work_hours", precision = 10, scale = 2)
    private BigDecimal workHours;

    /**
     * Processing steps as JSON array
     * Format: [{"stageType": "SLICING", "orderIndex": 1, "requiredSkillLevel": 3, "estimatedMinutes": 30}, ...]
     */
    @Column(name = "processing_steps", columnDefinition = "JSON")
    private String processingSteps;

    /**
     * Skill requirements as JSON object
     * Format: {"minLevel": 2, "preferredLevel": 4, "specialSkills": ["knife_handling"]}
     */
    @Column(name = "skill_requirements", columnDefinition = "JSON")
    private String skillRequirements;

    /**
     * Required equipment IDs as JSON array
     * Format: ["EQ-001", "EQ-002"]
     */
    @Column(name = "equipment_ids", columnDefinition = "JSON")
    private String equipmentIds;

    /**
     * Associated quality check item IDs as JSON array
     * Format: ["QC-001", "QC-002"]
     */
    @Column(name = "quality_check_ids", columnDefinition = "JSON")
    private String qualityCheckIds;

    /**
     * Production complexity score (1-5)
     * Used by LinUCB feature extraction for worker recommendation
     */
    @Column(name = "complexity_score")
    private Integer complexityScore;

    // ==================== End Phase 5 Fields ====================

    // ==================== Custom Form Schema Configuration ====================

    /**
     * Custom schema overrides for various entity types associated with this product type.
     * Allows each product type to define specialized input forms for different data entities.
     *
     * JSON Format:
     * {
     *   "MATERIAL_BATCH": {
     *     "additionalFields": [...],
     *     "requiredFields": [...],
     *     "fieldValidations": {...}
     *   },
     *   "QUALITY_CHECK": {
     *     "additionalFields": [...],
     *     "checkItems": [...]
     *   },
     *   "PRODUCTION_RECORD": {...}
     * }
     */
    @Column(name = "custom_schema_overrides", columnDefinition = "TEXT")
    private String customSchemaOverrides;

    // ==================== End Custom Form Schema Configuration ====================

    @Column(name = "package_spec", length = 100)
    private String packageSpec;

    // ==================== 产品信息管理增强字段 ====================

    /**
     * 产品大类: FINISHED_PRODUCT, RAW_MATERIAL, PACKAGING, SEASONING, CUSTOMER_MATERIAL
     */
    @Column(name = "product_category", length = 50)
    private String productCategory;

    /**
     * 规格（如 310g*42袋/箱）
     */
    @Column(name = "specification", length = 200)
    private String specification;

    /**
     * 关联客户
     */
    @Column(name = "related_customer", length = 100)
    private String relatedCustomer;

    /**
     * 产品图片URL
     */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * 温区: 常温, 冷藏, 冷冻
     */
    @Column(name = "temperature_zone", length = 20)
    private String temperatureZone;

    /** 箱规转换系数 (如 10kg/箱 则系数为10) */
    @Column(name = "box_conversion_coefficient", precision = 10, scale = 4)
    private java.math.BigDecimal boxConversionCoefficient;

    /**
     * P0-2 标准克重: 1 份/盒 = X 克 (报工末道"份/盒→kg"折算整批出成率 + 成品入库换算用;
     * null=无标准克重, 跨单位出成率保持诚实 null, 不臆造).
     */
    @Column(name = "grams_per_unit", precision = 10, scale = 2)
    private java.math.BigDecimal gramsPerUnit;

    /** 在产半成品折成品下游出率系数 (备货看板 WIP 估算; null = 按 1.0). */
    @Column(name = "wip_to_fg_yield", precision = 5, scale = 4)
    private java.math.BigDecimal wipToFgYield;

    /** 库存预警值 */
    @Column(name = "inventory_warning_threshold", precision = 15, scale = 2)
    private java.math.BigDecimal inventoryWarningThreshold;

    /** 起订量 (MOQ) */
    @Column(name = "minimum_order_quantity", precision = 15, scale = 2)
    private java.math.BigDecimal minimumOrderQuantity;

    /** 品牌 */
    @Column(name = "brand", length = 100)
    private String brand;

    /** 结算方式 (月结/现结/预付等) */
    @Column(name = "settlement_method", length = 50)
    private String settlementMethod;

    /** 含税单价 */
    @PriceSensitive
    @Column(name = "tax_included_unit_price", precision = 15, scale = 4)
    private java.math.BigDecimal taxIncludedUnitPrice;

    /**
     * SP4-A8: 出货税率枚举 (9%农产品 / 13%一般货物).
     * <p>设置后, 含税单价 → 未税单价自动换算并更新 {@link #unitPrice}.</p>
     * <p>null = 未配置税率 (不换算).</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_rate", length = 10)
    private TaxRate taxRate;

    // ==================== End 产品信息管理增强字段 ====================

    // ==================== SKU 组装模型 ====================

    /**
     * 模板ID：null=此记录是产品模板, 有值=此记录是SKU(指向模板ProductType.id)
     */
    @Column(name = "template_id", length = 100)
    private String templateId;

    /**
     * 客户ID：关联 Customer 实体 (六扇门要求每个SKU必须绑定客户)
     * 画布引擎可配置此字段是否必填
     */
    @Column(name = "customer_id", length = 191)
    private String customerId;

    /**
     * 配方版本标识：默认 "default"，用于同产品+同客户的不同配方变体
     */
    @Column(name = "recipe_version", length = 50)
    private String recipeVersion;

    // ==================== T123: 规格两级单位 + 名称分离 ====================

    /**
     * 一级单位 (如: 筐, 箱, 件, 袋, 桶, 盒).
     * 与 boxConversionCoefficient 联用: 1 level1Unit = boxConversionCoefficient 个 unit(二级单位).
     * 客户原话: "一级单位和二级单位的转换系数", "箱数嘛根据转换系数自动转好来"
     */
    @Column(name = "level1_unit", length = 20)
    private String level1Unit;

    /**
     * 产品基础名 (名称分离).
     * 如 "好食光卤猪蹄" — 不含规格/客户后缀.
     * RN 展示优先使用此字段, 无则 fallback 到 name.
     */
    @Column(name = "base_product_name", length = 200)
    private String baseProductName;

    // ==================== End T123 ====================

    // ==================== End SKU 组装模型 ====================

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    // 关联关系 (使用 @JsonIgnore 防止循环引用)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;

    @JsonIgnore
    @OneToMany(mappedBy = "productType", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialProductConversion> conversions = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "productType", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductionPlan> productionPlans = new ArrayList<>();
}
