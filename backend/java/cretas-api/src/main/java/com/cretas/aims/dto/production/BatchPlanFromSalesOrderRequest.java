package com.cretas.aims.dto.production;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 以销定产批量建计划请求 (六扇门 2026-06-24).
 *
 * <p>选一张销售订单 → 默认带出全部产品行 → 用户可在多选中取消不需要的行 →
 * 对每个保留的产品行各创建一张生产计划 (产品类型 + 数量取自该 SO 行)。
 * 计划级共享设置 (计划生产日 / 报工模式 / 主管 / 工人数 / 备注) 应用到所有生成的计划。
 *
 * <p>单产品退化 = itemIds 只有 1 个, 与原单条创建等价。
 */
@Data
@Schema(description = "以销定产批量建计划请求 — 选 SO 多产品行各建一张计划")
public class BatchPlanFromSalesOrderRequest {

    @jakarta.validation.constraints.Size(max = 128, message = "创建请求幂等键不能超过128个字符")
    @Schema(description = "本次批量创建的客户端幂等键")
    private String clientRequestId;

    @Schema(description = "确认短时间内创建内容相同但业务上独立的新计划集合")
    private Boolean confirmPotentialDuplicate = false;

    @NotBlank(message = "销售订单ID不能为空")
    @Schema(description = "来源销售订单ID")
    private String sourceOrderId;

    @NotEmpty(message = "至少选择一个产品行")
    @Schema(description = "选中的销售订单产品行ID列表 (每行各建一张计划)")
    private List<String> itemIds;

    @jakarta.validation.constraints.NotNull(message = "计划生产日不能为空")
    @Schema(description = "计划生产日 (共享到所有生成的计划)")
    private LocalDate plannedDate;

    @jakarta.validation.constraints.NotNull(message = "批次日期不能为空")
    @Schema(description = "实际转批次日期 (共享到所有生成的计划，与计划生产日相互独立)")
    private LocalDate batchDate;

    @Schema(description = "预计完成日 (可选, 共享)")
    private LocalDate expectedCompletionDate;

    @Schema(description = "预计工人数 (可选, 共享)")
    private Integer estimatedWorkers;

    @Schema(description = "指派主管ID (可选, 共享)")
    private Long assignedSupervisorId;

    @Schema(description = "备注 (可选, 共享)")
    private String notes;

    @Schema(description = "免工序报工开关 (true=批次级两点报工; null=工厂默认; 共享)")
    private Boolean skipProcessReporting;
}
