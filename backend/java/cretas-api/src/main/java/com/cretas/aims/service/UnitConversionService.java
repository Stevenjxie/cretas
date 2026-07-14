package com.cretas.aims.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitDimension;

import java.time.LocalDateTime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * UnitConversionService — 物料计量单位换算 (Track D1 Bug-3 refactor).
 *
 * <p>客户原话 (六扇门第四次 May10 line 263):
 *   "我在这写的克, 那我做调包的时候会自动折换成公斤"
 *
 * <p>原 implementation 是 {@code ProductionWorkflowOrchestrator.convertUnit()}
 * 的 private method (D3 2026-05-10 客户对接会议落地). 该 method 被反射测试
 * (ProductionWorkflowOrchestratorUnitConversionTest), 但 only available 给
 * orchestration 路径. BOM 配方 + 库存出库 + 采购入库 都需要同一逻辑 — 提到
 * 公共 Spring service 避免重复实现 / drift.
 *
 * <p>支持的换算 (per 客户 May10 确认, 六腾门 BOM 只需 g↔kg, 餐饮历史数据有 ml↔L):
 * <ul>
 *   <li>g ↔ kg (1:1000)</li>
 *   <li>ml ↔ L (1:1000)</li>
 *   <li>同单位透传</li>
 * </ul>
 *
 * <p>不支持的换算 (e.g. g → ml, 个 → kg) 返回 {@code null} —
 * caller 应回退到原值 + 原单位 (per ProductionWorkflowOrchestrator line 137-145 既有逻辑).
 *
 * <p>Java→Python parity 规则: HALF_UP scale 6 (per python-java-port.md Rule 10).
 * 输出 BigDecimal 保留 setScale 让 caller 决定显示精度.
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Slf4j
@Service
public class UnitConversionService {

    private final UnitContractService unitContractService;

    public UnitConversionService(UnitContractService unitContractService) {
        this.unitContractService = unitContractService;
    }

    /** Test-only source compatibility; production beans always receive UnitContractService. */
    @Deprecated(forRemoval = true)
    public UnitConversionService() {
        this.unitContractService = null;
    }

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    /**
     * 质量单位 → kg 基准的换算系数 (R13, 2026-06-22).
     *
     * <p>客户决策 (R13): 凡按非-kg 重量单位录入的量, 成本/库存计算前归一到 kg 基准.
     * 1斤=0.5kg (中国市制斤), 1吨=1000kg, 1g=0.001kg, 1mg=0.000001kg.
     *
     * <p>所有 key 已 lowercase (中文不受 lowercase 影响). 含中文别名 (斤/吨/克/公斤/千克/毫克)
     * + latin token (kg/g/mg/t), 与 {@code MaterialBatchCreateTool.convertToKg} 既有
     * 斤=0.5 / 吨=1000 系数对齐 (本表为唯一权威, 消除散落硬编码 drift).
     *
     * <p><b>不含</b> ml/L (体积维度) 与 个/箱/袋 (离散计数) — 这些不是重量, 没有 kg 系数.
     */
    private static final java.util.Map<String, BigDecimal> WEIGHT_TO_KG = java.util.Map.ofEntries(
            java.util.Map.entry("kg", BigDecimal.ONE),
            java.util.Map.entry("千克", BigDecimal.ONE),
            java.util.Map.entry("公斤", BigDecimal.ONE),
            java.util.Map.entry("g", new BigDecimal("0.001")),
            java.util.Map.entry("克", new BigDecimal("0.001")),
            java.util.Map.entry("mg", new BigDecimal("0.000001")),
            java.util.Map.entry("毫克", new BigDecimal("0.000001")),
            java.util.Map.entry("斤", new BigDecimal("0.5")),
            java.util.Map.entry("吨", new BigDecimal("1000")),
            java.util.Map.entry("t", new BigDecimal("1000"))
    );

    /**
     * 单位换算.
     *
     * @param value     原值, null → null
     * @param fromUnit  源单位 (case-insensitive, trimmed)
     * @param toUnit    目标单位
     * @return 换算后的值, scale=6 HALF_UP; 不支持的换算返 null; 同单位透传原值
     */
    public BigDecimal convert(BigDecimal value, String fromUnit, String toUnit) {
        if (value == null || fromUnit == null || toUnit == null) return null;

        if (unitContractService != null) {
            UnitConversionResult result = unitContractService.convert(value, new UnitConversionContext(
                    null, null, fromUnit, toUnit, LocalDateTime.now(), null, 6, RoundingMode.HALF_UP));
            return result.succeeded() && result.quantity() != null
                    ? result.quantity().setScale(6, RoundingMode.HALF_UP)
                    : null;
        }

        String from = fromUnit.trim().toLowerCase();
        String to = toUnit.trim().toLowerCase();

        if (from.equals(to)) return value;

        // 质量: 任意重量单位 ↔ 任意重量单位 (经 kg 基准, R13).
        //   覆盖 g↔kg / 斤↔kg / 吨↔kg / mg↔kg / 斤↔g / 斤↔吨 等全部重量两两组合.
        BigDecimal fromFactor = WEIGHT_TO_KG.get(from);
        BigDecimal toFactor = WEIGHT_TO_KG.get(to);
        if (fromFactor != null && toFactor != null) {
            // value(from) → kg → to:  value × fromFactor / toFactor
            BigDecimal inKg = value.multiply(fromFactor);
            return inKg.divide(toFactor, 6, RoundingMode.HALF_UP);
        }

        // 体积: ml ↔ L
        if ("ml".equals(from) && "l".equals(to)) {
            return value.divide(THOUSAND, 6, RoundingMode.HALF_UP);
        }
        if ("l".equals(from) && "ml".equals(to)) {
            return value.multiply(THOUSAND);
        }

        // 不支持的换算 (跨维度 / 离散单位): null 表示沿用原值
        log.debug("UnitConversionService: unsupported conversion {}→{}, returning null", fromUnit, toUnit);
        return null;
    }

    /**
     * 把一个 (value, unit) 折算到 kg 基准 (R13, 2026-06-22).
     *
     * <p>用于成本/库存计算前的"边界归一": 凡重量单位 (kg/g/mg/斤/吨/克/公斤/千克/t)
     * 一律折到 kg; <b>非重量单位</b> (个/箱/袋/ml/L 等离散或体积单位) <b>不归一</b>,
     * 返回 {@code null} 让 caller 保持原值原单位 (绝不强行当 kg).
     *
     * @param value 原值, null → null
     * @param unit  原单位
     * @return kg 基准值 (scale=6 HALF_UP); 非重量单位 / null 单位 → null
     */
    public BigDecimal toKg(BigDecimal value, String unit) {
        if (value == null || unit == null) return null;
        if (unitContractService != null) {
            return convert(value, unit, "kg");
        }
        BigDecimal factor = WEIGHT_TO_KG.get(unit.trim().toLowerCase());
        if (factor == null) return null;          // 非重量单位: 不归一
        if (BigDecimal.ONE.compareTo(factor) == 0) return value;   // 已是 kg, 透传
        return value.multiply(factor).setScale(6, RoundingMode.HALF_UP);
    }

    /** 该单位是否为可归一到 kg 的重量单位 (kg/g/mg/斤/吨/克/公斤/千克/t). */
    public boolean isWeightUnit(String unit) {
        if (unitContractService != null) {
            return unitContractService.describe(null, unit)
                    .map(canonical -> canonical.dimension() == UnitDimension.MASS)
                    .orElse(false);
        }
        return unit != null && WEIGHT_TO_KG.containsKey(unit.trim().toLowerCase());
    }

    /**
     * 便捷方法: 不支持的换算回退到原值 (而非 null).
     *
     * <p>BOM 配方编辑 UI / 显示场景: 用户输入 200g, 想 preview "0.2kg",
     * 当 unit 是 '个' 这种不支持换算时, 应返回 200 个 (原值原单位) 而不是 null.
     */
    public BigDecimal convertOrSame(BigDecimal value, String fromUnit, String toUnit) {
        BigDecimal converted = convert(value, fromUnit, toUnit);
        return converted != null ? converted : value;
    }

    /**
     * 检查换算是否支持 (e.g. UI 决定是否显示 "= 0.2kg" 提示).
     */
    public boolean isSupported(String fromUnit, String toUnit) {
        if (fromUnit == null || toUnit == null) return false;
        if (unitContractService != null) {
            UnitConversionResult result = unitContractService.convert(BigDecimal.ONE, new UnitConversionContext(
                    null, null, fromUnit, toUnit, LocalDateTime.now(), null, 6, RoundingMode.HALF_UP));
            return result.succeeded();
        }
        String from = fromUnit.trim().toLowerCase();
        String to = toUnit.trim().toLowerCase();
        if (from.equals(to)) return true;
        // 任意两个重量单位之间可换算 (经 kg 基准, R13)
        if (WEIGHT_TO_KG.containsKey(from) && WEIGHT_TO_KG.containsKey(to)) {
            return true;
        }
        return ("ml".equals(from) && "l".equals(to))
            || ("l".equals(from) && "ml".equals(to));
    }
}
