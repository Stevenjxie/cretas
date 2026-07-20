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
import com.cretas.aims.dto.production.ProductionSettlementBomEligibilityResponse;
import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.dto.production.ProductionTransitClearingRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.utils.ExcelUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final ObjectMapper PROCESS_SHEET_ROW_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository productionBatchRepository;
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
    /** Sprint 3 Track-F: unified cross-business link service (double-write w/ sourceOrderId). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LinkArrayService linkArrayService;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public ProductionPlanServiceImpl(
            ProductionPlanRepository productionPlanRepository,
            ProductionBatchRepository productionBatchRepository,
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
            SalesOrderItemRepository salesOrderItemRepository) {
        this.productionPlanRepository = productionPlanRepository;
        this.productionBatchRepository = productionBatchRepository;
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
    }

    /** SP2 二次加工: WIP 半成品库存扣减. required=false 避免循环依赖风险. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;

    /** R2 (2026-07-04): 结单族 process-row 的 SFI/FG 投料严格扣减 (防 phantom). required=false 兼容单测. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;

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

    /** 逐道报工原料量按行单位记录，结单扣减前转换回批次库存单位。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.UnitConversionService unitConversionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.SalesOrderPlanQuantityNormalizer salesOrderPlanQuantityNormalizer;

    /**
     * T144: 物料库存单位改读 MaterialBatch.quantityUnit (称重批次单位, e.g. kg) 而非
     * RawMaterialType.unit (箱). 保留 rawMaterialTypeRepository 仅作 fallback (无可用批次时).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    /** raw-centric 多成品: 建计划/转批次守卫 —— 断言 owner 启用 workflow 终端覆盖所选成品. required=false 兼容单测. */
    @Autowired(required = false)
    private com.cretas.aims.service.workflow.ProductWorkflowResolutionService workflowResolutionService;

    /** 以销定产批量建计划: 取产品工序名拼 processName (镜像前端 loadBomProcesses). required=false 兼容单测. */
    @Autowired(required = false)
    private com.cretas.aims.service.ProductWorkProcessService productWorkProcessService;

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

    /**
     * BY_STOCK 小结与仓库确认桥接。小结已经完成库存过账，本仓库只读取其会话摘要来补建
     * {@link ProductionSettlement} 元数据，绝不重放小结库存动作。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionInterimSettlementRepository productionInterimSettlementRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementLaborRepository productionSettlementLaborRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionTransitLedgerRepository productionTransitLedgerRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomRecipeRepository bomRecipeRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomRecipeItemRepository bomRecipeItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomYieldSuggestionRepository bomYieldSuggestionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WarehouseResolver warehouseResolver;

    /**
     * 🔴 成本传导 (2026-07-04): 结单族 CUSTOMER_ORDER 成品入库单位成本基准.
     *
     * <p>结单族 {@code createFinishedGoodsFromReceipt} 历史只写 unitPrice(售价) 不写 unitCost(成本) →
     * 成品批次 unitCost=null → 毛利/COGS 对结单族全盲 (期末 COGS 结转 honest-null 排除, 结单族销售无成本)。
     * 复用 {@link OrderCostBreakdownService#computeByPlan} (单一权威成本, 与 SAFETY_STOCK 小结的成本传导
     * 同源 = 逐道 原料+人工+调料+包装, 但结单族生产批不设 ProductionBatch.totalCost, 故不能直接镜像
     * interim-settle 的 computeOutputUnitCost, 改走 MaterialConsumption 派生的权威成本, 与 出厂核算 字节一致)。
     * required=false 兼容单测 (反射注入 mock); 不注入 → unitCost 保持 null (诚实, 不伪造 ¥0)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.yield.OrderCostBreakdownService orderCostBreakdownService;

    /**
     * R1/R2 (2026-06-14): 取消计划按批次定向级联关闭 WorkProcessTask (新表), 取代旧的
     * "按产品类型全关 ProcessTask" (会误关同产品并行另一批次的活跃任务)。
     * required=false 兼容单测 (反射注入) 与无工序任务场景。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository workProcessTaskRepository;

    /**
     * R2 (2026-06-14): 检测 IN_PROGRESS 计划是否已有 YIELD 报工 (有数据 → 禁止直接取消, 导向撤回审批)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionReportRepository productionReportRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProcessSheetRowRepository processSheetRowRepository;

    /**
     * R2 (2026-06-14 v2): 报工整单撤回服务 (恢复 WIP/原料/FGB/工序任务 + 反冲成本)。
     *
     * <p><b>为何用它而非 executeCancelApproved</b>: PRODUCTION_REVERSAL 审批流的"审批通过回调"
     * ({@code executeCancelApproved}) 在主代码中<b>无任何调用方</b> —— 工作流引擎
     * ({@code WorkflowEngineServiceImpl.terminateAtEnd}) 是纯状态机, 到达 APPROVED 终态只设置
     * instance.status, <b>从不回调业务层</b> (无 moduleCode→handler 派发器)。即便 executeCancelApproved
     * 被触发, 它本身也只置 CANCELLED + 级联关任务, <b>不恢复任何库存</b>。
     *
     * <p>而 {@link ReportReversalService} 是<b>自包含且已验证可恢复库存</b>的撤回流:
     * {@code submitReversal} (G1/G2/G3 守卫 + 自身 PENDING 审批) → {@code approveReversal}
     * → {@code executeReversal} (软删报工 + 写 REVERSE SIT 行 + 回放移动均价 + 复位工序任务 +
     * 标 FGB REVERSED + 清回填 costUnitPrice)。
     *
     * <p>故 R2 把"有报工/WIP 的计划取消"导向报工撤回流 (文员先撤报工恢复库存, 报工软删后
     * {@link #hasProductionData} 返 false → 再直接取消空计划)。required=false 兼容单测。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.reversal.ReportReversalService reportReversalService;

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

    /**
     * {@code plannedQuantity} is the planned finished-product output, never the
     * first-process feed quantity.  Raw-material feed is only known after a
     * recipe/yield calculation or actual reporting, so it must not be guessed
     * from a sales-order line.
     */
    static String resolvePlannedOutputUnit(String productUnit) {
        if (productUnit == null || productUnit.isBlank()) {
            throw new BusinessException(422, "产品主单位未配置，无法创建生产计划")
                    .withCode("PRODUCTION_UNIT_NOT_CONFIGURED")
                    .withHint("请先在成品 SKU 中配置主单位，再创建或复制生产计划");
        }
        return productUnit.trim();
    }

    private String resolvePlannedOutputUnitForProduct(String productTypeId) {
        String productUnit = productTypeId == null ? null
                : productTypeRepository.findById(productTypeId).map(ProductType::getUnit).orElse(null);
        return resolvePlannedOutputUnit(productUnit);
    }

    private PlanUnitAuthority resolvePlanUnitAuthority(
            String factoryId, String productTypeId, List<String> targetFinishedGoodIds) {
        return resolvePlanUnitAuthority(
                factoryId, productTypeId, targetFinishedGoodIds, null, null);
    }

    private PlanUnitAuthority resolvePlanUnitAuthority(
            String factoryId, String productTypeId, List<String> targetFinishedGoodIds,
            Long selectedWorkflowId, Integer selectedWorkflowVersion) {
        String productionBaseUnit = resolvePlannedOutputUnitForProduct(productTypeId);
        boolean hasSelectedId = selectedWorkflowId != null;
        boolean hasSelectedVersion = selectedWorkflowVersion != null;
        if (hasSelectedId != hasSelectedVersion) {
            throw new BusinessException(400, "Workflow ID 与版本必须成对提交")
                    .withCode("WORKFLOW_SELECTION_INCOMPLETE")
                    .withHintTarget(hasSelectedId ? "selectedWorkflowVersion" : "selectedWorkflowId");
        }
        if (hasSelectedId && workflowResolutionService == null) {
            throw new BusinessException(500, "Workflow 解析服务未初始化，无法固定所选版本")
                    .withCode("WORKFLOW_RESOLUTION_UNAVAILABLE");
        }
        if (workflowResolutionService != null) {
            Optional<com.cretas.aims.service.workflow.WorkflowPlanOutputContract> contract;
            if (hasSelectedId) {
                contract = Optional.of(workflowResolutionService.resolvePinnedPlanOutputContract(
                        factoryId, productTypeId, selectedWorkflowId, selectedWorkflowVersion,
                        targetFinishedGoodIds));
            } else {
                contract = workflowResolutionService.resolveActivePlanOutputContract(
                        factoryId, productTypeId, targetFinishedGoodIds);
            }
            if (contract.isPresent()) {
                var value = contract.get();
                return new PlanUnitAuthority(productionBaseUnit, value.plannedUnit(),
                        resolveNetWeightGramsForProduct(factoryId, productTypeId),
                        ProductionBatch.WorkflowSelectionMode.WORKFLOW,
                        value.workflowId(), value.definitionVersion());
            }
        }
        return new PlanUnitAuthority(productionBaseUnit, productionBaseUnit,
                resolveNetWeightGramsForProduct(factoryId, productTypeId),
                ProductionBatch.WorkflowSelectionMode.LEGACY, null, null);
    }

    private BigDecimal resolveNetWeightGramsForProduct(String factoryId, String productTypeId) {
        if (productTypeId == null) return null;
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(ProductType::getGramsPerUnit)
                .orElse(null);
    }

    private void applyPlanUnitAuthority(ProductionPlan plan, PlanUnitAuthority authority) {
        plan.setPlannedUnit(authority.unit());
        plan.setWorkflowOutputUnit(authority.workflowOutputUnit());
        plan.setPlannedNetWeightGrams(authority.netWeightGrams());
        plan.setWorkflowSelectionMode(authority.mode());
        plan.setSelectedWorkflowId(authority.workflowId());
        plan.setSelectedWorkflowVersion(authority.workflowVersion());
        if (bomRecipeRepository == null) {
            return;
        }
        bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(
                        plan.getFactoryId(), plan.getProductTypeId())
                .ifPresentOrElse(recipe -> {
                    plan.setSelectedBomRecipeId(recipe.getId());
                    plan.setSelectedBomVersion(recipe.getVersion());
                }, () -> {
                    plan.setSelectedBomRecipeId(null);
                    plan.setSelectedBomVersion(null);
                });
    }

    private record PlanUnitAuthority(
            String unit,
            String workflowOutputUnit,
            BigDecimal netWeightGrams,
            ProductionBatch.WorkflowSelectionMode mode,
            Long workflowId,
            Integer workflowVersion) {
    }

    private boolean sameTargetSelection(List<String> left, List<String> right) {
        return normalizedTargetSelection(left).equals(normalizedTargetSelection(right));
    }

    private Set<String> normalizedTargetSelection(List<String> values) {
        if (values == null) return Set.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
     *   <li>新版 BOM Repository 未注入 (隔离单测场景) → skip</li>
     *   <li>stock 恰好等于 required → 通过 (边界严格不阻断)</li>
     * </ul>
     */
    private void validateMaterialStockSufficient(String factoryId, ProductionPlan plan) {
        if (bomRecipeItemRepository == null) {
            log.debug("BomRecipeItemRepository 未注入, 跳过 B3 库存校验");
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

        List<BomRecipeItem> bomItems;
        try {
            bomItems = bomRecipeItemRepository.findCurrentByProduct(factoryId, plan.getProductTypeId());
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

        for (BomRecipeItem item : bomItems) {
            if (item.getMaterialTypeId() == null || item.getStandardQuantity() == null) {
                continue;
            }
            // T159-B Change4: prefer live RawMaterialType.name over BOM snapshot (snapshot may be stale).
            String materialName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());

            // 单位需求 (已含出成率: standardQuantity / (yieldRate/100)), 单位 = BOM unit (e.g. g)
            BigDecimal perUnitRequired = item.calculateActualQuantity();
            BigDecimal totalRequiredBom = perUnitRequired.multiply(plannedQty);

            // 库存量 (称重批次单位, e.g. kg)
            BigDecimal available = materialBatchRepository.sumAvailableRawStockQuantityByMaterialType(
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
        if (bomRecipeItemRepository == null
                || plan.getProductTypeId() == null || plan.getProductTypeId().isBlank()
                || plan.getPlannedQuantity() == null
                || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyList();
        }

        List<BomRecipeItem> bomItems;
        try {
            bomItems = bomRecipeItemRepository.findCurrentByProduct(factoryId, plan.getProductTypeId());
        } catch (Exception e) {
            log.warn("Material advisory failed to load BOM: planId={}, err={}", plan.getId(), e.getMessage());
            return Collections.emptyList();
        }
        if (bomItems == null || bomItems.isEmpty()) {
            return Collections.emptyList();
        }

        BigDecimal plannedQty = plan.getPlannedQuantity();
        BigDecimal effectiveProductYieldRate = resolveEffectiveProductYieldRate(factoryId, plan.getProductTypeId(), bomItems);
        List<ProductionPlanMaterialAdvisoryDTO.Item> warnings = new ArrayList<>();
        for (BomRecipeItem item : bomItems) {
            if (item.getMaterialTypeId() == null || item.getStandardQuantity() == null) {
                continue;
            }
            String materialName = resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName());
            BigDecimal perUnitRequired = calculateRuntimeRequiredQuantity(item, effectiveProductYieldRate);
            BigDecimal totalRequiredBom = perUnitRequired.multiply(plannedQty);

            BigDecimal available = materialBatchRepository.sumAvailableRawStockQuantityByMaterialType(
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
    private BigDecimal resolveEffectiveProductYieldRate(String factoryId, String productTypeId, List<BomRecipeItem> bomItems) {
        if (productTypeId == null || bomItems == null) {
            return null;
        }
        long mainMaterialCount = bomItems.stream()
                .filter(this::isMainMaterialCandidate)
                .count();
        if (mainMaterialCount != 1) {
            if (mainMaterialCount > 1) {
                log.warn("Material advisory yield self-learning skipped: multiple main materials factoryId={}, productTypeId={}, count={}",
                        factoryId, productTypeId, mainMaterialCount);
            }
            return null;
        }
        if (bomYieldSuggestionRepository != null) {
            Optional<BomYieldSuggestion> latestApplied =
                    bomYieldSuggestionRepository.findFirstByFactoryIdAndProductTypeIdAndStatusAndDeletedAtIsNullOrderByAppliedAtDescGeneratedAtDesc(
                            factoryId, productTypeId, BomYieldSuggestion.Status.APPLIED);
            if (latestApplied.isPresent() && isPositive(latestApplied.get().getSuggestedYieldRate())) {
                return latestApplied.get().getSuggestedYieldRate();
            }
        }
        if (bomRecipeRepository != null) {
            Optional<BomRecipe> recipe = bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                    factoryId, productTypeId, BomRecipe.Status.ACTIVE);
            if (recipe.isPresent()
                    && isPositive(recipe.get().getOverallYieldRate())
                    && recipe.get().getOverallYieldRate().compareTo(new BigDecimal("100.00")) != 0) {
                return recipe.get().getOverallYieldRate();
            }
        }
        return null;
    }

    private BigDecimal calculateRuntimeRequiredQuantity(BomRecipeItem item, BigDecimal effectiveProductYieldRate) {
        if (item == null || item.getStandardQuantity() == null) {
            return BigDecimal.ZERO;
        }
        if (effectiveProductYieldRate != null && isMainMaterialCandidate(item)) {
            return item.getStandardQuantity().divide(
                    effectiveProductYieldRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP),
                    6, RoundingMode.HALF_UP);
        }
        return item.calculateActualQuantity();
    }

    private boolean isMainMaterialCandidate(BomRecipeItem item) {
        return item != null
                && item.getYieldRate() != null
                && item.getYieldRate().compareTo(new BigDecimal("100.00")) != 0;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

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
                    .findRawStockUnitsByMaterialType(factoryId, materialTypeId);
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

        // P1-0: 存货生产(SAFETY_STOCK) 计划数量非必填; 其他来源类型必须大于 0
        if (request.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            if (request.getPlannedQuantity() == null
                    || request.getPlannedQuantity().compareTo(new java.math.BigDecimal("0.01")) < 0) {
                throw new BusinessException(400, "计划数量不能为空")
                        .withHint("请输入计划数量").withHintTarget("plannedQuantity");
            }
        } else if (request.getPlannedQuantity() == null) {
            // 存货生产无计划数量要求; 但 production_plans.planned_quantity 列在 prod 是 NOT NULL,
            // 故存 0 (下游一律按 ≤0 视为"无计划": zeroIfNull / B3 库存校验跳过, 语义正确)。
            request.setPlannedQuantity(java.math.BigDecimal.ZERO);
        }

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

        // Resolve by selected terminal outputs, not by the legacy product_type_id anchor.
        // The resolved exact workflow/version is persisted on the plan and later copied to its batch.
        PlanUnitAuthority planUnitAuthority = resolvePlanUnitAuthority(
                factoryId, request.getProductTypeId(), request.getTargetFinishedGoodIds(),
                request.getSelectedWorkflowId(), request.getSelectedWorkflowVersion());
        if (planUnitAuthority.mode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW) {
            if (Boolean.TRUE.equals(request.getSkipProcessReporting())) {
                throw new BusinessException(400, "Workflow 生产计划必须使用逐道报工")
                        .withCode("WORKFLOW_PLAN_REQUIRES_STEPWISE")
                        .withHint("请把报工模式切换为「逐道报工」后再创建")
                        .withHintTarget("skipProcessReporting");
            }
            // Workflow-backed plans always materialize stepwise tasks.
            request.setSkipProcessReporting(Boolean.FALSE);
        }

        // Fable 审计修复 (2026-06-11 — 多租户安全红线):
        //   免工序报工开关 skipProcessReporting=null (调用方未显式指定, 如 RN/AI 客户端)
        //   → 解析为"该工厂的默认值" (F006=true 两点, 其他工厂=false 逐道), 而非旧的"全系统 null→true"。
        //   显式 true/false 一律尊重调用方 (web 开关传显式值)。
        //   这样其他工厂 (逐道是其溯源/成本/出成率/自学习/人效价值) 不被静默改成两点。
        if (request.getSkipProcessReporting() == null) {
            request.setSkipProcessReporting(resolveSkipProcessReportingDefault(factoryId));
        }

        request.setPlannedUnit(planUnitAuthority.unit());

        // 创建生产计划
        ProductionPlan plan = productionPlanMapper.toEntity(request, factoryId, userId.longValue());
        applyPlanUnitAuthority(plan, planUnitAuthority);

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
     * 以销定产批量建计划 (六扇门 2026-06-24): 选 SO 多个产品行, 各建一张计划。
     * 产品+数量后端权威解析自 SalesOrderItem (不信前端传值); @Transactional 原子。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public java.util.List<ProductionPlanDTO> createPlansFromSalesOrder(
            String factoryId, com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest req, Long userId) {
        // 跨租户校验: SO 须属当前工厂
        SalesOrder so = salesOrderRepository.findById(req.getSourceOrderId())
                .filter(o -> factoryId.equals(o.getFactoryId()))
                .orElseThrow(() -> new com.cretas.aims.exception.EntityNotFoundException(
                        "销售订单不存在或不属于当前工厂: " + req.getSourceOrderId()));

        // SO 行 (权威来源): 按 id 索引, 只接受属于本 SO 的行
        java.util.Map<String, SalesOrderItem> itemById = new java.util.HashMap<>();
        for (SalesOrderItem it : salesOrderItemRepository.findBySalesOrderId(so.getId())) {
            itemById.put(String.valueOf(it.getId()), it);
        }

        java.util.List<ProductionPlanDTO> created = new java.util.ArrayList<>();
        for (String itemId : req.getItemIds()) {
            SalesOrderItem item = itemById.get(itemId);
            if (item == null) {
                throw new com.cretas.aims.exception.BusinessException(400,
                        "产品行不属于该销售订单: " + itemId)
                        .withHint("请刷新后重新选择产品行");
            }
            if (item.getProductTypeId() == null) {
                throw new com.cretas.aims.exception.BusinessException(400,
                        "产品行缺少产品类型, 无法生成计划: " + (item.getProductName() != null ? item.getProductName() : itemId));
            }
            // reviewer Issue4: 计划数量取"未交付剩余量"(订单量 - 已交付), 避免对已部分交付的 SO 过量生产。
            // 转录"据销售领用量算出需领量"。全部已交付 → 拒绝该行 (防呆: 提示取消)。
            BigDecimal ordered = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal delivered = item.getDeliveredQuantity() != null ? item.getDeliveredQuantity() : BigDecimal.ZERO;
            BigDecimal remaining = ordered.subtract(delivered);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                throw new com.cretas.aims.exception.BusinessException(400,
                        "产品行已全部交付, 无需生产: " + (item.getProductName() != null ? item.getProductName() : itemId))
                        .withHint("请在产品行多选中取消该已交付产品");
            }
            ProductType product = productTypeRepository
                    .findByIdAndFactoryId(item.getProductTypeId(), factoryId)
                    .orElseThrow(() -> new com.cretas.aims.exception.EntityNotFoundException(
                            "产品类型不存在或不属于当前工厂: " + item.getProductTypeId()));
            if (salesOrderPlanQuantityNormalizer == null) {
                throw new BusinessException(500, "销售订单单位换算服务未初始化")
                        .withCode("SALES_PLAN_NORMALIZER_UNAVAILABLE");
            }
            var normalizedPlanQuantity = salesOrderPlanQuantityNormalizer.normalize(remaining, item, product);
            // CUSTOMER_ORDER 来源在 createProductionPlan 强制要求 processName + batchDate。
            // 批量路径补齐 processName；batchDate 是实际转批次日，必须与计划生产日独立保存。
            String processName = "两点报工";
            if (productWorkProcessService != null) {
                try {
                    java.util.List<String> names = productWorkProcessService
                            .listByProduct(factoryId, item.getProductTypeId()).stream()
                            .map(com.cretas.aims.dto.ProductWorkProcessDTO::getProcessName)
                            .filter(n -> n != null && !n.isBlank())
                            .distinct().collect(java.util.stream.Collectors.toList());
                    if (!names.isEmpty()) processName = String.join("、", names);
                } catch (Exception e) {
                    log.warn("以销定产取工序名失败, 用默认两点报工: productTypeId={}, err={}",
                            item.getProductTypeId(), e.getMessage());
                }
            }
            CreateProductionPlanRequest one = new CreateProductionPlanRequest();
            // 每行各自的产品 + 数量 (权威自 SO 行)
            one.setProductTypeId(item.getProductTypeId());
            one.setPlannedQuantity(normalizedPlanQuantity.quantity());
            one.setPlannedUnit(normalizedPlanQuantity.unit());
            one.setSourceDisplayQuantity(normalizedPlanQuantity.displayQuantity());
            one.setSourceDisplayUnit(normalizedPlanQuantity.displayUnit());
            one.setSourceType(PlanSourceType.CUSTOMER_ORDER);
            one.setSourceOrderId(so.getId());
            one.setSourceOrderItemId(itemId);
            one.setSourceCustomerName(so.getCustomerName());
            one.setCustomerOrderNumber(so.getOrderNumber());
            one.setProcessName(processName);
            one.setBatchDate(req.getBatchDate());
            // 计划级共享设置
            one.setPlannedDate(req.getPlannedDate());
            one.setExpectedCompletionDate(req.getExpectedCompletionDate());
            one.setEstimatedWorkers(req.getEstimatedWorkers());
            one.setAssignedSupervisorId(req.getAssignedSupervisorId());
            one.setNotes(req.getNotes());
            one.setSkipProcessReporting(req.getSkipProcessReporting());
            // 注 (reviewer Issue2): createProductionPlan 走 REQUIRED 传播参与本外层事务, 其 afterCommit
            // 自动排程在外层唯一一次 commit 后逐 plan 触发 (回滚则全不触发) — 原子语义正确。
            created.add(createProductionPlan(factoryId, one, userId));
        }
        log.info("以销定产批量建计划: factoryId={}, soId={}, 产品行={}, 创建计划={}",
                factoryId, so.getId(), req.getItemIds().size(), created.size());
        return created;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public ProductionPlanDTO repairSalesPlanBatchDate(
            String factoryId, String planId, LocalDate expectedCurrentBatchDate, LocalDate targetBatchDate) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (plan.getSourceType() != PlanSourceType.CUSTOMER_ORDER
                || plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "仅允许修复尚未开工的销售来源计划")
                    .withCode("PLAN_BATCH_DATE_REPAIR_STATUS_INVALID")
                    .withHint("请刷新计划状态；已开工计划必须走正式业务更正流程");
        }
        if (!productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId).isEmpty()
                || (processSheetRowRepository != null
                    && !processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId).isEmpty())) {
            throw new BusinessException(409, "计划已有批次或报工，拒绝修改批次日期")
                    .withCode("PLAN_BATCH_DATE_REPAIR_ALREADY_STARTED")
                    .withHint("不得通过历史修复接口改写已开始生产的计划");
        }

        LocalDate current = plan.getBatchDate();
        if (targetBatchDate.equals(current)) {
            return productionPlanMapper.toDTO(plan);
        }
        // 该桥接只修复旧 batch-from-so 把 plannedDate 误写进 batchDate 的已知形态。
        // 一旦已校正为独立日期，除相同目标重放外，任何再次改写都必须走正式业务更正流程。
        if (!java.util.Objects.equals(current, plan.getPlannedDate())) {
            throw new BusinessException(409, "计划批次日期已完成历史校正，拒绝再次覆盖")
                    .withCode("PLAN_BATCH_DATE_REPAIR_ALREADY_CORRECTED")
                    .withHint("相同目标可安全重放；不同日期请走正式业务更正流程")
                    .withHintTarget("targetBatchDate");
        }
        if (!java.util.Objects.equals(expectedCurrentBatchDate, current)) {
            throw new BusinessException(409, "批次日期已变化，拒绝覆盖")
                    .withCode("PLAN_BATCH_DATE_REPAIR_CONFLICT")
                    .withHint("请重新读取计划后核对批次日期")
                    .withHintTarget("expectedCurrentBatchDate");
        }
        if (plan.getPlannedDate() == null || targetBatchDate.isAfter(plan.getPlannedDate())) {
            throw new BusinessException(400, "批次日期不能晚于计划生产日")
                    .withCode("PLAN_BATCH_DATE_INVALID")
                    .withHintTarget("targetBatchDate");
        }

        plan.setBatchDate(targetBatchDate);
        ProductionPlan saved = productionPlanRepository.saveAndFlush(plan);
        return productionPlanMapper.toDTO(saved);
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

        boolean productChanged = request.getProductTypeId() != null
                && !request.getProductTypeId().equals(plan.getProductTypeId());
        boolean targetsChanged = request.getTargetFinishedGoodIds() != null
                && !sameTargetSelection(
                        request.getTargetFinishedGoodIds(), plan.getTargetFinishedGoodIds());
        PlanUnitAuthority updatedAuthority = null;
        if (productChanged || targetsChanged || plan.getPlannedUnit() == null
                || plan.getWorkflowSelectionMode() == null) {
            String effectiveProductId = request.getProductTypeId() != null
                    ? request.getProductTypeId() : plan.getProductTypeId();
            List<String> effectiveTargets = request.getTargetFinishedGoodIds() != null
                    ? request.getTargetFinishedGoodIds() : plan.getTargetFinishedGoodIds();
            updatedAuthority = resolvePlanUnitAuthority(factoryId, effectiveProductId, effectiveTargets);
            request.setPlannedUnit(updatedAuthority.unit());
        } else {
            request.setPlannedUnit(plan.getPlannedUnit());
        }

        // 更新计划信息
        productionPlanMapper.updateEntity(plan, request);
        if (updatedAuthority != null) applyPlanUnitAuthority(plan, updatedAuthority);
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
        newPlan.setTargetFinishedGoodIds(source.getTargetFinishedGoodIds() == null ? null
                : new ArrayList<>(source.getTargetFinishedGoodIds()));
        applyPlanUnitAuthority(newPlan, resolvePlanUnitAuthority(
                factoryId, source.getProductTypeId(), source.getTargetFinishedGoodIds()));
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

        // headed-audit 修复 (2026-07-03): keyword 之前从未被应用到查询, 搜索框全无效。
        // 空白/空字符串一律当"未提供"处理 (null), 交给 repository 端的 CAST null-guard。
        String keyword = pageRequest.getKeyword() != null && !pageRequest.getKeyword().isBlank()
                ? pageRequest.getKeyword().trim()
                : null;

        // 如果指定了状态过滤
        if (pageRequest.getStatus() != null && !pageRequest.getStatus().isEmpty()) {
            String statusFilter = pageRequest.getStatus().toUpperCase();
            if ("UNFINISHED".equals(statusFilter) || "ACTIVE".equals(statusFilter)) {
                planPage = productionPlanRepository.findByFactoryIdAndStatusInAndKeyword(
                        factoryId,
                        List.of(ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS),
                        keyword,
                        pageable);
                log.info("按未完成状态过滤生产计划: factoryId={}, status={}, keyword={}", factoryId, statusFilter, keyword);
            } else {
                try {
                    ProductionPlanStatus status = ProductionPlanStatus.valueOf(statusFilter);
                    planPage = productionPlanRepository.findByFactoryIdAndStatusAndKeyword(factoryId, status, keyword, pageable);
                    log.info("按状态过滤生产计划: factoryId={}, status={}, keyword={}", factoryId, status, keyword);
                } catch (IllegalArgumentException e) {
                    log.warn("无效的状态值: {}, 返回全部数据 (keyword={})", pageRequest.getStatus(), keyword);
                    planPage = productionPlanRepository.findByFactoryIdAndKeyword(factoryId, keyword, pageable);
                }
            }
        } else {
            planPage = productionPlanRepository.findByFactoryIdAndKeyword(factoryId, keyword, pageable);
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
        // R6 (2026-06-14): 悲观写锁取计划, 并发双击/重试串行化。第二个请求拿锁后看到已 IN_PROGRESS → 409。
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 验证工厂ID
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }

        // 验证状态 (在悲观锁内 — 并发第二个请求在此被拦)
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
        if (requiresProductionSettlement(factoryId)) {
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

        ProductionPlan plan = request.isConfirm()
                ? productionPlanRepository.findByIdForUpdate(planId)
                        .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                        .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId))
                : productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
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

        ensureWorkflowSettlementUsesSubmittedReports(factoryId, plan);
        ProductionSettlementRequest effectiveRequest = request.isConfirm()
                ? deriveConfirmedSettlementRequest(factoryId, planId, request)
                : request;

        validateSettlementRequest(plan, effectiveRequest);

        List<ProductionSettlementConsumption> consumptionLines = new ArrayList<>();
        appendConsumptionLines(factoryId, plan, "RAW_MATERIAL", effectiveRequest.getRawMaterialConsumptions(), consumptionLines);
        appendConsumptionLines(factoryId, plan, "SEMI_FINISHED", effectiveRequest.getSemiFinishedConsumptions(), consumptionLines);
        appendConsumptionLines(factoryId, plan, "AUXILIARY", effectiveRequest.getAuxiliaryConsumptions(), consumptionLines);

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(UUID.randomUUID().toString());
        settlement.setFactoryId(factoryId);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber(plan.getPlanNumber());
        settlement.setIdempotencyKey(request.getIdempotencyKey());
        settlement.setPlannedQuantity(zeroIfNull(plan.getPlannedQuantity()));
        settlement.setActualFinishedQuantity(zeroIfNull(effectiveRequest.getActualFinishedQuantity()));
        settlement.setActualSemiFinishedQuantity(zeroIfNull(effectiveRequest.getActualSemiFinishedQuantity()));
        settlement.setQuantityUnit(trimToNull(effectiveRequest.getQuantityUnit()));
        settlement.setQuantityVarianceReason(trimToNull(effectiveRequest.getQuantityVarianceReason()));
        settlement.setQuantityVarianceNote(trimToNull(effectiveRequest.getQuantityVarianceNote()));
        settlement.setMaterialVarianceReason(trimToNull(effectiveRequest.getMaterialVarianceReason()));
        settlement.setMaterialVarianceNote(trimToNull(effectiveRequest.getMaterialVarianceNote()));
        settlement.setLaborDeferredReason(trimToNull(effectiveRequest.getLaborDeferredReason()));
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setPostingMessage("已完成结单和实际领用扣减; 等待仓库确认实收后才生成成品库存, 差异进入中转挂账");
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now());
        settlement = productionSettlementRepository.save(settlement);

        for (ProductionSettlementConsumption line : consumptionLines) {
            line.setSettlementId(settlement.getId());
        }
        postConsumptionToInventory(factoryId, consumptionLines);
        // 🔴🔒 R2 (2026-07-04): 结单族 process-row 的 SFI/FG 投料<b>严格扣减</b> (防 phantom 库存腐蚀)。
        //   逐道录入把常驻半成品(SFI)/成品(FG)作投料记在 process_sheet_rows.upstreamSources, 但结单预填
        //   把 semiFinishedConsumptions 留空 (让文员"手工再加"), 且 FG 投料结单请求无对应字段 → 这些投料
        //   此前<b>从不扣减</b> = SFI/FG 可用量不降 (幻库存: 消耗了却还在库, 可被重复领用/发货)。此处按 process-row
        //   直接严格扣减 (缺失/不足即抛, 整事务回滚, 禁止降级)。成本侧由 R4 computeByPlan (含 SFI/FG 投料桶)
        //   在仓库确认实收创建 FG 时补入, 此处仅补扣减。仅结单族 (SAFETY_STOCK 走小结扣减, 内部守卫)。
        deductProcessSheetStockFeeds(factoryId, plan, effectiveRequest);
        productionSettlementConsumptionRepository.saveAll(consumptionLines);
        productionSettlementLaborRepository.saveAll(toLaborLines(factoryId, planId, settlement.getId(), effectiveRequest.getLaborSegments()));

        plan.setStatus(ProductionPlanStatus.COMPLETED);
        if (plan.getStartTime() == null) {
            plan.setStartTime(LocalDateTime.now());
        }
        plan.setEndTime(LocalDateTime.now());
        plan.setActualQuantity(settlement.getActualFinishedQuantity());
        productionPlanRepository.save(plan);

        // 🔴🔒🔒 结单族「扣减即打戳」根修 (bug fix 2026-07-04, #1216↔#1217 姊妹流根因闭合):
        //   延迟扣减设计下, 报工写的 MaterialConsumption 行恒 interimSettledAt IS NULL; SAFETY_STOCK 走
        //   「小结」逐笔扣 usedQuantity 时原子盖戳, 而结单族 (非 SAFETY_STOCK) 走本「结单」路径扣减 usedQuantity
        //   却从不盖戳 → 其报工消耗行永久停留 IS NULL, 使该谓词对结单族失去「待扣减」语义 (#1216 盘点 SUM /
        //   #1215 关单 count 守卫此前各自把结单族门控排除来绕开)。此处在结单时补盖戳, 令 interimSettledAt IS NULL
        //   对所有计划族统一 = 「尚未扣减」→ 两处门控退化为冗余而安全; 残留 #2 (跨计划投料把结单族未结行留在他单
        //   WKS 批次 → 关单守卫误 409 永久卡死) 随之闭合。⚠️ 仅结单族 (非 SAFETY_STOCK) 打戳: SAFETY_STOCK
        //   的打戳由小结原子完成, 提前打戳会让小结 §① 漏扣 → 幻库存 (故此处 sourceType 守卫不可去)。
        //   按 productionBatchId ∈ 本计划 process_sheet_rows.batch_id 定位 (非 mc.production_plan_id —— 非末道行
        //   plan_id 故意 null, 会漏), 与小结 §① / 撤销侧同 key。幂等 (已戳不再戳), factory-scoped。
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            int stamped = materialConsumptionRepository
                    .stampInterimSettledForPlan(factoryId, planId, settlement.getSettledAt());
            if (stamped > 0) {
                log.info("结单族消耗打戳: factoryId={}, planId={}, sourceType={}, 打戳 {} 笔报工消耗 "
                                + "(interimSettledAt IS NULL 现统一=待扣减)",
                        factoryId, planId, plan.getSourceType(), stamped);
            }
            // 🔴🔒🔒 结单族 process_sheet_rows「结单即打戳」(2026-07-05, 镜像上方消耗打戳):
            //   SFI/FG 投料延迟扣减对结单族在上方 deductProcessSheetStockFeeds 已扣, 但 process_sheet_rows
            //   的 interim_settled_at 若不打戳会永久 IS NULL → 被 findUnsettledStockFeedRows (已去族门控) 永久
            //   计入 pending → 结单后 (SFI/FG 已扣) 仍被半成品盘点/发货/调拨减去 → 双减幻库存。此处打戳令
            //   IS NULL 对所有族统一 = 「待扣」。⚠️ 仅结单族 (sourceType 守卫内): SAFETY_STOCK 的 row 打戳由
            //   小结原子完成, 提前打戳会让小结漏处理产出行。processSheetRowRepository @Autowired(required=false),
            //   prod 恒存在; 缺失 (精简测试上下文) 则跳过 (与 deductProcessSheetStockFeeds null-guard 同策略)。
            if (processSheetRowRepository != null) {
                int rowsStamped = processSheetRowRepository
                        .stampInterimSettledForPlan(factoryId, planId, settlement.getSettledAt());
                if (rowsStamped > 0) {
                    log.info("结单族逐工序行打戳: factoryId={}, planId={}, 打戳 {} 行 "
                                    + "(process_sheet_rows.interim_settled_at, pending SFI/FG 投料现统一排除)",
                            factoryId, planId, rowsStamped);
                }
            }
        }

        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.ProductionSettledEvent(
                    this, factoryId, plan.getId(), plan.getPlanNumber(), plan.getProductTypeId(),
                    settlement.getId(), settlement.getActualFinishedQuantity()));
        }

        List<String> warnings = List.of("已按实际领用扣减原料/半成品库存; 成品需仓库确认实收后再入库");
        log.info("六扇门生产结单完成: factoryId={}, planId={}, settlementId={}, finished={}, semiFinished={}",
                factoryId, planId, settlement.getId(),
                settlement.getActualFinishedQuantity(), settlement.getActualSemiFinishedQuantity());
        return toSettlementResponse(settlement, warnings);
    }

    /**
     * Rebuild the normal close-plan request from current server facts. Only explicit
     * exception reasons are carried from the caller; quantities, batches and labor
     * lines are never trusted in confirmation-only mode.
     */
    private ProductionSettlementRequest deriveConfirmedSettlementRequest(
            String factoryId, String planId, ProductionSettlementRequest confirmation) {
        ProductionSettlementPrefillResponse latest = getSettlementPrefill(factoryId, planId);
        ProductionSettlementRequest derived = latest != null ? latest.getPrefill() : null;
        List<ProductionSettlementPrefillResponse.Issue> blockers = latest == null || latest.getAudit() == null
                ? List.of(issue("SETTLEMENT_FACTS_UNAVAILABLE", "无法读取当前报工事实，不能结单。", "processSheetRows"))
                : Optional.ofNullable(latest.getAudit().getIssues()).orElseGet(ArrayList::new).stream()
                        .filter(i -> i.getSeverity() == ProductionSettlementPrefillResponse.Severity.BLOCKER)
                        .filter(i -> !isResolvedSettlementOverride(i, confirmation))
                        .toList();
        if (derived == null || !blockers.isEmpty()) {
            String detail = blockers.stream()
                    .map(ProductionSettlementPrefillResponse.Issue::getMessage)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("；"));
            throw new BusinessException(409, "当前生产事实仍有阻塞项，不能结单")
                    .withCode("PRODUCTION_SETTLEMENT_BLOCKED")
                    .withHint(isBlank(detail) ? "请先完成并正式提交逐道报工" : detail)
                    .withHintTarget("核对结单");
        }
        derived.setIdempotencyKey(confirmation.getIdempotencyKey());
        derived.setConfirm(true);
        derived.setQuantityVarianceReason(firstNonBlank(
                confirmation.getQuantityVarianceReason(), derived.getQuantityVarianceReason()));
        derived.setQuantityVarianceNote(trimToNull(confirmation.getQuantityVarianceNote()));
        derived.setMaterialVarianceReason(trimToNull(confirmation.getMaterialVarianceReason()));
        derived.setMaterialVarianceNote(trimToNull(confirmation.getMaterialVarianceNote()));
        derived.setLaborDeferredReason(firstNonBlank(
                derived.getLaborDeferredReason(), confirmation.getLaborDeferredReason()));
        return derived;
    }

    private boolean isResolvedSettlementOverride(ProductionSettlementPrefillResponse.Issue issue,
                                                 ProductionSettlementRequest confirmation) {
        if (issue == null || issue.getCode() == null) {
            return false;
        }
        return switch (issue.getCode()) {
            case "QUANTITY_VARIANCE_OVER_PLAN" -> !isBlank(confirmation.getQuantityVarianceReason());
            case "MATERIAL_CONSUMPTION_EMPTY" -> !isBlank(confirmation.getMaterialVarianceReason());
            default -> false;
        };
    }

    private void ensureWorkflowSettlementUsesSubmittedReports(String factoryId, ProductionPlan plan) {
        boolean workflowPlan = plan != null && (plan.getSelectedWorkflowId() != null
                || plan.getWorkflowSelectionMode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW
                || (plan.getTargetFinishedGoodIds() != null && !plan.getTargetFinishedGoodIds().isEmpty()));
        if (!workflowPlan || Boolean.TRUE.equals(plan.getSkipProcessReporting())) {
            return;
        }
        if (processSheetRowRepository == null) {
            throw new BusinessException(500, "逐道报工服务未初始化，workflow 计划不能结单")
                    .withCode("WORKFLOW_REPORTING_UNAVAILABLE")
                    .withHintTarget("核对结单");
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        boolean hasSubmitted = rows != null && rows.stream().anyMatch(this::isUsableProcessSheetRow);
        if (!hasSubmitted) {
            throw new BusinessException(409, "workflow 计划必须先完成并正式提交逐道报工")
                    .withCode("WORKFLOW_REPORTING_REQUIRED")
                    .withHint("请逐道录入并提交后，再核对结单")
                    .withHintTarget("核对结单");
        }
    }

    // ==================== Phase 2A: 报工→核算自动化 (核对结单预填) ====================

    /** 数量差异预填阈值: |计划-实际|/计划 ≤ 5% 视为"无显著差异", 自动填原因; 超则留空让人选。 */
    private static final BigDecimal SETTLEMENT_VARIANCE_THRESHOLD = new BigDecimal("0.05");

    /**
     * A修: 判断计划是否跨单位 (末道产出单位 份/盒 ≠ 原计划单位 kg)。
     *
     * <p>completeProduction 把批次 unit 覆盖成产出单位时, 原计划单位写入 {@code plannedUnit}
     * (见 {@link ProductionBatch#getPlannedUnit()})。任一非取消批次 plannedUnit != unit 即跨单位 →
     * {@code actualFinished(份)} 与 {@code plan.plannedQuantity(kg)} 不可直接比较, 跳过超产判断,
     * 镜像 batch 层 efficiency=null 的诚实留空做法。</p>
     */
    private boolean isCrossUnitPlan(List<ProductionBatch> batches) {
        if (batches == null) {
            return false;
        }
        return batches.stream()
                .filter(b -> b != null && b.getStatus() != ProductionBatchStatus.CANCELLED)
                .anyMatch(b -> b.getPlannedUnit() != null && b.getUnit() != null
                        && !b.getPlannedUnit().equals(b.getUnit()));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementPrefillResponse getSettlementPrefill(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        List<ProductionSettlementPrefillResponse.Issue> issues = new ArrayList<>();

        // 1) 取本计划全部非取消批次的逐道 YIELD 报工 (按 processOrder, createdAt 已排序)
        List<ProductionBatch> batches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);

        // 逐道电子表格包含本计划实际使用的原料批次、内部 WIP 流转和工时，
        // 是核对结单的完整事实来源。旧 YIELD 报工只保留为历史计划的兼容回退，
        // 不能因为它存在就跳过更完整的逐道数据。
        ProductionSettlementPrefillResponse sheetPrefill =
                deriveSettlementPrefillFromProcessSheetRows(factoryId, plan, batches);
        if (sheetPrefill != null) {
            return sheetPrefill;
        }

        List<ProductionReport> allReports = new ArrayList<>();
        if (batches != null) {
            for (ProductionBatch b : batches) {
                if (b == null || b.getId() == null || b.getStatus() == ProductionBatchStatus.CANCELLED) {
                    continue;
                }
                List<ProductionReport> reports = productionReportRepository
                        .findYieldReportsByBatch(factoryId, b.getId());
                if (reports != null) {
                    allReports.addAll(reports);
                }
            }
        }

        if (allReports.isEmpty()) {
            // 无任何报工 → 不臆造, 返回空预填 + 明确 issue 让人手填 (诚实空态)
            issues.add(issue("NO_YIELD_REPORTS",
                    "该计划暂无逐道报工记录, 无法自动预填; 请手工录入实际产量、领用与人效后结单。",
                    "actualFinishedQuantity"));
            return buildPrefillResponse(emptyPrefill(plan), issues);
        }

        // 2) actualFinishedQuantity ← 末道 (最大 processOrder) 报工的产出量汇总
        BigDecimal actualFinished = deriveLastStepOutput(allReports);
        if (actualFinished == null || actualFinished.compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(issue("FINISHED_OUTPUT_MISSING",
                    "末道工序产出量缺失或为 0, 无法自动带入实际成品产量; 请在产出核对处手工填写。",
                    "actualFinishedQuantity"));
            actualFinished = null;
        }

        // 3) rawMaterialConsumptions ← 各道 materialBatchRefs 聚合 (按 materialBatchId 求和)
        List<ProductionSettlementRequest.ConsumptionLine> rawLines =
                deriveRawConsumptions(factoryId, plan, allReports, issues);

        // 4) laborSegments ← 各道 laborSegments / 单一工时汇总
        List<ProductionSettlementRequest.LaborSegment> laborSegments = deriveLaborSegments(allReports);
        String laborDeferredReason = null;
        if (laborSegments.isEmpty()) {
            // derive 不出工时 → 不臆造分钟数, 留空 + 默认延期原因让人确认/补录 (满足 settle 校验)。
            // INFO 级: 已给合法延期原因, 不阻塞提交; 仅提醒可补录。
            laborDeferredReason = "工时稍后补录";
            issues.add(infoIssue("LABOR_MISSING",
                    "报工中未带可用工时段, 已默认置为“工时稍后补录”; 如需计入人效请手工补录工时/人数。",
                    "laborSegments"));
        }

        // 5) quantityVarianceReason ← 阈值内自动填, 超阈值留空 + issue
        BigDecimal planned = zeroIfNull(plan.getPlannedQuantity());
        // A修: 末道产出单位(份/盒) 可能 ≠ plan.plannedQuantity 单位(kg) → 跨单位裸比误报超产.
        boolean crossUnit = isCrossUnitPlan(batches);
        String varianceReason = null;
        if (crossUnit) {
            // 跨单位: 不比较产量差异, 不臆造; 留 INFO 让人核对实际产量 (非 BLOCKER, 不阻一键确认).
            issues.add(infoIssue("QUANTITY_UNIT_CROSS",
                    "末道产出单位与计划单位不同(如 份/盒 vs kg), 已跳过产量差异自动判断; 请人工核对实际产量。",
                    "quantityVarianceReason"));
        } else if (actualFinished != null && planned.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = actualFinished.subtract(planned).abs();
            BigDecimal ratio = diff.divide(planned, 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(SETTLEMENT_VARIANCE_THRESHOLD) <= 0) {
                varianceReason = "无显著差异";
            } else if (actualFinished.compareTo(planned) > 0) {
                // settle 校验: 实际 > 计划 必须有超产原因; 留空让人选 (对齐前端差异原因下拉)
                issues.add(issue("QUANTITY_VARIANCE_OVER_PLAN",
                        "实际产量(" + stripTrailing(actualFinished) + ")超过计划("
                                + stripTrailing(planned) + ")超过 5%, 请选择超产原因后再确认。",
                        "quantityVarianceReason"));
            }
            // 实际 < 计划 且超阈值: settle 不强制原因, 不阻塞, 不标 issue (留空合法)
        }

        // 6) 材料领用为空 → 不能自动臆造原因, 标 issue (settle 要求至少一行或 materialVarianceReason)
        if (rawLines.isEmpty()) {
            issues.add(issue("MATERIAL_CONSUMPTION_EMPTY",
                    "报工中未找到可用原料批次领用记录, 无法自动带入实际领用; 请手工录入原料/半成品领用, 或选择物料差异原因。",
                    "rawMaterialConsumptions"));
        }

        // semiFinishedConsumptions: 默认留空。本计划 WIP 是计划内部自产自耗 (不进官方半成品领用扣减)。
        // 仅"跨计划领用外部既有半成品库存"才填 — 该信息不在 YIELD 报工里可靠 derive, 故留空 + INFO 提示让人确认。
        // INFO 级不阻塞一键确认 (绝大多数计划无跨计划半成品领用)。
        issues.add(infoIssue("SEMI_FINISHED_CONFIRM",
                "如本次生产领用了其他计划产出的外部半成品库存, 请在“半成品实际领用”处手工添加; 本计划内部自产自耗的中间品无需在此填写。",
                "semiFinishedConsumptions"));

        ProductionSettlementRequest prefill = ProductionSettlementRequest.builder()
                .idempotencyKey(null) // 前端生成, 防呆 Rule 4 (打开 dialog 一次性生成)
                .actualFinishedQuantity(actualFinished)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .quantityUnit(null) // ProductionPlan 无单位字段; 前端提交时从行数据(unit/quantityUnit)补
                .quantityVarianceReason(varianceReason)
                .laborDeferredReason(laborDeferredReason)
                .rawMaterialConsumptions(rawLines)
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(laborSegments)
                .build();

        return buildPrefillResponse(prefill, issues);
    }

    private ProductionSettlementPrefillResponse deriveSettlementPrefillFromProcessSheetRows(
            String factoryId, ProductionPlan plan, List<ProductionBatch> batches) {
        if (processSheetRowRepository == null || plan == null || isBlank(plan.getId())) {
            return null;
        }

        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        List<ProductionSettlementPrefillResponse.Issue> issues = new ArrayList<>();
        List<ParsedProcessSheetRow> parsedRows = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (!isUsableProcessSheetRow(row)) {
                continue;
            }
            ProcessSheetRowRequest request = parseProcessSheetRowPayload(row, issues);
            if (request != null) {
                parsedRows.add(new ParsedProcessSheetRow(row, request));
            }
        }
        if (parsedRows.isEmpty()) {
            return issues.isEmpty() ? null : buildPrefillResponse(emptyPrefill(plan), issues);
        }
        parsedRows.sort(Comparator
                .comparing((ParsedProcessSheetRow r) -> r.request().getProcessOrder(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(r -> r.row().getCreatedAt(), Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(r -> r.row().getId(), Comparator.nullsLast(Long::compareTo)));

        List<ProductionSettlementRequest.OutputLine> terminalOutputs =
                deriveTerminalProcessSheetOutputs(parsedRows);
        BigDecimal actualFinished = sumCompatibleTerminalOutputs(terminalOutputs);
        long terminalUnitCount = terminalOutputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getUnit)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .map(unit -> unit.toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        if (terminalUnitCount > 1) {
            issues.add(issue("FINISHED_OUTPUT_UNIT_MIXED",
                    "终端产出包含多个不能直接相加的单位，请按 SKU、批次和单位分别核对。",
                    "terminalOutputs"));
        }
        if (actualFinished == null || actualFinished.compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(issue("FINISHED_OUTPUT_MISSING",
                    "逐道电子表格末道产出量缺失或为 0, 无法自动带入实际成品产量; 请在产出核对处手工填写。",
                    "actualFinishedQuantity"));
            actualFinished = null;
        }

        List<ProductionSettlementRequest.ConsumptionLine> rawLines =
                deriveRawConsumptionsFromProcessSheetRows(factoryId, parsedRows, issues);

        List<ProductionSettlementRequest.LaborSegment> laborSegments =
                deriveLaborSegmentsFromProcessSheetRows(parsedRows);
        String laborDeferredReason = null;
        if (laborSegments.isEmpty()) {
            laborDeferredReason = "工时稍后补录";
            issues.add(infoIssue("LABOR_MISSING",
                    "逐道电子表格中未带可用工时段, 已默认置为“工时稍后补录”; 如需计入人效请手工补录工时和人数。",
                    "laborSegments"));
        }

        BigDecimal planned = zeroIfNull(plan.getPlannedQuantity());
        boolean crossUnit = isCrossUnitPlan(batches);
        String varianceReason = null;
        if (crossUnit) {
            issues.add(infoIssue("QUANTITY_UNIT_CROSS",
                    "末道产出单位与计划单位不同, 已跳过产量差异自动判断; 请人工核对实际产量。",
                    "quantityVarianceReason"));
        } else if (actualFinished != null && planned.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = actualFinished.subtract(planned).abs();
            BigDecimal ratio = diff.divide(planned, 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(SETTLEMENT_VARIANCE_THRESHOLD) <= 0) {
                varianceReason = "无显著差异";
            } else if (actualFinished.compareTo(planned) > 0) {
                issues.add(issue("QUANTITY_VARIANCE_OVER_PLAN",
                        "实际产量(" + stripTrailing(actualFinished) + ")超过计划("
                                + stripTrailing(planned) + ")超过 5%, 请选择超产原因后再确认。",
                        "quantityVarianceReason"));
            }
        }

        if (rawLines.isEmpty()) {
            issues.add(issue("MATERIAL_CONSUMPTION_EMPTY",
                    "逐道电子表格中未找到可用原料批次领用记录, 无法自动带入实际领用; 请手工录入原料/半成品领用或选择物料差异原因。",
                    "rawMaterialConsumptions"));
        }

        issues.add(infoIssue("SEMI_FINISHED_CONFIRM",
                "如本次生产领用了其他计划产出的外部半成品库存, 请在“半成品实际领用”处手工添加; 本计划内部自产自耗的中间品无需在此填写。",
                "semiFinishedConsumptions"));

        ProductionSettlementRequest prefill = ProductionSettlementRequest.builder()
                .idempotencyKey(null)
                .actualFinishedQuantity(actualFinished)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .quantityUnit(null)
                .quantityVarianceReason(varianceReason)
                .laborDeferredReason(laborDeferredReason)
                .rawMaterialConsumptions(rawLines)
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(laborSegments)
                .terminalOutputs(terminalOutputs)
                .build();
        return buildPrefillResponse(prefill, issues);
    }

    private boolean isUsableProcessSheetRow(ProcessSheetRow row) {
        if (row == null || isBlank(row.getRowPayload())) {
            return false;
        }
        String status = trimToNull(row.getRowStatus());
        String submission = trimToNull(row.getSubmissionStatus());
        if (ProcessSheetRow.SUBMISSION_DRAFT.equals(submission)) {
            return false;
        }
        if (ProcessSheetRow.SUBMISSION_SUBMITTED.equals(submission)) {
            return true;
        }
        // Explicit legacy compatibility: rows written before submission_status existed
        // retain their historical rowStatus semantics, but new DRAFT rows never enter settlement.
        boolean legacy = submission == null || ProcessSheetRow.SUBMISSION_LEGACY.equals(submission);
        return legacy && (status == null || "SAVED".equals(status) || "SUBMITTED".equals(status));
    }

    private ProcessSheetRowRequest parseProcessSheetRowPayload(ProcessSheetRow row,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        try {
            return PROCESS_SHEET_ROW_MAPPER.readValue(row.getRowPayload(), ProcessSheetRowRequest.class);
        } catch (Exception ex) {
            issues.add(issue("PROCESS_SHEET_PAYLOAD_INVALID",
                    "逐道电子表格行 " + row.getClientRowId() + " 的报工内容无法解析, 请重新保存该行后再结单。",
                    "processSheetRows"));
            return null;
        }
    }

    private List<ProductionSettlementRequest.OutputLine> deriveTerminalProcessSheetOutputs(
            List<ParsedProcessSheetRow> rows) {
        Set<String> consumedBatchNumbers = rows.stream()
                .flatMap(r -> Optional.ofNullable(r.request().getUpstreamSources()).orElseGet(ArrayList::new).stream())
                .map(ProcessSheetRowRequest.UpstreamRef::getSourceBatchNumber)
                .filter(s -> !isBlank(s))
                .collect(Collectors.toSet());

        Map<String, ProductionSettlementRequest.OutputLine> outputs = new LinkedHashMap<>();
        for (ParsedProcessSheetRow parsed : rows) {
            ProcessSheetRow row = parsed.row();
            ProcessSheetRowRequest req = parsed.request();
            BigDecimal output = req.getOutputQuantity();
            if (output == null || output.compareTo(BigDecimal.ZERO) <= 0 || isBlank(row.getBatchNumber())) {
                continue;
            }
            if (consumedBatchNumbers.contains(row.getBatchNumber())) {
                continue;
            }
            String productTypeId = trimToNull(req.getProductTypeId());
            String unit = firstNonBlank(trimToNull(req.getOutputUnit()), trimToNull(req.getUnit()));
            String key = firstNonBlank(productTypeId, "") + "\u0000" + row.getBatchNumber()
                    + "\u0000" + firstNonBlank(unit, "");
            ProductionSettlementRequest.OutputLine existing = outputs.get(key);
            if (existing == null) {
                outputs.put(key, ProductionSettlementRequest.OutputLine.builder()
                        .productTypeId(productTypeId)
                        .batchNumber(row.getBatchNumber())
                        .quantity(output)
                        .unit(unit)
                        .build());
            } else {
                existing.setQuantity(zeroIfNull(existing.getQuantity()).add(output));
            }
        }
        if (!outputs.isEmpty()) {
            return new ArrayList<>(outputs.values());
        }

        Integer maxOrder = rows.stream()
                .map(r -> r.request().getProcessOrder())
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        List<ProductionSettlementRequest.OutputLine> fallback = new ArrayList<>();
        for (ParsedProcessSheetRow parsed : rows) {
            ProcessSheetRowRequest req = parsed.request();
            boolean inLastStep = maxOrder == null || maxOrder.equals(req.getProcessOrder());
            BigDecimal output = req.getOutputQuantity();
            if (inLastStep && output != null && output.compareTo(BigDecimal.ZERO) > 0) {
                fallback.add(ProductionSettlementRequest.OutputLine.builder()
                        .productTypeId(trimToNull(req.getProductTypeId()))
                        .batchNumber(trimToNull(parsed.row().getBatchNumber()))
                        .quantity(output)
                        .unit(firstNonBlank(trimToNull(req.getOutputUnit()), trimToNull(req.getUnit())))
                        .build());
            }
        }
        return fallback;
    }

    private BigDecimal sumCompatibleTerminalOutputs(List<ProductionSettlementRequest.OutputLine> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        Set<String> units = outputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getUnit)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .map(unit -> unit.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (units.size() > 1) {
            return null;
        }
        return outputs.stream()
                .map(ProductionSettlementRequest.OutputLine::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<ProductionSettlementRequest.ConsumptionLine> deriveRawConsumptionsFromProcessSheetRows(
            String factoryId, List<ParsedProcessSheetRow> rows,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        String currentPlanId = rows.stream()
                .map(r -> trimToNull(r.row().getPlanId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        Set<Long> currentProcessBatchIds = rows.stream()
                .map(r -> r.row().getBatchId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> qtyByBatchAndProduct = new LinkedHashMap<>();
        Map<String, String> batchIdByKey = new LinkedHashMap<>();
        Map<String, String> productTypeIdByKey = new LinkedHashMap<>();
        Map<String, String> reportedUnitByKey = new LinkedHashMap<>();
        for (ParsedProcessSheetRow parsed : rows) {
            List<ProcessSheetRowRequest.RawInput> rawInputs = parsed.request().getRawMaterialInputs();
            if (rawInputs == null) {
                continue;
            }
            String productTypeId = trimToNull(parsed.request().getProductTypeId());
            String reportedUnit = firstNonBlank(
                    trimToNull(parsed.request().getInputUnit()),
                    firstNonBlank(trimToNull(parsed.request().getUnit()), "kg"));
            for (ProcessSheetRowRequest.RawInput input : rawInputs) {
                if (input == null || isBlank(input.getMaterialBatchId())
                        || input.getQuantity() == null || input.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String batchId = input.getMaterialBatchId();
                String key = batchId + "\u0000" + firstNonBlank(productTypeId, "")
                        + "\u0000" + reportedUnit;
                batchIdByKey.putIfAbsent(key, batchId);
                productTypeIdByKey.putIfAbsent(key, productTypeId);
                reportedUnitByKey.putIfAbsent(key, reportedUnit);
                qtyByBatchAndProduct.merge(key, input.getQuantity(), BigDecimal::add);
            }
        }

        List<ProductionSettlementRequest.ConsumptionLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : qtyByBatchAndProduct.entrySet()) {
            String batchId = batchIdByKey.get(e.getKey());
            String productTypeId = productTypeIdByKey.get(e.getKey());
            String reportedUnit = reportedUnitByKey.get(e.getKey());
            BigDecimal reportedQuantity = e.getValue();
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            if (batch == null) {
                issues.add(issue("RAW_BATCH_NOT_FOUND",
                        "逐道电子表格引用的原料批次 " + batchId + " 已不存在, 未自动带入; 请手工核对该料领用。",
                        "rawMaterialConsumptions"));
                continue;
            }
            String quantityUnit = trimToNull(batch.getQuantityUnit());
            if (quantityUnit == null) {
                issues.add(issue("RAW_BATCH_UNIT_MISSING",
                        "原料批次 " + safeBatchRef(batch) + " 未配置计量单位, 未自动带入; 请先修正批次单位。",
                        "rawMaterialConsumptions"));
                continue;
            }
            BigDecimal qty = convertProcessInputToBatchUnit(reportedQuantity, reportedUnit, quantityUnit);
            if (qty == null) {
                issues.add(issue("RAW_BATCH_UNIT_INCOMPATIBLE",
                        "原料批次 " + safeBatchRef(batch) + " 的库存单位 " + quantityUnit
                                + " 无法与逐道报工单位 " + reportedUnit + " 换算, 未自动带入; 请先修正批次单位。",
                        "rawMaterialConsumptions"));
                continue;
            }
            BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
            PendingConsumptionConflict conflict = resolveOtherPendingConsumption(
                    factoryId, currentPlanId, currentProcessBatchIds, batchId);
            if (conflict.quantity().add(qty).compareTo(available) > 0) {
                String planRefs = conflict.planRefs().isEmpty()
                        ? "其他未结生产计划"
                        : String.join("、", conflict.planRefs());
                issues.add(issue("RAW_BATCH_CROSS_PLAN_CONFLICT",
                        "原料批次 " + safeBatchRef(batch) + " 当前可用 " + stripTrailing(available)
                                + quantityUnit + "，但 " + planRefs + " 已待结占用 "
                                + stripTrailing(conflict.quantity()) + quantityUnit + "；本计划还需 "
                                + stripTrailing(qty) + quantityUnit
                                + "。请先撤销错误报工、补充库存或结清冲突计划，系统不会让两个计划重复扣同一批原料。",
                        "rawMaterialConsumptions"));
                continue;
            }
            if (available.compareTo(qty) < 0) {
                issues.add(issue("RAW_BATCH_INSUFFICIENT",
                        "原料批次 " + safeBatchRef(batch) + " 逐道领用 " + stripTrailing(reportedQuantity)
                                + reportedUnit + "（库存单位 " + stripTrailing(qty) + quantityUnit + "）超过当前可用量 "
                                + stripTrailing(available) + quantityUnit
                                + ", 未自动带入该行; 请人工核对实际领用批次和数量。",
                        "rawMaterialConsumptions"));
                continue;
            }
            lines.add(ProductionSettlementRequest.ConsumptionLine.builder()
                    .materialBatchId(batchId)
                    .productTypeId(productTypeId)
                    .materialTypeId(trimToNull(batch.getMaterialTypeId()))
                    .batchNumber(trimToNull(batch.getBatchNumber()))
                    .quantity(qty)
                    .unit(quantityUnit)
                    .warehouseId(trimToNull(batch.getWarehouseId()))
                    .note("自动带入自逐道电子表格")
                    .build());
        }
        return lines;
    }

    private PendingConsumptionConflict resolveOtherPendingConsumption(
            String factoryId, String currentPlanId, Set<Long> currentProcessBatchIds, String materialBatchId) {
        if (materialConsumptionRepository == null || isBlank(materialBatchId)) {
            return PendingConsumptionConflict.NONE;
        }
        BigDecimal quantity = BigDecimal.ZERO;
        Set<String> planRefs = new LinkedHashSet<>();
        List<MaterialConsumption> pending = materialConsumptionRepository
                .findByFactoryIdAndBatchId(factoryId, materialBatchId);
        if (pending == null) {
            return PendingConsumptionConflict.NONE;
        }
        for (MaterialConsumption consumption : pending) {
            if (consumption == null || consumption.getInterimSettledAt() != null
                    || zeroIfNull(consumption.getQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long productionBatchId = consumption.getProductionBatchId();
            if (productionBatchId != null && currentProcessBatchIds.contains(productionBatchId)) {
                continue;
            }
            String ownerPlanId = trimToNull(consumption.getProductionPlanId());
            if (ownerPlanId == null && productionBatchId != null && processSheetRowRepository != null) {
                ownerPlanId = processSheetRowRepository.findByFactoryIdAndBatchId(factoryId, productionBatchId)
                        .stream()
                        .map(ProcessSheetRow::getPlanId)
                        .map(this::trimToNull)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            }
            if (currentPlanId != null && currentPlanId.equals(ownerPlanId)) {
                continue;
            }
            quantity = quantity.add(consumption.getQuantity());
            if (ownerPlanId != null) {
                String resolvedOwnerPlanId = ownerPlanId;
                String ref = productionPlanRepository.findByIdAndFactoryId(ownerPlanId, factoryId)
                        .map(p -> firstNonBlank(p.getPlanNumber(), resolvedOwnerPlanId))
                        .orElse(ownerPlanId);
                planRefs.add(ref);
            }
        }
        return new PendingConsumptionConflict(quantity, List.copyOf(planRefs));
    }

    private record PendingConsumptionConflict(BigDecimal quantity, List<String> planRefs) {
        private static final PendingConsumptionConflict NONE =
                new PendingConsumptionConflict(BigDecimal.ZERO, List.of());
    }

    private BigDecimal convertProcessInputToBatchUnit(
            BigDecimal quantity, String reportedUnit, String batchUnit) {
        if (quantity == null || reportedUnit == null || batchUnit == null) {
            return null;
        }
        if (unitConversionService != null) {
            BigDecimal converted = unitConversionService.convert(quantity, reportedUnit, batchUnit);
            if (converted != null) {
                return converted.stripTrailingZeros();
            }
        }
        BigDecimal kilograms = toKilograms(quantity, reportedUnit);
        if (kilograms == null) {
            return null;
        }
        return fromKilograms(kilograms, batchUnit);
    }

    private BigDecimal toKilograms(BigDecimal quantity, String unit) {
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "kg", "kilogram", "公斤", "千克" -> quantity;
            case "g", "gram", "克" -> quantity.divide(new BigDecimal("1000"), 9, RoundingMode.HALF_UP);
            case "t", "ton", "吨" -> quantity.multiply(new BigDecimal("1000"));
            default -> null;
        };
    }

    private BigDecimal fromKilograms(BigDecimal kilograms, String unit) {
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "kg", "kilogram", "公斤", "千克" -> kilograms.stripTrailingZeros();
            case "g", "gram", "克" -> kilograms.multiply(new BigDecimal("1000")).stripTrailingZeros();
            case "t", "ton", "吨" -> kilograms.divide(new BigDecimal("1000"), 9, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            default -> null;
        };
    }

    private List<ProductionSettlementRequest.LaborSegment> deriveLaborSegmentsFromProcessSheetRows(
            List<ParsedProcessSheetRow> rows) {
        List<ProductionSettlementRequest.LaborSegment> segments = new ArrayList<>();
        for (ParsedProcessSheetRow parsed : rows) {
            List<com.cretas.aims.dto.processentry.LaborSegment> raw = parsed.request().getLaborSegments();
            if (raw == null) {
                continue;
            }
            for (com.cretas.aims.dto.processentry.LaborSegment seg : raw) {
                Integer minutes = deriveSegmentMinutes(seg);
                if (minutes == null || minutes <= 0) {
                    continue;
                }
                Integer headcount = seg.getWorkerCount();
                segments.add(ProductionSettlementRequest.LaborSegment.builder()
                        .workType(firstNonBlank(parsed.request().getProcessName(), parsed.request().getProcessCode(), "逐道电子表格"))
                        .minutes(minutes)
                        .headcount(headcount != null && headcount > 0 ? headcount : 1)
                        .build());
            }
        }
        return segments;
    }

    private Integer deriveSegmentMinutes(com.cretas.aims.dto.processentry.LaborSegment seg) {
        if (seg == null || isBlank(seg.getStartTime()) || isBlank(seg.getEndTime())) {
            return null;
        }
        try {
            java.time.LocalTime start = java.time.LocalTime.parse(seg.getStartTime());
            java.time.LocalTime end = java.time.LocalTime.parse(seg.getEndTime());
            long minutes = java.time.Duration.between(start, end).toMinutes();
            return minutes > 0 ? (int) minutes : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private record ParsedProcessSheetRow(ProcessSheetRow row, ProcessSheetRowRequest request) {
    }

    /** 末道工序产出量: 取最大 processOrder 的全部报工, 汇总其 outputQuantity。 */
    private BigDecimal deriveLastStepOutput(List<ProductionReport> reports) {
        Integer maxOrder = null;
        for (ProductionReport r : reports) {
            Integer order = r.getProcessOrder();
            if (order != null && (maxOrder == null || order > maxOrder)) {
                maxOrder = maxOrder == null ? order : Math.max(maxOrder, order);
            }
        }
        BigDecimal sum = null;
        for (ProductionReport r : reports) {
            // maxOrder 为 null (报工无 processOrder) → 退化为汇总全部 outputQuantity
            boolean inLastStep = maxOrder == null
                    ? true
                    : maxOrder.equals(r.getProcessOrder());
            if (!inLastStep) {
                continue;
            }
            BigDecimal out = r.getOutputQuantity();
            if (out != null) {
                sum = sum == null ? out : sum.add(out);
            }
        }
        return sum;
    }

    /**
     * 从各道报工的 materialBatchRefs 聚合原料领用 (按 materialBatchId 求和)。
     * <p>materialBatchRefs JSON 元素: {"materialBatchId": Long, "quantity": Number, "unit": String|null}。
     * <p>每个 batch 必须仍存在 + 在原料仓 + 在当前 BOM + 可用量足够, 否则不臆造该行 (settle 会拒),
     * 而是<b>留空 + 标 issue</b> 让人核对补录 (宁可少填不可瞎填)。
     */
    private List<ProductionSettlementRequest.ConsumptionLine> deriveRawConsumptions(
            String factoryId, ProductionPlan plan, List<ProductionReport> reports,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        // 聚合: materialBatchId(String) → 累计数量
        Map<String, BigDecimal> qtyByBatch = new LinkedHashMap<>();
        Map<String, String> unitByBatch = new LinkedHashMap<>();
        for (ProductionReport r : reports) {
            List<Map<String, Object>> refs = r.getMaterialBatchRefs();
            if (refs == null) {
                continue;
            }
            for (Map<String, Object> ref : refs) {
                if (ref == null) {
                    continue;
                }
                String batchId = asString(ref.get("materialBatchId"));
                BigDecimal qty = asBigDecimal(ref.get("quantity"));
                if (isBlank(batchId) || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                qtyByBatch.merge(batchId, qty, BigDecimal::add);
                String unit = asString(ref.get("unit"));
                if (unit != null && !unitByBatch.containsKey(batchId)) {
                    unitByBatch.put(batchId, unit);
                }
            }
        }

        List<ProductionSettlementRequest.ConsumptionLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : qtyByBatch.entrySet()) {
            String batchId = e.getKey();
            BigDecimal qty = e.getValue();
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            if (batch == null) {
                issues.add(issue("RAW_BATCH_NOT_FOUND",
                        "报工记录引用的原料批次 " + batchId + " 已不存在, 未自动带入; 请手工核对该料领用。",
                        "rawMaterialConsumptions"));
                continue;
            }
            BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
            if (available.compareTo(qty) < 0) {
                // 报工领用量 > 当前可用 (批次已被部分消耗/标 USED_UP): 不臆造, 让人核对
                issues.add(issue("RAW_BATCH_INSUFFICIENT",
                        "原料批次 " + safeBatchRef(batch) + " 报工领用 " + stripTrailing(qty)
                                + " 超过当前可用量 " + stripTrailing(available)
                                + ", 未自动带入该行; 请人工核对实际领用批次/数量。",
                        "rawMaterialConsumptions"));
                continue;
            }
            // 不在此预填阶段强校验 BOM/原料仓 (settle 时会校验); 但若已知不在原料仓, 提示让人换批次
            ProductionSettlementRequest.ConsumptionLine line = ProductionSettlementRequest.ConsumptionLine.builder()
                    .materialBatchId(batchId)
                    .materialTypeId(trimToNull(batch.getMaterialTypeId()))
                    .batchNumber(trimToNull(batch.getBatchNumber()))
                    .quantity(qty)
                    .unit(firstNonBlank(unitByBatch.get(batchId), batch.getQuantityUnit()))
                    .warehouseId(trimToNull(batch.getWarehouseId()))
                    .note("自动带入自逐道报工")
                    .build();
            lines.add(line);
        }
        return lines;
    }

    /**
     * 从各道报工聚合工时段。优先用 report.laborSegments (多时段×人数);
     * 缺失时回退到单一 totalWorkMinutes + totalWorkers。derive 不出任何工时 → 返回空列表。
     */
    private List<ProductionSettlementRequest.LaborSegment> deriveLaborSegments(List<ProductionReport> reports) {
        List<ProductionSettlementRequest.LaborSegment> segments = new ArrayList<>();
        for (ProductionReport r : reports) {
            String workType = trimToNull(r.getProcessCategory());
            List<Map<String, Object>> raw = r.getLaborSegments();
            boolean addedFromRaw = false;
            if (raw != null) {
                for (Map<String, Object> seg : raw) {
                    if (seg == null) {
                        continue;
                    }
                    Integer minutes = deriveSegmentMinutes(seg);
                    if (minutes == null || minutes <= 0) {
                        continue;
                    }
                    Integer headcount = asInteger(seg.get("headcount"));
                    segments.add(ProductionSettlementRequest.LaborSegment.builder()
                            .workType(firstNonBlank(workType, "逐道报工"))
                            .minutes(minutes)
                            .headcount(headcount != null && headcount > 0 ? headcount : 1)
                            .note(asString(seg.get("note")))
                            .build());
                    addedFromRaw = true;
                }
            }
            if (!addedFromRaw) {
                Integer minutes = r.getTotalWorkMinutes();
                if (minutes != null && minutes > 0) {
                    Integer headcount = r.getTotalWorkers();
                    segments.add(ProductionSettlementRequest.LaborSegment.builder()
                            .workType(firstNonBlank(workType, "逐道报工"))
                            .minutes(minutes)
                            .headcount(headcount != null && headcount > 0 ? headcount : 1)
                            .build());
                }
            }
        }
        return segments;
    }

    /** 工时段分钟: 直接读 minutes, 否则按 startTime/endTime 差值 (HH:mm) 计算。无法算 → null。 */
    private Integer deriveSegmentMinutes(Map<String, Object> seg) {
        Integer minutes = asInteger(seg.get("minutes"));
        if (minutes != null) {
            return minutes;
        }
        String start = asString(seg.get("startTime"));
        String end = asString(seg.get("endTime"));
        if (start == null || end == null) {
            return null;
        }
        try {
            java.time.LocalTime s = java.time.LocalTime.parse(start);
            java.time.LocalTime e = java.time.LocalTime.parse(end);
            long mins = java.time.Duration.between(s, e).toMinutes();
            return mins > 0 ? (int) mins : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private ProductionSettlementRequest emptyPrefill(ProductionPlan plan) {
        return ProductionSettlementRequest.builder()
                .idempotencyKey(null)
                .actualFinishedQuantity(null)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .quantityUnit(null) // ProductionPlan 无单位字段; 前端提交时从行数据(unit/quantityUnit)补
                .rawMaterialConsumptions(new ArrayList<>())
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(new ArrayList<>())
                .build();
    }

    private ProductionSettlementPrefillResponse buildPrefillResponse(
            ProductionSettlementRequest prefill,
            List<ProductionSettlementPrefillResponse.Issue> issues) {
        // clean 只看 BLOCKER 级 — INFO 级提示 (如跨计划半成品确认) 不阻塞一键确认
        boolean clean = issues.stream()
                .noneMatch(i -> i.getSeverity() == ProductionSettlementPrefillResponse.Severity.BLOCKER);
        return ProductionSettlementPrefillResponse.builder()
                .prefill(prefill)
                .audit(ProductionSettlementPrefillResponse.Audit.builder()
                        .clean(clean)
                        .issues(issues)
                        .build())
                .build();
    }

    /** BLOCKER 级 issue (令 clean=false, 阻塞一键确认)。 */
    private ProductionSettlementPrefillResponse.Issue issue(String code, String message, String field) {
        return ProductionSettlementPrefillResponse.Issue.builder()
                .code(code).message(message).field(field)
                .severity(ProductionSettlementPrefillResponse.Severity.BLOCKER).build();
    }

    /** INFO 级 issue (仅提示, 不阻塞一键确认)。 */
    private ProductionSettlementPrefillResponse.Issue infoIssue(String code, String message, String field) {
        return ProductionSettlementPrefillResponse.Issue.builder()
                .code(code).message(message).field(field)
                .severity(ProductionSettlementPrefillResponse.Severity.INFO).build();
    }

    private String safeBatchRef(MaterialBatch batch) {
        if (batch == null) {
            return "?";
        }
        return !isBlank(batch.getBatchNumber()) ? batch.getBatchNumber() : String.valueOf(batch.getId());
    }

    private String firstNonBlank(String a, String b) {
        if (!isBlank(a)) {
            return a.trim();
        }
        return trimToNull(b);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stripTrailing(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementResponse getProductionSettlement(String factoryId, String planId) {
        ProductionSettlement settlement = requireProductionSettlement(factoryId, planId);
        return toSettlementResponse(settlement, Collections.emptyList());
    }

    @Override
    @Transactional
    public ProductionSettlementResponse bridgeByStockSettlement(String factoryId, String planId) {
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .orElseGet(() -> bridgeProductionSettlementForWarehouse(factoryId, planId));
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
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .orElseGet(() -> bridgeProductionSettlementForWarehouse(factoryId, planId));

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
        if (plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
            unit = canonicalReceiptUnit(unit);
        }
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
        if (needsTransitLedger && isPendingResponsibilitySide(request.getResponsibilitySide())) {
            throw new BusinessException(409, "仓库实收差异超出容差, 必须明确责任侧")
                    .withCode("PRODUCTION_RECEIPT_RESPONSIBILITY_REQUIRED")
                    .withHint("请选择生产侧处理、仓库侧处理或称重误差后再确认入库")
                    .withHintTarget("责任侧");
        }

        FinishedGoodsBatch fgBatch = plan.getSourceType() == PlanSourceType.SAFETY_STOCK
                ? requireInterimFinishedGoodsForReceipt(plan, settlement, reported, received, unit)
                : createFinishedGoodsFromReceipt(plan, settlement, received, unit, receivedBy);
        ProductionTransitLedger ledger = needsTransitLedger
                ? createTransitLedger(settlement, reported, received, variance, tolerance, unit, request, receivedBy)
                : null;

        settlement.setWarehouseReceiptIdempotencyKey(request.getIdempotencyKey());
        settlement.setWarehouseReceivedQuantity(received);
        settlement.setWarehouseVarianceQuantity(variance);
        settlement.setQuantityUnit(unit);
        settlement.setWarehouseVarianceReason(trimToNull(request.getVarianceReason()));
        settlement.setWarehouseResponsibilitySide(needsTransitLedger
                ? trimToNull(request.getResponsibilitySide())
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
        int completedBatchCount = markReceiptProductionBatchesCompleted(factoryId, planId, settlement);

        List<String> warnings = new ArrayList<>();
        if (needsTransitLedger) {
            warnings.add("差异已进入中转挂账, 需继续判断责任归属并清账");
        } else if (withinTolerance) {
            warnings.add("差异在称重容差内, 本次不生成中转挂账");
        }
        if (completedBatchCount > 0) {
            warnings.add("已同步 " + completedBatchCount + " 个逐道报工成品批次为已完工, 可进入成品出厂核算");
        }
        return toWarehouseReceiptResponse(settlement, settlement.getPostingMessage(), warnings);
    }

    private int markReceiptProductionBatchesCompleted(String factoryId, String planId, ProductionSettlement settlement) {
        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
        if (batches == null || batches.isEmpty()) {
            return 0;
        }
        int changed = 0;
        LocalDateTime completedAt = LocalDateTime.now();
        for (ProductionBatch batch : batches) {
            if (batch == null || batch.getId() == null) {
                continue;
            }
            if ("CLERK_WIP".equals(batch.getBatchType())) {
                continue;
            }
            if (batch.getStatus() == ProductionBatchStatus.COMPLETED
                    || batch.getStatus() == ProductionBatchStatus.CANCELLED) {
                continue;
            }
            BigDecimal actualQuantity = zeroIfNull(batch.getQuantity());
            if (actualQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                actualQuantity = zeroIfNull(batch.getActualQuantity());
            }
            if (actualQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                actualQuantity = zeroIfNull(settlement.getActualFinishedQuantity());
            }
            batch.setStatus(ProductionBatchStatus.COMPLETED);
            if (batch.getEndTime() == null) {
                batch.setEndTime(completedAt);
            }
            batch.setActualQuantity(actualQuantity);
            if (batch.getGoodQuantity() == null || batch.getGoodQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                batch.setGoodQuantity(actualQuantity);
            }
            if (batch.getDefectQuantity() == null) {
                batch.setDefectQuantity(BigDecimal.ZERO);
            }
            batch.calculateMetrics();
            productionBatchRepository.save(batch);
            changed++;
        }
        return changed;
    }

    @Override
    @Transactional
    public ProductionWarehouseReceiptResponse clearProductionTransitLedger(String factoryId, String planId,
                                                                          ProductionTransitClearingRequest request,
                                                                          Long clearedBy) {
        if (request == null || isBlank(request.getClearingReason())) {
            throw new BusinessException(400, "清账原因不能为空")
                    .withHintTarget("中转挂账清账");
        }
        ensurePostingDependencies();

        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产结单", "productionPlanId", planId));

        if (!"PENDING_CLEARING".equals(settlement.getPostingStatus())) {
            throw new BusinessException(409, "该生产结单没有待清账的中转挂账")
                    .withCode("PRODUCTION_TRANSIT_NOT_PENDING")
                    .withHint("当前状态: " + settlement.getPostingStatus())
                    .withHintTarget("中转挂账清账");
        }

        ProductionTransitLedger ledger = productionTransitLedgerRepository
                .findOpenByFactoryIdAndSettlementIdForUpdate(factoryId, settlement.getId(), "OPEN")
                .orElseThrow(() -> new BusinessException(409, "未找到待清账的生产中转挂账")
                        .withCode("PRODUCTION_TRANSIT_LEDGER_NOT_FOUND")
                        .withHintTarget("中转挂账清账"));

        String clearingNote = firstNonBlank(request.getClearingNote(), "");
        String note = firstNonBlank(ledger.getNote(), "");
        String appended = "清账原因: " + request.getClearingReason()
                + (clearingNote.isBlank() ? "" : "; 清账说明: " + clearingNote)
                + "; clearedBy=" + (clearedBy != null ? clearedBy : 0L);
        ledger.setNote(note.isBlank() ? appended : note + "\n" + appended);
        ledger.setStatus("RESOLVED");
        productionTransitLedgerRepository.save(ledger);

        settlement.setPostingStatus("POSTED");
        settlement.setPostingMessage("中转挂账已清账，成品库存入库闭环完成");
        productionSettlementRepository.save(settlement);

        return toWarehouseReceiptResponse(settlement,
                "中转挂账已清账: " + plan.getPlanNumber(),
                List.of("责任侧已处理差异并完成清账"));
    }

    private void validateSettlementRequest(ProductionPlan plan, ProductionSettlementRequest request) {
        // 🔒🔒 (2026-07-04) sourceType 守卫: 存货生产(SAFETY_STOCK) 走「小结」逐批扣减入库, 严禁「结单」。
        //   结单的 postConsumptionToInventory 会再次扣减原料; 而 SAFETY_STOCK 的 interimSettle 早已逐笔
        //   扣过 usedQuantity → 若放行结单 = 原料双重扣减 + 财务口径腐蚀 (幻库存)。UI 隐藏了结单按钮,
        //   但 POST /production-plans/{id}/settle 是开放的 API, 必须在服务层锁死。与 interimSettle 反向对称
        //   (interimSettle 拒绝非 SAFETY_STOCK), 使每条结算路径按计划族锁死。
        if (plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "存货生产计划不能结单, 请走「小结」逐批结算")
                    .withCode("SAFETY_STOCK_MUST_INTERIM_SETTLE")
                    .withHint("存货生产(SAFETY_STOCK)按小结逐批扣减入库; 结单会造成原料重复扣减")
                    .withHintTarget("小结");
        }
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
        // A修: 跨单位(份/盒 vs kg)时产量裸比无意义, 跳过超产校验 (prefill 已留 INFO 让人核对).
        boolean crossUnit = isCrossUnitPlan(
                productionBatchRepository.findByFactoryIdAndProductionPlanId(plan.getFactoryId(), plan.getId()));
        if (!crossUnit
                && planned.compareTo(BigDecimal.ZERO) > 0
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

    private void appendConsumptionLines(String factoryId, ProductionPlan plan, String sourceType,
                                        List<ProductionSettlementRequest.ConsumptionLine> requestLines,
                                        List<ProductionSettlementConsumption> target) {
        if (isEmpty(requestLines)) {
            return;
        }
        for (ProductionSettlementRequest.ConsumptionLine requestLine : requestLines) {
            ProductionSettlementConsumption line = new ProductionSettlementConsumption();
            line.setFactoryId(factoryId);
            line.setProductionPlanId(plan.getId());
            line.setSourceType(sourceType);
            line.setMaterialBatchId(trimToNull(requestLine.getMaterialBatchId()));
            line.setSemiFinishedInventoryId(requestLine.getSemiFinishedInventoryId());
            line.setMaterialTypeId(trimToNull(requestLine.getMaterialTypeId()));
            line.setBatchNumber(trimToNull(requestLine.getBatchNumber()));
            line.setQuantity(requestLine.getQuantity());
            line.setUnit(trimToNull(requestLine.getUnit()));
            line.setWarehouseId(trimToNull(requestLine.getWarehouseId()));
            line.setNote(trimToNull(requestLine.getNote()));
            line.setAvailableBefore(resolveAvailableBefore(factoryId, plan, sourceType, requestLine));
            target.add(line);
        }
    }

    private BigDecimal resolveAvailableBefore(String factoryId, ProductionPlan plan,
                                              String sourceType,
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
        ensureMaterialBatchAllowedForSettlement(factoryId, plan, line.getProductTypeId(), batch, "实际领用");
        BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
        Set<Long> currentProcessBatchIds = processSheetRowRepository == null
                ? Set.of()
                : processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId()).stream()
                        .map(ProcessSheetRow::getBatchId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        PendingConsumptionConflict conflict = resolveOtherPendingConsumption(
                factoryId, plan.getId(), currentProcessBatchIds, line.getMaterialBatchId());
        if (conflict.quantity().add(zeroIfNull(line.getQuantity())).compareTo(available) > 0) {
            String planRefs = conflict.planRefs().isEmpty()
                    ? "其他未结生产计划"
                    : String.join("、", conflict.planRefs());
            throw new BusinessException(409, "原料批次 " + batch.getBatchNumber()
                    + " 已被 " + planRefs + " 待结占用，本计划不能重复扣减")
                    .withCode("RAW_BATCH_CROSS_PLAN_CONFLICT")
                    .withHint("请先撤销错误报工、补充库存或结清冲突计划")
                    .withHintTarget("实际领用");
        }
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

    private void ensureMaterialBatchAllowedForSettlement(String factoryId,
                                                         ProductionPlan plan,
                                                         String productTypeIdOverride,
                                                         MaterialBatch batch,
                                                         String hintTarget) {
        if (warehouseResolver == null) {
            throw new BusinessException(500, "仓库解析服务未初始化，不能核对生产领料")
                    .withHintTarget(hintTarget);
        }
        String rawWarehouseId = warehouseResolver.resolveLogisticsId(factoryId);
        if (isBlank(rawWarehouseId)) {
            throw new BusinessException(500, "未配置原料仓/物流仓，不能核对生产领料")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_NOT_CONFIGURED")
                    .withHint("请先维护工厂仓库配置")
                    .withHintTarget(hintTarget);
        }
        // 2026-07-03 料流对齐修复: 生产领料 (transferToFactory) 把原料从原料仓 (WH-LOG) 物理迁到
        //   生产仓 (WORKSHOP/WH-WKS) 后, 逐道报工的 ensureRawMaterialWarehouse 已放行 WORKSHOP 批次
        //   (ProcessSheetServiceImpl 2026-07-03), 但本结单闸 (2026-06-13, 早于领料→生产仓模型) 仍只认
        //   原料仓/物流仓单一仓 → 结单预填自 WKS 批次带入的行被自己的结单闸 409 拒收, 阻断
        //   领料→调拨→生产仓→报工→结单 标准料流。现与报工闸对齐: 除配置原料仓外, 也放行本工厂
        //   RAW/LOGISTICS 类型仓 及 WORKSHOP 类型仓 (料合法落点) 的批次。
        //   诚实-null: batch 无仓 / 仓非 RAW/LOGISTICS/WORKSHOP (如 成品仓 FINISHED / 研发库 RD) → loud-fail 409。
        String batchWarehouseId = batch.getWarehouseId();
        boolean warehouseAllowed = !isBlank(batchWarehouseId)
                && (rawWarehouseId.equals(batchWarehouseId)
                    || warehouseResolver.isRawOrLogisticsWarehouse(factoryId, batchWarehouseId)
                    || warehouseResolver.isWorkshopWarehouse(factoryId, batchWarehouseId));
        if (!warehouseAllowed) {
            throw new BusinessException(409, "生产结单原料只能从原料仓/物流仓/生产仓领用，不能从其他仓库扣减")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_REQUIRED")
                    .withHint("请重新选择原料仓/物流仓/生产仓批次后再提交")
                    .withHintTarget(hintTarget);
        }

        String productTypeId = firstNonBlank(productTypeIdOverride, plan != null ? plan.getProductTypeId() : null);
        BomSettlementEligibility eligibility = resolveBomEligibilityForSettlement(factoryId, productTypeId);
        if (!eligibility.restricted()) {
            return;
        }
        if (!eligibility.bomFound()) {
            throw new BusinessException(409, "该产品没有当前 BOM，不能直接核对原料领用")
                    .withCode("PRODUCTION_BOM_REQUIRED")
                    .withHint("请先维护产品 BOM，或由主管确认物料差异后再结单")
                    .withHintTarget(hintTarget);
        }
        if (eligibility.materialTypeIds().isEmpty()) {
            throw new BusinessException(409, "该产品当前 BOM 没有原料明细，不能直接核对原料领用")
                    .withCode("PRODUCTION_BOM_ITEMS_REQUIRED")
                    .withHint("请先维护 BOM 原料明细")
                    .withHintTarget(hintTarget);
        }
        if (!eligibility.materialTypeIds().contains(batch.getMaterialTypeId())) {
            throw new BusinessException(409, "所选原料批次不属于该产品当前 BOM")
                    .withCode("PRODUCTION_CONSUMPTION_NOT_IN_BOM")
                    .withHint("请按产品 BOM 选择原料批次，避免结单扣错料")
                    .withHintTarget(hintTarget);
        }
    }

    /**
     * BOM 结单校验结果 (单一 source of truth, 被写路径守卫 {@link #ensureMaterialBatchAllowedForSettlement}
     * 和只读预过滤端点 {@link #getSettlementBomEligibility} 共用 —— 防止两处判定逻辑各写一份后来漂移不一致)。
     *
     * @param restricted      产品是否有 BOM 限制 (false = productTypeId 为空或 BOM 模块未启用, 不受限)
     * @param bomFound        restricted=true 时, 是否找到该产品当前生效 BOM
     * @param materialTypeIds BOM 允许的原料 materialTypeId 集合 (restricted=false 时无意义)
     */
    private record BomSettlementEligibility(boolean restricted, boolean bomFound, Set<String> materialTypeIds) {
        private static final BomSettlementEligibility UNRESTRICTED =
                new BomSettlementEligibility(false, false, Set.of());
    }

    /**
     * 解析产品当前 BOM 对结单原料领用的限制 (与写路径守卫判定逻辑 1:1 一致, 详见类上方
     * {@link #ensureMaterialBatchAllowedForSettlement} 调用处)。
     */
    private BomSettlementEligibility resolveBomEligibilityForSettlement(String factoryId, String productTypeId) {
        if (isBlank(productTypeId) || bomRecipeRepository == null || bomRecipeItemRepository == null) {
            return BomSettlementEligibility.UNRESTRICTED;
        }
        Optional<BomRecipe> recipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
        if (recipe.isEmpty()) {
            return new BomSettlementEligibility(true, false, Set.of());
        }
        Set<String> bomMaterialTypeIds = bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.get().getId())
                .stream()
                .map(BomRecipeItem::getMaterialTypeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return new BomSettlementEligibility(true, true, bomMaterialTypeIds);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionSettlementBomEligibilityResponse getSettlementBomEligibility(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        BomSettlementEligibility eligibility =
                resolveBomEligibilityForSettlement(factoryId, plan.getProductTypeId());
        return ProductionSettlementBomEligibilityResponse.builder()
                .restricted(eligibility.restricted())
                .bomFound(eligibility.bomFound())
                .materialTypeIds(new ArrayList<>(eligibility.materialTypeIds()))
                .build();
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

    private void postConsumptionToInventory(String factoryId, List<ProductionSettlementConsumption> lines) {
        if (isEmpty(lines)) {
            return;
        }
        for (ProductionSettlementConsumption line : lines) {
            if ("SEMI_FINISHED".equals(line.getSourceType())) {
                postSemiFinishedConsumption(factoryId, line);
            } else {
                postMaterialBatchConsumption(factoryId, line);
            }
        }
    }

    private void postMaterialBatchConsumption(String factoryId, ProductionSettlementConsumption line) {
        MaterialBatch batch = materialBatchRepository
                .findByIdAndFactoryIdForUpdate(line.getMaterialBatchId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "原料批次不存在: " + line.getMaterialBatchId())
                        .withHintTarget("实际领用"));
        ensureMaterialBatchAllowedForSettlement(factoryId, null, null, batch, "实际领用");
        BigDecimal available = zeroIfNull(batch.getCurrentQuantity());
        ensureQuantityWithinAvailable("原料批次 " + batch.getBatchNumber(), line.getQuantity(), available, "实际领用");

        BigDecimal qty = zeroIfNull(line.getQuantity());
        batch.setUsedQuantity(zeroIfNull(batch.getUsedQuantity()).add(qty));
        batch.setLastUsedAt(LocalDateTime.now());
        if (batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }
        materialBatchRepository.save(batch);
        inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "OUT");
    }

    private void postSemiFinishedConsumption(String factoryId, ProductionSettlementConsumption line) {
        SemiFinishedInventory inventory = semiFinishedInventoryRepository.findByIdForUpdate(line.getSemiFinishedInventoryId())
                .orElseThrow(() -> new BusinessException(404, "半成品库存不存在: " + line.getSemiFinishedInventoryId())
                        .withHintTarget("半成品领用"));
        if (!factoryId.equals(inventory.getFactoryId())) {
            throw new BusinessException(403, "无权领用其他工厂的半成品库存")
                    .withHintTarget("半成品领用");
        }
        BigDecimal available = zeroIfNull(inventory.getAvailableQuantity());
        ensureQuantityWithinAvailable("半成品库存 " + inventory.getIntermediateBatchNo(), line.getQuantity(), available, "半成品领用");

        BigDecimal qty = zeroIfNull(line.getQuantity());
        BigDecimal remaining = available.subtract(qty);
        inventory.setConsumedQuantity(zeroIfNull(inventory.getConsumedQuantity()).add(qty));
        inventory.setAvailableQuantity(remaining);
        inventory.setStatus(remaining.compareTo(BigDecimal.ZERO) <= 0
                ? SemiFinishedInventory.Status.DEPLETED
                : SemiFinishedInventory.Status.AVAILABLE);
        semiFinishedInventoryRepository.save(inventory);
    }

    /**
     * 🔴🔒 R2 (2026-07-04): 结单族 —— 按 process-row 严格扣减常驻 SFI/FG 投料 (防 phantom 库存腐蚀)。
     *
     * <p>逐道录入把常驻半成品(SFI, {@code semiFinished=true})/成品(FG, {@code finishedGoods=true})作投料记在
     * {@code process_sheet_rows.upstreamSources}。这些投料边<b>不写</b> MaterialConsumption (投的是外部常驻库存,
     * 非本计划在制 WIP), 且结单预填把 {@code semiFinishedConsumptions} 留空 + FG 投料结单请求无字段 → 此前
     * 永不扣减 = 幻库存 (消耗了却不减可用量)。此处严格扣减 (镜像小结 §②/(C) 的 consumeClerkSemiStrict /
     * consumeForFeedStrict, 缺失/不足即抛 → 整 {@code settleProduction @Transactional} 回滚, 禁止降级)。
     *
     * <ul>
     *   <li><b>仅结单族</b>: SAFETY_STOCK 走小结 (interimSettle) 扣减, 此处守卫跳过避免双扣。</li>
     *   <li><b>防双扣</b>: 若结单请求带手工 {@code semiFinishedConsumptions} (文员手工管理 SFI 领用) → 尊重手工,
     *       跳过 SFI 自动扣; FG 投料无手工字段, 照常自动扣。</li>
     *   <li><b>幂等</b>: {@code settleProduction} 已阻断重复结单 (PRODUCTION_ALREADY_SETTLED + idempotencyKey),
     *       故本扣减每个计划恰执行一次。</li>
     * </ul>
     */
    private void deductProcessSheetStockFeeds(String factoryId, ProductionPlan plan,
                                              ProductionSettlementRequest request) {
        if (plan == null || plan.getSourceType() == PlanSourceType.SAFETY_STOCK) {
            return;   // SAFETY_STOCK 由小结扣减
        }
        if (processSheetRowRepository == null || isBlank(plan.getId())) {
            return;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        if (isEmpty(rows)) {
            return;
        }
        Set<String> manuallyPostedSemiBatchNumbers = resolveManualSemiBatchNumbers(factoryId, request);
        BigDecimal sfiOut = BigDecimal.ZERO;
        BigDecimal fgOut = BigDecimal.ZERO;
        int sfiCount = 0;
        int fgCount = 0;
        List<ProductionSettlementPrefillResponse.Issue> ignore = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (!isUsableProcessSheetRow(row)) {
                continue;
            }
            ProcessSheetRowRequest req = parseProcessSheetRowPayload(row, ignore);
            if (req == null || isEmpty(req.getUpstreamSources())) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                BigDecimal feed = zeroIfNull(ref.getFeedQuantityKg());
                if (feed.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String srcBatchNo = ref.getSourceBatchNumber();
                if (ref.isFinishedGoods()) {
                    if (finishedGoodsFeedService == null) {
                        // 禁止降级: 有 FG 投料却无扣减服务 → loud-fail (绝不静默留 phantom)
                        throw new BusinessException(500, "成品投料扣减服务未就绪, 无法完成结单 (投料来源: "
                                + srcBatchNo + ")").withHintTarget("核对结单");
                    }
                    BigDecimal drawn = finishedGoodsFeedService.consumeForFeedStrict(factoryId, srcBatchNo, feed, "kg");
                    fgOut = fgOut.add(zeroIfNull(drawn));
                    fgCount++;
                } else if (ref.isSemiFinished()) {
                    if (manuallyPostedSemiBatchNumbers.contains(srcBatchNo)) {
                        continue;   // 仅该同一 SFI 已走手工领用时跳过，不能全局跳过其他真实投料
                    }
                    if (wipInventoryService == null) {
                        throw new BusinessException(500, "半成品投料扣减服务未就绪, 无法完成结单 (投料来源: "
                                + srcBatchNo + ")").withHintTarget("核对结单");
                    }
                    BigDecimal drawn = wipInventoryService.consumeClerkSemiStrict(factoryId, srcBatchNo, feed);
                    sfiOut = sfiOut.add(zeroIfNull(drawn));
                    sfiCount++;
                }
            }
        }
        if (sfiCount > 0 || fgCount > 0) {
            log.info("结单族 SFI/FG 投料扣减 (R2): factoryId={}, planId={}, SFI {} 笔 ({}), FG {} 笔 ({}){}",
                    factoryId, plan.getId(), sfiCount, sfiOut, fgCount, fgOut,
                    manuallyPostedSemiBatchNumbers.isEmpty()
                            ? ""
                            : " [同批次 SFI 手工补录已定点替换]");
        }
    }

    private Set<String> resolveManualSemiBatchNumbers(String factoryId,
                                                       ProductionSettlementRequest request) {
        if (request == null || isEmpty(request.getSemiFinishedConsumptions())
                || semiFinishedInventoryRepository == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (ProductionSettlementRequest.ConsumptionLine line : request.getSemiFinishedConsumptions()) {
            if (line == null || line.getSemiFinishedInventoryId() == null) {
                continue;
            }
            semiFinishedInventoryRepository.findById(line.getSemiFinishedInventoryId())
                    .filter(inventory -> factoryId.equals(inventory.getFactoryId()))
                    .map(SemiFinishedInventory::getIntermediateBatchNo)
                    .map(this::trimToNull)
                    .ifPresent(result::add);
        }
        return result;
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
                .orElseThrow(this::missingProductionSettlement);
    }

    /**
     * 把 BY_STOCK 小结的库存真值桥接为仓库确认所需的唯一结单元数据。
     *
     * <p>历史上 {@code interim-settle} 与普通 {@code settle} 各写一张互不相通的表：前者已经扣料并
     * 创建可用 FG，后者才是仓库列表 GET/确认入口读取的表。这里在计划行悲观锁内做一次严格派生，
     * 只插入缺失的 {@link ProductionSettlement}；不会调用小结、扣料、保存报工行或创建 FG。
     */
    private ProductionSettlement bridgeProductionSettlementForWarehouse(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        // 所有补建入口都锁同一 plan；拿锁后必须重读，保证重复 GET/刷新只创建一行。
        Optional<ProductionSettlement> raced = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId);
        if (raced.isPresent()) {
            return raced.get();
        }
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw missingProductionSettlement();
        }
        if (productionInterimSettlementRepository == null
                || finishedGoodsBatchRepository == null || processSheetRowRepository == null) {
            throw new BusinessException(500, "存货生产仓库确认桥接服务未初始化")
                    .withCode("BY_STOCK_SETTLEMENT_BRIDGE_UNAVAILABLE")
                    .withHint("请联系管理员检查小结、报工和成品库存组件")
                    .withHintTarget("仓库实收");
        }
        if (plan.getStatus() != ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "存货生产计划尚未停产, 不能由仓库确认入库")
                    .withCode("BY_STOCK_RECEIPT_REQUIRES_STOPPED_PLAN")
                    .withHint("请先完成本次小结并停产")
                    .withHintTarget("仓库实收");
        }

        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        if (isEmpty(rows) || rows.stream().anyMatch(row ->
                !ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())
                        || row.getInterimSettledAt() == null)) {
            throw new BusinessException(409, "存货生产报工尚未全部正式提交并小结, 不能补建仓库确认")
                    .withCode("BY_STOCK_SETTLEMENT_ROWS_INCOMPLETE")
                    .withHint("请核对逐道报工和小结状态；系统不会重放库存动作")
                    .withHintTarget("仓库实收");
        }

        List<ProductionInterimSettlement> sessions = productionInterimSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(factoryId, planId);
        if (isEmpty(sessions)) {
            throw new BusinessException(409, "该存货生产计划没有可核验的小结会话")
                    .withCode("BY_STOCK_INTERIM_SETTLEMENT_REQUIRED")
                    .withHint("系统不会用报工行猜测库存结果；请先完成小结")
                    .withHintTarget("仓库实收");
        }
        for (int i = 0; i < sessions.size(); i++) {
            Integer sessionSeq = sessions.get(i).getSessionSeq();
            if (sessionSeq == null || sessionSeq != i + 1) {
                throw new BusinessException(409, "存货生产小结会话序号不连续, 不能安全补建仓库确认")
                        .withCode("BY_STOCK_INTERIM_SETTLEMENT_SEQUENCE_INVALID")
                        .withHint("请联系管理员核对小结审计记录；系统未改动任何库存")
                        .withHintTarget("仓库实收");
            }
        }

        LinkedHashSet<String> batchNumbers = new LinkedHashSet<>();
        BigDecimal summarizedFinishedQuantity = BigDecimal.ZERO;
        for (ProductionInterimSettlement session : sessions) {
            Map<String, Object> summary = session.getSummary();
            if (summary == null) {
                throw invalidInterimFinishedGoods("小结摘要缺失");
            }
            Object numbers = summary.get("finishedGoodsBatchNumbers");
            if (numbers instanceof Collection<?> collection) {
                collection.stream()
                        .map(value -> value != null ? String.valueOf(value) : null)
                        .map(this::trimToNull)
                        .filter(Objects::nonNull)
                        .forEach(batchNumbers::add);
            }
            BigDecimal sessionFinished = asBigDecimal(summary.get("finishedQuantity"));
            if (sessionFinished == null || sessionFinished.signum() < 0) {
                throw invalidInterimFinishedGoods("小结成品数量无效");
            }
            summarizedFinishedQuantity = summarizedFinishedQuantity.add(sessionFinished);
        }
        if (batchNumbers.size() != 1 || summarizedFinishedQuantity.signum() <= 0) {
            throw invalidInterimFinishedGoods("仓库单数量确认仅支持唯一成品批次, 当前批次数=" + batchNumbers.size());
        }

        String batchNumber = batchNumbers.iterator().next();
        FinishedGoodsBatch finishedGoods = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> invalidInterimFinishedGoods("小结记录的成品批次不存在: " + batchNumber));
        validateInterimFinishedGoods(plan, finishedGoods, summarizedFinishedQuantity);

        ProductionInterimSettlement latestSession = sessions.get(sessions.size() - 1);
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(UUID.randomUUID().toString());
        settlement.setFactoryId(factoryId);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber(plan.getPlanNumber());
        settlement.setIdempotencyKey("by-stock-interim:" + latestSession.getId());
        settlement.setPlannedQuantity(zeroIfNull(plan.getPlannedQuantity()));
        settlement.setActualFinishedQuantity(finishedGoods.getProducedQuantity());
        settlement.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        settlement.setQuantityUnit(trimToNull(finishedGoods.getUnit()));
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setPostingMessage("存货生产小结已完成扣料并生成唯一成品批次; 等待仓库确认实收, 确认不会重复创建成品库存");
        settlement.setFinishedGoodsBatchId(finishedGoods.getId());
        settlement.setSettledBy(latestSession.getPostedBy());
        settlement.setSettledAt(latestSession.getPostedAt());
        ProductionSettlement saved = productionSettlementRepository.save(settlement);
        log.info("BY_STOCK 结单桥接仅补建元数据: factoryId={}, planId={}, settlementId={}, sessions={}, fgBatch={}, quantity={} {}",
                factoryId, planId, saved.getId(), sessions.size(), finishedGoods.getBatchNumber(),
                finishedGoods.getProducedQuantity(), finishedGoods.getUnit());
        return saved;
    }

    private void validateInterimFinishedGoods(ProductionPlan plan, FinishedGoodsBatch finishedGoods,
                                              BigDecimal summarizedFinishedQuantity) {
        if (!plan.getId().equals(finishedGoods.getProductionPlanId())) {
            throw invalidInterimFinishedGoods("小结成品批次不属于当前生产计划");
        }
        List<String> targetIds = Optional.ofNullable(plan.getTargetFinishedGoodIds()).orElseGet(ArrayList::new)
                .stream().map(this::trimToNull).filter(Objects::nonNull).distinct().toList();
        if (targetIds.size() > 1) {
            throw invalidInterimFinishedGoods("多成品计划不能合并为一条仓库实收");
        }
        String expectedProductTypeId = targetIds.isEmpty()
                ? trimToNull(plan.getProductTypeId()) : targetIds.get(0);
        if (!Objects.equals(expectedProductTypeId, trimToNull(finishedGoods.getProductTypeId()))) {
            throw invalidInterimFinishedGoods("小结成品 SKU 与计划终端 SKU 不一致");
        }
        BigDecimal produced = zeroIfNull(finishedGoods.getProducedQuantity());
        if (produced.signum() <= 0 || produced.compareTo(summarizedFinishedQuantity) != 0) {
            throw invalidInterimFinishedGoods("小结摘要数量与唯一成品批次数量不一致");
        }
        if (isBlank(finishedGoods.getUnit())) {
            throw invalidInterimFinishedGoods("小结成品批次缺少计量单位");
        }
    }

    /** BY_STOCK 仓库确认只确认小结已创建的唯一 FG，禁止再建第二个成品批次。 */
    private FinishedGoodsBatch requireInterimFinishedGoodsForReceipt(ProductionPlan plan,
                                                                     ProductionSettlement settlement,
                                                                     BigDecimal reported,
                                                                     BigDecimal received,
                                                                     String unit) {
        if (received.compareTo(reported) != 0) {
            throw new BusinessException(409, "存货生产小结已按报产数量生成成品库存, 仓库实收差异不能直接覆盖")
                    .withCode("BY_STOCK_RECEIPT_VARIANCE_REQUIRES_RECONCILIATION")
                    .withHint("请先由生产侧撤销/修正小结后再确认；系统未改动现有成品库存")
                    .withHintTarget("仓库实收");
        }
        if (isBlank(settlement.getFinishedGoodsBatchId())) {
            throw invalidInterimFinishedGoods("桥接结单没有关联小结成品批次");
        }
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(settlement.getFinishedGoodsBatchId())
                .filter(candidate -> settlement.getFactoryId().equals(candidate.getFactoryId()))
                .orElseThrow(() -> invalidInterimFinishedGoods("桥接关联的成品批次不存在"));
        validateInterimFinishedGoods(plan, batch, reported);
        if (!Objects.equals(canonicalReceiptUnit(unit), canonicalReceiptUnit(batch.getUnit()))) {
            throw new BusinessException(409, "仓库实收单位与小结成品批次单位不一致")
                    .withCode("BY_STOCK_RECEIPT_UNIT_MISMATCH")
                    .withHint("小结批次单位: " + batch.getUnit() + ", 本次实收单位: " + unit)
                    .withHintTarget("仓库实收");
        }
        return batch;
    }

    private String canonicalReceiptUnit(String unit) {
        if (unit == null) {
            return null;
        }
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "盒", "box" -> "box";
            case "箱", "case" -> "case";
            case "片", "slice" -> "slice";
            case "公斤", "千克", "kg" -> "kg";
            case "克", "g" -> "g";
            default -> unit.trim().toLowerCase(Locale.ROOT);
        };
    }

    private BusinessException invalidInterimFinishedGoods(String detail) {
        return new BusinessException(409, "存货生产小结成品真值无法唯一核验: " + detail)
                .withCode("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID")
                .withHint("请联系管理员核对小结会话与成品批次；系统未改动任何库存")
                .withHintTarget("仓库实收");
    }

    private BusinessException missingProductionSettlement() {
        return new BusinessException(404, "该生产计划尚未结单, 不能确认入库")
                .withHint("请先由生产文员录入实际产量、实际领用和人效并提交结单")
                .withHintTarget("仓库实收");
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
        List<String> targetFinishedGoodIds = Optional.ofNullable(plan.getTargetFinishedGoodIds())
                .orElseGet(ArrayList::new).stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (targetFinishedGoodIds.size() > 1) {
            throw new BusinessException(409, "多成品计划必须按终端 SKU 逐行确认入库，不能把原料 owner 当成品入库")
                    .withCode("MULTI_OUTPUT_RECEIPT_REQUIRES_LINES")
                    .withHint("请按逐道报工的终端 SKU、批次和单位分别确认实收")
                    .withHintTarget("仓库实收");
        }
        String finishedProductTypeId = targetFinishedGoodIds.isEmpty()
                ? trimToNull(plan.getProductTypeId())
                : targetFinishedGoodIds.get(0);
        if (isBlank(finishedProductTypeId)) {
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

        ProductType productType = finishedProductTypeId != null
                ? productTypeRepository.findById(finishedProductTypeId).orElse(null)
                : null;

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(settlement.getFactoryId());
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId(finishedProductTypeId);
        batch.setProductName(productType != null ? productType.getName() : null);
        batch.setProducedQuantity(received);
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit(unit);
        batch.setUnitPrice(productType != null ? productType.getUnitPrice() : null);
        // 🔴 成本传导 (2026-07-04): 结单族成品单位成本 = 该计划权威生产成本 / 入库量 (诚实 null, 不伪造 ¥0)。
        //   与 SAFETY_STOCK 小结成本同基准 (原料+人工+调料+包装), 让期末 COGS 结转能纳入结单族销售。
        batch.setUnitCost(resolveReceiptUnitCost(settlement.getFactoryId(), plan.getId(), received));
        batch.setProductionDate(LocalDate.now());
        int shelfLifeDays = productType != null && productType.getShelfLifeDays() != null
                ? productType.getShelfLifeDays()
                : 180;
        batch.setExpireDate(LocalDate.now().plusDays(shelfLifeDays));
        batch.setStorageLocation("仓库确认入库");
        batch.setProductionPlanId(plan.getId());
        batch.setWarehouseId(warehouseResolver.resolveFinishedGoodsId(settlement.getFactoryId()));
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        batch.setCreatedBy(receivedBy != null ? receivedBy : 0L);
        batch.setRemark("生产结单仓库确认入库: " + settlement.getPlanNumber());
        return finishedGoodsBatchRepository.save(batch);
    }

    /**
     * 🔴 成本传导 — 结单族成品入库单位成本 (诚实 null, 禁止降级).
     *
     * <p>unitCost = 该生产计划的权威生产成本 (原料+人工+调料+包装, 由 {@link OrderCostBreakdownService}
     * 沿 MaterialConsumption 边回溯归集) / 入库量 (scale-4, HALF_UP)。与 SAFETY_STOCK 小结的成本传导
     * 同一基准, 也与 出厂核算/成本汇总页字节一致。
     *
     * <p><b>诚实 null (禁止伪造 ¥0)</b>: 以下任一 → 返 null (成品成本未知, 不当 0 摊入 COGS):
     * <ul>
     *   <li>{@code orderCostBreakdownService} 未注入 (单测反射场景);</li>
     *   <li>该计划无生产批次 (hasData=false) — 无成本可归集;</li>
     *   <li>归集总成本 null 或 ≤0 — 全部投入物料/人工均未定价 (upstream priceless);</li>
     *   <li>入库量 ≤0 — 无分母。</li>
     * </ul>
     * 常规场景 (物料已定价) → 返回真实传导成本。null 由 期末 COGS 结转 honest-null 排除 + 记 WARN 暴露缺口,
     * 不静默造 0。
     */
    private BigDecimal resolveReceiptUnitCost(String factoryId, String planId, BigDecimal received) {
        if (orderCostBreakdownService == null || planId == null
                || received == null || received.signum() <= 0) {
            return null;
        }
        try {
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO cb =
                    orderCostBreakdownService.computeByPlan(factoryId, planId, false);
            if (cb == null || !cb.isHasData()
                    || cb.getTotalCost() == null || cb.getTotalCost().signum() <= 0) {
                return null;   // 诚实: 无成本基准 (无批次 / 全未定价) → 未知, 不伪造 ¥0
            }
            return cb.getTotalCost().divide(received, 4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("结单族成品成本传导失败 (factory={}, plan={}): {} — unitCost 置 null (诚实, 不伪造)",
                    factoryId, planId, e.getMessage());
            return null;
        }
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
        String responsibility = trimToNull(request.getResponsibilitySide());
        if (responsibility == null || !Set.of("PRODUCTION", "WAREHOUSE", "WEIGHING_ERROR").contains(responsibility)) {
            throw new BusinessException(400, "责任归属无效: " + responsibility)
                    .withHint("请选择 PRODUCTION、WAREHOUSE 或 WEIGHING_ERROR")
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

    private boolean requiresProductionSettlement(String factoryId) {
        if (factorySettingsRepository != null) {
            Boolean skipProcessReportingDefault =
                    factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(factoryId);
            if (skipProcessReportingDefault != null) {
                return Boolean.TRUE.equals(skipProcessReportingDefault);
            }
        }
        return isLiushanmenFactory(factoryId);
    }

    private boolean isLiushanmenFactory(String factoryId) {
        return "F006".equalsIgnoreCase(factoryId);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isPendingResponsibilitySide(String value) {
        String normalized = trimToNull(value);
        return normalized == null || "PENDING".equals(normalized);
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

        // 已完成的计划不能直接取消 — 引导走批次级「整单撤回」(canonical 撤回, 会恢复库存 + 走审批).
        // 计划级 PRODUCTION_REVERSAL 已废弃 (死路, 不恢复库存); 客户需求的整单撤回是工单/批次级.
        if (plan.getStatus() == ProductionPlanStatus.COMPLETED) {
            throw new BusinessException(409, "已完成的生产计划不能直接取消")
                    .withHint("请在「生产批次详情」用「整单撤回」逐批次撤回 — 会自动恢复原料/半成品库存并走审批");
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

        // 找到本计划关联的活跃批次 (R1: 仅本计划的批次, 不波及同产品其它计划)。
        List<ProductionBatch> planBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId);

        // R2 (2026-06-14 v2): IN_PROGRESS 且已有报工 / 已消耗 WIP 的计划禁止直接取消。
        // 直接置 CANCELLED 会留下孤儿批次 + 未冲销的 WIP 消耗 (后续同产品领料被 G7 防呆拦)。
        //
        // ⚠️ 不再导向 PRODUCTION_REVERSAL 审批流: 该流的"审批通过回调" executeCancelApproved 在主代码
        //    中无调用方 (工作流引擎是纯状态机, 不回调业务层), 且即便触发也不恢复库存 —— 是死路。
        //    改导向「报工撤回」流 (ReportReversalService): 它自带审批 + 真正恢复 WIP/原料/FGB/任务。
        //    两步: ① 对每个有报工的批次提交报工撤回 (有数据 → 主管审批 → 自动 executeReversal 恢复库存);
        //          ② 报工软删后本计划 hasProductionData 返 false → 再调本接口直接取消空计划。
        // 防呆 4 位一体: message 具体 (说明已开工有报工 + 列出批次号) + 含 next action (报工撤回, 指明路径)
        //    + sticky (前端按 4xx 处理) + hintTarget (报工撤回 button)。
        // 修1 (🔴): 区分 SECONDARY 二次加工"只开工扣了 WIP 无报工" vs "已有报工"。
        //   - 只开工扣 WIP (无 YIELD 报工): 报工撤回流是 no-op (无报工可冲), 旧逻辑让用户去报工撤回 = 死路
        //     (撤回不还 startProduction 扣的那笔 WIP → 计划永卡 IN_PROGRESS, WIP 孤儿)。
        //     → 此处在同一事务内先反冲还回开工扣的 WIP, 再直接取消, 不导向报工撤回。
        //   - 已有报工: 仍走报工撤回流 (ReportReversalService 冲报工产生的 WIP)。
        boolean hasYields = hasYieldReports(factoryId, plan);
        boolean isSecondaryInProgress = plan.getStatus() == ProductionPlanStatus.IN_PROGRESS
                && "SECONDARY".equals(plan.getPlanSourceType())
                && plan.getSecondarySourceWipId() != null;

        if (isSecondaryInProgress && !hasYields) {
            // 反冲开工扣的半成品 WIP (还回 plannedQuantity), 然后直接取消 (不卡 IN_PROGRESS, 不导向报工撤回)。
            if (wipInventoryService == null) {
                throw new BusinessException(500, "二次加工服务未初始化, 无法反冲半成品库存");
            }
            BigDecimal reverseQty = plan.getPlannedQuantity() != null
                    ? plan.getPlannedQuantity() : java.math.BigDecimal.ZERO;
            if (reverseQty.compareTo(java.math.BigDecimal.ZERO) > 0) {
                wipInventoryService.reverseSecondaryDeduct(
                        plan.getSecondarySourceWipId(), reverseQty, factoryId,
                        null /* operatorId — cancelProductionPlan 不接收 userId */);
                log.info("修1 SECONDARY 取消反冲 WIP: planId={}, wipId={}, qty={}",
                        planId, plan.getSecondarySourceWipId(), reverseQty);
            }
            // 落入下方直接取消逻辑 (置 CANCELLED + 级联空批次/任务)。
        } else if (plan.getStatus() == ProductionPlanStatus.IN_PROGRESS
                && (hasYields || isSecondaryInProgress)) {
            // 有报工 (任意计划) 或 SECONDARY 已有报工 → 导向报工撤回流, 拒绝直接取消。
            String batchHint = buildReversalBatchHint(planBatches);
            throw new BusinessException(409, "该计划已开工并有报工/WIP 消耗, 不能直接取消"
                    + (batchHint.isEmpty() ? "" : " (涉及批次: " + batchHint + ")"))
                    .withCode("PLAN_HAS_PRODUCTION_DATA")
                    .withHint("请先在「报工撤回」中对相关批次提交整单撤回 (经主管审批后自动冲销报工与 WIP 库存恢复原料), "
                            + "撤回完成后即可取消此空计划")
                    .withHintTarget("报工撤回");
        }

        // R2: IN_PROGRESS 但无报工无 WIP (空批次) — 安全直接取消, 顺手把空批次置 CANCELLED。
        // PENDING — 通常无批次, 即便有 (异常态) 也按 R1 仅级联本批次任务。

        // 更新计划状态
        plan.setStatus(ProductionPlanStatus.CANCELLED);
        plan.setNotes(plan.getNotes() != null ?
            plan.getNotes() + "\n取消原因：" + reason :
            "取消原因：" + reason);
        productionPlanRepository.save(plan);

        // R1 (2026-06-14): 按批次定向级联关闭 WorkProcessTask (新表), 取代旧的"按产品类型全关 ProcessTask"。
        // 旧逻辑 findByFactoryIdAndProductTypeId 会把同产品另一并行活跃批次的任务一并 CLOSED (六扇门 6.1+6.2 场景误伤)。
        cancelPlanBatchesAndTasks(factoryId, planId, planBatches);

        log.info("取消生产计划: planId={}, reason={}, batches={}", planId, reason,
                planBatches != null ? planBatches.size() : 0);
    }

    /**
     * R2 (2026-06-14): 判断一个计划是否已产生生产数据 (报工 / 已消耗 WIP), 用于禁止 IN_PROGRESS 直接取消。
     *
     * <p>判定信号 (任一为真即"有数据"):
     * <ul>
     *   <li>本计划任一非取消批次已有 YIELD 报工 (production_reports.report_type=YIELD)</li>
     *   <li>SECONDARY 二次加工计划已 IN_PROGRESS (开工时已扣减 secondarySourceWipId 的 WIP 半成品)</li>
     * </ul>
     */
    private boolean hasProductionData(String factoryId, ProductionPlan plan) {
        // SECONDARY 计划开工即扣 WIP — 已 IN_PROGRESS 视为有数据 (供 requestCancelWithApproval 判定撤回资格)。
        // 注: cancelProductionPlan 不再用本方法做"导向报工撤回 vs 反冲"的二分, 改用 hasYieldReports 细分 (修1)。
        if ("SECONDARY".equals(plan.getPlanSourceType())
                && plan.getSecondarySourceWipId() != null
                && plan.getStatus() == ProductionPlanStatus.IN_PROGRESS) {
            return true;
        }
        return hasYieldReports(factoryId, plan);
    }

    /**
     * 修1 (🔴): 纯报工检测 — 本计划任一非取消批次是否已有 YIELD 报工。
     *
     * <p>不含 {@link #hasProductionData} 的 "SECONDARY 开工即扣 WIP 视为有数据" 短路, 用于 cancel 区分
     * "SECONDARY 只开工扣 WIP 无报工" (→ 反冲 WIP 直接取消) vs "已有报工" (→ 导向报工撤回流)。
     */
    private boolean hasYieldReports(String factoryId, ProductionPlan plan) {
        if (productionReportRepository != null) {
            List<ProductionBatch> batches = productionBatchRepository
                    .findByFactoryIdAndProductionPlanId(factoryId, plan.getId());
            if (batches != null) {
                for (ProductionBatch b : batches) {
                    if (b.getId() == null || b.getStatus() == ProductionBatchStatus.CANCELLED) {
                        continue;
                    }
                    List<ProductionReport> yields = productionReportRepository
                            .findYieldReportsByBatch(factoryId, b.getId());
                    if (yields != null && !yields.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return hasMaterializedProcessSheetRows(factoryId, plan.getId());
    }

    private boolean hasMaterializedProcessSheetRows(String factoryId, String planId) {
        if (processSheetRowRepository == null || isBlank(planId)) {
            return false;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().anyMatch(row -> isUsableProcessSheetRow(row)
                && (row.getBatchId() != null || !isBlank(row.getBatchNumber())));
    }

    /**
     * R2 (2026-06-14 v2): 拼出本计划"需先撤报工"的批次号提示 (防呆 Rule 2 — context 必带身份信息)。
     * 仅列出非取消批次的 batchNumber (无则退回 id), 用于 cancel 拒绝消息引导用户去报工撤回。
     */
    private String buildReversalBatchHint(List<ProductionBatch> planBatches) {
        if (planBatches == null || planBatches.isEmpty()) {
            return "";
        }
        java.util.List<String> refs = new java.util.ArrayList<>();
        for (ProductionBatch b : planBatches) {
            if (b == null || b.getId() == null || b.getStatus() == ProductionBatchStatus.CANCELLED) {
                continue;
            }
            String ref = b.getBatchNumber() != null && !b.getBatchNumber().isBlank()
                    ? b.getBatchNumber()
                    : String.valueOf(b.getId());
            refs.add(ref);
        }
        return String.join(", ", refs);
    }

    /**
     * R1 (2026-06-14): 按批次定向级联取消 — 把本计划每个未取消批次置 CANCELLED, 并 CANCELLED 其活跃 WorkProcessTask。
     *
     * <p>严格按 batchId 查任务 ({@code findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc}),
     * 不碰其它批次的任务 (即便同一 productTypeId)。终态任务 (COMPLETED/SKIPPED/CANCELLED) 不变。
     */
    private void cancelPlanBatchesAndTasks(String factoryId, String planId, List<ProductionBatch> planBatches) {
        if (planBatches == null || planBatches.isEmpty()) {
            return;
        }
        for (ProductionBatch batch : planBatches) {
            if (batch.getId() == null) {
                continue;
            }
            // 空批次 / 活跃批次 → CANCELLED (已 CANCELLED/COMPLETED 不动)
            if (batch.getStatus() != ProductionBatchStatus.CANCELLED
                    && batch.getStatus() != ProductionBatchStatus.COMPLETED) {
                batch.setStatus(ProductionBatchStatus.CANCELLED);
                productionBatchRepository.save(batch);
            }
            if (workProcessTaskRepository == null) {
                continue; // 单测无注入 → 跳过 (有注入的测试覆盖级联)
            }
            List<com.cretas.aims.entity.workprocess.WorkProcessTask> tasks = workProcessTaskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batch.getId());
            int closedCount = 0;
            for (com.cretas.aims.entity.workprocess.WorkProcessTask task : tasks) {
                if (!task.getStatus().isTerminal()) {
                    task.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.CANCELLED);
                    closedCount++;
                }
            }
            if (closedCount > 0) {
                workProcessTaskRepository.saveAll(tasks);
                log.info("R1 级联取消 {} 个工序任务: planId={}, batchId={}", closedCount, planId, batch.getId());
            }
        }
    }

    /**
     * @deprecated 计划级 PRODUCTION_REVERSAL 审批流已废弃。结构性死路: workflow 引擎是纯状态机, 从不
     * 回调 {@link #executeCancelApproved}/{@code executeReversal} (二者在主代码零调用方), 即便回调也只
     * 改状态、不恢复库存。且无前端调用方 (web-admin 批次详情用的是批次级「整单撤回」)。
     * <p>canonical 撤回 = 批次级整单撤回 {@link com.cretas.aims.service.reversal.ReportReversalService}
     * (符合客户 6.09 需求「工单整单撤回 + 审批 + 无数据直撤 + 角色」, 含 G1 下游/G2 出货/G3 幂等守卫 +
     * 真正恢复原料/WIP/FGB)。IN_PROGRESS+数据的计划取消已由 {@link #cancelProductionPlan} 导向报工撤回流。
     * <p>本方法保留签名但现抛 409 引导改走批次整单撤回 (防呆 Rule 5: dead-end → 导航)。
     */
    @Override
    @Deprecated
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String requestCancelWithApproval(String factoryId, String planId, String reason, Long userId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂");
        }
        throw new BusinessException(409, "计划级撤回审批流已停用")
                .withCode("USE_BATCH_REVERSAL")
                .withHint("请在「生产批次详情」用「整单撤回」逐批次撤回 — 会经主管审批并自动恢复原料/半成品库存 (无报工数据则直接撤回)")
                .withHintTarget("报工撤回");
    }

    /**
     * SP12 T3 / R2 (2026-06-14 v2): 审批通过后执行撤回（仅供 PRODUCTION_REVERSAL workflow 回调调用）.
     * PENDING_APPROVAL → CANCELLED，先冲销报工 + 恢复 WIP/原料/FGB 库存，再级联关闭关联工序任务。
     *
     * <p><b>⚠️ 接通现状 (R2 v2 查清)</b>: 本方法在主代码中<b>无任何调用方</b> —— 工作流引擎
     * ({@code WorkflowEngineServiceImpl.terminateAtEnd}) 是纯状态机, PRODUCTION_REVERSAL 审批到达
     * APPROVED 终态后只设置 instance.status, <b>从不回调业务层</b> (无 moduleCode→handler 派发器)。
     * 因此<b>用户面的"有报工计划取消"已改为导向报工撤回流</b> ({@link ReportReversalService},
     * 见 {@link #cancelProductionPlan} R2)。
     *
     * <p>本方法保留并<b>加固</b>为: 若未来有人把 PRODUCTION_REVERSAL 审批回调接到此方法,
     * 它会<b>真正恢复库存</b> (调 {@code reportReversalService.submitReversal} 对每个有报工的批次执行
     * 整单撤回, 自包含 G1/G2/G3 守卫 + 软删报工 + 写 REVERSE SIT + 回放均价 + 复位任务 + 标 FGB REVERSED),
     * 而非像旧版只置 CANCELLED 留下未冲销 WIP。
     */
    @Override
    @Deprecated
    @org.springframework.transaction.annotation.Transactional
    public void executeCancelApproved(String planId) {
        // @Deprecated: 计划级 PRODUCTION_REVERSAL 已废弃 (见 requestCancelWithApproval)。本回调零调用方。
        // body 保留 #834 加固版 (调 ReportReversalService 真恢复库存) 作为安全网 —— 万一有遗留
        // PENDING_APPROVAL 计划被接上, 它会正确冲销而非旧版只置 CANCELLED。新撤回一律走批次级整单撤回。
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (plan.getStatus() != ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "生产计划不处于待审批状态，无法执行撤回")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        List<ProductionBatch> planBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(plan.getFactoryId(), planId);

        // R2 (2026-06-14 v2): 审批已通过 → 对每个有报工的批次真正冲销库存 (恢复 WIP/原料/FGB)。
        // 用报工撤回服务 (自包含恢复逻辑), 审批已在 PRODUCTION_REVERSAL 流完成, 故直接 submit→execute。
        // reportReversalService 未注入 (单测/旧 context) → 跳过, 仅做状态收尾 (旧行为, 由有注入路径覆盖)。
        reverseBatchReportsForApprovedCancel(plan.getFactoryId(), planId, planBatches);

        plan.setStatus(ProductionPlanStatus.CANCELLED);
        productionPlanRepository.save(plan);

        // R1 (2026-06-14): 审批通过后也按批次定向级联 (取代旧的"按产品类型全关 ProcessTask")。
        // 与 cancelProductionPlan 一致, 仅关本计划批次的活跃 WorkProcessTask, 不波及同产品其它计划。
        cancelPlanBatchesAndTasks(plan.getFactoryId(), planId, planBatches);

        log.info("SP12 T3 生产撤回审批通过，计划已取消: planId={}, batches={}", planId,
                planBatches != null ? planBatches.size() : 0);
    }

    /**
     * R2 (2026-06-14 v2): PRODUCTION_REVERSAL 审批通过后, 对本计划每个有报工的批次执行整单撤回,
     * 恢复 WIP/原料/FGB 库存 + 复位工序任务 + 反冲成本。复用 {@link ReportReversalService} 自包含逻辑。
     *
     * <p>审批已在 PRODUCTION_REVERSAL 流完成 → 直接 submit (有报工时通常返 PENDING) 再显式
     * executeReversal 落地恢复。守卫 (下游消费 G1 / 成品出货 G2) 失败会抛出 → 整体事务回滚,
     * 计划不会被错误置 CANCELLED (诚实失败, 禁止静默)。
     *
     * <p>reportReversalService / productionReportRepository 未注入时跳过 (单测/旧 context)。
     */
    private void reverseBatchReportsForApprovedCancel(String factoryId, String planId,
            List<ProductionBatch> planBatches) {
        if (reportReversalService == null || planBatches == null || planBatches.isEmpty()) {
            return;
        }
        for (ProductionBatch batch : planBatches) {
            if (batch == null || batch.getId() == null
                    || batch.getStatus() == ProductionBatchStatus.CANCELLED) {
                continue;
            }
            // 仅对有 YIELD 报工的批次撤回 (无报工批次 submitReversal 是 no-op DONE, 跳过省调用)。
            if (productionReportRepository != null) {
                List<ProductionReport> yields = productionReportRepository
                        .findYieldReportsByBatch(factoryId, batch.getId());
                if (yields == null || yields.isEmpty()) {
                    continue;
                }
            }
            // submitReversal: 有报工 → 创建 PENDING (或快速撤回 APPROVED 自动 execute); 再显式 execute 兜底恢复。
            var reversalLog = reportReversalService.submitReversal(
                    factoryId, batch.getId(), null,
                    "PRODUCTION_REVERSAL 审批通过自动撤回 (planId=" + planId + ")");
            if (reversalLog != null && reversalLog.getId() != null
                    && reversalLog.getStatus() != com.cretas.aims.entity.ReportReversalLog.ReversalStatus.DONE) {
                reportReversalService.executeReversal(reversalLog.getId(), factoryId);
            }
            log.info("R2 审批撤回: planId={}, batchId={}, reversalLogId={}, status={}", planId,
                    batch.getId(), reversalLog != null ? reversalLog.getId() : null,
                    reversalLog != null ? reversalLog.getStatus() : null);
        }
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
    public void recordMaterialConsumption(String factoryId, String planId, String batchId, BigDecimal quantity, Long operatorId) {
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
        // C-B1 同族 fix: MaterialConsumption.unitPrice/totalCost/recordedBy 均 NOT NULL。
        //   - 批次单价可能为 null (未录价批次) → 兜底 ZERO, 否则 setUnitPrice(null)/multiply(null) → 500
        //   - recordedBy 走 FK→users: 优先当前操作人 (operatorId), 兜底计划创建人, 均无则不可记录
        BigDecimal unitPrice = batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO;
        Long recordedBy = operatorId != null ? operatorId : plan.getCreatedBy();
        if (recordedBy == null) {
            throw new BusinessException(401, "无法识别操作人，无法记录消耗")
                    .withHint("请重新登录后重试");
        }
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(factoryId);
        consumption.setProductionPlanId(planId);
        consumption.setBatchId(batchId);
        consumption.setQuantity(quantity);
        consumption.setUnitPrice(unitPrice);
        consumption.setTotalCost(quantity.multiply(unitPrice));
        consumption.setConsumptionTime(LocalDateTime.now());
        consumption.setRecordedBy(recordedBy);
        materialConsumptionRepository.save(consumption);

        // 更新批次库存
        // 注意: currentQuantity 是计算属性，通过增加 usedQuantity 来减少 currentQuantity
        batch.setUsedQuantity(batch.getUsedQuantity().add(quantity));
        batch.setLastUsedAt(LocalDateTime.now());
        // 🔒 全局兜底: 增加 usedQuantity 后断言 used+reserved≤receipt, 防超扣 (误伤-proof 后置校验)
        batch.assertConsumptionInvariant();
        if (batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }
        materialBatchRepository.save(batch);
        inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "OUT");

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
        // R6 (2026-06-14): 悲观写锁取计划, 防 RN 双击/重试并发双建批次。
        // 第二个请求拿锁后看到计划已 IN_PROGRESS → 状态校验抛 409, 幂等拒绝。
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));

        if (!plan.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该生产计划")
                    .withHint("当前生产计划不属于该工厂, 无法操作");
        }
        if (plan.getStatus() != ProductionPlanStatus.PENDING) {
            throw new BusinessException(409, "只有待处理的计划可以转为批次")
                    .withHint("请刷新生产计划列表查看最新状态");
        }

        // Plans pin an exact workflow/version. Activation may move after plan creation;
        // conversion validates the pin itself and never silently resolves a newer graph.
        if (workflowResolutionService != null
                && plan.getWorkflowSelectionMode() == ProductionBatch.WorkflowSelectionMode.WORKFLOW) {
            List<String> selectedOutputs = plan.getTargetFinishedGoodIds() == null
                    || plan.getTargetFinishedGoodIds().isEmpty()
                    ? List.of(plan.getProductTypeId())
                    : plan.getTargetFinishedGoodIds();
            workflowResolutionService.assertPinnedWorkflowCoversOutputs(
                    factoryId, plan.getSelectedWorkflowId(), plan.getSelectedWorkflowVersion(), selectedOutputs);
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
                .unit(requireProductionUnit(plan.getPlannedUnit(), "生产计划 " + plan.getPlanNumber()))
                .workflowSelectionMode(plan.getWorkflowSelectionMode())
                .selectedWorkflowId(plan.getSelectedWorkflowId())
                .selectedWorkflowVersion(plan.getSelectedWorkflowVersion())
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
        // Workflow selection is pinned at batch insert. Spawn must remain in this transaction and
        // fail closed so a partially created batch can never retry under a newer activation.
        if (workProcessTaskService == null) {
            throw new BusinessException(500, "工序任务服务未初始化，批次创建已回滚")
                    .withCode("WORK_PROCESS_TASK_SERVICE_UNAVAILABLE")
                    .withHint("请检查后端服务配置后重试创建批次");
        }
        Long responsibleId = plan.getAssignedSupervisorId();
        List<WorkProcessTaskDTO> spawnedTasks =
                workProcessTaskService.spawnTasks(factoryId, saved.getId(), saved.getProductTypeId(),
                        plan.getSkipProcessReporting(), responsibleId, responsibleId);
        log.info("转批次已 spawn 报工任务: batchId={}, productTypeId={}, skipProcessReporting={}, taskCount={}",
                saved.getId(), saved.getProductTypeId(), plan.getSkipProcessReporting(), spawnedTasks.size());

        // 更新计划状态为 IN_PROGRESS
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setStartTime(LocalDateTime.now());
        productionPlanRepository.save(plan);

        log.info("从计划创建批次: planId={}, batchId={}, batchNumber={}", planId, saved.getId(), saved.getBatchNumber());
        return saved;
    }

    private static String requireProductionUnit(String unit, String context) {
        if (unit == null || unit.isBlank()) {
            throw new BusinessException(422, context + " 缺少单位，不能生成生产任务")
                    .withCode("PRODUCTION_UNIT_NOT_CONFIGURED")
                    .withHint("请修复产品、Workflow 或工序的单位配置后重试");
        }
        return unit.trim();
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
        applyPlanUnitAuthority(plan, resolvePlanUnitAuthority(factoryId, productTypeId, null));
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

    /**
     * G3b 停产 — BY_STOCK 库存永续计划纯状态关闭。
     *
     * <p><b>⚠️ 关键约束</b>: 此方法仅翻转状态 (→ COMPLETED) 并保存, 绝不:
     * <ul>
     *   <li>调用 completeProduction 或 settleProduction</li>
     *   <li>发布 BatchCompletedEvent 或 ProductionCompletedEvent</li>
     *   <li>触发任何物料扣减或成品创建</li>
     * </ul>
     * 小结 (interimSettle) 已逐批扣减原料 + 分批入库; 停产若再触发扣减会造成双重扣减。
     */
    @Override
    @Transactional
    public void stopProduction(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("生产计划", "id", planId));
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "仅存货生产计划可停产");
        }
        // 🔴 (2026-07-06) plan-status 守卫: 缺此守卫时对已 COMPLETED/CANCELLED 等终态计划重复调用本接口,
        //   状态置 COMPLETED 是 no-op, 但下方无条件 plan.setEndTime(now()) 仍会执行 → 覆盖原完工时间戳
        //   (审计腐蚀, 喂生产时长/人效报表); 双击或 API replay 即可复现。允许集 PENDING/IN_PROGRESS 镜像
        //   前端 web-admin/src/views/production/plans/list.vue isUnfinishedStatus() 白名单 (停产按钮
        //   仅在这两个状态下渲染) —— PENDING 允许是因为 interimSettle 从不要求 IN_PROGRESS, 存货生产计划
        //   可能全程停留 PENDING (从未 startProduction) 就直接小结+停产关闭。
        if (plan.getStatus() != ProductionPlanStatus.PENDING
                && plan.getStatus() != ProductionPlanStatus.IN_PROGRESS) {
            throw new BusinessException(409, "只能停产待处理或进行中的生产计划")
                    .withHint("当前状态: " + plan.getStatus().getDisplayName() + ", 请刷新生产计划列表查看最新状态");
        }
        // 🔒🔒 (2026-07-04) 幻库存守卫: 停产是纯状态翻转 (→ COMPLETED, 零扣减)。延迟扣减设计下, 报工写的
        //   MaterialConsumption 行恒 interimSettledAt IS NULL, 仅在「小结」时才逐笔扣减 usedQuantity。若计划
        //   尚有未结报工消耗却直接停产 → 状态变 COMPLETED → 小结守卫 (interimSettle 拒绝终态) 从此拦截 →
        //   这些原料永远不被扣减 = 幻库存 (物料实际消耗但库存从不减)。故停产前必须拦截: 有未结报工消耗时
        //   loud-block, 导向"先小结再停产"。检测口径与 interimSettle 完全一致 (按 process_sheet_rows.batch_id
        //   ∈ 本计划各道 → material_consumptions.production_batch_id, interim_settled_at IS NULL), 因逐工序
        //   在制道消耗 production_plan_id 故意为 null, 只能靠 batchId 定位。
        List<MaterialConsumption> unsettled = findUnsettledPlanConsumptions(factoryId, planId);
        // 🔴🔒🔒 (2026-07-05) 第二检测面 — SFI/FG 中段起步料流盲区: 上方消耗检测只覆盖写了
        //   MaterialConsumption 且 process_sheet_rows.batch_id 非空的行。但「中段起步」计划纯由外部
        //   常驻半成品(SFI)/成品(FG)投料喂 (row.upstreamSources semiFinished/finishedGoods), 这些投料边
        //   <b>不写</b> MaterialConsumption; 且纯 SFI 中间道 (SAVED_SFI) batchId 为 null 被上方 filter 排除 →
        //   全程零消耗 → 消耗守卫放行 → 停产 COMPLETED → 小结被终态守卫拦死 → 已投 SFI/FG 永不扣减 = 幻库存
        //   (可重复发货/领用)。故并联检测「本计划有未结产出行 或 未结 SFI/FG 投料行」(见 helper), 任一命中即拦。
        boolean unsettledStockFeed = hasUnsettledStockFeedOrOutputRows(factoryId, planId);
        if (!unsettled.isEmpty() || unsettledStockFeed) {
            String detail = !unsettled.isEmpty()
                    ? ("该计划仍有 " + unsettled.size() + " 笔未结报工消耗")
                    : "该计划仍有未小结的半成品/成品投料或产出";
            throw new BusinessException(409, detail + ", 请先小结再停产")
                    .withCode("STOP_BLOCKED_UNSETTLED_CONSUMPTION")
                    .withHint("停产不扣料; 若此时停产, 已报工消耗的原料 / 已投入的半成品(SFI)/成品(FG) 将永不扣减 "
                            + "(幻库存)。请先「小结」结算这些消耗与投料")
                    .withSeverity("BLOCKING")
                    .withHintTarget("小结");
        }
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        plan.setEndTime(LocalDateTime.now());
        if (plan.getStartTime() == null) {
            plan.setStartTime(LocalDateTime.now());
        }
        productionPlanRepository.save(plan);
        if (productionInterimSettlementRepository == null) {
            throw new BusinessException(500, "存货生产小结服务未初始化, 无法建立仓库确认桥接")
                    .withCode("BY_STOCK_SETTLEMENT_BRIDGE_UNAVAILABLE")
                    .withHintTarget("停产");
        }
        List<ProductionInterimSettlement> completedSessions = productionInterimSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(factoryId, planId);
        if (!isEmpty(completedSessions)) {
            // 小结已经完成全部库存动作；停产只额外创建仓库确认所需的元数据，绝不重放小结。
            bridgeProductionSettlementForWarehouse(factoryId, planId);
        }
        log.info("停产 (存货生产纯状态关闭, 无扣料无事件): factoryId={}, planId={}", factoryId, planId);
        // NO completeProduction, NO settleProduction, NO BatchCompletedEvent, NO consumption posting.
    }

    /**
     * 🔒🔒 (2026-07-04) 查本计划待结算 (interimSettledAt IS NULL) 的报工消耗。
     *
     * <p>检测口径与 {@code InterimSettleServiceImpl.interimSettle} 的扣减侧完全一致: 按
     * {@code process_sheet_rows.batch_id} (物化后的 per-道 ProductionBatch.id) ∈ 本计划各道, 反查
     * {@code material_consumptions.production_batch_id + interim_settled_at IS NULL}。不能按
     * production_plan_id 查 —— 逐工序首/中间道写的 raw 消耗其 production_plan_id 故意为 null (防成本双计),
     * 只有 production_batch_id 恒有值。
     *
     * <p>{@code processSheetRowRepository} 为 {@code @Autowired(required=false)}, 生产环境恒存在;
     * 若缺失 (某些精简测试上下文) 则返回空集 (与既有 helper line ~1785/~3184 的 null-guard 同策略)。
     */
    private List<MaterialConsumption> findUnsettledPlanConsumptions(String factoryId, String planId) {
        if (processSheetRowRepository == null || isBlank(planId)) {
            return List.of();
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        List<Long> planBatchIds = rows.stream()
                .map(ProcessSheetRow::getBatchId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (planBatchIds.isEmpty()) {
            return List.of();
        }
        return materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(factoryId, planBatchIds);
    }

    /**
     * 🔴🔒🔒 (2026-07-05) 停产守卫第二检测面 —— 本计划是否存在<b>未结</b> (interim_settled_at IS NULL) 且
     * 「有产出锚 或 含 SFI/FG 投料」的逐工序行。
     *
     * <p>补 {@link #findUnsettledPlanConsumptions} 的两处盲区: (a) 纯 SFI 中间道 (SAVED_SFI) {@code batchId}
     * 为 null 被消耗守卫的 {@code batchId != null} filter 排除; (b) SFI/FG 投料边根本不写 MaterialConsumption。
     * 「中段起步」计划全程零消耗但有真实待扣 SFI/FG 投料, 只有靠 process_sheet_rows 才检测得到。
     *
     * <p>判据: 行 {@code interim_settled_at IS NULL} 且 (i) {@code batchNumber != null} (有产出/SFI 锚 —— 含
     * SAVED_SFI, 其 batchId null 但 batchNumber 非空) 或 (ii) upstreamSources 含 {@code semiFinished/finishedGoods}
     * 且 feed>0 (边缘: 无产出锚但确有外部投料)。命中即代表小结会处理该行 (扣 SFI/FG / 入库产出), 停产会把它
     * 遗留成幻库存。processSheetRowRepository @Autowired(required=false), 缺失则返回 false (与既有 null-guard 同策略)。
     */
    private boolean hasUnsettledStockFeedOrOutputRows(String factoryId, String planId) {
        if (processSheetRowRepository == null || isBlank(planId)) {
            return false;
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        List<ProductionSettlementPrefillResponse.Issue> ignore = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (row == null || row.getInterimSettledAt() != null) {
                continue;   // 已结: 小结/结单已处理该行, 停产不会遗留
            }
            // (i) 有产出/SFI 锚 (含 SAVED_SFI: batchId null 但 batchNumber 非空 → 消耗守卫盲区)
            if (trimToNull(row.getBatchNumber()) != null) {
                return true;
            }
            // (ii) 无产出锚但含外部 SFI/FG 投料 (边缘)
            ProcessSheetRowRequest req = parseProcessSheetRowPayload(row, ignore);
            if (req == null || isEmpty(req.getUpstreamSources())) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                if ((ref.isSemiFinished() || ref.isFinishedGoods())
                        && zeroIfNull(ref.getFeedQuantityKg()).compareTo(BigDecimal.ZERO) > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
