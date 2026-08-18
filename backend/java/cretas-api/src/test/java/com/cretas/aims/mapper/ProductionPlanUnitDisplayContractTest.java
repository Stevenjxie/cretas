package com.cretas.aims.mapper;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸: 生产计划 API 响应里的单位字段, 用户看到的必须是中文。
 *
 * <h2>🔴 实测出处 (2026-08-18, prod)</h2>
 * <pre>
 * GET /api/mobile/F006/production-plans/ffc61a6f-...
 *   "plannedUnit":        "盒"
 *   "workflowOutputUnit": "盒"
 *   "sourceDisplayUnit":  "box"   ← 字段名里带 display, 值却是英文码
 * </pre>
 * 库里 {@code production_plans.source_display_unit} 确有 {@code box} / {@code case} 各 1 行。
 *
 * <h2>为什么用反射而不是逐字段断言</h2>
 * 逐字段断言只覆盖<b>今天存在</b>的那几个字段 —— 明天有人加一个
 * {@code settlementUnit}, 断言不会红, 而它照样把码丢给用户。
 * 反射按<b>结构</b>(DTO 上所有 {@code String} 且名字以 {@code Unit} 结尾的字段)遍历,
 * 新字段<b>自动纳入</b>。这也是本仓的取舍: 闸要判结构, ⛔ 不要在源码上数字符串
 * (「grep 把 docstring 里提到的名字也数了进去」已经栽过好几次)。
 *
 * <h2>⚠️ 空转防护</h2>
 * 这道闸最容易的坏法是「字段全是 null ⇒ 循环里什么都没检查 ⇒ 恒绿」。
 * 所以每个被遍历到的字段都<b>必须非空</b>, 并且先断言遍历到的字段数 ≥ 4。
 */
class ProductionPlanUnitDisplayContractTest {

    private final ProductionPlanMapper mapper = new ProductionPlanMapper();

    /** 刻意保留拉丁的国际计量符号 —— 秤上/单据上/国标上都这么写。 */
    private static final Set<String> INTERNATIONAL_SYMBOLS = Set.of(
            "mg", "g", "kg", "t", "ml", "l", "mm", "cm", "m", "km");

    private static boolean hasChinese(String s) {
        return s != null && s.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /** DTO 上所有「String 且名字以 Unit 结尾」的字段 —— 结构判据, 新字段自动纳入。 */
    private static List<Field> unitFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : ProductionPlanDTO.class.getDeclaredFields()) {
            if (f.getType() == String.class && f.getName().endsWith("Unit")) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        return fields;
    }

    /** 每个单位字段都塞英文码的计划 —— 库里存量就长这样。 */
    private static ProductionPlan planWithEnglishCodes() {
        ProductionPlan plan = new ProductionPlan();
        plan.setPlannedUnit("box");
        plan.setSourceDisplayUnit("box");
        plan.setWorkflowOutputUnit("case");
        Map<String, String> byProduct = new LinkedHashMap<>();
        byProduct.put("PT-1", "box");
        byProduct.put("PT-2", "pcs");
        plan.setWorkflowOutputUnitsByProduct(byProduct);

        ProductType pt = new ProductType();
        pt.setUnit("pcs");
        plan.setProductType(pt);
        return plan;
    }

    @Test
    @DisplayName("阳性对照: 反射真的找到了单位字段, 且映射后全都有值 —— 否则下面是空转")
    void reflectionFindsPopulatedUnitFields() throws IllegalAccessException {
        List<Field> fields = unitFields();
        assertTrue(fields.size() >= 4,
                "只反射到 " + fields.size() + " 个 *Unit 字段 (" + fields.stream().map(Field::getName).toList()
                        + ") —— DTO 改结构了? 这道闸正在读空气");

        ProductionPlanDTO dto = mapper.toDTO(planWithEnglishCodes());
        for (Field f : fields) {
            assertNotNull(f.get(dto),
                    "字段 " + f.getName() + " 映射后是 null —— 它没被 mapper 填充, "
                            + "下面那条「必须是中文」的断言对它恒真");
        }
        assertNotNull(dto.getWorkflowOutputUnitsByProduct(), "按产品的单位表没被填充");
    }

    @Test
    @DisplayName("🔴 每个 *Unit 字段出口后都是中文 —— 不许把 box / pcs 丢给用户")
    void everyUnitFieldIsChineseAfterMapping() throws IllegalAccessException {
        ProductionPlanDTO dto = mapper.toDTO(planWithEnglishCodes());
        List<String> leaked = new ArrayList<>();
        for (Field f : unitFields()) {
            String value = (String) f.get(dto);
            if (!hasChinese(value) && !INTERNATIONAL_SYMBOLS.contains(value)) {
                leaked.add(f.getName() + " = \"" + value + "\"");
            }
        }
        assertTrue(leaked.isEmpty(),
                "这些字段把英文单位码丢给了用户 —— 请让它们经 UnitDisplayNames.display() 出口: " + leaked);
    }

    @Test
    @DisplayName("🔴 按产品的单位表也要翻 —— jsonb 里同样存的是码")
    void perProductUnitMapIsTranslated() {
        Map<String, String> byProduct = mapper.toDTO(planWithEnglishCodes())
                .getWorkflowOutputUnitsByProduct();
        assertEquals("盒", byProduct.get("PT-1"));
        assertEquals("件", byProduct.get("PT-2"));
        assertEquals(2, byProduct.size(), "键被改动了 —— 出口只该翻值, 不该动 key");
    }

    @Test
    @DisplayName("这次实测的那条: sourceDisplayUnit 不再是 box")
    void theReportedFieldIsFixed() {
        ProductionPlan plan = new ProductionPlan();
        plan.setSourceDisplayUnit("box");
        assertEquals("盒", mapper.toDTO(plan).getSourceDisplayUnit());
    }

    @Test
    @DisplayName("阴性对照: 已经是中文的原样保留, 计量符号不许被翻掉, null 仍是 null")
    void chineseAndSymbolsAndNullSurviveUntouched() {
        ProductionPlan chinese = new ProductionPlan();
        chinese.setPlannedUnit("盒");
        chinese.setSourceDisplayUnit("托盘");
        assertEquals("盒", mapper.toDTO(chinese).getPlannedUnit());
        assertEquals("托盘", mapper.toDTO(chinese).getSourceDisplayUnit());

        ProductionPlan kg = new ProductionPlan();
        kg.setPlannedUnit("kg");
        kg.setWorkflowOutputUnit("t");
        assertEquals("kg", mapper.toDTO(kg).getPlannedUnit(), "计量符号被翻了, 与既有取舍冲突");
        assertEquals("t", mapper.toDTO(kg).getWorkflowOutputUnit(), "t 是吨的法定符号, 不该翻");

        // fail-closed: 缺单位就是缺单位, ⛔ 不许出口顺手编一个出来
        // (ProductionPlan.workflowOutputUnitsByProduct 字段初始化成空 map, 出口原样保留空,
        //  不因为「翻译了一下」变成 null —— 这是改动前后一致的既有行为)
        ProductionPlan empty = new ProductionPlan();
        assertEquals(null, mapper.toDTO(empty).getPlannedUnit());
        assertTrue(mapper.toDTO(empty).getWorkflowOutputUnitsByProduct().isEmpty());
    }
}
