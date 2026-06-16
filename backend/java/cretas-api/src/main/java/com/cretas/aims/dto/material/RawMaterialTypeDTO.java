package com.cretas.aims.dto.material;

import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
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

    // 不加 @NotBlank: 创建时前端故意不传 code, 由 service 自动生成
    // (RawMaterialTypeServiceImpl.createMaterialType 在 code 为空时调 generateNextCode +
    //  查重; 更新路径 code 始终带值且只在变化时 set). @NotBlank 会在 @Valid 阶段
    // 提前 400 拒绝自动生成请求 → 报 "原材料编码不能为空", 阻断建档. DB NOT NULL +
    // service 自动生成 + 查重 才是真正的保证.
    @JsonProperty("code")
    @JsonAlias("materialCode")  // 支持前端发送 materialCode
    private String code;

    @NotBlank(message = "原材料名称不能为空")
    private String name;
    private String category;

    @NotBlank(message = "单位不能为空")
    private String unit;
    // unitPrice 不再 expose: 价格按采购批次浮动, 走 movingAvgPrice / 采购单价.
    // entity 字段保留以兼容历史数据.
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
    private BigDecimal totalValue;
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
     * null = 未配置, 不触发换算.
     */
    private TaxRate taxRate;

    /**
     * SP4-A8: 含税单价 (发票价). Price-sensitive: 同 unitPrice.
     * service 层在 taxRate 非 null 时自动换算 → unitPrice (未税).
     */
    @PriceSensitive
    private BigDecimal taxIncludedUnitPrice;

    // ========== SP8: 16位分段编码 ==========

    /**
     * SP8: 16位编码前三位主编码 (类型段), 如 001/002/003.
     * 历史数据可为空; 新建时从 code 前三位自动提取或由前端级联选择传入.
     */
    private String primaryCode;

    /**
     * SP8: 前端级联选择的 L3 cumulative segment code (10位), 供16位生成器用.
     * 不持久化到 DB — 仅用于建档请求中传递级联选择结果.
     */
    private String segmentCode;

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
