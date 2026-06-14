package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.StandardCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 六扇门 D1: 同口径标准成本解析实现.
 *
 * <p>组装 = BOM 料标准 ({@link BomRecipe#getTotalMaterialCost()}) + 研发预估标准人工
 * ({@link QuotationTask#getLaborPerKg()} 元/kg → 折算到每单位成品)。
 *
 * <p>详见 {@link StandardCostService} 接口文档的口径定义与诚实-null 规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardCostServiceImpl implements StandardCostService {

    /** 单位成本计算 scale, 与 CostRollupUtil.COST_SCALE 对齐. */
    private static final int COST_SCALE = 4;
    private static final BigDecimal GRAMS_PER_KG = new BigDecimal("1000");

    private final BomRecipeService bomRecipeService;
    private final QuotationTaskRepository quotationTaskRepository;

    @Override
    @Transactional(readOnly = true)
    public StandardUnitCost resolveStandardUnitCost(String factoryId, String productTypeId) {
        if (factoryId == null || productTypeId == null) {
            return StandardUnitCost.builder()
                    .laborIncluded(false)
                    .caliberHint("缺少工厂或产品标识，无法解析标准成本")
                    .build();
        }

        // ── 1. 料标准成本 (BomRecipe.totalMaterialCost, 配方料 only) ──────────
        BigDecimal materialUnitCost = null;
        BomRecipe recipe = null;
        try {
            Optional<BomRecipe> recipeOpt = bomRecipeService.getCurrentRecipe(factoryId, productTypeId);
            if (recipeOpt.isPresent()) {
                recipe = recipeOpt.get();
                BigDecimal mat = recipe.getTotalMaterialCost();
                // 诚实: null (含未定价料) 或 <=0 (空配方) 视为料标准不可用, 不当 0
                if (mat != null && mat.compareTo(BigDecimal.ZERO) > 0) {
                    materialUnitCost = mat;
                }
            }
        } catch (Exception e) {
            log.warn("[D1-StdCost] BOM 料标准查询失败: factoryId={}, productTypeId={}: {}",
                    factoryId, productTypeId, e.getMessage());
        }

        // ── 2. 标准人工 (研发预估 laborPerKg → 折算到每单位成品) ──────────────
        BigDecimal laborUnitCost = resolveStandardLaborUnitCost(factoryId, productTypeId, recipe);

        // ── 3. 标准制费: 当前无规范数据源 → 诚实 null ──────────────────────────
        BigDecimal overheadUnitCost = null;

        // ── 4. 同口径总标准成本 = 料 + 标准人工 (+ 制费). 料或人工任一缺 → null ──
        boolean materialReady = materialUnitCost != null;
        boolean laborReady = laborUnitCost != null;
        BigDecimal totalUnitCost = null;
        boolean laborIncluded = false;
        String caliberHint;

        if (materialReady && laborReady) {
            BigDecimal sum = materialUnitCost.add(laborUnitCost);
            if (overheadUnitCost != null) {
                sum = sum.add(overheadUnitCost);
            }
            totalUnitCost = sum.setScale(COST_SCALE, RoundingMode.HALF_UP);
            laborIncluded = true;
            caliberHint = "标准成本含人工 (料 + 研发预估人工)，与含人工的实际成本同口径可比";
        } else {
            // 口径不全 → totalUnitCost 留 null, 不与含人工实际比 (避免假阳性超支报警)
            StringBuilder hint = new StringBuilder("标准成本口径不全，未与实际成本对比: ");
            if (!materialReady) {
                hint.append("BOM 料标准成本不可用 (无 ACTIVE BOM 或料未定价); ");
            }
            if (!laborReady) {
                hint.append("缺研发预估人工 (请在研发报价任务填写 人工成本 元/kg); ");
            }
            caliberHint = hint.toString().trim();
        }

        return StandardUnitCost.builder()
                .materialUnitCost(materialUnitCost)
                .laborUnitCost(laborUnitCost)
                .overheadUnitCost(overheadUnitCost)
                .totalUnitCost(totalUnitCost)
                .laborIncluded(laborIncluded)
                .caliberHint(caliberHint)
                .build();
    }

    /**
     * 标准人工单位成本 = 研发预估 {@code laborPerKg} (元/kg成品) × 单位成品 kg.
     *
     * <p>单位成品 kg = {@code BomRecipe.outputQuantityPerUnit} 按 {@code outputUnit} 折算到 kg:
     * <ul>
     *   <li>outputUnit = "g"  → kg = outputQuantityPerUnit / 1000</li>
     *   <li>outputUnit = "kg" → kg = outputQuantityPerUnit</li>
     *   <li>其他单位 (个/件/...) → 无法折算到 kg, 诚实 null</li>
     * </ul>
     *
     * <p>诚实 null: 无研发报价任务 / laborPerKg 为 null/<=0 / 无 BOM 或 outputQuantityPerUnit
     * 缺失 / 单位非 g·kg → 返 null (不伪造标准人工)。
     */
    private BigDecimal resolveStandardLaborUnitCost(String factoryId, String productTypeId, BomRecipe recipe) {
        // 研发预估人工 (元/kg成品)
        BigDecimal laborPerKg;
        try {
            Optional<QuotationTask> taskOpt = quotationTaskRepository
                    .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId);
            if (taskOpt.isEmpty()) {
                log.debug("[D1-StdCost] 产品 {} 无研发报价任务 → 无标准人工", productTypeId);
                return null;
            }
            laborPerKg = taskOpt.get().getLaborPerKg();
        } catch (Exception e) {
            log.warn("[D1-StdCost] 研发报价任务查询失败: factoryId={}, productTypeId={}: {}",
                    factoryId, productTypeId, e.getMessage());
            return null;
        }
        if (laborPerKg == null || laborPerKg.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[D1-StdCost] 产品 {} 研发报价任务无 laborPerKg → 无标准人工", productTypeId);
            return null;
        }

        // 单位成品 kg (BOM 的 outputQuantityPerUnit + outputUnit)
        if (recipe == null) {
            log.debug("[D1-StdCost] 产品 {} 无 BOM, 无法折算单位成品 kg → 无标准人工", productTypeId);
            return null;
        }
        BigDecimal outputQtyPerUnit = recipe.getOutputQuantityPerUnit();
        if (outputQtyPerUnit == null || outputQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[D1-StdCost] 产品 {} BOM outputQuantityPerUnit={} 非正 → 无标准人工",
                    productTypeId, outputQtyPerUnit);
            return null;
        }
        BigDecimal unitKg = toKilograms(outputQtyPerUnit, recipe.getOutputUnit());
        if (unitKg == null) {
            log.debug("[D1-StdCost] 产品 {} BOM outputUnit={} 非 g/kg, 无法折算 → 无标准人工",
                    productTypeId, recipe.getOutputUnit());
            return null;
        }

        // 标准人工单位成本 = laborPerKg × unitKg
        return laborPerKg.multiply(unitKg).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 把 BOM 单位成品产量折算到 kg.
     *
     * @return kg 值; 单位非 g/kg (个/件/...) 时返 null (无法折算)
     */
    private BigDecimal toKilograms(BigDecimal outputQtyPerUnit, String outputUnit) {
        String unit = outputUnit == null ? "" : outputUnit.trim().toLowerCase();
        switch (unit) {
            case "g":
            case "克":
                return outputQtyPerUnit.divide(GRAMS_PER_KG, 8, RoundingMode.HALF_UP);
            case "kg":
            case "千克":
            case "公斤":
                return outputQtyPerUnit;
            default:
                return null;
        }
    }
}
