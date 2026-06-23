package com.cretas.aims.dto.disposal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建报废记录请求 DTO.
 *
 * <p>Rule 17.1 cleanup (Issue #384 batch 5): isolates the wire contract from
 * {@link com.cretas.aims.entity.DisposalRecord} persistence model for
 * {@code POST /api/mobile/{factoryId}/disposal-records}.
 * Continues the sweep from PR #383 (batch 1: BomController),
 * PR #388 (batch 2: BOM /overhead), PR #390 (batch 3: ProcessingController.createBatch),
 * PR #391 (batch 4: ProcessingController.createMaterialReceipt).
 *
 * <p>Service-owned defaults filled by
 * {@link com.cretas.aims.service.DisposalRecordService#createDisposalRecord} —
 * <em>not</em> replicated by the controller mapper (single source of truth):
 * <ul>
 *   <li>{@code factoryId} — set in Controller from {@code @PathVariable}</li>
 *   <li>{@code disposalDate} — service defaults to {@code LocalDateTime.now()} if absent</li>
 *   <li>{@code isApproved} — service forces {@code false} on creation</li>
 *   <li>{@code approvedBy} / {@code approvedByName} / {@code approvalDate} —
 *       set later via {@code approveDisposal} flow, not on creation</li>
 *   <li>{@code actualLoss} — only set after approval / disposal completion</li>
 *   <li>{@code id} — DB IDENTITY</li>
 *   <li>{@code createdAt} / {@code updatedAt} / {@code deletedAt} —
 *       {@link com.cretas.aims.entity.BaseEntity} audit</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "创建报废记录请求")
public class CreateDisposalRecordRequest {

    @Schema(description = "报废数量", example = "100.50", required = true)
    @NotNull(message = "报废数量不能为空")
    @DecimalMin(value = "0.01", message = "报废数量必须大于0")
    private BigDecimal disposalQuantity;

    @Schema(description = "报废类型 (SCRAP/RECYCLE/RETURN/DONATE/DESTROY)", example = "SCRAP", required = true)
    @NotBlank(message = "报废类型不能为空")
    @Size(max = 30, message = "报废类型长度不能超过30个字符")
    private String disposalType;

    @Schema(description = "报废原因（详细描述）")
    private String disposalReason;

    @Schema(description = "报废日期 (可选, 服务端缺省 now)")
    private LocalDateTime disposalDate;

    @Schema(description = "处理方式说明")
    private String disposalMethod;

    @Schema(description = "质检记录ID（可选，如果是质检后直接报废）")
    @Size(max = 191, message = "质检记录ID长度不能超过191个字符")
    private String qualityInspectionId;

    @Schema(description = "返工记录ID（可选，如果是返工失败后报废）")
    private Long reworkRecordId;

    @Schema(description = "生产批次ID（针对成品报废）")
    private String productionBatchId;

    @Schema(description = "原材料批次ID（针对原料报废）")
    @Size(max = 191, message = "原材料批次ID长度不能超过191个字符")
    private String materialBatchId;

    @Schema(description = "成品库存批次ID（针对成品报损 → 扣减 FinishedGoodsBatch 可售库存）")
    @Size(max = 191, message = "成品库存批次ID长度不能超过191个字符")
    private String finishedGoodsBatchId;

    @Schema(description = "预估损失金额", example = "5000.00")
    @PositiveOrZero(message = "预估损失金额不能为负数")
    private BigDecimal estimatedLoss;

    @Schema(description = "回收价值（如果可回收）", example = "1500.00")
    @PositiveOrZero(message = "回收价值不能为负数")
    private BigDecimal recoveryValue;

    @Schema(description = "证据图片 URL 列表（逗号分隔）", example = "https://cdn.example.com/a.jpg,https://cdn.example.com/b.jpg")
    private String evidenceImages;

    @Schema(description = "备注")
    private String notes;
}
