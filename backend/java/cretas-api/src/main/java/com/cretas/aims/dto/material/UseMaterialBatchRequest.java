package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 使用原材料批次请求
 *
 * ⚠️ 这里原先那两个 {@code @NotNull/@DecimalMin} 一条都没生效 —— controller 的
 * {@code @RequestBody} 上没有 {@code @Valid}, 而真实契约横跨 URL 参数与 body 两条来源
 * (URL 传了 quantity 时 body 的 quantity 本就可以为空), 逐字段 bean validation 表达不了。
 * 数量的非正数校验实际由 {@code MaterialBatchServiceImpl#useBatchMaterial} 兜底(400)。
 * 此处只保留如实的文档, 不再摆着不执行的约束假装有校验。
 */
@Data
@Schema(description = "使用原材料批次请求")
public class UseMaterialBatchRequest {

    @Schema(description = "使用数量, 必须大于 0。与 URL 参数 quantity 二选一。",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private BigDecimal quantity;

    @Schema(description = "领料用途。MaterialConsumption 没有独立的 purpose 列, "
            + "由 controller 以「用途: xxx」前缀拼进 notes 一并落库。")
    private String purpose;

    @Schema(description = "生产计划ID, 落库到 MaterialConsumption.productionPlanId "
            + "(外键 → production_plans)。传不存在的 ID 会 404; 不关联计划时省略即可。")
    private String productionPlanId;

    @Schema(description = "备注, 落库到 MaterialConsumption.notes(与 purpose 拼接)。")
    private String notes;
}
