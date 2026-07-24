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

    /**
     * 修2: 标准人工解析结果, 区分三种"为何 null"的语义:
     * <ul>
     *   <li>{@code value != null} — 成功算出标准人工单位成本</li>
     *   <li>{@code value == null, skipReason contains "单位"} — 产品 outputUnit 非 g/kg,
     *       无法折算到 kg; 超支报警对此产品永久禁用 → caliberHint 必须说明单位</li>
     *   <li>{@code value == null, skipReason 其他} — 正常缺配置 (无研发任务/laborPerKg 空等)</li>
     * </ul>
     */
    private record LaborResult(BigDecimal value, String skipReason) {
        static LaborResult of(BigDecimal value) { return new LaborResult(value, null); }
        static LaborResult skip(String reason) { return new LaborResult(null, reason); }
    }

    private final BomRecipeService bomRecipeService;
    private final QuotationTaskRepository quotationTaskRepository;

    /**
     * 可选注入: 统一单位换算 (R13 / #7 drift sweep, 2026-06-22).
     *
     * <p>消除本类原 {@link #toKilograms} 本地硬编码 g/kg switch 的 drift —
     * 委托 {@link com.cretas.aims.service.UnitConversionService#toKg} 让斤/吨/mg
     * 也能折算到 kg (与 BOM 成本点 R13 同源)。
     *
     * <p>{@code @Autowired(required = false)} + null fallback: 单测旧构造器
     * (2-arg @RequiredArgsConstructor) 不传时退化为本地 g/kg switch (旧行为不崩),
     * 与 {@code BomServiceImpl} R13 一致。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.UnitConversionService unitConversionService;

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
        // 修1: 查询异常直接向上抛 — 不吞成 null 当"无 BOM 配置"处理。
        //   "无 BOM" = Optional.empty() (正常业务态, 诚实跳过)。
        //   "查询失败" = 抛异常 (临时故障, 不能伪装成"缺配置"导致静默漏报超支报警)。
        BigDecimal materialUnitCost = null;
        BomRecipe recipe = null;
        Optional<BomRecipe> recipeOpt = bomRecipeService.getCurrentRecipe(factoryId, productTypeId);
        if (recipeOpt.isPresent()) {
            recipe = recipeOpt.get();
            BigDecimal mat = recipe.getTotalMaterialCost();
            // 诚实: null (含未定价料) 或 <=0 (空配方) 视为料标准不可用, 不当 0
            if (mat != null && mat.compareTo(BigDecimal.ZERO) > 0) {
                materialUnitCost = mat;
            }
        }

        // ── 2. 标准人工 (研发预估 laborPerKg → 折算到每单位成品) ──────────────
        // 修1+修2: 异常向上抛 (不吞成 null); LaborResult 区分"无配置"/"单位不支持"两种跳过语义
        LaborResult laborResult = resolveStandardLaborUnitCost(factoryId, productTypeId, recipe);
        BigDecimal laborUnitCost = laborResult.value();

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
                // 修2: 区分"单位无法折算"(产品决策)与"正常缺配置", 两者 caliberHint 不同
                if (laborResult.skipReason() != null) {
                    hint.append(laborResult.skipReason()).append("; ");
                } else {
                    hint.append("缺研发预估人工 (请在研发报价任务填写 人工成本 元/kg); ");
                }
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
     *   <li>其他单位 (个/件/盒/...) → 无法折算到 kg → LaborResult.skip 含"单位"说明</li>
     * </ul>
     *
     * <p><b>修1</b>: 不再 catch 查询异常 — 临时 DB 故障必须向上抛, 不能被当"正常缺配置"处理。
     * "正常缺配置" = Optional.empty() (无研发任务) 或 laborPerKg null/<=0 (任务存在但未填)。
     *
     * <p><b>修2</b>: 当 outputUnit 不可折算时返回 {@code LaborResult.skip} 含单位说明,
     * 而非"缺研发预估人工" — 让 caliberHint 可见"此产品单位 X 无法折算到 kg → 超支报警禁用"。
     * net-content 折算 (盒×净重/克重) 标为待 Steve 决策: BomRecipe 有 gramsPerUnit 字段时可顺手支持;
     * 当前 gramsPerUnit 尚未普遍填写, 不擅自造, 只做可见化。
     */
    private LaborResult resolveStandardLaborUnitCost(String factoryId, String productTypeId, BomRecipe recipe) {
        // 修1: 研发报价任务查询异常直接向上抛 (不 catch 吞成 null)
        Optional<QuotationTask> taskOpt = quotationTaskRepository
                .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId);
        if (taskOpt.isEmpty()) {
            log.debug("[D1-StdCost] 产品 {} 无研发报价任务 → 无标准人工", productTypeId);
            return LaborResult.skip(null);
        }
        BigDecimal laborPerKg = taskOpt.get().getLaborPerKg();
        if (laborPerKg == null || laborPerKg.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[D1-StdCost] 产品 {} 研发报价任务无 laborPerKg → 无标准人工", productTypeId);
            return LaborResult.skip(null);
        }

        // 单位成品 kg (BOM 的 outputQuantityPerUnit + outputUnit)
        if (recipe == null) {
            log.debug("[D1-StdCost] 产品 {} 无 BOM, 无法折算单位成品 kg → 无标准人工", productTypeId);
            return LaborResult.skip(null);
        }
        BigDecimal outputQtyPerUnit = recipe.getOutputQuantityPerUnit();
        if (outputQtyPerUnit == null || outputQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[D1-StdCost] 产品 {} BOM outputQuantityPerUnit={} 非正 → 无标准人工",
                    productTypeId, outputQtyPerUnit);
            return LaborResult.skip(null);
        }
        BigDecimal unitKg = toKilograms(outputQtyPerUnit, recipe.getOutputUnit());
        if (unitKg == null
                && recipe.getNetContentQuantity() != null
                && recipe.getNetContentQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal netContentKg = toKilograms(
                    recipe.getNetContentQuantity(),
                    recipe.getNetContentUnit());
            if (netContentKg != null) {
                unitKg = outputQtyPerUnit.multiply(netContentKg);
            }
        }
        if (unitKg == null) {
            // 修2: 单位无法折算 → warn 级别 (可见) + 明确说明"超支报警对本品禁用"
            String outputUnit = recipe.getOutputUnit();
            log.warn("[D1-StdCost] 产品 {} BOM outputUnit={} 无法折算到 kg → 标准人工缺失, 超支报警对本品禁用。" +
                            "请为包装单位配置可换算为重量的净含量。",
                    productTypeId, outputUnit);
            return LaborResult.skip(
                    "产品单位 \"" + outputUnit + "\" 无法折算到 kg, 标准人工缺失 → 超支报警对本品禁用" +
                    "；请配置可换算为重量的净含量");
        }

        // 标准人工单位成本 = laborPerKg × unitKg
        BigDecimal allocation = recipe.getCostAllocationRatio() == null
                ? BigDecimal.ONE
                : recipe.getCostAllocationRatio().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        return LaborResult.of(laborPerKg.multiply(unitKg).multiply(allocation)
                .setScale(COST_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 把 BOM 单位成品产量折算到 kg.
     *
     * <p>#7 drift sweep (2026-06-22): 优先委托 {@link com.cretas.aims.service.UnitConversionService#toKg}
     * (统一权威表, 多认斤/吨/mg 等重量单位); UnitConversionService 缺失 (旧 2-arg
     * 构造器单测) 时回退本地 g/kg switch (旧行为不变, 不崩)。
     *
     * <p>g/kg/克/千克/公斤 现有换算结果与旧 switch 完全一致 (1g=0.001kg / kg 透传);
     * 仅新增 斤(0.5kg)/吨(1000kg)/mg(0.000001kg) 的折算能力。
     *
     * @return kg 值; 单位非重量 (个/件/ml/...) 时返 null (无法折算)
     */
    private BigDecimal toKilograms(BigDecimal outputQtyPerUnit, String outputUnit) {
        // 优先委托统一换算服务 (消除 drift, 多认斤/吨/mg).
        if (unitConversionService != null) {
            return unitConversionService.toKg(outputQtyPerUnit, outputUnit);
        }
        // Fallback: UnitConversionService 未注入 (旧单测) → 本地 g/kg switch (旧行为).
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
