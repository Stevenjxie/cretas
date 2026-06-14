package com.cretas.aims.dto.disposal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新报废记录请求 DTO.
 *
 * <p>Rule 17.1 cleanup (Issue #384 batch 5): isolates the wire contract from
 * {@link com.cretas.aims.entity.DisposalRecord} persistence model for
 * {@code PUT /api/mobile/{factoryId}/disposal-records/{id}}.
 *
 * <p>Per {@link com.cretas.aims.service.DisposalRecordService#updateDisposalRecord},
 * only the following fields are mutable on an un-approved record (the service
 * iterates each one with an {@code if (... != null)} guard). All other entity
 * fields (approval state, audit timestamps, factory binding, IDs, etc.) are
 * NOT mutable through this endpoint.
 *
 * <p>Service-owned guard: throws {@code IllegalStateException} when
 * {@code isApproved == true} (already-approved records cannot be modified).
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "更新报废记录请求")
public class UpdateDisposalRecordRequest {

    @Schema(description = "报废数量", example = "100.50")
    @DecimalMin(value = "0.01", message = "报废数量必须大于0")
    private BigDecimal disposalQuantity;

    @Schema(description = "报废类型 (SCRAP/RECYCLE/RETURN/DONATE/DESTROY)")
    @Size(max = 30, message = "报废类型长度不能超过30个字符")
    private String disposalType;

    @Schema(description = "报废原因（详细描述）")
    private String disposalReason;

    @Schema(description = "处理方式说明")
    private String disposalMethod;

    @Schema(description = "预估损失金额")
    @PositiveOrZero(message = "预估损失金额不能为负数")
    private BigDecimal estimatedLoss;

    @Schema(description = "回收价值（如果可回收）")
    @PositiveOrZero(message = "回收价值不能为负数")
    private BigDecimal recoveryValue;

    @Schema(description = "证据图片 URL 列表（逗号分隔）")
    private String evidenceImages;

    @Schema(description = "备注")
    private String notes;
}
