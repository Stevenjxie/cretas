package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.ImportResult;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.DeliveryWarnDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanImportDTO;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.utils.ExcelUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产计划服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class ProductionPlanServiceImpl implements ProductionPlanService {
    private static final Logger log = LoggerFactory.getLogger(ProductionPlanServiceImpl.class);

    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final com.cretas.aims.repository.ProcessTaskRepository processTaskRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final ProductionPlanBatchUsageRepository planBatchUsageRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductionPlanMapper productionPlanMapper;
    private final ConversionRepository conversionRepository;
    private final SchedulingService schedulingService;
    private final ProductionLineRepository productionLineRepository;
    private final UserRepository userRepository;
    private final ExcelUtil excelUtil;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    /**
     * PR #289 §B3 (2026-05-10 客户对接): 开始生产前的原料库存校验.
     * Optional 注入: 单测时可以省略 (无 BOM 配置即跳过校验, 不破坏现有行为).
     */
    private final BomService bomService;

    /** Sprint 3 Track-F: unified cross-business link service (double-write w/ sourceOrderId). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LinkArrayService linkArrayService;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public ProductionPlanServiceImpl(
            ProductionPlanRepository productionPlanRepository,
            ProductionBatchRepository productionBatchRepository,
            com.cretas.aims.repository.ProcessTaskRepository processTaskRepository,
            MaterialBatchRepository materialBatchRepository,
            MaterialConsumptionRepository materialConsumptionRepository,
            ProductionPlanBatchUsageRepository planBatchUsageRepository,
            ProductTypeRepository productTypeRepository,
            ProductionPlanMapper productionPlanMapper,
            ConversionRepository conversionRepository,
            SchedulingService schedulingService,
            ProductionLineRepository productionLineRepository,
            UserRepository userRepository,
            ExcelUtil excelUtil,
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository salesOrderItemRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BomService bomService) {
        this.productionPlanRepository = productionPlanRepository;
        this.productionBatchRepository = productionBatchRepository;
        this.processTaskRepository = processTaskRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.materialConsumptionRepository = materialConsumptionRepository;
        this.planBatchUsageRepository = planBatchUsageRepository;
        this.productTypeRepository = productTypeRepository;
        this.productionPlanMapper = productionPlanMapper;
        this.conversionRepository = conversionRepository;
        this.schedulingService = schedulingService;
        this.productionLineRepository = productionLineRepository;
        this.userRepository = userRepository;
        this.excelUtil = excelUtil;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.bomService = bomService;
    }

    /** SP2 二次加工: WIP 半成品库存扣减. required=false 避免循环依赖风险. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;

    /** SP12 T3: 生产撤回审批流引擎. required=false 兼容无 WorkflowEngine 环境 (e.g. 单测). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.WorkflowEngineService workflowEngine;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Round 9 Fix (R8-α Gap #3): Canvas dynamic field persistence for production_plan. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    /**
     * T143: 物料单位换算器 (箱↔kg via MaterialPackagingHierarchy). required=false 兼容单测:
     * 未注入时 B3 库存校验退回旧的同单位比较 (历史 F001 RPF 路径单位一致, 不受影响).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

    /**
     * T144: 物料库存单位改读 MaterialBatch.quantityUnit (称重批次单位, e.g. kg) 而非
     * RawMaterialType.unit (箱). 保留 rawMaterialTypeRepository 仅作 fallback (无可用批次时).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * 完工链 GAP 3/4 (F006 — 2026-06-02): 转批次时从 product_work_processes 模板 spawn 工序任务.
     * {@code WorkProcessTaskServiceImpl} 不注入 ProductionPlanService → 无循环依赖, 普通注入即可.
     * required=false 兼容单测 (反射注入 mock) 与无工序配置场景.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;

    /**
     * Fable 审计修复 (2026-06-11 — 多租户安全红线): 工厂级"免工序报工默认值".
     *
     * <p>createProductionPlan 对 skipProcessReporting=null 的新建计划解析为"该工厂的默认值"
     * (F006=true 两点, 其他工厂=false 逐道), 取代旧的"全系统 null→true"过度泛化。
     * required=false 兼容单测 (反射注入或不注入); 不注入 / 查不到 → 兜底 false (安全=逐道)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.FactorySettingsRepository factorySettingsRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementRepository productionSettlementRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementLaborRepository productionSettlementLaborRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionTransitLedgerRepository productionTransitLedgerRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WarehouseResolver warehouseResolver;

    /**
     * 解析某工厂的"免工序报工默认值" (Fable 审计修复 — 多租户安全).
     *
     * <p>仅当 createProductionPlan 的 request.skipProcessReporting 为 null (调用方未显式指定,
     * 如 RN/AI 客户端) 时使用。显式 true/false 一律尊重调用方。
     *
     * <p>多租户安全: 查询带 factoryId, 一个工厂的配置不影响另一个;
     * 无 settings 行 / 列为 null → false (逐道, 安全默认, 保留溯源/成本/出成率/自学习/人效)。
     */
    private boolean resolveSkipProcessReportingDefault(String factoryId) {
        if (factorySettingsRepository == null) {
            return false; // 单测无注入 → 安全默认逐道
        }
        try {
            Boolean def = factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(factoryId);
            return Boolean.TRUE.equals(def);
        } catch (Exception e) {
            log.warn("解析工厂免工序报工默认值失败, 兜底逐道 (false): factoryId={}, err={}", factoryId, e.getMessage());
            return false;
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public boolean getSkipProcessReportingDefault(String factoryId) {
        return resolveSkipProcessReportingDefault(factoryId);
    }

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "production_plan", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    /**
     * PR #289 §B3 (2026-05-10 客户对接): 开始生产前的原料库存校验.
     *
     * <p>客户原话 (line 35): "那这个开始的话点的时候会有一个判断吗就是我的库存够不够…加一个我觉得还是
     * 加一个不然万一是不就得合对一下是吧"
     *
     * <p>校验逻辑:
     * <ol>
     *   <li>拉取 BOM items (按 productTypeId)</li>
     *   <li>对每个 BOM item 计算需求量: bomItem.getActualQuantity() (已含出成率) × plan.plannedQuantity</li>
     *   <li>查询该 materialType 在该 factory 的可用库存合计 (AVAILABLE 状态, receipt - used - reserved)</li>
     *   <li>若任一原料 available &lt; required → 抛 BusinessException 列出所有缺口</li>
     * </ol>
     *
     * <p>边界:
     * <ul>
     *   <li>无 BOM 配置 (BOM 空 list) → skip 校验, 仍允许开始 (生产可能不需要原料, 或客户尚未配置)</li>
     *   <li>plan.plannedQuantity == null → skip (历史数据兼容)</li>
     *   <li>BomService 未注入 (单测场景) → skip</li>
     *   <li>stock 恰好等于 required → 通过 (边界严格不阻断)</li>
     * </ul>
     */
    private void validateMaterialStockSufficient(String factoryId, ProductionPlan plan) {
        if (bomService == null) {
            log.debug("BomService 未注入, 跳过 B3 库存校验");
            return;
        }
        if (plan.getProductTypeId() == null || plan.getProductTypeId().isBlank()) {
            log.debug("生产计划无 productTypeId, 跳过 B3 库存校验: planId={}", plan.getId());
            return;
        }
        if (plan.getPlannedQuantity() == null
                || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("生产计划 plannedQuantity 为空或非正, 跳过 B3 库存校验: planId={}", plan.getId());
            return;
        }

        List<BomItem> bomItems;
        try {
            bomItems = bomService.getBomItemsByProduct(factoryId, plan.getProductTypeId());
        } catch (Exception e) {
            log.warn("加载 BOM 失败, 跳过 B3 库存校验: planId={}, err={}", plan.getId(), e.getMessage());
            return;
        }
        if (bomItems == null || bomItems.isEmpty()) {
            log.debug("产品无 BOM 配置, 跳过 B3 库存校验: productTypeId={}", plan.getProductTypeId());
            return;
        }

        BigDecimal plannedQty = plan.getPlannedQuantity();
        List<String> shortages = new ArrayList<>();

        for (BomItem item : bomItems) {
            if (item.getMaterialTypeId() == null || item.getStandardQuantity() == null) {
                continue;
            }
            // T159-B Change4: prefer live RawMaterialType.name over BOM snapshot (snapshot may be stale).
            String materialName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());

            // 单位需求 (已含出成率: standardQuantity / (yieldRate/100)), 单位 = BOM unit (e.g. g)
            BigDecimal perUnitRequired = item.getActualQuantity();
            BigDecimal totalRequiredBom = perUnitRequired.multiply(plannedQty);

            // 库存量 (称重批次单位, e.g. kg)
            BigDecimal available = materialBatchRepository.sumAvailableQuantityByMaterialType(
                    factoryId, item.getMaterialTypeId());
            if (available == null) {
                available = BigDecimal.ZERO;
            }

            // T144: 把 BOM 需求量 (g) 换算到称重批次单位 (kg) 再比较. g↔kg 走 converter → CONVERTED.
            String stockUnit = resolveMaterialStockUnit(factoryId, item.getMaterialTypeId());
            String bomUnit = item.getUnit();
            BigDecimal totalRequired = totalRequiredBom;  // 默认: 单位一致或无换算器, 沿用原值

            if (materialUomConverter != null && bomUnit != null && stockUnit != null
                    && !bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) {
                com.cretas.aims.service.uom.MaterialUomConverter.ConversionResult conv =
                        materialUomConverter.toComparableQuantity(
                                item.getMaterialTypeId(), totalRequiredBom, bomUnit, stockUnit);
                if (conv.isAbacaSkip()) {
                    // 抄码料: 每箱重量不一, 跳过按规格的库存校验 (不阻断), 仅记录.
                    log.info("B3 库存校验: 抄码料 {} 跳过库存校验 (planId={})", materialName, plan.getId());
                    continue;
                }
                if (conv.isUnconvertible()) {
                    log.warn("N1 开工无条件化: 原料单位无法换算, 仅记录预警不阻塞开工: planId={}, materialTypeId={}, materialName={}, bomUnit={}, stockUnit={}",
                            plan.getId(), item.getMaterialTypeId(), materialName, bomUnit, stockUnit);
                    continue;
                }
                totalRequired = conv.getQuantity();  // 已换算到称重批次单位 (kg)
            }

            if (available.compareTo(totalRequired) < 0) {
                BigDecimal shortageQty = totalRequired.subtract(available);
                String unit = stockUnit != null ? stockUnit : (bomUnit != null ? bomUnit : "");
                shortages.add(String.format(
                        "%s: 需要 %s%s, 可用 %s%s, 缺口 %s%s",
                        materialName,
                        totalRequired.stripTrailingZeros().toPlainString(), unit,
                        available.stripTrailingZeros().toPlainString(), unit,
                        shortageQty.stripTrailingZeros().toPlainString(), unit));
            }
        }

        if (!shortages.isEmpty()) {
            log.warn("N1 开工无条件化: 原料库存不足仅预警, 不阻塞开工: planId={}, shortages={}", plan.getId(), shortages);
            return;
        }

        log.debug("B3 库存校验通过: planId={}, productTypeId={}, plannedQuantity={}, bomItems={}",
                plan.getId(), plan.getProductTypeId(), plannedQty, bomItems.size());
    }

    private List<ProductionPlanMaterialAdvisoryDTO.Item> buildMaterialAdvisoryItems(String factoryId, ProductionPlan plan) {
        if (bomService == null
                || plan.getProductTypeId() == null || plan.getProductTypeId().isBlank()
                || plan.getPlannedQuantity() == null
                || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyList();
        }

        List<BomItem> bomItems;
        try {
            bomItems = bomService.getBomItemsByProduct(factoryId, plan.getProductTypeId());
        } catch (Exception e) {
            log.warn("Material advisory failed to load BOM: planId={}, err={}", plan.getId(), e.getMessage());
            return Collections.emptyList();
        }
        if (bomItems == null || bomItems.isEmpty()) {
            return Collections.emptyList();
        }

        BigDecimal plannedQty = plan.getPlannedQuantity();
        List<ProductionPlanMaterialAdvisoryDTO.Item> warnings = new ArrayList<>();
        for (BomItem item : bomItems) {
            if (item.getMaterialTypeId() == null || item.getStandardQuantity() == null) {
                continue;
            }
            String materialName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());
            BigDecimal perUnitRequired = item.getActualQuantity();
            BigDecimal totalRequiredBom = perUnitRequired.multiply(plannedQty);

            BigDecimal available = materialBatchRepository.sumAvailableQuantityByMaterialType(
                    factoryId, item.getMaterialTypeId());
            if (available == null) {
                available = BigDecimal.ZERO;
            }

            String stockUnit = resolveMaterialStockUnit(factoryId, item.getMaterialTypeId());
            String bomUnit = item.getUnit();
            String unit = stockUnit != null ? stockUnit : (bomUnit != null ? bomUnit : "");
            BigDecimal totalRequired = totalRequiredBom;
            if (materialUomConverter != null && bomUnit != null && stockUnit != null
                    && !bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) {
                com.cretas.aims.service.uom.MaterialUomConverter.ConversionResult conv =
                        materialUomConverter.toComparableQuantity(
                                item.getMaterialTypeId(), totalRequiredBom, bomUnit, stockUnit);
                if (conv.isAbacaSkip()) {
                    continue;
                }
                if (conv.isUnconvertible()) {
                    warnings.add(ProductionPlanMaterialAdvisoryDTO.Item.builder()
                            .materialTypeId(item.getMaterialTypeId())
                            .materialName(materialName)
                            .requiredQuantity(totalRequiredBom)
                            .availableQuantity(available)
                            .shortageQuantity(null)
                            .unit(bomUnit)
                            .message(String.format("原料「%s」BOM单位(%s)与库存单位(%s)无法换算, 请核对单位配置",
                                    materialName, bomUnit, stockUnit))
                            .build());
                    continue;
                }
                totalRequired = conv.getQuantity();
                unit = stockUnit;
            }

            if (available.compareTo(totalRequired) < 0) {
                BigDecimal shortageQty = totalRequired.subtract(available);
                warnings.add(ProductionPlanMaterialAdvisoryDTO.Item.builder()
                        .materialTypeId(item.getMaterialTypeId())
                        .materialName(materialName)
                        .requiredQuantity(totalRequired)
                        .availableQuantity(available)
                        .shortageQuantity(shortageQty)
                        .unit(unit)
                        .message(String.format("%s: 需要 %s%s, 可用 %s%s, 缺口 %s%s",
                                materialName,
                                totalRequired.stripTrailingZeros().toPlainString(), unit,
                                available.stripTrailingZeros().toPlainString(), unit,
                                shortageQty.stripTrailingZeros().toPlainString(), unit))
                        .build());
            }
        }
        return warnings;
    }

    /**
     * T144: 读取物料的实际库存单位 = AVAILABLE 批次的 {@code MaterialBatch.quantityUnit}
     * (称重入库口径, e.g. "kg"), <b>不是</b> {@code RawMaterialType.unit} (箱, 仅采购/展示标签).
     *
     * <p>原料称重入库 — 权威库存量是 kg. BOM 克(g) 与 kg 走 converter 的 g↔kg 路径 → CONVERTED,
     * 不再误报"原料不足", 也不需要装箱规格/409 摩擦.
     *
     * <p>各批次单位混用时取最常见的并记 warning. 无可用批次时回退 RawMaterialType.unit
     * (无库存场景, 后续 available=0 仍会正确判短缺).
     */
    /**
     * T159-B Change4: 优先从 RawMaterialType 主数据读取原料真实名称, BOM 快照作 fallback.
     *
     * <p>BOM 快照 (item.getMaterialName()) 在重命名后可能陈旧 (例如"气调盒"变更为"吸塑盒").
     * 使用主数据真名可以让 UNCONVERTIBLE 错误消息向用户展示正确的原料名, 减少误导.
     *
     * @param materialTypeId 原料类型 ID
     * @param snapshotName   BOM 快照名称 (fallback)
     * @return 优先返回主数据真名; 快照名次选; 兜底 "原料 {id}"
     */
    private String resolveLiveMaterialName(String materialTypeId, String snapshotName) {
        if (rawMaterialTypeRepository != null && materialTypeId != null) {
            try {
                var mt = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
                if (mt != null && mt.getName() != null && !mt.getName().isBlank()) {
                    return mt.getName();
                }
            } catch (Exception e) {
                log.debug("[T159] 读取原料名失败 {} ({})", materialTypeId, e.getMessage());
            }
        }
        // Fallback to BOM snapshot
        if (snapshotName != null && !snapshotName.isBlank()) {
            return snapshotName;
        }
        return "原料 " + (materialTypeId != null ? materialTypeId : "?");
    }

    private String resolveMaterialStockUnit(String factoryId, String materialTypeId) {
        if (materialTypeId == null) {
            return null;
        }
        try {
            java.util.List<String> units = materialBatchRepository
                    .findStockUnitsByMaterialType(factoryId, materialTypeId);
            if (units != null && !units.isEmpty()) {
                if (units.size() > 1) {
                    log.warn("物料 {} 可用批次单位混用 {}, 取最常见 {}",
                            materialTypeId, units, units.get(0));
                }
                return units.get(0);
            }
        } catch (Exception e) {
            log.debug("读取批次库存单位失败: {} ({})", materialTypeId, e.getMessage());
        }
        // 无可用批次: 回退 RawMaterialType.unit (后续 available=0 判短缺, 不影响正确性)
        if (rawMaterialTypeRepository != null) {
            try {
                return rawMaterialTypeRepository.findById(materialTypeId)
                        .map(RawMaterialType::getUnit)
                        .orElse(null);
            } catch (Exception e) {
                log.debug("读取物料库存单位失败: {} ({})", materialTypeId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * P0-12: 校验销售订单行来源 (字段粒度修正),自动回填订单/客户/产品
     * 客户原话 4216s: "关联销售订单产品 / 客户名称应该自动带出来"
     */
    private void validateAndEnrichSalesOrderSource(String factoryId, CreateProductionPlanRequest request) {
        if (request.getSourceType() != PlanSourceType.CUSTOMER_ORDER) {
            return;
        }
        // 优先使用 sourceOrderItemId (新粒度); 兼容老 sourceOrderId 调用方
        String itemIdStr = request.getSourceOrderItemId();
        if (itemIdStr == null || itemIdStr.isBlank()) {
            if (request.getSourceOrderId() != null && !request.getSourceOrderId().isBlank()) {
                // 向后兼容: 旧调用只传 sourceOrderId — 仅校验订单, 不回填行
                SalesOrder so = salesOrderRepository.findById(request.getSourceOrderId())
                        .orElseThrow(() -> new BusinessException(404, "关联的销售订单不存在: " + request.getSourceOrderId())
                                .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderId"));
                if (!factoryId.equals(so.getFactoryId())) {
                    throw new BusinessException(403, "无权关联其他工厂的销售订单")
                            .withHint("销售订单不属于该工厂, 请选择本工厂的订单").withHintTarget("sourceOrderId");
                }
                // 财审闸门: 客户订单必须先通过财务审核才能流转车间排产
                assertSalesOrderFinanceApproved(so, "sourceOrderId");
                if ((request.getSourceCustomerName() == null || request.getSourceCustomerName().isBlank())
                        && so.getCustomerName() != null) {
                    request.setSourceCustomerName(so.getCustomerName());
                }
                return;
            }
            throw new BusinessException(400, "选择客户订单来源时,必须指定关联的销售订单产品行 (sourceOrderItemId)")
                    .withHint("请选择销售订单中具体的产品行").withHintTarget("sourceOrderItemId");
        }

        Long itemId;
        try {
            itemId = Long.parseLong(itemIdStr);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "销售订单行ID格式无效: " + itemIdStr)
                    .withHint("销售订单行 ID 应为数字, 请重新选择").withHintTarget("sourceOrderItemId");
        }
        SalesOrderItem item = salesOrderItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "销售订单行不存在或不属于本工厂")
                        .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderItemId"));
        SalesOrder so = salesOrderRepository.findById(item.getSalesOrderId())
                .orElseThrow(() -> new BusinessException(404, "销售订单行不存在或不属于本工厂")
                        .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderItemId"));
        if (!factoryId.equals(so.getFactoryId())) {
            throw new BusinessException(404, "销售订单行不存在或不属于本工厂")
                    .withHint("请刷新销售订单列表后重新选择").withHintTarget("sourceOrderItemId");
        }
        // 财审闸门: 客户订单必须先通过财务审核才能流转车间排产
        assertSalesOrderFinanceApproved(so, "sourceOrderItemId");

        // 自动回填: 订单ID/客户名/产品类型
        request.setSourceOrderId(so.getId());
        if (request.getSourceCustomerName() == null || request.getSourceCustomerName().isBlank()) {
            request.setSourceCustomerName(so.getCustomerName());
        }
        if ((request.getProductTypeId() == null || request.getProductTypeId().isBlank())
                && item.getProductTypeId() != null) {
            request.setProductTypeId(item.getProductTypeId());
        }
    }

    /**
     * 财审闸门 — 客户订单(CUSTOMER_ORDER 源)必须先通过财务审核, 才能流转车间排产建生产计划.
     *
     * <p>对应需求 (转录 C-1): "销售计划→下单→财务审批→流转车间→排产闭环, 财务审批完流到张权按订单排产".
     * 在此之前任何 production:read_write 角色可对未审订单排产, 绕过财务. 此校验堵住该缺口.
     *
     * <p>放行条件: SO 状态为 FINANCE_APPROVED 或其后续态 (PROCESSING / PARTIAL_DELIVERED / COMPLETED),
     * 即已通过财审且尚未取消的订单. 拒绝条件: 审批前态 (DRAFT / CONFIRMED / PENDING_FINANCE_REVIEW /
     * FINANCE_REJECTED) 或 CANCELLED. 仅对 CUSTOMER_ORDER 源生效; SAFETY_STOCK 等非客户订单源无 SO, 不经过此校验.
     *
     * <p>仅约束新建计划, 不回溯历史数据 (无 DB 约束).
     *
     * @param so         已校验属于本工厂的销售订单
     * @param hintTarget 防呆提示定位字段 (sourceOrderId 或 sourceOrderItemId)
     */
    private void assertSalesOrderFinanceApproved(SalesOrder so, String hintTarget) {
        SalesOrderStatus status = so.getStatus();
        boolean approved = status == SalesOrderStatus.FINANCE_APPROVED
                || status == SalesOrderStatus.PROCESSING
                || status == SalesOrderStatus.PARTIAL_DELIVERED
                || status == SalesOrderStatus.COMPLETED;
        if (!approved) {
            String statusLabel = status != null ? status.getDisplayName() : "未知";
            throw new BusinessException(409,
                    "该销售订单未通过财务审核 (当前状态: " + statusLabel + "), 无法排产")
                    .withHint("请先提交财审, 待财务审核通过后再创建生产计划")
                    .withHintTarget(hintTarget);
        }
    }

    /**
     * SP5 多 SO 合并工单: 规范化并校验 sourceOrderIds.
     *
     * <p>规则:
     * <ol>
     *   <li>若 request.sourceOrderIds 为 null/空 → 用 plan.sourceOrderId (单 SO 场景向后兼容) 填充。</li>
     *   <li>若 plan.sourceOrderId 不在列表中 → 自动追加 (保证列表包含主 SO)。</li>
     *   <li>每个追加的 SO (超出主 sourceOrderId 的) 校验: 属于本工厂 + 已财审。</li>
     *   <li>列表去重 (相同 ID 多次出现)。</li>
     * </ol>
     *
     * <p>只对 CUSTOMER_ORDER 场景执行额外 SO 校验; MANUAL/AI_FORECAST 等源不传 sourceOrderIds.
     * 此方法在 plan entity 已赋值但尚未 save 时调用 — 直接写 plan.sourceOrderIds。
     *
     * @param factoryId 工厂 ID (用于 SO 归属校验)
     * @param plan      已由 mapper 赋值的 entity (未 save)
     * @param request   原始请求 (用于读取 sourceOrderIds / sourceOrderId)
     */
    private void normalizeAndValidateSourceOrderIds(String factoryId, ProductionPlan plan,
            com.cretas.aims.dto.production.CreateProductionPlanRequest request) {
        java.util.List<String> ids = plan.getSourceOrderIds() != null
                ? new java.util.ArrayList<>(plan.getSourceOrderIds())
                : new java.util.ArrayList<>();

        // 单 SO 向后兼容: 若列表为空, 用 sourceOrderId 初始化
        String primarySoId = plan.getSourceOrderId();
        if (ids.isEmpty() && primarySoId != null && !primarySoId.isBlank()) {
            ids.add(primarySoId);
        }

        // 若 primarySoId 不在列表中, 补进去
        if (primarySoId != null && !primarySoId.isBlank() && !ids.contains(primarySoId)) {
            ids.add(0, primarySoId);  // 保持主 SO 在首位
        }

        // 去重保持顺序
        java.util.List<String> deduped = ids.stream()
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        // 对超出主 SO 的额外 SO 做校验 (主 SO 在 validateAndEnrichSalesOrderSource 里已验过)
        if (deduped.size() > 1) {
            for (int i = 1; i < deduped.size(); i++) {
                String extraSoId = deduped.get(i);
                if (extraSoId == null || extraSoId.isBlank()) continue;
                SalesOrder so = salesOrderRepository.findById(extraSoId)
                        .orElseThrow(() -> new BusinessException(404,
                                "追加的销售订单不存在: " + extraSoId)
                                .withHint("请刷新销售订单列表后重新选择")
                                .withHintTarget("sourceOrderIds"));
                if (!factoryId.equals(so.getFactoryId())) {
                    throw new BusinessException(403,
                            "追加的销售订单不属于该工厂: " + extraSoId)
                            .withHint("只能合并本工厂的销售订单")
                            .withHintTarget("sourceOrderIds");
                }
                assertSalesOrderFinanceApproved(so, "sourceOrderIds");
            }
        }

        plan.setSourceOrderIds(deduped);
        log.debug("SP5 normalizeSourceOrderIds: planId={}, sourceOrderIds={}", plan.getId(), deduped);
    }

    @Override
    @Transactional
    public ProductionPlanDTO createProductionPlan(String factoryId, CreateProductionPlanRequest request, Long userId) {
        // Build validation context including Canvas V3 custom fields (e.g. cf_tank_id)
        // so SpEL rules like '#cf_tank_id != null' can evaluate correctly.
        java.util.Map<String, Object> validationCtx = new java.util.HashMap<>();
        validationCtx.put("plannedQuantity", request.getPlannedQuantity() != null ? request.getPlannedQuantity() : java.math.BigDecimal.ZERO);
        validationCtx.put("productTypeId", request.getProductTypeId() != null ? request.getProductTypeId() : "");
        validationCtx.put("status", "DRAFT");
        // Merge custom fields into context so SpEL rules can reference cf_* variables
        if (request.getCustomFields() != null) {
            for (var entry : request.getCustomFields().entrySet()) {
                validationCtx.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        runConfiguredValidation(factoryId, "CREATE", validationCtx);
        // 验证产品类型是否存在
        if (!productTypeRepository.existsById(request.getProductTypeId())) {
            throw new ResourceNotFoundException("产品类型不存在");
        }

        // P0-12: 校验销售订单来源 + 回填客户名
        validateAndEnrichSalesOrderSource(factoryId, request);

        // P1-4: 客户订单来源必须填写工序名称和批次日期
        if (request.getSourceType() == PlanSourceType.CUSTOMER_ORDER) {
            if (request.getProcessName() == null || request.getProcessName().isBlank()) {
                throw new BusinessException(400, "客户订单来源的生产计划必须填写工序名称")
                        .withHint("请填写工序名称").withHintTarget("processName");
            }
            if (request.getBatchDate() == null) {
                throw new BusinessException(400, "客户订单来源的生产计划必须填写批次日期")
                        .withHint("请选择批次日期").withHintTarget("batchDate");
            }
        }

        // Fable 审计修复 (2026-06-11 — 多租户安全红线):
        //   免工序报工开关 skipProcessReporting=null (调用方未显式指定, 如 RN/AI 客户端)
        //   → 解析为"该工厂的默认值" (F006=true 两点, 其他工厂=false 逐道), 而非旧的"全系统 null→true"。
        //   显式 true/false 一律尊重调用方 (web 开关传显式值)。
        //   这样其他工厂 (逐道是其溯源/成本/出成率/自学习/人效价值) 不被静默改成两点。
        if (request.getSkipProcessReporting() == null) {
            request.setSkipProcessReporting(resolveSkipProcessReportingDefault(factoryId));
        }

        // 创建生产计划
        ProductionPlan plan = productionPlanMapper.toEntity(request, factoryId, userId.longValue());

        // SP5 多 SO 合并: 规范化 sourceOrderIds — 确保 sourceOrderId 也在列表中,
        // 并校验追加的每个 SO 属于本工厂且已财审 (向后兼容: 单 SO 场景 sourceOrderIds 为空时自动补填)。
        normalizeAndValidateSourceOrderIds(factoryId, plan, request);

        plan = productionPlanRepository.save(plan);

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink double-write.
        // ProductionPlan.sourceOrderId stays for backward compat; new code reads via
        // LinkArrayService.getOutboundLinks(PRODUCTION_PLAN, id).
        // SP5: 额外的 SO (合并场景) 也写入 LinkArray, 保证双向检索一致。
        if (linkArrayService != null) {
            final String planIdForLink = plan.getId();
            final String userIdStr = userId != null ? userId.toString() : null;
            java.util.List<String> allSourceOrderIds = plan.getSourceOrderIds() != null
                    ? plan.getSourceOrderIds() : java.util.Collections.emptyList();
            for (String soId : allSourceOrderIds) {
                try {
                    linkArrayService.link(factoryId,
                            "PRODUCTION_PLAN", planIdForLink,
                            "sale",
                            "SALES_ORDER", soId,
                            "生产源单", userIdStr);
                } catch (Exception e) {
                    log.warn("BusinessLink double-write failed for production plan {} → SO {}: {}",
                            planIdForLink, soId, e.getMessage());
                }
            }
        }

        // Round 9 Fix (R8-α Gap #3 per-module template): persist Canvas V3 dynamic fields.
        // Customer-configured fields like 客户订单号, QC 等级, 特殊工艺参数, 成品包装要求
        // now land in the cf_* columns of production_plans. Previously silently dropped.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "production_plan", plan.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for production plan {}: {}", plan.getId(), e.getMessage());
            }
        }

        // 如果指定了原材料批次，创建关联
        if (request.getMaterialBatchIds() != null && request.getMaterialBatchIds().length > 0) {
            assignMaterialBatchesToPlan(plan, Arrays.asList(request.getMaterialBatchIds()));
        }

        log.info("创建生产计划成功: planNumber={}", plan.getPlanNumber());

        // 触发自动排产（在事务提交后异步执行，确保数据已持久化）
        final String finalPlanId = plan.getId();
        final String finalPlanNumber = plan.getPlanNumber();
        final String finalFactoryId = factoryId;
        final Long finalUserId = userId;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    schedulingService.onProductionPlanCreated(finalFactoryId, finalPlanId, finalPlanNumber, finalUserId);
                    log.debug("已触发自动排产（事务提交后）: planId={}", finalPlanId);
                } catch (Exception e) {
                    // 自动排产失败不影响计划创建
                    log.error("触发自动排产失败: planId={}, error={}", finalPlanId, e.getMessage());
                }
            }
        });

        return toDTOWithConversionInfo(plan);
    }

    /**
     * M-PREP-1 (Sprint 4 W2): 创建草稿态生产计划 — status=PREPARED.
     *
     * <p>调用 {@link #createProductionPlan} 走完正常创建流程后,
     * 把 status 从 PENDING 翻成 PREPARED 并保存。
     * 这样 validation / sales-order-source 校验等业务逻辑全部复用。</p>
     */
    @Override
    @Transactional
    public ProductionPlanDTO createDraftProductionPlan(String factoryId, CreateProductionPlanRequest request, Long userId) {
        ProductionPlanDTO created = createProductionPlan(factoryId, request, userId);
        ProductionPlan plan = productionPlanRepository.findById(created.getId())
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", created.getId()));
        plan.setStatus(ProductionPlanStatus.PREPARED);
        plan = productionPlanRepository.save(plan);
        log.info("[M-PREP-1] 创建草稿生产计划: planId={}, planNumber={}", plan.getId(), plan.getPlanNumber());
        return toDTOWithConversionInfo(plan);
    }

    /**
     * M-PREP-1 (Sprint 4 W2): 提交草稿态生产计划 — PREPARED → PENDING.
     */
    @Override
    @Transactional
    public ProductionPlanDTO commitDraftProductionPlan(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        if (plan.getStatus() != ProductionPlanStatus.PREPARED) {
            throw new BusinessException(409, "只能提交草稿态 (PREPARED) 的生产计划, 当前状态: " + plan.getStatus())
                    .withHint("请刷新生产计划列表查看最新状态");
        }
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan = productionPlanRepository.save(plan);
        log.info("[M-PREP-1] 提交草稿生产计划: planId={}, planNumber={}", plan.getId(), plan.getPlanNumber());
        return toDTOWithConversionInfo(plan);
    }

    /**
     * M-DELIVERY-WARN-1 (Sprint 4 W2): 获取交货预警列表.
     *
     * <p>查询 expectedCompletionDate &lt; today + windowDays 且状态非 COMPLETED/CANCELLED
     * 的生产计划, 然后按距交期天数分级 (OVERDUE / URGENT / WARN / NORMAL)。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<DeliveryWarnDTO> getDeliveryWarnings(String factoryId, int windowDays) {
        if (windowDays <= 0) windowDays = 7;  // 默认 7 天
        LocalDate today = LocalDate.now();
        LocalDate upperBound = today.plusDays(windowDays);
        List<ProductionPlan> plans = productionPlanRepository.findDeliveryWarnPlans(factoryId, upperBound);
        return plans.stream()
                .map(p -> classifyDeliveryWarn(p, today))
                .collect(Collectors.toList());
    }

    /**
     * 计算单条生产计划的交货预警等级.
     * <ul>
     *   <li>OVERDUE: daysUntilDeadline &lt; 0 (已超期)</li>
     *   <li>URGENT:  0 &le; daysUntilDeadline &lt; 3</li>
     *   <li>WARN:    3 &le; daysUntilDeadline &lt; 7</li>
     *   <li>NORMAL:  daysUntilDeadline &ge; 7</li>
     * </ul>
     */
    private DeliveryWarnDTO classifyDeliveryWarn(ProductionPlan plan, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, plan.getExpectedCompletionDate());
        String level;
        if (days < 0) {
            level = "OVERDUE";
        } else if (days < 3) {
            level = "URGENT";
        } else if (days < 7) {
            level = "WARN";
        } else {
            level = "NORMAL";
        }
        String productTypeName = null;
        try {
            if (plan.getProductType() != null) {
                productTypeName = plan.getProductType().getName();
            }
        } catch (Exception ignored) {
            // 容忍 lazy-load 失败 — productTypeName 为 null 由前端兜底
        }
        return DeliveryWarnDTO.builder()
                .planId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .factoryId(plan.getFactoryId())
                .productTypeId(plan.getProductTypeId())
                .productTypeName(productTypeName)
                .plannedQuantity(plan.getPlannedQuantity())
                .actualQuantity(plan.getActualQuantity())
                .expectedCompletionDate(plan.getExpectedCompletionDate())
                .status(plan.getStatus() != null ? plan.getStatus().name() : null)
                .daysUntilDeadline(days)
                .warnLevel(level)
                .sourceCustomerName(plan.getSourceCustomerName())
                .build();
    }

    @Override
    @Transactional
    public ProductionPlanDTO updateProductionPlan(String factoryId, String planId, CreateProductionPlanRequest request) {
        // Build validation context including Canvas V3 custom fields for UPDATE too
        java.util.Map<String, Object> updateCtx = new java.util.HashMap<>();
        updateCtx.put("planId", planId);
        updateCtx.put("plannedQuantity", request.getPlannedQuantity() != null ? request.getPlannedQuantity() : java.math.BigDecimal.ZERO);
        updateCtx.put("productTypeId", request.getProductTypeId() != null ? request.getProductTypeId() : "");
        if (request.getCustomFields() != null) {
            for (var entry : request.getCustomFields().entrySet()) {
                updateCtx.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }
        runConfiguredValidation(factoryId, "UPDATE", updateCtx);
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // M-PREP-1: 草稿态 (PREPARED) 也允许更新, 与 PENDING 相同
        if (plan.getStatus() != ProductionPlanStatus.PENDING
                && plan.getStatus() != ProductionPlanStatus.PREPARED) {
            throw new BusinessException(409, "只能修改待处理或草稿态的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        // Issue #759: 锁定的计划不可编辑
        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            throw new BusinessException(409, "生产计划已锁定, 不可编辑")
                    .withHint("先解锁该计划再尝试修改");
        }

        // P0-12: 校验销售订单来源 + 回填客户名
        validateAndEnrichSalesOrderSource(factoryId, request);

        // 更新计划信息
        productionPlanMapper.updateEntity(plan, request);
        plan = productionPlanRepository.save(plan);

        log.info("更新生产计划成功: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public void deleteProductionPlan(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // M-PREP-1: 草稿态 (PREPARED) 也允许删除 (丢弃草稿)
        if (plan.getStatus() != ProductionPlanStatus.PENDING
                && plan.getStatus() != ProductionPlanStatus.PREPARED) {
            throw new BusinessException(409, "只能删除待处理或草稿态的生产计划")
                    .withHint("已开始或已完成的计划不可删除, 请取消代替");
        }

        productionPlanRepository.delete(plan);
        log.info("删除生产计划成功: planId={}", planId);
    }

    /**
     * 复制生产计划 — 见 {@link ProductionPlanService#copyProductionPlan} 文档.
     *
     * <p>实现说明:
     * <ul>
     *   <li>用 {@link ProductionPlanRepository#findById} + factory 隔离 (404 / 403).</li>
     *   <li>新计划 status = PENDING, createdBy = 当前用户, planNumber 重新生成.</li>
     *   <li>不走 {@link #createProductionPlan} — 跳过 SO source validation /
     *       Canvas validation / auto scheduling (复制场景假设源已通过).</li>
     *   <li>不复制 actualQuantity / actual*Cost / approval* / locked* / 状态字段.</li>
     * </ul>
     */
    @Override
    @Transactional
    @com.cretas.aims.annotation.Loggable(module = "PRODUCTION_PLAN", action = "COPY",
            entityType = "ProductionPlan", entityIdParam = "sourcePlanId")
    public ProductionPlanDTO copyProductionPlan(String factoryId, String sourcePlanId, Long userId) {
        ProductionPlan source = productionPlanRepository.findById(sourcePlanId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", sourcePlanId));

        if (!source.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法复制");
        }

        ProductionPlan newPlan = new ProductionPlan();
        newPlan.setId(UUID.randomUUID().toString());
        newPlan.setFactoryId(factoryId);
        // planNumber: 复用 ProductionPlanMapper 的 generatePlanNumber 模式 (PLAN-{ts}-{uuid8})
        newPlan.setPlanNumber("PLAN-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        // 复制业务字段
        newPlan.setProductTypeId(source.getProductTypeId());
        newPlan.setPlannedQuantity(source.getPlannedQuantity());
        newPlan.setPlannedDate(source.getPlannedDate());
        newPlan.setExpectedCompletionDate(source.getExpectedCompletionDate());
        newPlan.setPlanType(source.getPlanType());
        newPlan.setCustomerOrderNumber(source.getCustomerOrderNumber());
        newPlan.setPriority(source.getPriority());
        newPlan.setNotes(source.getNotes());
        newPlan.setEstimatedMaterialCost(source.getEstimatedMaterialCost());
        newPlan.setEstimatedLaborCost(source.getEstimatedLaborCost());
        newPlan.setEstimatedEquipmentCost(source.getEstimatedEquipmentCost());
        newPlan.setEstimatedOtherCost(source.getEstimatedOtherCost());
        newPlan.setSuggestedProductionLineId(source.getSuggestedProductionLineId());
        newPlan.setEstimatedWorkers(source.getEstimatedWorkers());
        newPlan.setAssignedSupervisorId(source.getAssignedSupervisorId());
        newPlan.setSourceType(source.getSourceType());
        newPlan.setSourceOrderId(source.getSourceOrderId());
        newPlan.setSourceOrderItemId(source.getSourceOrderItemId());
        newPlan.setSourceCustomerName(source.getSourceCustomerName());
        newPlan.setProcessName(source.getProcessName());
        newPlan.setBatchDate(source.getBatchDate());
        newPlan.setForecastReason(source.getForecastReason());
        newPlan.setAiConfidence(source.getAiConfidence());
        newPlan.setIsMixedBatch(source.getIsMixedBatch() != null ? source.getIsMixedBatch() : false);
        newPlan.setMixedBatchType(source.getMixedBatchType());
        newPlan.setRelatedOrders(source.getRelatedOrders());
        newPlan.setCrValue(source.getCrValue());
        // 重置状态字段
        newPlan.setStatus(ProductionPlanStatus.PENDING);
        newPlan.setCreatedBy(userId);
        newPlan.setAllocatedQuantity(java.math.BigDecimal.ZERO);
        newPlan.setIsFullyMatched(false);
        newPlan.setIsLocked(false);
        // 不复制: actualQuantity / actual*Cost / approval* / locked*
        // / currentProbability / probabilityUpdatedAt / startTime / endTime
        // / isForceInserted / requiresApproval (默认值已 OK)

        newPlan = productionPlanRepository.save(newPlan);

        log.info("复制生产计划: source={}({}) → new={}({})",
                sourcePlanId, source.getPlanNumber(),
                newPlan.getId(), newPlan.getPlanNumber());
        return toDTOWithConversionInfo(newPlan);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionPlanDTO getProductionPlanById(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权查看该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法查看");
        }

        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductionPlanDTO> getProductionPlanList(String factoryId, PageRequest pageRequest) {
        Sort sort = Sort.by(
                pageRequest.getSortDirection().equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC,
                pageRequest.getSortBy()
        );

        org.springframework.data.domain.PageRequest pageable =
            org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                sort
            );

        Page<ProductionPlan> planPage;

        // 如果指定了状态过滤
        if (pageRequest.getStatus() != null && !pageRequest.getStatus().isEmpty()) {
            String statusFilter = pageRequest.getStatus().toUpperCase();
            if ("UNFINISHED".equals(statusFilter) || "ACTIVE".equals(statusFilter)) {
                planPage = productionPlanRepository.findByFactoryIdAndStatusIn(
                        factoryId,
                        List.of(ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS),
                        pageable);
                log.info("按未完成状态过滤生产计划: factoryId={}, status={}", factoryId, statusFilter);
            } else {
                try {
                    ProductionPlanStatus status = ProductionPlanStatus.valueOf(statusFilter);
                    planPage = productionPlanRepository.findByFactoryIdAndStatus(factoryId, status, pageable);
                    log.info("按状态过滤生产计划: factoryId={}, status={}", factoryId, status);
                } catch (IllegalArgumentException e) {
                    log.warn("无效的状态值: {}, 返回全部数据", pageRequest.getStatus());
                    planPage = productionPlanRepository.findByFactoryId(factoryId, pageable);
                }
            }
        } else {
            planPage = productionPlanRepository.findByFactoryId(factoryId, pageable);
        }

        List<ProductionPlanDTO> planDTOs = planPage.getContent().stream()
                .map(this::toDTOWithConversionInfo)
                .collect(Collectors.toList());

        return PageResponse.of(
                planDTOs,
                pageRequest.getPage(),
                pageRequest.getSize(),
                planPage.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionPlanDTO> getProductionPlansByStatus(String factoryId, ProductionPlanStatus status) {
        return productionPlanRepository.findByFactoryIdAndStatus(factoryId, status)
                .stream()
                .map(this::toDTOWithConversionInfo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionPlanDTO> getProductionPlansByDateRange(String factoryId, LocalDate startDate, LocalDate endDate) {
        // 暂时注释 - 数据库表中没有planned_date字段
        // return productionPlanRepository.findByDateRange(factoryId, startDate, endDate)
        //         .stream()
        //         .map(productionPlanMapper::toDTO)
        //         .collect(Collectors.toList());
        return new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionPlanDTO> getTodayProductionPlans(String factoryId) {
        // 暂时注释 - 数据库表中没有planned_date字段
        // return productionPlanRepository.findTodayPlans(factoryId)
        //         .stream()
        //         .map(productionPlanMapper::toDTO)
        //         .collect(Collectors.toList());
        return new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionPlanMaterialAdvisoryDTO getMaterialAdvisory(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权查看该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法查看");
        }

        List<ProductionPlanMaterialAdvisoryDTO.Item> warnings = buildMaterialAdvisoryItems(factoryId, plan);
        String message = warnings.isEmpty()
                ? "原料库存参考: 暂无缺料预警"
                : "原料库存参考: " + warnings.stream()
                        .map(ProductionPlanMaterialAdvisoryDTO.Item::getMessage)
                        .collect(Collectors.joining("; "));
        return ProductionPlanMaterialAdvisoryDTO.builder()
                .planId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .hasWarning(!warnings.isEmpty())
                .message(message)
                .warnings(warnings)
                .build();
    }

    @Override
    @Transactional
    public ProductionPlanDTO startProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 验证状态
        if (plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只能开始待处理的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        runConfiguredValidation(factoryId, "START", java.util.Map.of("planId", planId));

        // N1 (2026-06-12): 开工无条件化。原料不足只记录预警, 不再阻断开工;
        // 领料/实际消耗在报工或结单时按现场填写。
        validateMaterialStockSufficient(factoryId, plan);

        // SP2 二次加工: 开始生产时扣减 WIP 半成品库存
        // 注: 在事务内执行, 扣减失败直接抛出异常回滚整个 startProduction (fail-closed, 无 fail-soft)
        if ("SECONDARY".equals(plan.getPlanSourceType()) && plan.getSecondarySourceWipId() != null) {
            if (wipInventoryService == null) {
                throw new BusinessException(500, "二次加工服务未初始化, 无法扣减半成品库存");
            }
            wipInventoryService.deductForSecondaryPlan(
                    plan.getSecondarySourceWipId(),
                    plan.getPlannedQuantity() != null ? plan.getPlannedQuantity() : java.math.BigDecimal.ZERO,
                    factoryId,
                    null /* operatorId — startProduction does not receive userId, set null */);
            log.info("SP2 二次加工 WIP 扣减完成: planId={}, wipId={}", planId, plan.getSecondarySourceWipId());
        }

        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setStartTime(LocalDateTime.now());
        plan = productionPlanRepository.save(plan);

        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.ProductionStartedEvent(
                        this, factoryId, plan.getId(), plan.getPlanNumber(), plan.getProductTypeId()));
            } catch (Exception e) { log.warn("Publish ProductionStartedEvent failed: {}", e.getMessage()); }
        }

        log.info("开始生产: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO completeProduction(String factoryId, String planId, BigDecimal actualQuantity) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        if (isLiushanmenFactory(factoryId)) {
            throw new BusinessException(409, "六扇门生产完成必须先核对结单")
                    .withCode("PRODUCTION_SETTLEMENT_REQUIRED")
                    .withHint("请使用“核对结单”录入实际产量、实际领用明细和人效后再完成")
                    .withHintTarget("核对结单");
        }
        if (plan.getStatus() != ProductionPlanStatus.IN_PROGRESS
                && plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只能完成未完成的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        runConfiguredValidation(factoryId, "COMPLETE", java.util.Map.of(
            "planId", planId,
            "actualQuantity", actualQuantity != null ? actualQuantity : java.math.BigDecimal.ZERO));

        plan.setStatus(ProductionPlanStatus.COMPLETED);
        if (plan.getStartTime() == null) {
            plan.setStartTime(LocalDateTime.now());
        }
        plan.setEndTime(LocalDateTime.now());
        plan.setActualQuantity(actualQuantity);
        plan = productionPlanRepository.save(plan);

        // GAP 6 (F006): 计划级完工应级联完成关联批次并发 BatchCompletedEvent,
        // 触发 SupplyChainOrchestrator.onBatchCompleted (扣料 REQUIRES_NEW + goodQuantity>0 时建成品).
        // ProductionCompletedEvent 无建成品监听器, 必须显式走批次完工链.
        List<ProductionBatch> linked = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);
        for (ProductionBatch b : linked) {
            if (b.getStatus() == ProductionBatchStatus.IN_PROGRESS
                    || b.getStatus() == ProductionBatchStatus.PLANNED
                    || b.getStatus() == ProductionBatchStatus.PRODUCING) {
                b.setStatus(ProductionBatchStatus.COMPLETED);
                b.setEndTime(LocalDateTime.now());
                if (actualQuantity != null) {
                    b.setActualQuantity(actualQuantity);
                    // 计划级无良/次品拆分 → 全部计为良品 (成品创建需 goodQuantity>0).
                    b.setGoodQuantity(actualQuantity);
                }
                try { b.calculateMetrics(); } catch (Exception ignore) {}
                ProductionBatch cb = productionBatchRepository.save(b);
                if (applicationEventPublisher != null) {
                    try {
                        applicationEventPublisher.publishEvent(
                                new com.cretas.aims.event.BatchCompletedEvent(this, cb));
                        log.info("计划完成级联完成批次 + 发 BatchCompletedEvent(建成品): batchId={}", cb.getId());
                    } catch (Exception e) {
                        log.warn("BatchCompletedEvent 发布失败 (fail-soft): batchId={}, err={}", cb.getId(), e.getMessage());
                    }
                }
            }
        }

        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.ProductionCompletedEvent(
                        this, factoryId, plan.getId(), plan.getPlanNumber(),
                        plan.getProductTypeId(), actualQuantity));
            } catch (Exception e) { log.warn("Publish ProductionCompletedEvent failed: {}", e.getMessage()); }
        }

        log.info("完成生产: planId={}, actualQuantity={}", planId, actualQuantity);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionSettlementResponse settleProduction(String factoryId, String planId,
                                                         ProductionSettlementRequest request, Long settledBy) {
        if (request == null) {
            throw new BusinessException(400, "结单内容不能为空").withHintTarget("核对结单");
        }
        if (isBlank(request.getIdempotencyKey())) {
            throw new BusinessException(400, "缺少幂等键 idempotencyKey")
                    .withHint("请刷新页面后重试, 系统会自动生成结单提交键")
                    .withHintTarget("核对结单");
        }

        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        Optional<ProductionSettlement> sameRequest = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                        factoryId, planId, request.getIdempotencyKey());
        if (sameRequest.isPresent()) {
            return toSettlementResponse(sameRequest.get(),
                    List.of("该结单请求已提交过, 已返回原结单结果"));
        }
        productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .ifPresent(existing -> {
                    throw new BusinessException(409, "该生产计划已结单, 不能重复完成")
                            .withCode("PRODUCTION_ALREADY_SETTLED")
                            .withHint("请刷新生产计划列表查看最新状态")
                            .withHintTarget("核对结单");
                });

        validateSettlementRequest(plan, request);

        List<ProductionSettlementConsumption> consumptionLines = new ArrayList<>();
        appendConsumptionLines(factoryId, planId, "RAW_MATERIAL", request.getRawMaterialConsumptions(), consumptionLines);
        appendConsumptionLines(factoryId, planId, "SEMI_FINISHED", request.getSemiFinishedConsumptions(), consumptionLines);
        appendConsumptionLines(factoryId, planId, "AUXILIARY", request.getAuxiliaryConsumptions(), consumptionLines);

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(UUID.randomUUID().toString());
        settlement.setFactoryId(factoryId);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber(plan.getPlanNumber());
        settlement.setIdempotencyKey(request.getIdempotencyKey());
        settlement.setPlannedQuantity(zeroIfNull(plan.getPlannedQuantity()));
        settlement.setActualFinishedQuantity(zeroIfNull(request.getActualFinishedQuantity()));
        settlement.setActualSemiFinishedQuantity(zeroIfNull(request.getActualSemiFinishedQuantity()));
        settlement.setQuantityUnit(trimToNull(request.getQuantityUnit()));
        settlement.setQuantityVarianceReason(trimToNull(request.getQuantityVarianceReason()));
        settlement.setQuantityVarianceNote(trimToNull(request.getQuantityVarianceNote()));
        settlement.setMaterialVarianceReason(trimToNull(request.getMaterialVarianceReason()));
        settlement.setMaterialVarianceNote(trimToNull(request.getMaterialVarianceNote()));
        settlement.setLaborDeferredReason(trimToNull(request.getLaborDeferredReason()));
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setPostingMessage("已记录结单事实; 等待仓库确认实收后才生成成品库存, 差异进入中转挂账");
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now());
        settlement = productionSettlementRepository.save(settlement);

        for (ProductionSettlementConsumption line : consumptionLines) {
            line.setSettlementId(settlement.getId());
        }
        productionSettlementConsumptionRepository.saveAll(consumptionLines);
        productionSettlementLaborRepository.saveAll(toLaborLines(factoryId, planId, settlement.getId(), request.getLaborSegments()));

        plan.setStatus(ProductionPlanStatus.COMPLETED);
        if (plan.getStartTime() == null) {
            plan.setStartTime(LocalDateTime.now());
        }
        plan.setEndTime(LocalDateTime.now());
        plan.setActualQuantity(settlement.getActualFinishedQuantity());
        productionPlanRepository.save(plan);

        List<String> warnings = List.of("本次仅完成文员结单事实记录; 成品库存不会增加, 需仓库确认实收后才入库");
        log.info("六扇门生产结单完成: factoryId={}, planId={}, settlementId={}, finished={}, semiFinished={}",
                factoryId, planId, settlement.getId(),
                settlement.getActualFinishedQuantity(), settlement.getActualSemiFinishedQuantity());
        return toSettlementResponse(settlement, warnings);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementResponse getProductionSettlement(String factoryId, String planId) {
        ProductionSettlement settlement = requireProductionSettlement(factoryId, planId);
        return toSettlementResponse(settlement, Collections.emptyList());
    }

    @Override
    @Transactional
    public ProductionWarehouseReceiptResponse confirmWarehouseReceipt(String factoryId, String planId,
                                                                      ProductionWarehouseReceiptRequest request,
                                                                      Long receivedBy) {
        if (request == null) {
            throw new BusinessException(400, "仓库确认内容不能为空").withHintTarget("仓库实收");
        }
        if (isBlank(request.getIdempotencyKey())) {
            throw new BusinessException(400, "缺少幂等键 idempotencyKey")
                    .withHint("请刷新页面后重试, 系统会自动生成仓库确认提交键")
                    .withHintTarget("仓库实收");
        }
        ensurePostingDependencies();

        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        ProductionSettlement settlement = requireProductionSettlement(factoryId, planId);

        if (settlement.getWarehouseReceivedAt() != null) {
            if (request.getIdempotencyKey().equals(settlement.getWarehouseReceiptIdempotencyKey())) {
                return toWarehouseReceiptResponse(settlement, "该仓库确认请求已提交过, 已返回原确认结果",
                        Collections.emptyList());
            }
            throw new BusinessException(409, "该生产结单已由仓库确认, 不能重复入库")
                    .withCode("PRODUCTION_RECEIPT_ALREADY_CONFIRMED")
                    .withHint("请刷新生产计划列表查看最新入库状态")
                    .withHintTarget("仓库实收");
        }

        BigDecimal reported = zeroIfNull(settlement.getActualFinishedQuantity());
        BigDecimal received = zeroIfNull(request.getReceivedQuantity());
        if (received.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "仓库实收数量必须大于 0").withHintTarget("仓库实收");
        }
        if (reported.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409, "该结单没有成品产量, 不能确认成品入库")
                    .withHint("请确认是否只产半成品, 或先修正生产结单")
                    .withHintTarget("仓库实收");
        }
        if (received.compareTo(reported) > 0) {
            throw new BusinessException(409, "仓库实收数量不能超过生产报产数量")
                    .withCode("RECEIPT_EXCEEDS_PRODUCTION_REPORTED")
                    .withHint("生产报产: " + reported + ", 仓库实收: " + received + "; 请先让生产修正结单或拆分补录")
                    .withHintTarget("仓库实收");
        }

        String unit = firstNonBlank(request.getQuantityUnit(), settlement.getQuantityUnit(), "件");
        BigDecimal variance = reported.subtract(received);
        BigDecimal tolerance = receiptTolerance(unit);
        boolean hasVariance = variance.compareTo(BigDecimal.ZERO) != 0;
        boolean withinTolerance = hasVariance && variance.abs().compareTo(tolerance) <= 0 && tolerance.compareTo(BigDecimal.ZERO) > 0;
        boolean needsTransitLedger = hasVariance && !withinTolerance;

        if (needsTransitLedger && isBlank(request.getVarianceReason())) {
            throw new BusinessException(409, "仓库实收与生产报产不一致, 必须选择差异原因")
                    .withCode("PRODUCTION_RECEIPT_VARIANCE_REASON_REQUIRED")
                    .withHint("生产报产: " + reported + unit + ", 仓库实收: " + received + unit + ", 差异: " + variance + unit)
                    .withHintTarget("差异原因");
        }

        FinishedGoodsBatch fgBatch = createFinishedGoodsFromReceipt(plan, settlement, received, unit, receivedBy);
        ProductionTransitLedger ledger = needsTransitLedger
                ? createTransitLedger(settlement, reported, received, variance, tolerance, unit, request, receivedBy)
                : null;

        settlement.setWarehouseReceiptIdempotencyKey(request.getIdempotencyKey());
        settlement.setWarehouseReceivedQuantity(received);
        settlement.setWarehouseVarianceQuantity(variance);
        settlement.setQuantityUnit(unit);
        settlement.setWarehouseVarianceReason(trimToNull(request.getVarianceReason()));
        settlement.setWarehouseResponsibilitySide(needsTransitLedger
                ? firstNonBlank(request.getResponsibilitySide(), "PENDING")
                : (withinTolerance ? "WEIGHING_ERROR" : null));
        settlement.setWarehouseVarianceNote(trimToNull(request.getVarianceNote()));
        settlement.setFinishedGoodsBatchId(fgBatch.getId());
        settlement.setTransitLedgerId(ledger != null ? ledger.getId() : null);
        settlement.setWarehouseReceivedBy(receivedBy);
        settlement.setWarehouseReceivedAt(LocalDateTime.now());
        if (needsTransitLedger) {
            settlement.setPostingStatus("PENDING_CLEARING");
            settlement.setPostingMessage("仓库已确认实收并生成成品库存; 生产报产与仓库实收存在差异, 已进入中转挂账");
        } else if (withinTolerance) {
            settlement.setPostingStatus("POSTED_WITH_TOLERANCE");
            settlement.setPostingMessage("仓库已确认实收并生成成品库存; 差异在10kg称重容差内, 不生成中转挂账");
        } else {
            settlement.setPostingStatus("POSTED");
            settlement.setPostingMessage("仓库已确认实收并生成成品库存");
        }
        productionSettlementRepository.save(settlement);

        List<String> warnings = new ArrayList<>();
        if (needsTransitLedger) {
            warnings.add("差异已进入中转挂账, 需继续判断责任归属并清账");
        } else if (withinTolerance) {
            warnings.add("差异在称重容差内, 本次不生成中转挂账");
        }
        return toWarehouseReceiptResponse(settlement, settlement.getPostingMessage(), warnings);
    }

    private void validateSettlementRequest(ProductionPlan plan, ProductionSettlementRequest request) {
        if (plan.getStatus() != ProductionPlanStatus.IN_PROGRESS
                && plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只能结单未完成的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态")
                    .withHintTarget("核对结单");
        }

        BigDecimal finished = zeroIfNull(request.getActualFinishedQuantity());
        BigDecimal semiFinished = zeroIfNull(request.getActualSemiFinishedQuantity());
        if (finished.add(semiFinished).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "实际产量必须大于 0")
                    .withHint("请录入实际成品产量或半成品产量")
                    .withHintTarget("实际产量");
        }

        BigDecimal planned = zeroIfNull(plan.getPlannedQuantity());
        if (planned.compareTo(BigDecimal.ZERO) > 0
                && finished.compareTo(planned) > 0
                && isBlank(request.getQuantityVarianceReason())) {
            throw new BusinessException(409, "实际产量超过计划产量, 必须选择超产原因")
                    .withCode("PRODUCTION_OVER_PLAN_REASON_REQUIRED")
                    .withHint("请选择超产原因后再提交; 计划产量: " + planned + ", 实际产量: " + finished)
                    .withHintTarget("超产原因");
        }

        if (isEmpty(request.getRawMaterialConsumptions())
                && isEmpty(request.getSemiFinishedConsumptions())
                && isEmpty(request.getAuxiliaryConsumptions())
                && isBlank(request.getMaterialVarianceReason())) {
            throw new BusinessException(400, "必须录入实际领用明细")
                    .withHint("请至少录入原料、半成品或辅料领用; 确无领用时请选择物料差异原因")
                    .withHintTarget("实际领用");
        }

        if (isEmpty(request.getLaborSegments()) && isBlank(request.getLaborDeferredReason())) {
            throw new BusinessException(400, "必须录入人效或选择工时延期原因")
                    .withHint("请录入工时/人数, 或选择“工时稍后补录”等延期原因")
                    .withHintTarget("人效");
        }
    }

    private void appendConsumptionLines(String factoryId, String planId, String sourceType,
                                        List<ProductionSettlementRequest.ConsumptionLine> requestLines,
                                        List<ProductionSettlementConsumption> target) {
        if (isEmpty(requestLines)) {
            return;
        }
        for (ProductionSettlementRequest.ConsumptionLine requestLine : requestLines) {
            ProductionSettlementConsumption line = new ProductionSettlementConsumption();
            line.setFactoryId(factoryId);
            line.setProductionPlanId(planId);
            line.setSourceType(sourceType);
            line.setMaterialBatchId(trimToNull(requestLine.getMaterialBatchId()));
            line.setSemiFinishedInventoryId(requestLine.getSemiFinishedInventoryId());
            line.setMaterialTypeId(trimToNull(requestLine.getMaterialTypeId()));
            line.setBatchNumber(trimToNull(requestLine.getBatchNumber()));
            line.setQuantity(requestLine.getQuantity());
            line.setUnit(trimToNull(requestLine.getUnit()));
            line.setWarehouseId(trimToNull(requestLine.getWarehouseId()));
            line.setNote(trimToNull(requestLine.getNote()));
            line.setAvailableBefore(resolveAvailableBefore(factoryId, sourceType, requestLine));
            target.add(line);
        }
    }

    private BigDecimal resolveAvailableBefore(String factoryId, String sourceType,
                                              ProductionSettlementRequest.ConsumptionLine line) {
        if ("SEMI_FINISHED".equals(sourceType)) {
            if (line.getSemiFinishedInventoryId() == null) {
                throw new BusinessException(400, "半成品领用必须选择半成品库存")
                        .withHintTarget("半成品领用");
            }
            SemiFinishedInventory inventory = semiFinishedInventoryRepository.findByIdForUpdate(line.getSemiFinishedInventoryId())
                    .orElseThrow(() -> new BusinessException(404, "半成品库存不存在: " + line.getSemiFinishedInventoryId())
                            .withHintTarget("半成品领用"));
            if (!factoryId.equals(inventory.getFactoryId())) {
                throw new BusinessException(403, "无权领用其他工厂的半成品库存")
                        .withHintTarget("半成品领用");
            }
            BigDecimal available = zeroIfNull(inventory.getAvailableQuantity());
            ensureQuantityWithinAvailable("半成品库存 " + inventory.getIntermediateBatchNo(), line.getQuantity(), available, "半成品领用");
            return available;
        }

        if (isBlank(line.getMaterialBatchId())) {
            throw new BusinessException(400, "原料/辅料领用必须选择批次")
                    .withHintTarget("实际领用");
        }
        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(line.getMaterialBatchId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "原料批次不存在: " + line.getMaterialBatchId())
                        .withHintTarget("实际领用"));
        BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
        ensureQuantityWithinAvailable("原料批次 " + batch.getBatchNumber(), line.getQuantity(), available, "实际领用");
        if (line.getBatchNumber() == null) {
            line.setBatchNumber(batch.getBatchNumber());
        }
        if (line.getMaterialTypeId() == null) {
            line.setMaterialTypeId(batch.getMaterialTypeId());
        }
        if (line.getUnit() == null) {
            line.setUnit(batch.getQuantityUnit());
        }
        if (line.getWarehouseId() == null) {
            line.setWarehouseId(batch.getWarehouseId());
        }
        return available;
    }

    private void ensureQuantityWithinAvailable(String label, BigDecimal requested, BigDecimal available, String hintTarget) {
        BigDecimal qty = zeroIfNull(requested);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, label + " 领用数量必须大于 0")
                    .withHintTarget(hintTarget);
        }
        if (qty.compareTo(available) > 0) {
            throw new BusinessException(409, label + " 领用超出可用量; 可用: " + available + ", 本次领用: " + qty)
                    .withCode("PRODUCTION_CONSUMPTION_EXCEEDS_AVAILABLE")
                    .withHint("请将领用数量调整到不超过 " + available)
                    .withHintTarget(hintTarget);
        }
    }

    private List<ProductionSettlementLabor> toLaborLines(String factoryId, String planId, String settlementId,
                                                         List<ProductionSettlementRequest.LaborSegment> requestLines) {
        if (isEmpty(requestLines)) {
            return Collections.emptyList();
        }
        List<ProductionSettlementLabor> result = new ArrayList<>();
        for (ProductionSettlementRequest.LaborSegment requestLine : requestLines) {
            ProductionSettlementLabor line = new ProductionSettlementLabor();
            line.setSettlementId(settlementId);
            line.setFactoryId(factoryId);
            line.setProductionPlanId(planId);
            line.setWorkerId(requestLine.getWorkerId());
            line.setWorkerName(trimToNull(requestLine.getWorkerName()));
            line.setWorkType(trimToNull(requestLine.getWorkType()));
            line.setMinutes(requestLine.getMinutes());
            line.setHeadcount(requestLine.getHeadcount() != null ? requestLine.getHeadcount() : 1);
            line.setHourlyRate(requestLine.getHourlyRate());
            line.setLaborCost(requestLine.getLaborCost());
            line.setNote(trimToNull(requestLine.getNote()));
            result.add(line);
        }
        return result;
    }

    private ProductionSettlementResponse toSettlementResponse(ProductionSettlement settlement, List<String> warnings) {
        return ProductionSettlementResponse.builder()
                .settlementId(settlement.getId())
                .productionPlanId(settlement.getProductionPlanId())
                .planNumber(settlement.getPlanNumber())
                .status(settlement.getPlanStatusAfter() != null ? settlement.getPlanStatusAfter().name() : null)
                .plannedQuantity(settlement.getPlannedQuantity())
                .actualFinishedQuantity(settlement.getActualFinishedQuantity())
                .actualSemiFinishedQuantity(settlement.getActualSemiFinishedQuantity())
                .quantityUnit(settlement.getQuantityUnit())
                .postingStatus(settlement.getPostingStatus())
                .postingMessage(settlement.getPostingMessage())
                .warehouseReceivedQuantity(settlement.getWarehouseReceivedQuantity())
                .warehouseVarianceQuantity(settlement.getWarehouseVarianceQuantity())
                .finishedGoodsBatchId(settlement.getFinishedGoodsBatchId())
                .transitLedgerId(settlement.getTransitLedgerId())
                .warnings(warnings != null ? warnings : Collections.emptyList())
                .createdClearingLedgerIds(settlement.getTransitLedgerId() != null
                        ? List.of(settlement.getTransitLedgerId())
                        : Collections.emptyList())
                .createdInventoryTxnIds(Collections.emptyList())
                .build();
    }

    private ProductionSettlement requireProductionSettlement(String factoryId, String planId) {
        if (productionSettlementRepository == null) {
            throw new BusinessException(500, "生产结单服务未初始化")
                    .withHint("请确认 production_settlements migration 已执行")
                    .withHintTarget("生产结单");
        }
        return productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .orElseThrow(() -> new BusinessException(404, "该生产计划尚未结单, 不能确认入库")
                        .withHint("请先由生产文员录入实际产量、实际领用和人效并提交结单")
                        .withHintTarget("仓库实收"));
    }

    private void ensurePostingDependencies() {
        if (finishedGoodsBatchRepository == null || productionTransitLedgerRepository == null || warehouseResolver == null) {
            throw new BusinessException(500, "生产入库过账服务未初始化")
                    .withHint("请联系管理员检查成品库存、仓库解析和中转挂账组件")
                    .withHintTarget("仓库实收");
        }
    }

    private BigDecimal receiptTolerance(String unit) {
        String normalized = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "";
        if ("kg".equals(normalized) || "公斤".equals(normalized) || "千克".equals(normalized)) {
            return new BigDecimal("10.00");
        }
        if ("g".equals(normalized) || "克".equals(normalized)) {
            return new BigDecimal("10000.00");
        }
        return BigDecimal.ZERO;
    }

    private FinishedGoodsBatch createFinishedGoodsFromReceipt(ProductionPlan plan,
                                                              ProductionSettlement settlement,
                                                              BigDecimal received,
                                                              String unit,
                                                              Long receivedBy) {
        if (isBlank(plan.getProductTypeId())) {
            throw new BusinessException(409, "生产计划缺少产品类型, 不能生成成品库存")
                    .withHint("请先修正生产计划产品类型")
                    .withHintTarget("仓库实收");
        }
        String batchNumber = finishedGoodsBatchNumber(settlement);
        Optional<FinishedGoodsBatch> existing =
                finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(settlement.getFactoryId(), batchNumber);
        if (existing.isPresent()) {
            return existing.get();
        }

        ProductType productType = plan.getProductTypeId() != null
                ? productTypeRepository.findById(plan.getProductTypeId()).orElse(null)
                : null;

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(settlement.getFactoryId());
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId(plan.getProductTypeId());
        batch.setProductName(productType != null ? productType.getName() : null);
        batch.setProducedQuantity(received);
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit(unit);
        batch.setUnitPrice(productType != null ? productType.getUnitPrice() : null);
        batch.setProductionDate(LocalDate.now());
        int shelfLifeDays = productType != null && productType.getShelfLifeDays() != null
                ? productType.getShelfLifeDays()
                : 180;
        batch.setExpireDate(LocalDate.now().plusDays(shelfLifeDays));
        batch.setStorageLocation("仓库确认入库");
        batch.setProductionPlanId(plan.getId());
        batch.setWarehouseId(warehouseResolver.resolveLogisticsId(settlement.getFactoryId()));
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        batch.setCreatedBy(receivedBy != null ? receivedBy : 0L);
        batch.setRemark("生产结单仓库确认入库: " + settlement.getPlanNumber());
        return finishedGoodsBatchRepository.save(batch);
    }

    private String finishedGoodsBatchNumber(ProductionSettlement settlement) {
        String planNumber = settlement.getPlanNumber() != null ? settlement.getPlanNumber() : settlement.getProductionPlanId();
        String raw = "FG-" + planNumber;
        if (raw.length() <= 64) {
            return raw;
        }
        String suffix = settlement.getId() != null && settlement.getId().length() >= 8
                ? settlement.getId().substring(0, 8)
                : UUID.randomUUID().toString().substring(0, 8);
        return raw.substring(0, 55) + "-" + suffix;
    }

    private ProductionTransitLedger createTransitLedger(ProductionSettlement settlement,
                                                        BigDecimal reported,
                                                        BigDecimal received,
                                                        BigDecimal variance,
                                                        BigDecimal tolerance,
                                                        String unit,
                                                        ProductionWarehouseReceiptRequest request,
                                                        Long receivedBy) {
        String responsibility = firstNonBlank(request.getResponsibilitySide(), "PENDING");
        if (!Set.of("PENDING", "PRODUCTION", "WAREHOUSE", "WEIGHING_ERROR").contains(responsibility)) {
            throw new BusinessException(400, "责任归属无效: " + responsibility)
                    .withHint("请选择 PENDING、PRODUCTION、WAREHOUSE 或 WEIGHING_ERROR")
                    .withHintTarget("责任归属");
        }

        ProductionTransitLedger ledger = new ProductionTransitLedger();
        ledger.setId(UUID.randomUUID().toString());
        ledger.setFactoryId(settlement.getFactoryId());
        ledger.setSettlementId(settlement.getId());
        ledger.setProductionPlanId(settlement.getProductionPlanId());
        ledger.setPlanNumber(firstNonBlank(settlement.getPlanNumber(), settlement.getProductionPlanId()));
        ledger.setLedgerType("FINISHED_GOODS_RECEIPT");
        ledger.setReportedQuantity(reported);
        ledger.setConfirmedQuantity(received);
        ledger.setVarianceQuantity(variance);
        ledger.setToleranceQuantity(tolerance);
        ledger.setQuantityUnit(unit);
        ledger.setVarianceReason(trimToNull(request.getVarianceReason()));
        ledger.setResponsibilitySide(responsibility);
        ledger.setStatus("OPEN");
        ledger.setNote(trimToNull(request.getVarianceNote()));
        ledger.setCreatedBy(receivedBy);
        ledger.setCreatedAt(LocalDateTime.now());
        return productionTransitLedgerRepository.save(ledger);
    }

    private ProductionWarehouseReceiptResponse toWarehouseReceiptResponse(ProductionSettlement settlement,
                                                                         String message,
                                                                         List<String> warnings) {
        return ProductionWarehouseReceiptResponse.builder()
                .settlementId(settlement.getId())
                .productionPlanId(settlement.getProductionPlanId())
                .planNumber(settlement.getPlanNumber())
                .productionReportedQuantity(settlement.getActualFinishedQuantity())
                .warehouseReceivedQuantity(settlement.getWarehouseReceivedQuantity())
                .varianceQuantity(settlement.getWarehouseVarianceQuantity())
                .toleranceQuantity(receiptTolerance(settlement.getQuantityUnit()))
                .quantityUnit(firstNonBlank(settlement.getQuantityUnit(), "件"))
                .postingStatus(settlement.getPostingStatus())
                .finishedGoodsBatchId(settlement.getFinishedGoodsBatchId())
                .transitLedgerId(settlement.getTransitLedgerId())
                .message(message)
                .warnings(warnings != null ? warnings : Collections.emptyList())
                .build();
    }

    private boolean isLiushanmenFactory(String factoryId) {
        return "F006".equalsIgnoreCase(factoryId);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public void cancelProductionPlan(String factoryId, String planId, String reason) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 已完成的计划不能取消
        if (plan.getStatus() == ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "已完成的生产计划不能取消")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        // 六扇门红线 (审计 Tier0 #01): 待审批 (PENDING_APPROVAL) 的计划已进入
        // PRODUCTION_REVERSAL 审批流, 不允许通过本接口直接取消绕过主管审批.
        // 防止: COMPLETED → request-cancel (PENDING_APPROVAL) → 旧 cancel 直接 CANCELLED 绕审批 + 留下悬挂工作流实例.
        if (plan.getStatus() == ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "该计划正在撤回审批流程中, 不能直接取消")
                    .withHint("请在「撤回审批」列表中处理, 或等待主管审批");
        }

        // Issue #759: 锁定的计划不可取消
        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            throw new BusinessException(409, "生产计划已锁定, 不可取消")
                    .withHint("先解锁该计划再尝试取消");
        }

        // 更新状态
        plan.setStatus(ProductionPlanStatus.CANCELLED);
        plan.setNotes(plan.getNotes() != null ?
            plan.getNotes() + "\n取消原因：" + reason :
            "取消原因：" + reason);
        productionPlanRepository.save(plan);

        // 级联关闭关联的工序任务
        if (plan.getProductTypeId() != null) {
            var tasks = processTaskRepository.findByFactoryIdAndProductTypeId(
                    factoryId, plan.getProductTypeId());
            int closedCount = 0;
            for (var task : tasks) {
                if (task.getStatus() != com.cretas.aims.entity.enums.ProcessTaskStatus.CLOSED
                        && task.getStatus() != com.cretas.aims.entity.enums.ProcessTaskStatus.COMPLETED) {
                    task.setStatus(com.cretas.aims.entity.enums.ProcessTaskStatus.CLOSED);
                    processTaskRepository.save(task);
                    closedCount++;
                }
            }
            if (closedCount > 0) {
                log.info("级联关闭 {} 个工序任务: planId={}, productTypeId={}", closedCount, planId, plan.getProductTypeId());
            }
        }

        log.info("取消生产计划: planId={}, reason={}", planId, reason);
    }

    /**
     * SP12 T3: 申请撤回/取消已完成的生产计划（触发审批流）.
     * COMPLETED → PENDING_APPROVAL，启动 PRODUCTION_REVERSAL 审批流。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public String requestCancelWithApproval(String factoryId, String planId, String reason, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂");
        }
        if (plan.getStatus() != ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "只有已完成的计划才能申请撤回")
                    .withHint("请刷新生产计划列表查看最新状态");
        }
        if (workflowEngine == null || !workflowEngine.hasActiveWorkflow(factoryId, "PRODUCTION_REVERSAL")) {
            throw new BusinessException(409, "该工厂未配置生产撤回审批流程，请联系管理员配置")
                    .withHint("前往工作流设计器配置 PRODUCTION_REVERSAL 审批流");
        }

        plan.setStatus(ProductionPlanStatus.PENDING_APPROVAL);
        plan.setNotes(plan.getNotes() != null
                ? plan.getNotes() + "\n撤回申请原因：" + reason
                : "撤回申请原因：" + reason);
        productionPlanRepository.save(plan);

        java.util.Map<String, Object> context = java.util.Map.of(
                "planId", planId,
                "reason", reason,
                "factoryId", factoryId);
        var instance = workflowEngine.startWorkflow(factoryId, "PRODUCTION_REVERSAL", planId, context, userId);
        log.info("生产撤回审批已发起: planId={}, instanceId={}", planId, instance.getId());
        return instance.getId();
    }

    /**
     * SP12 T3: 审批通过后执行撤回（仅供 workflow 回调调用）.
     * PENDING_APPROVAL → CANCELLED，级联关闭关联工序任务。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void executeCancelApproved(String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (plan.getStatus() != ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "生产计划不处于待审批状态，无法执行撤回")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        plan.setStatus(ProductionPlanStatus.CANCELLED);
        productionPlanRepository.save(plan);

        // 级联关闭关联的工序任务（复用 cancelProductionPlan 逻辑）
        if (plan.getProductTypeId() != null) {
            var tasks = processTaskRepository.findByFactoryIdAndProductTypeId(
                    plan.getFactoryId(), plan.getProductTypeId());
            int closedCount = 0;
            for (var task : tasks) {
                if (task.getStatus() != com.cretas.aims.entity.enums.ProcessTaskStatus.CLOSED
                        && task.getStatus() != com.cretas.aims.entity.enums.ProcessTaskStatus.COMPLETED) {
                    task.setStatus(com.cretas.aims.entity.enums.ProcessTaskStatus.CLOSED);
                    processTaskRepository.save(task);
                    closedCount++;
                }
            }
            if (closedCount > 0) {
                log.info("SP12 T3 级联关闭 {} 个工序任务: planId={}", closedCount, planId);
            }
        }
        log.info("SP12 T3 生产撤回审批通过，计划已取消: planId={}", planId);
    }

    /**
     * 锁定生产计划 (Issue #759, 2026-05-17).
     */
    @Override
    @Transactional
    public ProductionPlanDTO lockProductionPlan(String factoryId, String planId, String reason, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 已取消/已完成的计划不需要锁定
        if (plan.getStatus() == ProductionPlanStatus.CANCELLED
                || plan.getStatus() == ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "已取消或已完成的生产计划无需锁定");
        }

        if (Boolean.TRUE.equals(plan.getIsLocked())) {
            log.info("生产计划已处于锁定状态, 重复 lock 调用: planId={}", planId);
            return toDTOWithConversionInfo(plan);
        }

        plan.setIsLocked(true);
        plan.setLockReason(reason);
        plan.setLockedAt(java.time.LocalDateTime.now());
        plan.setLockedBy(userId);
        productionPlanRepository.save(plan);

        log.info("锁定生产计划: planId={}, userId={}, reason={}", planId, userId, reason);
        return toDTOWithConversionInfo(plan);
    }

    /**
     * 解锁生产计划 (Issue #759, 2026-05-17).
     */
    @Override
    @Transactional
    public ProductionPlanDTO unlockProductionPlan(String factoryId, String planId, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        if (!Boolean.TRUE.equals(plan.getIsLocked())) {
            log.info("生产计划未锁定, 重复 unlock 调用: planId={}", planId);
            return toDTOWithConversionInfo(plan);
        }

        plan.setIsLocked(false);
        // 保留 lock_reason / locked_at / locked_by 作为最后一次记录
        productionPlanRepository.save(plan);

        log.info("解锁生产计划: planId={}, userId={}", planId, userId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO pauseProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 只能暂停进行中的计划
        if (plan.getStatus() != ProductionPlanStatus.IN_PROGRESS) {
            throw new BusinessException(409, "只能暂停进行中的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        plan.setStatus(ProductionPlanStatus.PAUSED);
        plan = productionPlanRepository.save(plan);

        log.info("暂停生产: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO resumeProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 只能恢复暂停的计划
        if (plan.getStatus() != ProductionPlanStatus.PAUSED) {
            throw new BusinessException(409, "只能恢复暂停的生产计划")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan = productionPlanRepository.save(plan);

        log.info("恢复生产: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public ProductionPlanDTO updateActualCosts(String factoryId, String planId,
                                               BigDecimal materialCost,
                                               BigDecimal laborCost,
                                               BigDecimal equipmentCost,
                                               BigDecimal otherCost) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 更新实际成本
        if (materialCost != null) {
            plan.setActualMaterialCost(materialCost);
        }
        if (laborCost != null) {
            plan.setActualLaborCost(laborCost);
        }
        if (equipmentCost != null) {
            plan.setActualEquipmentCost(equipmentCost);
        }
        if (otherCost != null) {
            plan.setActualOtherCost(otherCost);
        }

        plan = productionPlanRepository.save(plan);

        log.info("更新实际成本: planId={}", planId);
        return toDTOWithConversionInfo(plan);
    }

    @Override
    @Transactional
    public void assignMaterialBatches(String factoryId, String planId, List<String> batchIds) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        assignMaterialBatchesToPlan(plan, batchIds);
        log.info("分配原材料批次: planId={}, batchCount={}", planId, batchIds.size());
    }

    @Override
    @Transactional
    public void recordMaterialConsumption(String factoryId, String planId, String batchId, BigDecimal quantity) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 检查库存是否足够
        if (batch.getCurrentQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "批次库存不足")
                    .withHint("请减少计划数量, 或先入库补货");
        }

        // 创建消耗记录
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(factoryId);
        consumption.setProductionPlanId(planId);
        consumption.setBatchId(batchId);
        consumption.setQuantity(quantity);
        consumption.setUnitPrice(batch.getUnitPrice());
        consumption.setTotalCost(quantity.multiply(batch.getUnitPrice()));
        consumption.setConsumptionTime(LocalDateTime.now());
        consumption.setRecordedBy(plan.getCreatedBy());
        materialConsumptionRepository.save(consumption);

        // 更新批次库存
        // 注意: currentQuantity 是计算属性，通过增加 usedQuantity 来减少 currentQuantity
        batch.setUsedQuantity(batch.getUsedQuantity().add(quantity));
        batch.setLastUsedAt(LocalDateTime.now());
        if (batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }
        materialBatchRepository.save(batch);

        log.info("记录材料消耗: planId={}, batchId={}, quantity={}", planId, batchId, quantity);
    }

    @Override
    public Map<String, Object> getProductionStatistics(String factoryId, LocalDate startDate, LocalDate endDate) {
        // 暂时注释 - 数据库表中没有planned_date字段
        // List<ProductionPlan> plans = productionPlanRepository.findByDateRange(factoryId, startDate, endDate);
        List<ProductionPlan> plans = new ArrayList<>();

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalPlans", plans.size());
        statistics.put("completedPlans", plans.stream().filter(p -> p.getStatus() == ProductionPlanStatus.COMPLETED).count());
        statistics.put("inProgressPlans", plans.stream().filter(p -> p.getStatus() == ProductionPlanStatus.IN_PROGRESS).count());
        statistics.put("pendingPlans", plans.stream().filter(p -> p.getStatus() == ProductionPlanStatus.PENDING).count());

        // 计算总成本
        BigDecimal totalCost = plans.stream()
                .filter(p -> p.getStatus() == ProductionPlanStatus.COMPLETED)
                .map(p -> {
                    BigDecimal cost = BigDecimal.ZERO;
                    if (p.getActualMaterialCost() != null) cost = cost.add(p.getActualMaterialCost());
                    if (p.getActualLaborCost() != null) cost = cost.add(p.getActualLaborCost());
                    if (p.getActualEquipmentCost() != null) cost = cost.add(p.getActualEquipmentCost());
                    if (p.getActualOtherCost() != null) cost = cost.add(p.getActualOtherCost());
                    return cost;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        statistics.put("totalCost", totalCost);

        return statistics;
    }

    @Override
    public List<ProductionPlanDTO> getPendingPlansToExecute(String factoryId) {
        // N1b: 待执行列表即车间未完成列表, 包含已下达未开工(PENDING)和进行中(IN_PROGRESS)。
        List<ProductionPlan> plans = productionPlanRepository.findByFactoryIdAndStatusIn(
            factoryId,
            List.of(ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS)
        );
        return plans.stream()
            .filter(plan -> plan.getDeletedAt() == null)
            .map(productionPlanMapper::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<ProductionPlanDTO> batchCreateProductionPlans(String factoryId,
                                                              List<CreateProductionPlanRequest> requests,
                                                              Long userId) {
        return requests.stream()
                .map(request -> createProductionPlan(factoryId, request, userId))
                .collect(Collectors.toList());
    }

    @Override
    public byte[] exportProductionPlans(String factoryId, LocalDate startDate, LocalDate endDate,
                                        boolean maskPrice) {
        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): maskPrice parameter wired
        // through but currently no-op — ProductionPlanImportDTO has no cost/price
        // columns. When export is extended to include actualMaterialCost / laborCost /
        // etc., honor maskPrice by swapping to a masked DTO. PR #450 sweep matrix row 9.
        List<ProductionPlan> plans = productionPlanRepository.findByFactoryId(factoryId);
        // Filter by date range using expectedCompletionDate
        List<ProductionPlanImportDTO> exportData = plans.stream()
                .filter(p -> {
                    if (startDate == null || endDate == null) return true;
                    LocalDate date = p.getExpectedCompletionDate();
                    return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
                })
                .map(this::toImportDTO)
                .collect(Collectors.toList());
        return excelUtil.exportToExcel(exportData, ProductionPlanImportDTO.class, "生产计划");
    }

    @Override
    public byte[] generateImportTemplate() {
        return excelUtil.generateTemplate(ProductionPlanImportDTO.class, "生产计划导入模板");
    }

    @Override
    @Transactional
    public ImportResult<ProductionPlanDTO> importProductionPlansFromExcel(String factoryId, InputStream inputStream, Long userId) {
        List<ProductionPlanImportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream, ProductionPlanImportDTO.class);
        } catch (Exception e) {
            throw new BusinessException(400, "Excel文件解析失败: " + e.getMessage())
                    .withHint("请检查 Excel 文件格式是否正确, 列是否匹配模板").withHintTarget("file");
        }

        ImportResult<ProductionPlanDTO> result = ImportResult.create(excelData.size());

        for (int i = 0; i < excelData.size(); i++) {
            int rowNumber = i + 2; // Excel row (header is row 1)
            ProductionPlanImportDTO row = excelData.get(i);
            try {
                // Validate required fields
                if (row.getProductName() == null || row.getProductName().trim().isEmpty()) {
                    result.addFailure(rowNumber, "产品名称不能为空", toJson(row));
                    continue;
                }
                if (row.getPlannedQuantity() == null) {
                    result.addFailure(rowNumber, "计划数量不能为空", toJson(row));
                    continue;
                }
                if (row.getExpectedCompletionDate() == null) {
                    result.addFailure(rowNumber, "预计完成日期不能为空", toJson(row));
                    continue;
                }

                // Resolve product name to ID
                Optional<ProductType> productType = productTypeRepository
                        .findByFactoryIdAndName(factoryId, row.getProductName().trim());
                if (!productType.isPresent()) {
                    result.addFailure(rowNumber, "产品 \"" + row.getProductName() + "\" 不存在", toJson(row));
                    continue;
                }

                // Build CreateProductionPlanRequest
                CreateProductionPlanRequest request = new CreateProductionPlanRequest();
                request.setProductTypeId(productType.get().getId());
                request.setPlannedQuantity(row.getPlannedQuantity());
                request.setPlannedDate(row.getExpectedCompletionDate());
                request.setExpectedCompletionDate(row.getExpectedCompletionDate());
                request.setPriority(row.getPriority() != null ? row.getPriority() : 5);
                request.setCustomerOrderNumber(row.getCustomerOrderNumber());
                request.setNotes(row.getNotes());
                request.setEstimatedWorkers(row.getEstimatedWorkers());
                request.setSourceType(PlanSourceType.EXCEL_IMPORT);

                // Resolve optional supervisor username
                if (row.getSupervisorUsername() != null && !row.getSupervisorUsername().trim().isEmpty()) {
                    Optional<User> supervisor = userRepository.findByUsername(row.getSupervisorUsername().trim());
                    if (supervisor.isPresent()) {
                        request.setAssignedSupervisorId(supervisor.get().getId());
                    } else {
                        log.warn("第{}行: 主管用户名 \"{}\" 未找到，已忽略", rowNumber, row.getSupervisorUsername());
                    }
                }

                ProductionPlanDTO created = createProductionPlan(factoryId, request, userId);
                result.addSuccess(created);
            } catch (Exception e) {
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJson(row));
            }
        }

        log.info("Excel导入完成: 总计={}, 成功={}, 失败={}", result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    /**
     * 分配原材料批次到生产计划
     */
    private void assignMaterialBatchesToPlan(ProductionPlan plan, List<String> batchIds) {
        for (String batchId : batchIds) {
            MaterialBatch batch = materialBatchRepository.findById(batchId)
                    .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

            // 检查批次状态
            if (batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
                throw new BusinessException(409, "批次 " + batch.getBatchNumber() + " 不可用")
                        .withHint("请刷新批次列表后重新选择, 或选择其他可用批次");
            }

            // 创建关联
            ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
            usage.setProductionPlanId(plan.getId());
            usage.setMaterialBatchId(batchId);
            usage.setPlannedQuantity(BigDecimal.ZERO); // 需要根据实际需求设置
            planBatchUsageRepository.save(usage);
        }
    }

    /**
     * 将计划实体转换为DTO并填充转换率信息
     */
    private ProductionPlanDTO toDTOWithConversionInfo(ProductionPlan plan) {
        ProductionPlanDTO dto = productionPlanMapper.toDTO(plan);
        enrichWithConversionRateInfo(dto, plan.getFactoryId(), plan.getProductTypeId());
        enrichWithAssignmentNames(dto, plan);
        return dto;
    }

    private void enrichWithAssignmentNames(ProductionPlanDTO dto, ProductionPlan plan) {
        if (plan.getSuggestedProductionLineId() != null) {
            productionLineRepository.findById(plan.getSuggestedProductionLineId())
                .ifPresent(line -> dto.setSuggestedProductionLineName(line.getName()));
        }
        if (plan.getAssignedSupervisorId() != null) {
            userRepository.findById(plan.getAssignedSupervisorId())
                .ifPresent(user -> dto.setAssignedSupervisorName(user.getFullName()));
        }
    }

    /**
     * 填充转换率配置状态到DTO
     * 检查该产品类型是否有配置转换率
     */
    private void enrichWithConversionRateInfo(ProductionPlanDTO dto, String factoryId, String productTypeId) {
        if (factoryId == null || productTypeId == null) {
            dto.setConversionRateConfigured(false);
            return;
        }

        // 查询该产品类型的所有转换率配置
        List<MaterialProductConversion> conversions =
            conversionRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId);

        if (conversions != null && !conversions.isEmpty()) {
            // 有转换率配置
            dto.setConversionRateConfigured(true);

            // 如果只有一个配置，直接返回该转换率和损耗率
            // 如果有多个配置（多种原材料），返回第一个作为示例（前端可以点击查看详情）
            MaterialProductConversion firstConversion = conversions.get(0);
            dto.setConversionRate(firstConversion.getConversionRate());
            dto.setWastageRate(firstConversion.getWastageRate());

            log.debug("产品类型 {} 已配置转换率: {} 个配置", productTypeId, conversions.size());
        } else {
            // 没有转换率配置
            dto.setConversionRateConfigured(false);
            dto.setConversionRate(null);
            dto.setWastageRate(null);

            log.debug("产品类型 {} 未配置转换率", productTypeId);
        }
    }

    /**
     * 将对象转为JSON字符串（用于导入失败记录）
     */
    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /**
     * 将生产计划实体转为导入/导出DTO
     */
    @Override
    @Transactional
    public ProductionBatch createBatchFromPlan(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        if (plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只有待处理的计划可以转为批次")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        // N1 (2026-06-12): 转批次=开工不再受原料库存预检阻断; 缺料只记录预警。
        runConfiguredValidation(factoryId, "START", java.util.Map.of("planId", planId));
        validateMaterialStockSufficient(factoryId, plan);

        // 查产品名称
        String productName = "未知产品";
        Optional<ProductType> ptOpt = productTypeRepository.findById(plan.getProductTypeId());
        if (ptOpt.isPresent()) {
            productName = ptOpt.get().getName();
        }

        // 生成批次号
        String batchNumber = "PB-" + plan.getPlanNumber() + "-" + System.currentTimeMillis() % 100000;
        if (productionBatchRepository.existsByFactoryIdAndBatchNumber(factoryId, batchNumber)) {
            batchNumber = batchNumber + "-" + (int)(Math.random() * 1000);
        }

        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(factoryId)
                .batchNumber(batchNumber)
                .productionPlanId(plan.getId())
                .productTypeId(plan.getProductTypeId())
                .productName(productName)
                .plannedQuantity(plan.getPlannedQuantity())
                .quantity(plan.getPlannedQuantity())
                .unit("kg")
                // GAP 3/4 (F006): 转批次=开始生产, 批次直接 IN_PROGRESS + 设 startTime,
                // 使逐道报工 YieldBatchSelect (筛 status=IN_PROGRESS) 立刻可见.
                .status(ProductionBatchStatus.IN_PROGRESS)
                .startTime(LocalDateTime.now())
                .workerCount(plan.getEstimatedWorkers())
                .notes("从计划 " + plan.getPlanNumber() + " 创建")
                .createdBy(plan.getCreatedBy())
                .build();

        // 映射产线建议
        if (plan.getSuggestedProductionLineId() != null) {
            productionLineRepository.findById(plan.getSuggestedProductionLineId()).ifPresent(line -> {
                batch.setEquipmentName(line.getName());
            });
        }

        // 映射指派主管
        if (plan.getAssignedSupervisorId() != null) {
            batch.setSupervisorId(plan.getAssignedSupervisorId());
            userRepository.findById(plan.getAssignedSupervisorId()).ifPresent(user -> {
                batch.setSupervisorName(user.getFullName());
            });
        }

        ProductionBatch saved = productionBatchRepository.save(batch);

        // GAP 3/4 (F006): 转批次时 spawn 报工任务 (报工需要任务实例).
        //   计划级免工序报工 (六扇门 Wave2, V20261017_01): plan.skipProcessReporting=true 或 产品 0 工序
        //   → spawn 2 个批次级哨兵任务 (领料+产出); 否则逐道从 product_work_processes 模板 spawn。
        //   头尾责任人 = 计划的 assignedSupervisorId, 头尾同一人 (一人兼)。
        //   Fable 审计修复 (问题3 — 撤回 claim): 计划当前无独立的 materialResponsibleId / outputResponsibleId
        //   字段, 也无 spawn 后分设头尾责任人的代码。"web/RN 可 updatePlan 分设头尾责任人"不成立 (已删除该 claim)。
        //   头尾分设是后续 (P2): 需 plan 加两字段 + web 头尾责任人选择 UI; 一人兼对六扇门首期够用。
        //   任务级粒度: 操作员可在 WorkProcessTask.start 时自分配 / 主管经 WorkProcessTask.updatePlan 改单个任务的 assignedTo。
        // fail-soft: spawn 抛异常时不阻塞批次创建.
        if (workProcessTaskService != null) {
            try {
                Long responsibleId = plan.getAssignedSupervisorId();
                List<WorkProcessTaskDTO> spawnedTasks =
                        workProcessTaskService.spawnTasks(factoryId, saved.getId(), saved.getProductTypeId(),
                                plan.getSkipProcessReporting(), responsibleId, responsibleId);
                mirrorWorkProcessTasksForRn(factoryId, saved, plan, spawnedTasks);
                log.info("转批次已 spawn 报工任务: batchId={}, productTypeId={}, skipProcessReporting={}, taskCount={}",
                        saved.getId(), saved.getProductTypeId(), plan.getSkipProcessReporting(), spawnedTasks.size());
            } catch (Exception e) {
                log.warn("转批次 spawn 报工任务失败 (fail-soft, 不阻塞批次创建): batchId={}, err={}", saved.getId(), e.getMessage());
            }
        }

        // 更新计划状态为 IN_PROGRESS
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setStartTime(LocalDateTime.now());
        productionPlanRepository.save(plan);

        log.info("从计划创建批次: planId={}, batchId={}, batchNumber={}", planId, saved.getId(), saved.getBatchNumber());
        return saved;
    }

    private void mirrorWorkProcessTasksForRn(String factoryId, ProductionBatch batch, ProductionPlan plan,
                                             List<WorkProcessTaskDTO> workTasks) {
        if (workTasks == null || workTasks.isEmpty()) {
            return;
        }
        for (WorkProcessTaskDTO workTask : workTasks) {
            if (workTask.getId() == null || workTask.getWorkProcessId() == null) {
                continue;
            }
            String legacyId = "WPT-" + workTask.getId();
            if (processTaskRepository.existsById(legacyId)) {
                continue;
            }
            ProcessTask mirror = ProcessTask.builder()
                    .id(legacyId)
                    .factoryId(factoryId)
                    .productionRunId("BATCH-" + batch.getId())
                    .productTypeId(firstNonBlank(workTask.getProductTypeId(), batch.getProductTypeId()))
                    .workProcessId(workTask.getWorkProcessId())
                    .sourceCustomerName(plan.getCustomerOrderNumber())
                    .sourceDocType("BATCH")
                    .sourceDocId(String.valueOf(batch.getId()))
                    .plannedQuantity(firstPositive(workTask.getPlannedQuantity(), batch.getPlannedQuantity(), batch.getQuantity()))
                    .completedQuantity(BigDecimal.ZERO)
                    .pendingQuantity(BigDecimal.ZERO)
                    .unit(firstNonBlank(workTask.getOutputUnit(), workTask.getPlannedUnit(), batch.getUnit(), "kg"))
                    .startDate(LocalDate.now())
                    .expectedEndDate(plan.getExpectedCompletionDate())
                    .status(ProcessTaskStatus.PENDING)
                    .createdBy(plan.getCreatedBy() == null ? 0L : plan.getCreatedBy())
                    .notes("mirrorWorkProcessTaskId=" + workTask.getId())
                    .build();
            processTaskRepository.save(mirror);
        }
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        }
        return BigDecimal.ONE;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ProductionPlanImportDTO toImportDTO(ProductionPlan plan) {
        ProductionPlanImportDTO dto = new ProductionPlanImportDTO();
        // Resolve product name
        if (plan.getProductType() != null) {
            dto.setProductName(plan.getProductType().getName());
        } else {
            productTypeRepository.findById(plan.getProductTypeId())
                    .ifPresent(pt -> dto.setProductName(pt.getName()));
        }
        dto.setPlannedQuantity(plan.getPlannedQuantity());
        dto.setExpectedCompletionDate(plan.getExpectedCompletionDate());
        dto.setPriority(plan.getPriority());
        dto.setEstimatedWorkers(plan.getEstimatedWorkers());
        dto.setCustomerOrderNumber(plan.getCustomerOrderNumber());
        dto.setNotes(plan.getNotes());
        // Resolve production line code
        if (plan.getSuggestedProductionLineId() != null) {
            productionLineRepository.findById(plan.getSuggestedProductionLineId())
                    .ifPresent(line -> dto.setProductionLineCode(line.getLineCode()));
        }
        // Resolve supervisor username
        if (plan.getAssignedSupervisorId() != null) {
            userRepository.findById(plan.getAssignedSupervisorId())
                    .ifPresent(user -> dto.setSupervisorUsername(user.getUsername()));
        }
        return dto;
    }

    // -------------------------------------------------------------------------
    // SP2 二次加工
    // -------------------------------------------------------------------------

    /**
     * SP2 二次加工: 基于 WIP 半成品创建二次加工计划。
     *
     * <p>此方法只创建计划 (PENDING), 不扣减 WIP 库存。
     * WIP 扣减在 {@link #startProduction} 时执行 (fail-closed 事务内)。
     *
     * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
     */
    @Override
    @Transactional
    public ProductionPlanDTO createSecondaryPlan(
            String factoryId, Long wipId, java.math.BigDecimal quantity,
            String productTypeId, java.time.LocalDate plannedDate, Long submittedBy) {

        if (wipInventoryService == null) {
            throw new BusinessException(500, "二次加工服务未初始化");
        }

        // 1. 校验 WIP 存在且余量充足 (查询不扣减)
        var availableList = wipInventoryService.listAvailableWip(factoryId);
        com.cretas.aims.entity.SemiFinishedInventory wip = availableList.stream()
                .filter(w -> w.getId().equals(wipId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("半成品库存", "id", wipId));

        if (quantity == null || quantity.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "计划加工数量必须大于 0");
        }
        if (wip.getAvailableQuantity() == null ||
                quantity.compareTo(wip.getAvailableQuantity()) > 0) {
            throw new BusinessException(409, String.format(
                    "半成品可用量不足: 需要 %s, 可用 %s",
                    quantity.stripTrailingZeros().toPlainString(),
                    wip.getAvailableQuantity() != null
                            ? wip.getAvailableQuantity().stripTrailingZeros().toPlainString()
                            : "0"));
        }

        // 2. 校验目标产品类型存在
        com.cretas.aims.entity.ProductType productType = productTypeRepository.findById(productTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型", "id", productTypeId));

        // 3. 生成计划编号
        String planNumber = "SEC-" + factoryId + "-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(
                        plannedDate != null ? plannedDate : java.time.LocalDate.now())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // 4. 构建并保存计划
        ProductionPlan plan = new ProductionPlan();
        // BUG-GOLD-RERUN-SECONDARY-PLAN-500: ProductionPlan @Id 是手动赋值 String, 漏 setId →
        //   persist 抛 IdentifierGenerationException → 500。与常规 createPlan(line 878) 同模式补 UUID。
        plan.setId(java.util.UUID.randomUUID().toString());
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(planNumber);
        plan.setProductTypeId(productTypeId);
        plan.setPlannedQuantity(quantity);
        plan.setPlannedDate(plannedDate != null ? plannedDate : java.time.LocalDate.now());
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(submittedBy);
        // SP2 特有字段
        plan.setPlanSourceType("SECONDARY");
        plan.setSecondarySourceWipId(wipId);

        // Gap A (2026-06-12 多段链): 回填源订单 — secondary plan 从 WIP 派生, 追溯 wip→batch→origin plan→sourceOrder。
        //   否则 /multi-stage-cost 按 source_order_id 查计划时查不到 semi B 段 (stageCount 只见首段 normal plan)。
        //   fail-soft: 追溯失败不阻塞建计划 (二次加工本身可独立于订单)。
        try {
            if (wip.getBatchId() != null) {
                com.cretas.aims.entity.ProductionBatch srcBatch =
                        productionBatchRepository.findById(wip.getBatchId()).orElse(null);
                if (srcBatch != null && srcBatch.getProductionPlanId() != null) {
                    ProductionPlan originPlan =
                            productionPlanRepository.findById(srcBatch.getProductionPlanId()).orElse(null);
                    if (originPlan != null) {
                        plan.setSourceOrderId(originPlan.getSourceOrderId());
                        if (originPlan.getSourceOrderIds() != null && !originPlan.getSourceOrderIds().isEmpty()) {
                            plan.setSourceOrderIds(new java.util.ArrayList<>(originPlan.getSourceOrderIds()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SP2 createSecondaryPlan 源订单回填失败 (fail-soft, 不阻塞建计划): wipId={} err={}",
                    wipId, e.getMessage());
        }

        plan = productionPlanRepository.save(plan);

        log.info("SP2 创建二次加工计划: planId={}, wipId={}, quantity={}, factoryId={}",
                plan.getId(), wipId, quantity, factoryId);

        return toDTOWithConversionInfo(plan);
    }

    /**
     * SP5 双向检索: 供 controller 将已加载 entity 转为 DTO，避免 controller 直接调用 private helper.
     */
    @Override
    public ProductionPlanDTO toPlanDTO(com.cretas.aims.entity.ProductionPlan plan) {
        return toDTOWithConversionInfo(plan);
    }
}
