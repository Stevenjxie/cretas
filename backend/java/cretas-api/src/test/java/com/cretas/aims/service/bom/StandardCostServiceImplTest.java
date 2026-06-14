package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.bom.impl.StandardCostServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 六扇门 D1: StandardCostService 同口径标准成本解析单元测试.
 *
 * <p>核心断言: 有研发预估人工 → 标准成本含人工 (料+人工), 同口径可比;
 * 无研发预估人工 → 标准侧诚实 null + laborIncluded=false (caller 跳过报警, 不误报)。
 */
@ExtendWith(MockitoExtension.class)
class StandardCostServiceImplTest {

    @Mock
    private BomRecipeService bomRecipeService;
    @Mock
    private QuotationTaskRepository quotationTaskRepository;

    @InjectMocks
    private StandardCostServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String PT = "PT-猪舌";

    private BomRecipe recipe(BigDecimal materialCost, BigDecimal outputQty, String outputUnit) {
        BomRecipe r = new BomRecipe();
        r.setFactoryId(FACTORY);
        r.setProductTypeId(PT);
        r.setTotalMaterialCost(materialCost);
        // legacy bug: totalCost = 料 only (labor/overhead 全库 0 调用)
        r.setTotalCost(materialCost);
        r.setOutputQuantityPerUnit(outputQty);
        r.setOutputUnit(outputUnit);
        return r;
    }

    private QuotationTask quote(BigDecimal laborPerKg) {
        QuotationTask t = new QuotationTask();
        t.setFactoryId(FACTORY);
        t.setProductTypeId(PT);
        t.setLaborPerKg(laborPerKg);
        return t;
    }

    // ── D1-1: 有研发预估人工 → 标准成本含人工 (料 + 人工), 同口径 ─────────────
    @Test
    void withRdLabor_standardCostIncludesLabor_sameCaliber() {
        // 料标准 = 8.00 元/盒; 单位成品 200g; 研发预估人工 10 元/kg
        // 标准人工/盒 = 10 × (200/1000) = 2.00; 标准总 = 8 + 2 = 10.00
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("200"), "g")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(new BigDecimal("10.00"))));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getMaterialUnitCost()).isEqualByComparingTo("8.00");
        assertThat(std.getLaborUnitCost()).isEqualByComparingTo("2.0000");
        assertThat(std.getTotalUnitCost()).isEqualByComparingTo("10.0000");
        assertThat(std.isLaborIncluded()).isTrue();
        assertThat(std.getCaliberHint()).contains("含人工");
    }

    // ── D1-2: 无研发预估人工 → 标准侧诚实 null, laborIncluded=false (不误报) ──
    @Test
    void noRdLabor_standardLaborNull_totalNull_notComparable() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("200"), "g")));
        // 无研发报价任务
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.empty());

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getMaterialUnitCost()).isEqualByComparingTo("8.00");
        assertThat(std.getLaborUnitCost()).isNull();           // 诚实 null, 不默认 0
        assertThat(std.getTotalUnitCost()).isNull();           // 口径不全 → null, 不只用料
        assertThat(std.isLaborIncluded()).isFalse();
        assertThat(std.getCaliberHint()).contains("口径不全");
    }

    // ── D1-3: 研发任务存在但 laborPerKg=null → 同样诚实 null ──────────────────
    @Test
    void rdTaskExistsButLaborPerKgNull_standardLaborNull() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("200"), "g")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(null)));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getLaborUnitCost()).isNull();
        assertThat(std.getTotalUnitCost()).isNull();
        assertThat(std.isLaborIncluded()).isFalse();
    }

    // ── D1-4: 料标准缺失 (BOM 未定价 → totalMaterialCost null) → 诚实 null ────
    @Test
    void materialNull_totalNull_notComparable() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(null, new BigDecimal("200"), "g")));
        lenient().when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(new BigDecimal("10.00"))));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getMaterialUnitCost()).isNull();
        assertThat(std.getTotalUnitCost()).isNull();
        assertThat(std.isLaborIncluded()).isFalse();
        assertThat(std.getCaliberHint()).contains("料未定价").contains("BOM");
    }

    // ── D1-5: outputUnit = kg → 不除 1000 ───────────────────────────────────
    @Test
    void outputUnitKg_noDivideBy1000() {
        // 单位成品 0.2 kg; 研发预估人工 10 元/kg → 标准人工/盒 = 10 × 0.2 = 2.00
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("0.2"), "kg")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(new BigDecimal("10.00"))));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getLaborUnitCost()).isEqualByComparingTo("2.0000");
        assertThat(std.getTotalUnitCost()).isEqualByComparingTo("10.0000");
        assertThat(std.isLaborIncluded()).isTrue();
    }

    // ── D1-6: outputUnit = 个/件 (非重量) → 无法折算标准人工 → 诚实 null ──────
    @Test
    void outputUnitPiece_cannotConvertLabor_null() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("1"), "个")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(new BigDecimal("10.00"))));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getLaborUnitCost()).isNull();
        assertThat(std.getTotalUnitCost()).isNull();
        assertThat(std.isLaborIncluded()).isFalse();
    }

    // ── D1-7: null 入参 → 安全返回 (永不抛异常, 永不返回 null 对象) ────────────
    @Test
    void nullInputs_safeReturn() {
        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(null, null);
        assertThat(std).isNotNull();
        assertThat(std.getTotalUnitCost()).isNull();
        assertThat(std.isLaborIncluded()).isFalse();
    }

    // ── 修1-A: BOM 查询抛异常 → 不当"缺配置"吞成 null, 而是抛出让调用方可见 ────
    //   用例: 临时 DB 异常不应伪装成"无 ACTIVE BOM 或料未定价"导致静默漏报
    @Test
    void bomQueryThrows_propagatesException_notSilentNull() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenThrow(new RuntimeException("DB connection lost"));

        // resolveStandardUnitCost 应把 BOM 查询失败的异常向上抛,
        // 不能返回 materialUnitCost=null 装作"没有BOM"静默跳过报警
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.resolveStandardUnitCost(FACTORY, PT),
                "BOM 查询异常必须向上抛, 不能静默为 null 导致漏报超支报警");
    }

    // ── 修1-B: 研发报价任务查询抛异常 → 不当"无研发任务"吞成 null, 而是抛出 ──
    //   用例: DB 临时故障不应静默伪装成"产品没有研发任务"导致漏报
    @Test
    void rdTaskQueryThrows_propagatesException_notSilentNull() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("200"), "g")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenThrow(new RuntimeException("RD repo unavailable"));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.resolveStandardUnitCost(FACTORY, PT),
                "研发报价任务查询异常必须向上抛, 不能静默为 null 导致漏报超支报警");
    }

    // ── 修2: outputUnit = 盒/件 → caliberHint 明确说明"单位无法折算→超支报警禁用" ──
    //   用例: F006 某产品 outputUnit="盒", 超支报警对它永久禁用, 必须在 caliberHint 中可见
    @Test
    void outputUnitBox_caliberHintMentionsUnsupportedUnit_notMisleadingNolabor() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("1"), "盒")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(new BigDecimal("10.00"))));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.getLaborUnitCost()).isNull();
        assertThat(std.getTotalUnitCost()).isNull();
        assertThat(std.isLaborIncluded()).isFalse();
        // caliberHint MUST mention the unsupported unit "盒" and that alarm is disabled,
        // NOT just "缺研发预估人工" (which is misleading — the task exists with laborPerKg set)
        assertThat(std.getCaliberHint())
                .as("caliberHint should explain the unit '盒' cannot be converted, not just '缺研发预估人工'")
                .containsAnyOf("盒", "单位", "折算");
    }

    // ── 修2-B: outputUnit = 件 → 同样可见 ───────────────────────────────────────
    @Test
    void outputUnitPiece_caliberHintMentionsUnsupportedUnit() {
        when(bomRecipeService.getCurrentRecipe(FACTORY, PT))
                .thenReturn(Optional.of(recipe(new BigDecimal("8.00"), new BigDecimal("1"), "件")));
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PT))
                .thenReturn(Optional.of(quote(new BigDecimal("10.00"))));

        StandardCostService.StandardUnitCost std = service.resolveStandardUnitCost(FACTORY, PT);

        assertThat(std.isLaborIncluded()).isFalse();
        assertThat(std.getCaliberHint())
                .as("caliberHint should mention unsupported unit or conversion inability")
                .containsAnyOf("件", "单位", "折算");
    }
}
