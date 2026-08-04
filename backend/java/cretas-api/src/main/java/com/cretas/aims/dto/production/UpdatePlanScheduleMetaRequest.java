package com.cretas.aims.dto.production;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 已开工计划仍可修改的**排产元数据**（四个软字段）。
 *
 * <p>与 {@link CreateProductionPlanRequest} 的全量更新分开的原因：全量更新会一并写入
 * 计划日期/计划数量/产品/来源单，这些在开工后改会与已经发生的事实脱节 ——
 * 批次日期进批次号与追溯、计划数量是结单与小结的比对基准、订单生产的数量本就来自
 * 销售订单行。所以「已开工能改什么」不是把守卫放宽一档，而是**换一个更窄的入参契约**：
 * 契约里根本没有那些字段，也就没有被顺手改掉的可能。
 *
 * <p><b>PUT 语义（全量覆盖这四个字段）</b>：四个字段一律按传入值写入，{@code null} 表示
 * 清空而不是「保持原值」。调用方必须每次都把四个值都带上。不做「非空才覆盖」的 PATCH 语义，
 * 是因为那样无法表达「清空指派主管 / 清空备注」，而两者都是真实需求。
 */
@Schema(description = "更新生产计划的排产元数据（开工后仍可改的四个软字段）")
public record UpdatePlanScheduleMetaRequest(
        @Schema(description = "预计完成日期；null 表示清空")
        LocalDate expectedCompletionDate,

        @Min(value = 0, message = "预计工人数不能为负")
        @Max(value = 9999, message = "预计工人数超出合理范围")
        @Schema(description = "预计工人数；null 表示清空", example = "3")
        Integer estimatedWorkers,

        @Schema(description = "指派主管的用户 ID，必须属于同一工厂；null 表示清空指派")
        Long assignedSupervisorId,

        @Size(max = 1000, message = "备注长度不能超过 1000 字")
        @Schema(description = "备注；null 表示清空")
        String notes) {
}
