package com.cretas.aims.dto.processing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 创建生产批次请求 DTO.
 *
 * <p>Rule 17.1 cleanup (PR #370 audit, Issue #384 batch 3): isolates wire
 * contract from {@link com.cretas.aims.entity.ProductionBatch} persistence
 * model. The entity carries hard {@code @NotNull} / {@code @NotBlank} on
 * {@code quantity} / {@code unit} that the {@link
 * com.cretas.aims.service.impl.ProcessingServiceImpl#createBatch} method
 * defaults <em>after</em> validation — binding the entity directly forces
 * the FE to send phantom values or fail with 400.
 *
 * <p>Fields excluded from this DTO and managed elsewhere:
 * <ul>
 *   <li>{@code id} — DB IDENTITY auto-generated</li>
 *   <li>{@code factoryId} — auto-filled from {@code @PathVariable}</li>
 *   <li>{@code status} — service forces {@link
 *       com.cretas.aims.entity.enums.ProductionBatchStatus#PLANNED}</li>
 *   <li>{@code createdAt} / {@code updatedAt} / {@code deletedAt} —
 *       {@link com.cretas.aims.entity.BaseEntity} audit</li>
 *   <li>{@code productName} default — service fills "待设置产品名称" if null/empty</li>
 *   <li>{@code quantity} default — service fills from {@code plannedQuantity}
 *       or {@code BigDecimal.ZERO} if null</li>
 *   <li>{@code unit} default — service fills "kg" if null/empty</li>
 *   <li>{@code qualityStatus} / {@code startTime} / {@code endTime} /
 *       {@code actualQuantity} / {@code goodQuantity} / {@code defectQuantity}
 *       — set by downstream state-machine transitions (start / complete)</li>
 *   <li>{@code totalCost} / {@code unitCost} / {@code yieldRate} /
 *       {@code efficiency} / {@code workDurationMinutes} — computed by
 *       {@code calculateMetrics()} on update</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "创建生产批次请求")
public class CreateProductionBatchRequest {

    @Schema(description = "批次号", example = "BATCH-20260511-001", required = true)
    @NotBlank(message = "批次号不能为空")
    @Size(max = 50, message = "批次号长度不能超过50个字符")
    private String batchNumber;

    @Schema(description = "生产计划ID (可选, 关联 ProductionPlan)")
    @Size(max = 191, message = "生产计划ID长度不能超过191个字符")
    private String productionPlanId;

    @Schema(description = "产品类型ID (关联 ProductType.id)", required = true)
    @NotBlank(message = "产品类型ID不能为空")
    @Size(max = 100, message = "产品类型ID长度不能超过100个字符")
    private String productTypeId;

    @Schema(description = "产品名称 (可选, 服务端缺省填充 '待设置产品名称')")
    @Size(max = 100, message = "产品名称长度不能超过100个字符")
    private String productName;

    @Schema(description = "计划数量 (可选)")
    @PositiveOrZero(message = "计划数量不能为负数")
    private BigDecimal plannedQuantity;

    @Schema(description = "数量 (可选, 服务端缺省回退到 plannedQuantity 或 0)")
    @PositiveOrZero(message = "数量不能为负数")
    private BigDecimal quantity;

    @Schema(description = "计量单位 (可选, 服务端缺省填充 'kg')")
    @Size(max = 20, message = "计量单位长度不能超过20个字符")
    private String unit;

    @Schema(description = "生产线/设备ID (关联 FactoryEquipment.id, Long 类型)")
    private Long equipmentId;

    @Schema(description = "生产线/设备名称")
    @Size(max = 100, message = "设备名称长度不能超过100个字符")
    private String equipmentName;

    @Schema(description = "负责人ID")
    private Long supervisorId;

    @Schema(description = "负责人姓名")
    @Size(max = 50, message = "负责人姓名长度不能超过50个字符")
    private String supervisorName;

    @Schema(description = "操作工人数")
    @PositiveOrZero(message = "工人数不能为负数")
    private Integer workerCount;

    @Schema(description = "原料成本 (可选, 创建阶段可预估)")
    @PositiveOrZero(message = "原料成本不能为负数")
    private BigDecimal materialCost;

    @Schema(description = "人工成本 (可选)")
    @PositiveOrZero(message = "人工成本不能为负数")
    private BigDecimal laborCost;

    @Schema(description = "设备成本 (可选)")
    @PositiveOrZero(message = "设备成本不能为负数")
    private BigDecimal equipmentCost;

    @Schema(description = "其他成本 (可选)")
    @PositiveOrZero(message = "其他成本不能为负数")
    private BigDecimal otherCost;

    @Schema(description = "备注")
    private String notes;

    @Schema(description = "是否需要拍照证据 (Sprint 2 S2-4)", defaultValue = "false")
    private Boolean photoRequired;

    @Schema(description = "关联的 SOP 配置 ID")
    @Size(max = 50, message = "SOP 配置 ID 长度不能超过50个字符")
    private String sopConfigId;

    @Schema(description = "AI 配置的自定义字段 (JSON, 例: {\"drying_temp\": 85})")
    private Map<String, Object> customFields;

    // ========== SP10 研发试制扩字段 (feedback_dto_roundtrip_silent_drop fix) ==========

    @Schema(description = "是否为研发试制批次 (SP10, 默认 false)", defaultValue = "false")
    private Boolean isTrial;

    @Schema(description = "试制批次关联的样品 ID (FK → product_samples.id, SP10)")
    @Size(max = 191, message = "试制样品ID长度不能超过191个字符")
    private String trialSampleId;
}
