package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.uom.MaterialUomConverter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * BOM展开服务
 *
 * <p>负责根据生产计划中的产品类型和生产数量，展开所需原辅料清单（BOM展开），
 * 并校验当前库存是否满足需求，同时按FEFO策略输出批次分配方案。</p>
 *
 * <p><b>D4 Path B (2026-05-10 customer meeting, PR #309 A2=B):</b>
 * 优先读 {@link BomItem} (新 BOM 多原料配方表), 当 BomItem 不存在时
 * fallback 到 {@link MaterialProductConversion} (RPF, 旧出成率表) 保持向后兼容。
 * BomItem 路径会传递 {@code sourceUnit} (e.g. "g") 给 MaterialRequirement,
 * 激活 ProductionWorkflowOrchestrator 的 D3 g↔kg 1:1000 单位换算。
 * 详见 docs/architecture/2026-05-10-rpf-vs-bomitem-divergence.md §7</p>
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Service
@RequiredArgsConstructor
public class BomExpansionService {

    private static final Logger log = LoggerFactory.getLogger(BomExpansionService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ConversionRepository conversionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final BomService bomService;
    private final WarehouseResolver warehouseResolver;

    /**
     * T143: 物料单位换算器 (箱↔kg). 字段注入 (required=false) 不破坏 @RequiredArgsConstructor
     * 与既有单测 (@InjectMocks). 未注入时退回原同单位比较 (RPF 路径单位一致, 向后兼容).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialUomConverter materialUomConverter;

    /** T143: 读库存单位 (RawMaterialType.unit). 字段注入 required=false. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * 断点3 修复 (2026-06-11, Fable E2E, 同 #751): 自动级联展开优先读 bom_recipe_items (新表),
     * 与手动"开始采购"按钮 ({@code PurchaseServiceImpl.generatePurchaseSuggestion}) +
     * 财务成本拆分 ({@code BomRecipeService.getCurrentRecipe}) 同一 BOM 源,
     * 消除"自动算净需求 ≠ 按钮算净需求"的口径不一致.
     * 仅当产品无 ACTIVE+is_current recipe 时回退到 legacy BomItem / RPF (向后兼容旧品).
     *
     * <p>field-inject (required=false): 不破坏 @InjectMocks 既有单测 (未提供时为 null → 跳过 recipe 分支).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomRecipeRepository bomRecipeRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomRecipeItemRepository bomRecipeItemRepository;

    /**
     * BOM展开：根据产品类型和生产数量，计算所有需要的原辅料（含损耗）。
     *
     * <p>查询优先级：</p>
     * <ol>
     *   <li><b>BomItem (新 BOM 配方表)</b>：客户在「BOM 成本管理」页录入的多原料配方。
     *       使用 {@code standardQuantity / (yieldRate/100) * productionQuantity}
     *       计算每种原料的实际用量，并把 {@code unit} 传到 MaterialRequirement.sourceUnit
     *       供后续 D3 g↔kg 单位换算使用。</li>
     *   <li><b>MaterialProductConversion (RPF, fallback)</b>：旧出成率表，
     *       当产品无 BomItem 配置时沿用，保持向后兼容（F001 等老工厂数据）。
     *       RPF 路径不设置 sourceUnit (null)，调拨流程沿用原 1:1 透传逻辑。</li>
     * </ol>
     *
     * @param factoryId         工厂ID
     * @param productTypeId     产品类型ID
     * @param productionQuantity 计划生产数量
     * @return 原辅料需求清单（含损耗率换算后的总需求量）
     */
    @Transactional(readOnly = true)
    public List<MaterialRequirement> expandBOM(String factoryId,
                                               String productTypeId,
                                               BigDecimal productionQuantity) {
        // 断点3 修复 (2026-06-11, 同 #751): 优先读 bom_recipe_items (新表, 同财务/手动采购源).
        // recipe 存在即以它为准, 不再回退 — 避免自动算料口径与手动采购/财务成本拆分不一致.
        List<BomRecipeItem> recipeItems = loadCurrentRecipeItems(factoryId, productTypeId);
        if (recipeItems != null && !recipeItems.isEmpty()) {
            log.info("[BOM-EXPANSION] using bom_recipe_items for factoryId={}, productTypeId={}, items={}",
                    factoryId, productTypeId, recipeItems.size());
            return expandFromRecipeItems(recipeItems, productionQuantity);
        }

        // Fallback 1: D4 Path B (2026-05-10) legacy BomItem (旧 BOM 多原料配方表)
        List<BomItem> bomItems = bomService.getBomItemsByProduct(factoryId, productTypeId);

        if (bomItems != null && !bomItems.isEmpty()) {
            log.info("[BOM-EXPANSION] using BomItem for factoryId={}, productTypeId={}, items={}",
                    factoryId, productTypeId, bomItems.size());
            return expandFromBomItems(bomItems, productionQuantity);
        }

        // Fallback 2: 旧出成率表 (MaterialProductConversion)
        log.info("[BOM-EXPANSION] using RPF (MaterialProductConversion) fallback for factoryId={}, productTypeId={}",
                factoryId, productTypeId);
        return expandFromConversions(factoryId, productTypeId, productionQuantity);
    }

    /**
     * 断点3 修复 (同 #751): 取产品当前 ACTIVE + is_current=TRUE recipe 的配方项.
     *
     * <p>与 {@code BomRecipeService.getCurrentRecipe} +
     * {@code PurchaseServiceImpl.loadCurrentRecipeItems} 走同一查询
     * ({@code findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(ACTIVE)}),
     * 保证自动级联净需求 = 手动采购建议净需求 = 财务成本拆分用料, 三者口径一致.
     *
     * <p>repo 未注入 (老 context / @InjectMocks 单测) 或无 recipe → 返 null (回退 legacy).
     */
    private List<BomRecipeItem> loadCurrentRecipeItems(String factoryId, String productTypeId) {
        if (bomRecipeRepository == null || bomRecipeItemRepository == null) {
            return null;
        }
        java.util.Optional<BomRecipe> recipeOpt =
                bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, BomRecipe.Status.ACTIVE);
        if (recipeOpt.isEmpty()) {
            return null;
        }
        // 显式按 recipeId 查 items (LAZY 集合在事务外可能 detach; 直查 repo 稳妥).
        return bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(recipeOpt.get().getId());
    }

    /**
     * 断点3 修复: 从 BomRecipeItem (新配方表) 构建 MaterialRequirement 清单.
     *
     * <p>用 {@code actualQuantity} (已按出成率折算的每单位产品原料量; null 时
     * {@code calculateActualQuantity()} 实时折算, 同 entity 公式) × productionQuantity 算需求.
     * 与 PurchaseServiceImpl recipe 路径完全同源, 不双源重复计料.
     *
     * <p>{@code sourceUnit = recipeItem.getUnit()} 激活 D3 g↔kg 单位换算 (同 BomItem 路径).
     */
    private List<MaterialRequirement> expandFromRecipeItems(List<BomRecipeItem> recipeItems,
                                                            BigDecimal productionQuantity) {
        List<MaterialRequirement> requirements = new ArrayList<>();
        for (BomRecipeItem ri : recipeItems) {
            if (ri.getMaterialTypeId() == null) {
                continue;
            }
            BigDecimal actualPerUnit = ri.getActualQuantity();
            if (actualPerUnit == null) {
                actualPerUnit = ri.calculateActualQuantity();
            }
            BigDecimal required = (actualPerUnit != null && productionQuantity != null)
                    ? actualPerUnit.multiply(productionQuantity).setScale(6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            MaterialRequirement req = new MaterialRequirement();
            req.setMaterialTypeId(ri.getMaterialTypeId());
            // 优先 live RawMaterialType.name, recipe 快照名作 fallback (同 BomItem 路径)
            req.setMaterialTypeName(resolveLiveMaterialName(ri.getMaterialTypeId(), ri.getMaterialName()));
            req.setRequiredQuantity(required);
            // 出成率 → 损耗率展示 (100 - yieldRate), 不参与库存校验算术 (同 BomItem 路径)
            req.setWastageRate(deriveWastageFromYield(ri.getYieldRate()));
            // D3 激活点: sourceUnit 触发 ProductionWorkflowOrchestrator g↔kg 换算
            req.setSourceUnit(ri.getUnit());
            requirements.add(req);
        }
        return requirements;
    }

    /**
     * D4 Path B: 从 BomItem 配方表构建 MaterialRequirement 清单。
     *
     * <p>计算公式：required = (standardQuantity / (yieldRate/100)) * productionQuantity</p>
     * <p>等价于 {@code bomItem.getActualQuantity() * productionQuantity}，
     * 这里直接调 entity 的 @Transient method 复用同一公式，避免 drift。</p>
     *
     * <p>关键：传递 {@code bomItem.getUnit()} 到 MaterialRequirement.sourceUnit，
     * 激活 D3 g↔kg 单位换算（ProductionWorkflowOrchestrator.buildTransferRequest）。</p>
     */
    private List<MaterialRequirement> expandFromBomItems(List<BomItem> bomItems,
                                                         BigDecimal productionQuantity) {
        List<MaterialRequirement> requirements = new ArrayList<>();
        for (BomItem item : bomItems) {
            BigDecimal actualPerUnit = item.getActualQuantity();
            BigDecimal required = actualPerUnit != null && productionQuantity != null
                    ? actualPerUnit.multiply(productionQuantity).setScale(6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            MaterialRequirement req = new MaterialRequirement();
            req.setMaterialTypeId(item.getMaterialTypeId());
            // T159-B Change4: prefer live RawMaterialType.name over BOM snapshot.
            req.setMaterialTypeName(resolveLiveMaterialName(item.getMaterialTypeId(), item.getMaterialName()));
            req.setRequiredQuantity(required);
            // BomItem 的"损耗率"概念来自 yieldRate (出成率% → 100-yieldRate = 损耗%)
            // 仅用于展示 (BomExpansionTool / ProductionPlanServiceImpl)，不参与 checkMaterialAvailability 算术
            req.setWastageRate(deriveWastageFromYield(item.getYieldRate()));
            // D3 激活点 (PR #297 handoff): sourceUnit 触发 ProductionWorkflowOrchestrator 的 g↔kg 1:1000 换算
            req.setSourceUnit(item.getUnit());
            requirements.add(req);
        }
        return requirements;
    }

    /**
     * Legacy fallback: 从 MaterialProductConversion (RPF) 构建 MaterialRequirement。
     * 保持向后兼容 — F001 等老工厂数据仍依赖此路径。
     */
    private List<MaterialRequirement> expandFromConversions(String factoryId,
                                                            String productTypeId,
                                                            BigDecimal productionQuantity) {
        List<MaterialProductConversion> conversions =
                conversionRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId);

        List<MaterialRequirement> requirements = new ArrayList<>();
        for (MaterialProductConversion conv : conversions) {
            if (!Boolean.TRUE.equals(conv.getIsActive())) {
                continue;
            }

            BigDecimal required = conv.calculateActualUsage(productionQuantity);

            MaterialRequirement req = new MaterialRequirement();
            req.setMaterialTypeId(conv.getMaterialTypeId());
            req.setMaterialTypeName(
                    conv.getMaterialType() != null
                            ? conv.getMaterialType().getName()
                            : conv.getMaterialTypeId()
            );
            req.setRequiredQuantity(required);
            req.setWastageRate(conv.getWastageRate());
            // RPF 路径 sourceUnit 留 null → ProductionWorkflowOrchestrator 沿用原 1:1 透传逻辑 (向后兼容)
            requirements.add(req);
        }

        log.info("BOM展开 (RPF fallback): factoryId={}, productType={}, quantity={}, 需要原料种类={}",
                factoryId, productTypeId, productionQuantity, requirements.size());
        return requirements;
    }

    /**
     * T144: 物料实际库存单位 = AVAILABLE 批次的 {@code MaterialBatch.quantityUnit} (称重口径, e.g. kg),
     * <b>不是</b> {@code RawMaterialType.unit} (箱). 原料称重入库 — 权威库存量是 kg.
     *
     * <p>从已加载的批次列表里取最常见的 quantityUnit (避免额外查询). 各批次单位混用时记 warning.
     * 批次为空时回退 RawMaterialType.unit (无库存场景).
     */
    private String resolveMaterialStockUnit(String materialTypeId, List<MaterialBatch> batches) {
        if (batches != null && !batches.isEmpty()) {
            java.util.Map<String, Long> byUnit = batches.stream()
                    .map(MaterialBatch::getQuantityUnit)
                    .filter(u -> u != null && !u.isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(u -> u, java.util.stream.Collectors.counting()));
            if (!byUnit.isEmpty()) {
                if (byUnit.size() > 1) {
                    log.warn("[BOM-CHECK] 物料 {} 可用批次单位混用 {}, 取最常见", materialTypeId, byUnit.keySet());
                }
                return byUnit.entrySet().stream()
                        .max(java.util.Map.Entry.comparingByValue())
                        .map(java.util.Map.Entry::getKey)
                        .orElse(null);
            }
        }
        // 无可用批次: 回退 RawMaterialType.unit
        if (rawMaterialTypeRepository == null || materialTypeId == null) {
            return null;
        }
        try {
            return rawMaterialTypeRepository.findById(materialTypeId)
                    .map(RawMaterialType::getUnit)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[BOM-CHECK] 读取物料库存单位失败: {} ({})", materialTypeId, e.getMessage());
            return null;
        }
    }

    /**
     * 从 BomItem 的出成率 (yieldRate, 0-100%) 推导损耗率 (wastageRate, 0-100%)。
     * 100 - yieldRate = wastageRate (近似)，仅供展示用，不参与库存检查算术。
     */
    /**
     * T159-B Change4: 优先从 RawMaterialType 主数据读取原料真实名称, BOM 快照作 fallback.
     */
    private String resolveLiveMaterialName(String materialTypeId, String snapshotName) {
        if (rawMaterialTypeRepository != null && materialTypeId != null) {
            try {
                RawMaterialType mt = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
                if (mt != null && mt.getName() != null && !mt.getName().isBlank()) {
                    return mt.getName();
                }
            } catch (Exception e) {
                log.debug("[T159] 读取原料名失败 {} ({})", materialTypeId, e.getMessage());
            }
        }
        if (snapshotName != null && !snapshotName.isBlank()) {
            return snapshotName;
        }
        return materialTypeId != null ? materialTypeId : "?";
    }

    private BigDecimal deriveWastageFromYield(BigDecimal yieldRate) {
        if (yieldRate == null) return null;
        return HUNDRED.subtract(yieldRate).max(BigDecimal.ZERO);
    }

    /**
     * 检查原辅料库存是否满足BOM需求。
     *
     * <p>对每种原辅料，按FEFO策略（先到期先出）检查可用库存。
     * 库存充足时，生成批次分配方案；不足时，记录短缺信息。</p>
     *
     * @param factoryId    工厂ID
     * @param requirements BOM展开后的原辅料需求清单
     * @return 检查结果，含"是否全部满足"标志、短缺列表、批次分配方案
     */
    @Transactional(readOnly = true)
    public MaterialCheckResult checkMaterialAvailability(String factoryId,
                                                         List<MaterialRequirement> requirements) {
        boolean allSatisfied = true;
        List<MaterialShortfall> shortfalls = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();

        // D1: warehouse strategy per PR #310 §5 — BOM 物料分配 WH-WKS 优先 → fallback WH-LOG
        // (生产先用车间仓余料, 不够回 LOG 拿). 在循环外解析 warehouse_id 避免重复 lookup.
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        String logisticsId = warehouseResolver.resolveLogisticsId(factoryId);

        for (MaterialRequirement req : requirements) {
            // WH-WKS 优先, 不足再合并 WH-LOG
            List<MaterialBatch> workshopBatches = materialBatchRepository
                    .findAvailableBatchesFEFOByWarehouse(factoryId, req.getMaterialTypeId(), workshopId);
            List<MaterialBatch> logisticsBatches = materialBatchRepository
                    .findAvailableBatchesFEFOByWarehouse(factoryId, req.getMaterialTypeId(), logisticsId);
            List<MaterialBatch> batches = new ArrayList<>(workshopBatches);
            batches.addAll(logisticsBatches);

            // 汇总可用数量 (库存单位, e.g. 箱)
            BigDecimal totalAvailable = batches.stream()
                    .map(b -> b.getReceiptQuantity()
                            .subtract(b.getUsedQuantity() != null ? b.getUsedQuantity() : BigDecimal.ZERO)
                            .subtract(b.getReservedQuantity() != null ? b.getReservedQuantity() : BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // T144: 把需求量 (sourceUnit, e.g. g) 换算到称重批次单位 (kg) 再比较 + 分配.
            // requiredInStockUnit 默认 = req.getRequiredQuantity() (单位一致 / 无换算器 / RPF 路径).
            BigDecimal requiredInStockUnit = req.getRequiredQuantity();
            boolean abacaSkip = false;
            String stockUnit = resolveMaterialStockUnit(req.getMaterialTypeId(), batches);
            String bomUnit = req.getSourceUnit();

            if (materialUomConverter != null && bomUnit != null && stockUnit != null
                    && !bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) {
                MaterialUomConverter.ConversionResult conv = materialUomConverter.toComparableQuantity(
                        req.getMaterialTypeId(), req.getRequiredQuantity(), bomUnit, stockUnit);
                if (conv.isAbacaSkip()) {
                    // 抄码料: 跳过库存校验 (不阻断 transfer 自动生成 / 唤醒).
                    abacaSkip = true;
                    log.info("[BOM-CHECK] 抄码料 {} 跳过库存校验", req.getMaterialTypeName());
                } else if (conv.isUnconvertible()) {
                    // T144 安全网: BOM 单位与库存批次单位维度不可换算 (e.g. 个 vs kg) → 真实配置错误.
                    throw new BusinessException(409,
                            String.format("原料「%s」BOM单位(%s)与库存单位(%s)无法换算，请核对单位配置",
                                    req.getMaterialTypeName(), bomUnit, stockUnit))
                            .withCode("MATERIAL_UOM_UNCONFIGURED")
                            .withHint("请核对该原料 BOM 配方单位与入库称重单位是否同一计量维度")
                            .withHintTarget(req.getMaterialTypeId())
                            .withSeverity("BLOCKING");
                } else {
                    requiredInStockUnit = conv.getQuantity();
                }
            }

            if (abacaSkip) {
                // 抄码料: 不阻断, 把全部可用批次按需 (无法精确分配) 直接列为可用, 不计入短缺.
                // 仍按 FEFO 顺序逐批次分配 totalAvailable (尽量满足), 但不标记短缺.
                BigDecimal remaining = totalAvailable;
                for (MaterialBatch batch : batches) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                    BigDecimal available = batch.getReceiptQuantity()
                            .subtract(batch.getUsedQuantity() != null ? batch.getUsedQuantity() : BigDecimal.ZERO)
                            .subtract(batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO);
                    BigDecimal alloc = remaining.min(available);
                    MaterialAllocation allocation = new MaterialAllocation();
                    allocation.setMaterialTypeId(req.getMaterialTypeId());
                    allocation.setMaterialBatchId(batch.getId());
                    allocation.setBatchNumber(batch.getBatchNumber());
                    allocation.setAllocatedQuantity(alloc);
                    allocations.add(allocation);
                    remaining = remaining.subtract(alloc);
                }
            } else if (totalAvailable.compareTo(requiredInStockUnit) < 0) {
                // 库存不足，记录短缺 (用换算后的库存单位口径)
                allSatisfied = false;

                MaterialShortfall sf = new MaterialShortfall();
                sf.setMaterialTypeId(req.getMaterialTypeId());
                sf.setMaterialTypeName(req.getMaterialTypeName());
                sf.setRequiredQuantity(requiredInStockUnit);
                sf.setAvailableQuantity(totalAvailable);
                sf.setShortfallQuantity(requiredInStockUnit.subtract(totalAvailable));
                shortfalls.add(sf);
            } else {
                // 库存充足，按FEFO逐批次分配 (库存单位口径)
                BigDecimal remaining = requiredInStockUnit;
                for (MaterialBatch batch : batches) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }

                    BigDecimal available = batch.getReceiptQuantity()
                            .subtract(batch.getUsedQuantity() != null ? batch.getUsedQuantity() : BigDecimal.ZERO)
                            .subtract(batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO);

                    BigDecimal alloc = remaining.min(available);

                    MaterialAllocation allocation = new MaterialAllocation();
                    allocation.setMaterialTypeId(req.getMaterialTypeId());
                    allocation.setMaterialBatchId(batch.getId());
                    allocation.setBatchNumber(batch.getBatchNumber());
                    allocation.setAllocatedQuantity(alloc);
                    allocations.add(allocation);

                    remaining = remaining.subtract(alloc);
                }
            }
        }

        MaterialCheckResult result = new MaterialCheckResult();
        result.setAllSatisfied(allSatisfied);
        result.setShortfalls(shortfalls);
        result.setAllocations(allocations);

        log.info("原辅料检查: factoryId={}, 全部满足={}, 短缺种类={}, 分配批次数={}",
                factoryId, allSatisfied, shortfalls.size(), allocations.size());
        return result;
    }

    /**
     * 针对待排产的生产计划，重新检查其原辅料库存是否已到位。
     *
     * <p>通常在新原料入库（MaterialReceivedEvent）后触发，用于唤醒等待中的生产计划。</p>
     *
     * @param plan 待检查的生产计划
     * @return true 表示原辅料已全部到位，可以开始排产；false 表示仍有短缺
     */
    @Transactional(readOnly = true)
    public boolean recheckAvailability(ProductionPlan plan) {
        List<MaterialRequirement> requirements = expandBOM(
                plan.getFactoryId(),
                plan.getProductTypeId(),
                plan.getPlannedQuantity()
        );
        MaterialCheckResult result = checkMaterialAvailability(plan.getFactoryId(), requirements);
        log.info("生产计划复检: planId={}, allSatisfied={}", plan.getId(), result.isAllSatisfied());
        return result.isAllSatisfied();
    }
}
