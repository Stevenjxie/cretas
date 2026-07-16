package com.cretas.aims.dto.production;

import com.cretas.aims.entity.enums.MixedBatchType;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 创建生产计划请求对象
 *
 * <p>同一 DTO 复用于「创建」({@code POST /production-plans}, {@code POST /production-plans/draft})
 * 和「编辑」({@code PUT /production-plans/{planId}}) 两条端点。两者共享的字段级约束落在 Bean
 * Validation 默认组 {@link jakarta.validation.groups.Default}, 两端点都用 {@code @Valid} 校验时都会生效。
 *
 * <p>{@link #plannedDate} 的 {@code @FutureOrPresent} 只对「创建」有意义 — 编辑一个尚未开始
 * 的计划需要允许把计划日期改到今天/过去几天以内(六扇门"系统不强制按计划日"软约束; 也允许纯粹改期到未来),
 * 因此该约束被单独分组到 {@link OnCreate}, 编辑端点仍用 {@code @Valid}(仅校验 Default 组)
 * 会自动跳过它, 不需要改任何其他字段的校验行为。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Schema(description = "创建生产计划请求")
public class CreateProductionPlanRequest {

    /**
     * 校验分组: 仅「创建」端点使用 (extends Default, 所以校验 OnCreate 组时 Default 组约束照常生效)。
     * 「编辑」端点继续用 {@code @Valid}(隐式 Default 组), 天然跳过只挂在 OnCreate 组下的约束
     * (目前只有 {@link #plannedDate} 的 {@code @FutureOrPresent})。
     */
    public interface OnCreate extends jakarta.validation.groups.Default {}

    @Schema(description = "产品类型ID", required = true)
    @NotNull(message = "产品类型不能为空")
    private String productTypeId;

    @Schema(description = "计划数量 (SAFETY_STOCK 存货生产时可为空; 其他来源类型必填且 > 0)")
    private BigDecimal plannedQuantity;

    @Schema(description = "计划成品单位快照；后端按产品单位权威解析，客户端值不会覆盖")
    private String plannedUnit;

    @Schema(description = "来源单据原始展示数量，例如销售订单 10 箱")
    private BigDecimal sourceDisplayQuantity;

    @Schema(description = "来源单据原始展示单位")
    private String sourceDisplayUnit;

    @Schema(description = "Workflow 报工/产出单位，不覆盖计划数量单位")
    private String workflowOutputUnit;

    @Schema(description = "计划日期", required = true)
    @NotNull(message = "计划日期不能为空")
    @FutureOrPresent(message = "计划日期不能是过去", groups = OnCreate.class)
    private LocalDate plannedDate;

    @Schema(description = "预计完成日期（默认为计划日期+1天）")
    private LocalDate expectedCompletionDate;

    @Schema(description = "客户订单号")
    @Size(max = 100, message = "客户订单号不能超过100个字符")
    private String customerOrderNumber;

    @Schema(description = "优先级(1-10)")
    @Min(value = 1, message = "优先级最小为1")
    @Max(value = 10, message = "优先级最大为10")
    private Integer priority = 5;

    @Schema(description = "预估材料成本")
    @DecimalMin(value = "0", message = "预估材料成本不能为负数")
    private BigDecimal estimatedMaterialCost;

    @Schema(description = "预估人工成本")
    @DecimalMin(value = "0", message = "预估人工成本不能为负数")
    private BigDecimal estimatedLaborCost;

    @Schema(description = "预估设备成本")
    @DecimalMin(value = "0", message = "预估设备成本不能为负数")
    private BigDecimal estimatedEquipmentCost;

    @Schema(description = "预估其他成本")
    @DecimalMin(value = "0", message = "预估其他成本不能为负数")
    private BigDecimal estimatedOtherCost;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;

    @Schema(description = "客户ID")
    private Integer customerId;

    @Schema(description = "原材料批次ID列表")
    private String[] materialBatchIds;

    @Schema(description = "计划类型 (FUTURE=未来计划, FROM_INVENTORY=基于库存)", example = "FROM_INVENTORY")
    private ProductionPlanType planType = ProductionPlanType.FROM_INVENTORY;

    @Schema(description = "是否使用自动计算转换率")
    private Boolean autoCalculateConversionRate = false;

    // ======= 工厂管理员扩展字段 =======

    @Schema(description = "预计工人数")
    @Min(value = 1, message = "预计工人数最小为1")
    @Max(value = 500, message = "预计工人数最大为500")
    private Integer estimatedWorkers;

    @Schema(description = "指派车间主管ID")
    private Long assignedSupervisorId;

    // ======= 调度员模块扩展字段 =======

    @Schema(description = "计划来源类型: CUSTOMER_ORDER/AI_FORECAST/SAFETY_STOCK/MANUAL/URGENT_INSERT")
    private PlanSourceType sourceType = PlanSourceType.MANUAL;

    @Schema(description = "关联订单ID（来自客户订单时；P0-12 后由 sourceOrderItemId 自动回填）")
    @Size(max = 50, message = "关联订单ID不能超过50个字符")
    private String sourceOrderId;

    /**
     * SP5 多 SO 合并工单: 该计划覆盖的全部销售订单 ID 列表.
     * <p>单 SO 场景: 传 [sourceOrderId] 或不传 (服务层自动从 sourceOrderId 回填).
     * <p>多 SO 合并场景: 前端"加号追加"后传多个 SO ID; 服务层校验每个 SO 属于本工厂且已财审.
     * <p>向后兼容: 不传时服务层从 sourceOrderId 补填, 不破坏现有单 SO 流程.
     */
    @Schema(description = "SP5 合并工单覆盖的全部销售订单ID列表 (多SO合并建单; 单SO场景可不传, 服务层自动回填)")
    private List<String> sourceOrderIds;

    @Schema(description = "raw-centric 多成品: 本计划要产的终端成品 productTypeId 列表; 传值时 productTypeId 必须是解析出的 workflow owner(原料)")
    private List<String> targetFinishedGoodIds;

    @Schema(description = "关联销售订单行ID (P0-12 字段粒度修正; CUSTOMER_ORDER 必填)")
    @Size(max = 50, message = "关联销售订单行ID不能超过50个字符")
    private String sourceOrderItemId;

    @Schema(description = "客户名称")
    @Size(max = 100, message = "客户名称不能超过100个字符")
    private String sourceCustomerName;

    @Schema(description = "工序名称")
    @Size(max = 200, message = "工序名称不能超过200个字符")
    private String processName;

    @Schema(description = "批次日期")
    private LocalDate batchDate;

    @Schema(description = "AI预测置信度 (0-100)，仅AI预测计划需要")
    @Min(value = 0, message = "AI置信度最小为0")
    @Max(value = 100, message = "AI置信度最大为100")
    private Integer aiConfidence;

    @Schema(description = "预测原因 (如: 冬季火锅需求+15%)")
    @Size(max = 255, message = "预测原因不能超过255个字符")
    private String forecastReason;

    @Schema(description = "预估工期（天），用于计算CR值")
    @Min(value = 1, message = "预估工期最小为1天")
    private Integer estimatedWorkDays;

    @Schema(description = "是否混批")
    private Boolean isMixedBatch = false;

    @Schema(description = "混批类型: SAME_MATERIAL/SAME_PROCESS")
    private MixedBatchType mixedBatchType;

    @Schema(description = "混批关联订单ID列表")
    private List<String> relatedOrders;

    /**
     * 免工序报工开关 (六扇门 Wave2 升级, V20261017_01).
     * <p>true = 批次级两点报工 (领料入+产出出, 人工不报登下一期, 成本只含料, 出成率=产出/领料);
     * false = 逐道工序级报工 (现状)。
     * <p>null (未指定) → 服务层对"新建计划"默认 true (六扇门 want); 产品 0 工序时 spawn 强制走两点。
     */
    @Schema(description = "免工序报工开关 (true=批次级两点报工 领料+产出; null=新建默认true)")
    private Boolean skipProcessReporting;

    /**
     * Round 9 Fix (R8-α Gap #3): Canvas V3 dynamic field values for production_plan.
     * Customer-configured fields (如: 客户订单号, QC 要求等级, 特殊工艺参数, 成品包装
     * 要求 etc.) get persisted via DynamicFieldService.setDynamicFields. Previously
     * dropped silently — the frontend Canvas form submitted them but the DTO had no slot.
     */
    @Schema(description = "Canvas 动态字段值 (Round 9 Fix)")
    private Map<String, Object> customFields;

}
