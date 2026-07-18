package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
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
 * <p>所有生产展开只读取当前 ACTIVE/current 的 BomRecipeItem；缺配方时明确阻断。</p>
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Service
@RequiredArgsConstructor
public class BomExpansionService {

    private static final Logger log = LoggerFactory.getLogger(BomExpansionService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final WarehouseResolver warehouseResolver;

    /**
     * T143: 物料单位换算器 (箱↔kg). 字段注入 (required=false) 不破坏 @RequiredArgsConstructor
     * 与既有单测 (@InjectMocks). 未注入时仅保持同单位比较。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialUomConverter materialUomConverter;

    /** T143: 读库存单位 (RawMaterialType.unit). 字段注入 required=false. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    /** 当前 ACTIVE/current 配方明细仓库；生产展开的唯一 BOM 真值。 */
    private final com.cretas.aims.repository.bom.BomRecipeItemRepository bomRecipeItemRepository;

    /**
     * BOM展开：根据产品类型和生产数量，计算所有需要的原辅料（含损耗）。
     *
     * <p>仅使用当前 ACTIVE/current BomRecipe 配方明细，不再回退旧 BOM/RPF。</p>
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
        List<BomRecipeItem> recipeItems =
                bomRecipeItemRepository.findCurrentByProduct(factoryId, productTypeId);
        if (recipeItems.isEmpty()) {
            throw new BusinessException(409, "产品尚无已激活的新版 BOM 配方: " + productTypeId)
                    .withCode("BOM_ACTIVE_RECIPE_REQUIRED")
                    .withHint("请先创建至少一条配方明细并激活 BOM，再进入生产或领料")
                    .withSeverity("BLOCKING");
        }
        log.info("[BOM-EXPANSION] using bom_recipe_items for factoryId={}, productTypeId={}, items={}",
                factoryId, productTypeId, recipeItems.size());
        return expandFromRecipeItems(recipeItems, productionQuantity);
    }

    /**
     * 断点3 修复: 从 BomRecipeItem (新配方表) 构建 MaterialRequirement 清单.
     *
     * <p>用 {@code actualQuantity} (已按出成率折算的每单位产品原料量; null 时
     * {@code calculateActualQuantity()} 实时折算, 同 entity 公式) × productionQuantity 算需求.
     * 与 PurchaseServiceImpl recipe 路径完全同源, 不双源重复计料.
     *
     * <p>{@code sourceUnit = recipeItem.getUnit()} 激活 D3 g↔kg 单位换算 (同新版配方路径).
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
            // 优先 live RawMaterialType.name, recipe 快照名作 fallback (同新版配方路径)
            req.setMaterialTypeName(resolveLiveMaterialName(ri.getMaterialTypeId(), ri.getMaterialName()));
            req.setRequiredQuantity(required);
            // 出成率 → 损耗率展示 (100 - yieldRate), 不参与库存校验算术 (同新版配方路径)
            req.setWastageRate(deriveWastageFromYield(ri.getYieldRate()));
            // D3 激活点: sourceUnit 触发 ProductionWorkflowOrchestrator g↔kg 换算
            req.setSourceUnit(ri.getUnit());
            requirements.add(req);
        }
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
     * 从 BomRecipeItem 的出成率 (yieldRate, 0-100%) 推导损耗率 (wastageRate, 0-100%)。
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
            // requiredInStockUnit 默认 = req.getRequiredQuantity() (单位一致或无换算器).
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
        // doomed-tx 修复 Layer 2 (2026-06-12): recheckAvailability 是"就绪软探针"(收货后唤醒等待计划),
        // 不是写路径校验. expandBOM 对单位不可换算的 BOM 行 (如吸塑盒2014-3.5 BOM单位 g vs 库存 件)
        // 抛 BusinessException(409) —— 在软探针语境下应视为"该计划尚不就绪"(return false), 而非抛异常
        // (抛会 mark 事务 rollback-only, 即便上层 catch 也 doom). 写路径 (ProductionPlanServiceImpl /
        // FactoryMaterialRequisitionServiceImpl) 仍保留抛异常语义 (不能用坏 BOM 开工, 正确).
        try {
            List<MaterialRequirement> requirements = expandBOM(
                    plan.getFactoryId(),
                    plan.getProductTypeId(),
                    plan.getPlannedQuantity()
            );
            MaterialCheckResult result = checkMaterialAvailability(plan.getFactoryId(), requirements);
            log.info("生产计划复检: planId={}, allSatisfied={}", plan.getId(), result.isAllSatisfied());
            return result.isAllSatisfied();
        } catch (BusinessException e) {
            // 单位不可换算 / BOM 配置问题 → 软探针判定"尚不就绪", 不抛 (避免 doom 事务).
            log.warn("生产计划复检遇 BOM/单位问题, 视为尚不就绪: planId={}, reason={}",
                    plan.getId(), e.getMessage());
            return false;
        }
    }
}
