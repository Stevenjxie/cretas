package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 消耗原材料批次请求
 *
 * ⚠️ 这里原先那两个 {@code @NotNull/@DecimalMin} 一条都没生效 —— controller 的
 * {@code @RequestBody} 上没有 {@code @Valid}, 而真实契约横跨 URL 参数与 body 两条来源
 * (URL 传了 quantity 时 body 的 quantity 本就可以为空), 逐字段 bean validation 表达不了。
 * 数量的非正数校验实际由 {@code MaterialBatchServiceImpl#consumeBatchMaterial} 兜底(400)。
 * 此处只保留如实的文档, 不再摆着不执行的约束假装有校验。
 */
@Data
@Schema(description = "消耗原材料批次请求")
public class ConsumeMaterialBatchRequest {

    @Schema(description = "消耗数量, 必须大于 0。与 URL 参数 quantity 二选一。",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private BigDecimal quantity;

    /**
     * 字段名是历史遗留。它落库到 {@code MaterialConsumption.productionPlanId}
     * (FK → production_plans), 不是加工批次。URL 参数请用 {@code productionPlanId}。
     */
    @Schema(description = "生产计划ID(字段名为历史遗留)。落库到 MaterialConsumption.productionPlanId, "
            + "外键指向 production_plans —— 不要传加工批次ID。URL 参数请用 productionPlanId。")
    private String processId;

    @Schema(description = "备注, 落库到 MaterialConsumption.notes。")
    private String notes;
}
