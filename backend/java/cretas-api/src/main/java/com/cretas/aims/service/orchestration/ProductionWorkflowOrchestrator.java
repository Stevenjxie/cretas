package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.inventory.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产流程编排器
 *
 * 一期：硬编码步骤调用
 * 二期：从 WorkflowDefinition JSON 读取步骤，驱动 ToolRegistry 执行
 *
 * 当前编排流程：
 *   排产确认 → BOM展开 → 库存校验 → 创建调拨单 → 自动提交
 */
@Slf4j
@Service
public class ProductionWorkflowOrchestrator {

    private final BomExpansionService bomExpansionService;
    private final TransferService transferService;
    private final ProductionPlanService productionPlanService;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final InternalTransferRepository transferRepository;
    private final UnitConversionService unitConversionService;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionWorkflowOrchestrator(
            BomExpansionService bomExpansionService,
            TransferService transferService,
            ProductionPlanService productionPlanService,
            RawMaterialTypeRepository rawMaterialTypeRepository,
            InternalTransferRepository transferRepository,
            UnitConversionService unitConversionService) {
        this.bomExpansionService = bomExpansionService;
        this.transferService = transferService;
        this.productionPlanService = productionPlanService;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
        this.transferRepository = transferRepository;
        this.unitConversionService = unitConversionService;
    }


    /**
     * T143: 物料单位换算器. 字段注入 (required=false) 不破坏 @RequiredArgsConstructor 与
     * 反射单测 ({@code ProductionWorkflowOrchestratorUnitConversionTest}). 未注入时
     * 退回 private convertUnit (g↔kg) — 但调拨数量换算应优先走 converter (支持 箱).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

    /**
     * T144: 调拨目标单位以 MaterialBatch.quantityUnit (称重口径 kg) 为准. required=false 兼容单测.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.MaterialBatchRepository materialBatchRepository;

    /**
     * 排产确认 → 自动生成调拨单
     *
     * @param factoryId 工厂ID (调出方=物流仓 factoryId)
     * @param planId 生产计划ID
     * @param targetFactoryId 调入方工厂ID (工厂仓)，null 则默认同 factoryId
     * @param userId 操作人
     * @return 创建的调拨单
     */
    @Transactional
    public InternalTransfer generateTransferFromPlan(
            String factoryId, String planId, String targetFactoryId, Long userId) {

        // Step 1: 加载计划 — FUTURE TOOL: production_plan_query
        ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
        if (plan == null) {
            throw new BusinessException(404, "生产计划不存在: " + planId)
                    .withHint("请刷新生产计划列表后重新选择").withHintTarget("planId");
        }

        // One production plan owns one open rolling transfer. Re-opening the action
        // must return the same draft/approval task instead of creating duplicate
        // quantities for the same production preparation.
        List<InternalTransfer> existing = transferRepository
                .findBySourceFactoryIdAndProductionPlanIdAndStatusInOrderByCreatedAtDesc(
                        factoryId, planId,
                        List.of(TransferStatus.DRAFT, TransferStatus.REQUESTED, TransferStatus.APPROVED));
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        log.info("开始为生产计划生成调拨单: planId={}, product={}, qty={}",
                planId, plan.getProductTypeId(), plan.getPlannedQuantity());

        // SAFETY_STOCK 可不预设计划产量。null 以 1 个成品的 BOM 用量初始化滚动草稿，
        // 仓库在提交 OA 前调整为实际调拨量；显式 0/负数仍 fail-closed，避免零数量死单。
        BigDecimal plannedQty = plan.getPlannedQuantity();
        if (plannedQty != null && plannedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "该计划的计划量为 0，无法生成调拨单")
                    .withHint("存货生产(按库存生产)的产量在「逐道录入/小结」时才确定，不需要预先生成调拨单；"
                            + "若确需备料请直接使用逐道录入，或将本计划改为有明确数量的销售订单计划。")
                    .withHintTarget("plannedQuantity")
                    .withSeverity("BLOCKING");
        }
        BigDecimal transferBasisQuantity = plannedQty != null ? plannedQty : BigDecimal.ONE;

        // Step 2: BOM展开 — FUTURE TOOL: bom_expansion_calculate
        List<MaterialRequirement> requirements = bomExpansionService.expandBOM(
                factoryId, plan.getProductTypeId(), transferBasisQuantity);

        if (requirements.isEmpty()) {
            throw new BusinessException(409, "该产品未配置转换率，无法生成调拨单")
                    .withHint("请在 [生产管理 → BOM 成本管理 → 转换率 tab] 为该产品添加原料 → 产品的转换率配置 (conversionRate)")
                    .withHintTarget("productTypeId");
        }

        // 🔒 防呆 Rule 1 (深度防御): 即便计划量>0, 若 BOM 展开后所有需求项数量都 ≤ 0
        // (例如配方项均为 0), 同样拒绝, 绝不落地全 0 数量的死调拨单.
        boolean hasPositiveRequirement = requirements.stream().anyMatch(r -> {
            BigDecimal q = r.getRequiredQuantity();
            return q != null && q.compareTo(BigDecimal.ZERO) > 0;
        });
        if (!hasPositiveRequirement) {
            throw new BusinessException(400, "按 BOM 展开后所需物料数量均为 0，无法生成调拨单")
                    .withHint("请检查该产品的 BOM/转换率配置是否为 0，或计划量是否有效。")
                    .withHintTarget("productTypeId")
                    .withSeverity("BLOCKING");
        }

        // Step 3: 库存校验 (可选，记录日志但不阻断)
        MaterialCheckResult check = bomExpansionService.checkMaterialAvailability(factoryId, requirements);
        if (!check.isAllSatisfied()) {
            log.warn("部分原料库存不足，仍生成调拨单: planId={}, shortfalls={}",
                    planId, check.getShortfalls().size());
        }

        // Step 4: 构建调拨单 — FUTURE TOOL: transfer_create
        String target = targetFactoryId != null ? targetFactoryId : factoryId;
        CreateTransferRequest request = buildTransferRequest(
                factoryId, target, plan, requirements, plannedQty == null);
        InternalTransfer transfer = transferService.createTransfer(factoryId, request, userId);

        // Step 4.5: Persist the plan link. The task stays DRAFT so warehouse staff
        // can continuously adjust the calculated rolling quantity before it enters OA.
        transfer.setProductionPlanId(planId);
        transferRepository.save(transfer);

        log.info("调拨单生成成功: transferId={}, planId={}, items={}",
                transfer.getId(), planId, requirements.size());
        return transfer;
    }

    private CreateTransferRequest buildTransferRequest(
            String sourceFactoryId, String targetFactoryId,
            ProductionPlanDTO plan, List<MaterialRequirement> requirements,
            boolean rollingBaseline) {

        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType("HQ_TO_BRANCH");
        request.setTargetFactoryId(targetFactoryId);
        request.setTransferDate(LocalDate.now().plusDays(1)); // 默认次日调拨
        request.setExpectedArrivalDate(LocalDate.now().plusDays(1));
        String remark = String.format("生产计划自动生成 | 计划号: %s | 产品: %s | 计划量: %s",
                plan.getPlanNumber(), plan.getProductTypeId(), plan.getPlannedQuantity());
        if (rollingBaseline) {
            remark += " | 滚动备料基准：按 1 个成品的 BOM 用量初始化，提交审批前请调整为本次实际调拨量";
        }
        request.setRemark(remark);

        List<CreateTransferRequest.TransferItemDTO> items = new ArrayList<>();
        for (MaterialRequirement req : requirements) {
            CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
            item.setItemType(TransferItemType.RAW_MATERIAL.name());
            item.setMaterialTypeId(req.getMaterialTypeId());
            item.setItemName(req.getMaterialTypeName());

            // T144: 调拨目标单位 = 称重批次单位 (MaterialBatch.quantityUnit, e.g. kg), 不是
            // RawMaterialType.unit (箱). 原料称重入库, 调拨的也是称重量. 无可用批次时回退
            // RawMaterialType.unit 再回退 "kg".
            String targetUnit = resolveTransferUnit(sourceFactoryId, req.getMaterialTypeId());

            // D3 (2026-05-10 客户会议) + T144: BOM 配方层用 g, 仓库 / 调拨层用称重批次单位 (kg).
            // 调拨数量必须换算到 targetUnit (g↔kg), 否则会出现 "217 箱" (g 当 箱) 这种错误数量+错误单位.
            BigDecimal quantity = req.getRequiredQuantity();
            String sourceUnit = req.getSourceUnit();
            String finalUnit = targetUnit;
            if (quantity != null && sourceUnit != null && targetUnit != null
                    && !sourceUnit.equalsIgnoreCase(targetUnit)) {
                if (materialUomConverter != null) {
                    // T143: 优先走 converter (支持 箱↔kg via MaterialPackagingHierarchy + 抄码 + fail-loud).
                    com.cretas.aims.service.uom.MaterialUomConverter.ConversionResult conv =
                            materialUomConverter.toComparableQuantity(
                                    req.getMaterialTypeId(), quantity, sourceUnit, targetUnit);
                    if (conv.isConverted()) {
                        log.info("T143 调拨单位换算: material={}, {}{} → {}{}",
                                req.getMaterialTypeId(), quantity, sourceUnit, conv.getQuantity(), targetUnit);
                        quantity = conv.getQuantity();
                        finalUnit = targetUnit;
                    } else if (conv.isAbacaSkip()) {
                        // 抄码料: 无确定箱重, 不能算出 箱 数量. 用原始 sourceUnit 量 + sourceUnit 标签
                        // (入库时按实际称重逐箱录入). 绝不输出错误的 箱 数量.
                        log.info("T143 抄码料 {} 调拨保留源单位 {}{} (入库逐箱称重)",
                                req.getMaterialTypeId(), quantity, sourceUnit);
                        finalUnit = sourceUnit;
                    } else {
                        // UNCONVERTIBLE: 非抄码 + 无装箱规格 → fail-loud, 绝不输出错误单位的调拨单.
                        throw new BusinessException(409,
                                String.format("原料「%s」未配置装箱规格(%s↔%s)，无法生成调拨单，请先配置",
                                        req.getMaterialTypeName() != null ? req.getMaterialTypeName() : req.getMaterialTypeId(),
                                        targetUnit, sourceUnit))
                                .withCode("MATERIAL_UOM_UNCONFIGURED")
                                .withHint("请前往「原料管理」为该原料配置装箱规格(箱↔kg)后再生成调拨单")
                                .withHintTarget(req.getMaterialTypeId())
                                .withSeverity("BLOCKING");
                    }
                } else {
                    // Converter 未注入 (单测): 退回旧 g↔kg 同维度换算, 不支持的换算保留原值原单位.
                    BigDecimal converted = convertUnit(quantity, sourceUnit, targetUnit);
                    if (converted != null) {
                        quantity = converted;
                        finalUnit = targetUnit;
                    } else {
                        finalUnit = sourceUnit;  // 不支持: 保留源单位, 不伪装成 targetUnit
                    }
                }
            }

            item.setQuantity(quantity);
            item.setUnit(finalUnit);

            items.add(item);
        }
        request.setItems(items);
        return request;
    }

    /**
     * T144: 解析调拨目标单位 = 源工厂 AVAILABLE 批次的 {@code MaterialBatch.quantityUnit} (称重口径 kg).
     * 无可用批次时回退 {@code RawMaterialType.unit}, 再回退默认 "kg".
     */
    private String resolveTransferUnit(String factoryId, String materialTypeId) {
        if (materialTypeId != null && materialBatchRepository != null) {
            try {
                java.util.List<String> units = materialBatchRepository
                        .findStockUnitsByMaterialType(factoryId, materialTypeId);
                if (units != null && !units.isEmpty()) {
                    if (units.size() > 1) {
                        log.warn("物料 {} 可用批次单位混用 {}, 调拨取最常见 {}", materialTypeId, units, units.get(0));
                    }
                    return units.get(0);
                }
            } catch (Exception e) {
                log.debug("读取批次库存单位失败: {} ({})", materialTypeId, e.getMessage());
            }
        }
        // 无可用批次: 回退 RawMaterialType.unit
        try {
            RawMaterialType mt = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
            if (mt != null && mt.getUnit() != null) {
                return mt.getUnit();
            }
        } catch (Exception e) {
            log.debug("获取原料单位失败: {}", materialTypeId);
        }
        return "kg";
    }

    /**
     * D3 单位换算 (g↔kg / ml↔L 1:1000, 其他单位返回 null 不换算).
     *
     * <p>T143 (B7 collapse): 不再内联硬编码 1:1000 算术, 委托给共享的
     * {@link UnitConversionService} (单一事实源, 避免与 BomRecipeItem/库存出库/采购入库 drift).
     * {@code UnitConversionService} 无状态无依赖, 反射单测可直接 new 一个调用.
     *
     * @param value      原值
     * @param fromUnit   源单位 (如 "g")
     * @param toUnit     目标单位 (如 "kg")
     * @return 换算后的值, 不支持的换算返 null
     */
    private BigDecimal convertUnit(BigDecimal value, String fromUnit, String toUnit) {
        return unitConversionService.convert(value, fromUnit, toUnit);
    }

}
