package com.cretas.aims.dto.material;

import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 原材料批次数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "原材料批次信息")
public class MaterialBatchDTO {

    @Schema(description = "批次ID")
    private String id;

    @Schema(description = "工厂ID")
    private String factoryId;

    @Schema(description = "批次号")
    private String batchNumber;

    @Schema(description = "原材料类型ID")
    private String materialTypeId;

    @Schema(description = "原材料名称")
    private String materialName;

    @Schema(description = "原材料代码")
    private String materialCode;

    @Schema(description = "原材料类别")
    private String materialCategory;

    @Schema(description = "存储类型(来自原材料类型): fresh/frozen/dry")
    private String storageType;

    @Schema(description = "供应商ID")
    private String supplierId;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "入库日期")
    private LocalDate receiptDate;

    @Schema(description = "生产日期")
    private LocalDate productionDate;

    @Schema(description = "到期日期")
    private LocalDate expireDate;

    @Schema(description = "入库数量")
    private BigDecimal receiptQuantity;

    @Schema(description = "数量单位")
    private String quantityUnit;

    @Schema(description = "箱数 (粗略统计用, 不参与库存计算; 实际库存以称重 kg 为准)")
    private Integer boxCount;

    @Schema(description = "每单位重量(kg)")
    private BigDecimal weightPerUnit;

    @Schema(description = "入库总重量(kg)")
    private BigDecimal totalWeight;

    @Schema(description = "当前数量")
    private BigDecimal currentQuantity;

    @Schema(description = "已使用数量")
    private BigDecimal usedQuantity;

    @Schema(description = "预留数量")
    private BigDecimal reservedQuantity;

    @Schema(description = "单位")
    private String unit;

    @PriceSensitive
    @Schema(description = "入库总价值(元)")
    private BigDecimal totalValue;

    @PriceSensitive
    @Schema(description = "单价(元/kg)")
    private BigDecimal unitPrice;

    @PriceSensitive
    @Schema(description = "总价")
    private BigDecimal totalPrice;

    @Schema(description = "状态")
    private MaterialBatchStatus status;

    @Schema(description = "状态显示名称")
    private String statusDisplayName;

    /** D1 双仓流转 (PR #309 A1=A) — 仓库 UUID (factory_warehouses.id). */
    @Schema(description = "仓库ID (UUID, factory_warehouses.id)")
    private String warehouseId;

    /** D1 双仓流转 (PR #309 A1=A) — 仓库 code, 例如 "WH-LOG" / "WH-WKS". */
    @Schema(description = "仓库 code (WH-LOG / WH-WKS)")
    private String warehouseCode;

    /** D1 双仓流转 (PR #309 A1=A) — 仓库名称, 例如 "物流仓" / "鲜棉仓". */
    @Schema(description = "仓库名称 (物流仓 / 鲜棉仓)")
    private String warehouseName;

    @Schema(description = "库存法律归属")
    private InventoryOwnership ownership;

    @Schema(description = "库存归属客户ID（仅客户自有）")
    private String ownerCustomerId;

    @Schema(description = "来源销售订单ID快照")
    private String sourceSalesOrderId;

    @Schema(description = "来源销售订单行ID快照")
    private String sourceSalesOrderItemId;

    @Schema(description = "存储位置")
    private String storageLocation;

    @Schema(description = "质量证书")
    private String qualityCertificate;

    @Schema(description = "备注")
    private String notes;

    /** SP4-A4: 生产厂家编号，用于一物一码标签 */
    @Schema(description = "生产厂家编号 (SP4-A4)")
    private String factoryNumber;

    /** SP4-A4: 产地，用于一物一码标签 */
    @Schema(description = "产地 (SP4-A4)")
    private String originPlace;

    @Schema(description = "创建人ID")
    private Long createdBy;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "最后使用时间")
    private LocalDateTime lastUsedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "剩余天数")
    private Integer remainingDays;

    @Schema(description = "库存占用率")
    private BigDecimal usageRate;

    /**
     * 是否已消耗完（用完/过期/报废）
     */
    @Schema(description = "是否已消耗完")
    public Boolean getIfRunout() {
        if (status == null) {
            return false;
        }
        return status == MaterialBatchStatus.USED_UP ||
               status == MaterialBatchStatus.EXPIRED ||
               status == MaterialBatchStatus.SCRAPPED;
    }

    // ==================== 前端字段别名 ====================

    /**
     * inboundQuantity 别名（兼容前端）
     * 前端使用 inboundQuantity，后端使用 receiptQuantity
     */
    @JsonProperty("inboundQuantity")
    @Schema(description = "入库数量(前端别名)")
    public BigDecimal getInboundQuantity() {
        return receiptQuantity;
    }

    /**
     * inboundDate 别名（兼容前端）
     * 前端使用 inboundDate，后端使用 receiptDate
     */
    @JsonProperty("inboundDate")
    @Schema(description = "入库日期(前端别名)")
    public LocalDate getInboundDate() {
        return receiptDate;
    }

    /**
     * expiryDate 别名（兼容前端）
     * 前端使用 expiryDate，后端使用 expireDate
     */
    @JsonProperty("expiryDate")
    @Schema(description = "到期日期(前端别名)")
    public LocalDate getExpiryDate() {
        return expireDate;
    }

    /**
     * remainingQuantity 别名（兼容前端）
     * 前端使用 remainingQuantity，后端使用 currentQuantity
     */
    @JsonProperty("remainingQuantity")
    @Schema(description = "剩余数量(前端别名)")
    public BigDecimal getRemainingQuantity() {
        return currentQuantity;
    }
}
