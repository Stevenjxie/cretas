package com.cretas.aims.dto.label;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SP4-T5: 扫描物料批次标签响应 DTO.
 *
 * <p>RN 扫码枪扫描物料标签后，获取批次核心信息 + 标签状态。
 * VOIDED 状态时后端返回 HTTP 400 (而非 200) 避免下游误用已废标签。
 *
 * <p>A5 补缺: 响应包含厂号/产地/重量，满足六扇门仓管扫码弹窗需求。
 *
 * @since SP4 (一物一码补缺)
 */
@Data
@Builder
@Schema(description = "扫描物料批次标签响应")
public class MaterialBatchLabelScanResponse {

    // ──────── 标签元数据 ────────

    @Schema(description = "标签 ID")
    private String labelId;

    @Schema(description = "标签编码 (扫描值)")
    private String labelCode;

    @Schema(description = "标签状态: ACTIVE / PRINTED / APPLIED / VOIDED")
    private String labelStatus;

    @Schema(description = "追溯码 (可选, 二维码内容)")
    private String traceCode;

    // ──────── 物料批次关联 ────────

    @Schema(description = "关联物料批次 ID")
    private String batchId;

    @Schema(description = "批次号 (batchNumber)")
    private String batchNumber;

    @Schema(description = "物料类型名称 (product / raw-material name)")
    private String materialName;

    @Schema(description = "物料规格")
    private String specification;

    @Schema(description = "厂号 (factory_number, SP4-T1 新增)")
    private String factoryNumber;

    @Schema(description = "产地 (origin_place, SP4-T1 新增)")
    private String originPlace;

    // ──────── 数量 / 重量 (A5 补缺) ────────

    @Schema(description = "入库数量 (receipt_quantity). 缺值传 null，不补 0")
    private BigDecimal receiptQuantity;

    @Schema(description = "剩余可用数量 = receipt - used - reserved. 缺值传 null")
    private BigDecimal remainingQuantity;

    @Schema(description = "数量单位 (quantity_unit), e.g. \"kg\" / \"箱\"")
    private String quantityUnit;

    // ──────── 时间信息 ────────

    @Schema(description = "入库 / 批次创建时间")
    private LocalDateTime batchCreatedAt;

    @Schema(description = "标签创建时间")
    private LocalDateTime labelCreatedAt;
}
