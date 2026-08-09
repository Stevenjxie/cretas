package com.cretas.aims.dto.material;

import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 原材料类型数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawMaterialTypeDTO {
    private String id;
    private String factoryId;

    // 创建时可不传，service 会按基本类型和已有料号生成下一个短料号。
    // DB NOT NULL + 含软删除行的冲突检查是最终保证。
    @JsonProperty("code")
    @JsonAlias("materialCode")  // 支持前端发送 materialCode
    private String code;

    /** The API exposes one material code only. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getDisplayCode() {
        return code;
    }

    @NotBlank(message = "原材料名称不能为空")
    private String name;
    private String category;

    /**
     * 是否副产：生产产出物而非采购物。与 category **正交** —— 副产仍保留其材质分类，
     * 因此能被别的 workflow 当投入投料。null 视为 false（不猜）。
     */
    private Boolean isByproduct;

    private String unit;

    /**
     * Optional untaxed purchase reference price maintained on material master data.
     * The denominator is always {@link #unit}; supplier-specific quotes override it.
     */
    @PriceSensitive
    @Positive(message = "采购参考价必须大于0")
    @JsonAlias("unitPrice")
    private BigDecimal materialReferencePrice;

    /** Read-only denominator of {@link #materialReferencePrice}. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String materialReferencePriceUnit;
    // Actual purchase prices still come from supplier relations/orders/batches.
    // The material-level value above is only an explicitly labelled fallback reference price.
    private String storageType; // fresh, frozen, dry

    @JsonProperty("shelfLifeDays")
    @JsonAlias("shelfLife")  // 支持前端发送 shelfLife
    private Integer shelfLifeDays;

    private BigDecimal minStock;
    private BigDecimal maxStock;
    private Boolean isActive;

    @JsonProperty("notes")
    @JsonAlias("description")  // 支持前端发送 description
    private String notes;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // 关联信息
    private String factoryName;
    private String createdByName;
    // 统计信息
    private Integer totalBatches;
    private BigDecimal currentStock;
    // 价格脱敏: 库存货值/移动均价对无 procurement:price:view 角色置 null (同 MaterialStockSummaryDTO #967)
    @PriceSensitive
    private BigDecimal totalValue;
    @PriceSensitive
    private BigDecimal movingAvgPrice;

    // C-6 Canvas Reactive Default (2026-05-09): 包装层级换算系数 + 二级单位.
    // 由 RawMaterialTypeServiceImpl.getMaterialTypeById 单点 LEFT JOIN
    // material_packaging_hierarchy 写入 (其他 list 端点不取, 避免 N+1).
    // 前端 ReferenceSelector projectFields 选物料后写到 row 的 _level1PerLevel2 /
    // _level2Unit shadow 字段, 让 boxQuantity 等 computed 表达式可引用包装数据.
    // null 表示该 material 没配置包装 — 前端 boxQuantity computed 表达式必须 null-guard.
    private BigDecimal level1PerLevel2;
    private String level2Unit;

    // ========== SP4-A8: 税率 + 含税单价 ==========
    /**
     * SP4-A8: 采购税率枚举 (TAX_9 / TAX_13).
     * 创建/更新时传入此值 + taxIncludedUnitPrice → service 自动换算 unitPrice (未税).
     * 新建/编辑物料时必填，BOM 仅继承，不重复维护。
     */
    private TaxRate taxRate;
    private TaxTreatment taxTreatment;
    private String taxExemptionReason;

    /**
     * SP4-A8: 含税单价 (发票价). Price-sensitive: 同 unitPrice.
     * service 层在 taxRate 非 null 时自动换算 → unitPrice (未税).
    */
    @PriceSensitive
    @Positive(message = "含税单价必须大于0")
    private BigDecimal taxIncludedUnitPrice;

    // ========== 可选物料分类 ==========

    /** 可选的三级分类节点 ID；由系统生成，与简短料号完全独立。 */
    private Long classificationId;

    // ========== 包材规格 (packaging spec) ==========

    /**
     * 包材每产品单位用量 (仅 category=PACKAGING 有意义).
     *
     * <p>生产 1 个最小成品单位 (outputQuantityPerUnit) 需要此包材多少个/袋/盒。
     * null = 未配置, BOM 行需手填 standardQuantity.
     *
     * <p>DB: pack_qty_per_product NUMERIC(15,6) NULL
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "包材每产品单位用量 (category=PACKAGING 专用); null=未配置需手填")
    private java.math.BigDecimal packQtyPerProduct;
    // ========== P8: 包材关联固定客户 (catalog 行99-100) ==========

    /**
     * P8: 包材关联的固定客户ID (nullable).
     * 包材专属 — 非包材物料此字段为 null, 创建/更新时不传即忽略.
     */
    private String associatedCustomerId;

    /**
     * P8: 关联客户名称 (read-only, 从 customers 表 JOIN 填充, 不写库).
     */
    private String associatedCustomerName;
}
