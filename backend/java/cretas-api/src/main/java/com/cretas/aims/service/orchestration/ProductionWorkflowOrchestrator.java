package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.inventory.TransferService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ProductionWorkflowOrchestrator {

    private final BomExpansionService bomExpansionService;
    private final TransferService transferService;
    private final ProductionPlanService productionPlanService;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final InternalTransferRepository transferRepository;

    /**
     * T143: 物料单位换算器. 字段注入 (required=false) 不破坏 @RequiredArgsConstructor 与
     * 反射单测 ({@code ProductionWorkflowOrchestratorUnitConversionTest}). 未注入时
     * 退回 private convertUnit (g↔kg) — 但调拨数量换算应优先走 converter (支持 箱).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

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
        log.info("开始为生产计划生成调拨单: planId={}, product={}, qty={}",
                planId, plan.getProductTypeId(), plan.getPlannedQuantity());

        // Step 2: BOM展开 — FUTURE TOOL: bom_expansion_calculate
        List<MaterialRequirement> requirements = bomExpansionService.expandBOM(
                factoryId, plan.getProductTypeId(), plan.getPlannedQuantity());

        if (requirements.isEmpty()) {
            throw new BusinessException(409, "该产品未配置转换率，无法生成调拨单")
                    .withHint("请在 [生产管理 → BOM 成本管理 → 转换率 tab] 为该产品添加原料 → 产品的转换率配置 (conversionRate)")
                    .withHintTarget("productTypeId");
        }

        // Step 3: 库存校验 (可选，记录日志但不阻断)
        MaterialCheckResult check = bomExpansionService.checkMaterialAvailability(factoryId, requirements);
        if (!check.isAllSatisfied()) {
            log.warn("部分原料库存不足，仍生成调拨单: planId={}, shortfalls={}",
                    planId, check.getShortfalls().size());
        }

        // Step 4: 构建调拨单 — FUTURE TOOL: transfer_create
        String target = targetFactoryId != null ? targetFactoryId : factoryId;
        CreateTransferRequest request = buildTransferRequest(factoryId, target, plan, requirements);
        InternalTransfer transfer = transferService.createTransfer(factoryId, request, userId);

        // Step 4.5: 设置 production_plan_id 关联 — 必须先持久化再调 requestTransfer
        transfer.setProductionPlanId(planId);
        transferRepository.save(transfer);

        // Step 5: 自动提交申请 — FUTURE TOOL: transfer_approve (action=request)
        transfer = transferService.requestTransfer(factoryId, transfer.getId(), userId);

        log.info("调拨单生成成功: transferId={}, planId={}, items={}",
                transfer.getId(), planId, requirements.size());
        return transfer;
    }

    private CreateTransferRequest buildTransferRequest(
            String sourceFactoryId, String targetFactoryId,
            ProductionPlanDTO plan, List<MaterialRequirement> requirements) {

        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType("HQ_TO_BRANCH");
        request.setTargetFactoryId(targetFactoryId);
        request.setTransferDate(LocalDate.now().plusDays(1)); // 默认次日调拨
        request.setExpectedArrivalDate(LocalDate.now().plusDays(1));
        request.setRemark(String.format("生产计划自动生成 | 计划号: %s | 产品: %s | 计划量: %s",
                plan.getPlanNumber(), plan.getProductTypeId(), plan.getPlannedQuantity()));

        List<CreateTransferRequest.TransferItemDTO> items = new ArrayList<>();
        for (MaterialRequirement req : requirements) {
            CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
            item.setItemType(TransferItemType.RAW_MATERIAL.name());
            item.setMaterialTypeId(req.getMaterialTypeId());
            item.setItemName(req.getMaterialTypeName());

            // 从 RawMaterialType 获取目标单位
            String targetUnit = "kg"; // 默认
            try {
                RawMaterialType mt = rawMaterialTypeRepository.findById(req.getMaterialTypeId()).orElse(null);
                if (mt != null && mt.getUnit() != null) {
                    targetUnit = mt.getUnit();
                }
            } catch (Exception e) {
                log.debug("获取原料单位失败: {}", req.getMaterialTypeId());
            }

            // D3 (2026-05-10 客户会议) + T143: BOM 配方层用 g/箱, 仓库 / 调拨层用 targetUnit (RawMaterialType.unit).
            // 调拨数量必须换算到 targetUnit, 否则会出现 "217 箱" (g 当 箱) 这种错误数量+错误单位.
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
     * D3 单位换算 (g↔kg / ml↔L 1:1000, 其他单位返回 null 不换算).
     *
     * <p>T143 (B7 collapse): 不再内联硬编码 1:1000 算术, 委托给共享的
     * {@link UnitConversionService} (单一事实源, 避免与 BomItem/库存出库/采购入库 drift).
     * {@code UnitConversionService} 无状态无依赖, 反射单测可直接 new 一个调用.
     *
     * @param value      原值
     * @param fromUnit   源单位 (如 "g")
     * @param toUnit     目标单位 (如 "kg")
     * @return 换算后的值, 不支持的换算返 null
     */
    private BigDecimal convertUnit(BigDecimal value, String fromUnit, String toUnit) {
        return UNIT_CONVERSION.convert(value, fromUnit, toUnit);
    }

    /** B7 collapse: 共享换算逻辑 (无状态), 取代原 private 1:1000 硬编码副本. */
    private static final UnitConversionService UNIT_CONVERSION = new UnitConversionService();
}
