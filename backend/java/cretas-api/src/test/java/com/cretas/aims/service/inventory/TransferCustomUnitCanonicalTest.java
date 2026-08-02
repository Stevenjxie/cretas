package com.cretas.aims.service.inventory;

import com.cretas.aims.service.inventory.impl.TransferServiceImpl;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 调拨单位归一必须覆盖<b>自定义单位</b>(盒/箱/条/片), 不能只归一重量/体积。
 *
 * <p>缺陷现场 (F006, 2026-08-02): 包材「成品盒」物料档案单位是 {@code box}, 而采购入库写进批次的
 * {@code quantity_unit} 是中文「盒」。原 {@code canonicalTransferUnit} 只对 MASS/VOLUME 归一,
 * COUNT 类被 filter 掉 → 走 {@code toLowerCase()} → 「盒」仍是「盒」≠ {@code box}。结果同一物料两条路都堵死:
 * 传 {@code box} 报「可用 0」(存中文的批次被滤掉, 实际有 10000), 传「盒」报「必须选择具体规格」。
 *
 * <p>单位体系分两套: 重量/体积等科学单位有国际换算; 盒/箱/条/片等自定义单位换算因子恒为 1。
 * 两套<b>都</b>需要「中文写法 ↔ 英文码」互认 —— 客户录中文、档案存英文码是常态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransferService — 自定义单位归一 (盒↔box)")
class TransferCustomUnitCanonicalTest {

    private static final String FACTORY = "F006";

    @Mock private UnitContractService unitContractService;

    /**
     * 反射调私有纯函数。{@code unitContractService} 是 {@code @Autowired} <b>字段</b>注入(非构造参数),
     * 所以构造时全传 null 再反射设该字段; 本函数只用到它, 其余依赖不触达。
     */
    private String canonical(String rawUnit) {
        try {
            Constructor<?> ctor = TransferServiceImpl.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object impl = ctor.newInstance(new Object[ctor.getParameterCount()]);

            Field f = TransferServiceImpl.class.getDeclaredField("unitContractService");
            f.setAccessible(true);
            f.set(impl, unitContractService);

            Method m = TransferServiceImpl.class.getDeclaredMethod(
                    "canonicalTransferUnit", String.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(impl, FACTORY, rawUnit);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void catalog(String raw, String code, UnitDimension dimension) {
        // COUNT 类换算因子恒为 1 (自定义单位); MASS/VOLUME 这里也用 1, 本测试只关心 code 归一。
        CanonicalUnit unit = new CanonicalUnit(
                code, dimension, code, java.math.BigDecimal.ONE, raw, 2);
        when(unitContractService.describe(eq(FACTORY), eq(raw))).thenReturn(Optional.of(unit));
    }

    @Test
    @DisplayName("回归: 自定义单位「盒」必须归一成 box (缺陷版本原样返回「盒」)")
    void chineseCountUnitIsCanonicalized() {
        catalog("盒", "box", UnitDimension.COUNT);
        assertEquals("box", canonical("盒"));
    }

    @Test
    @DisplayName("回归: 「片」→ slice, 「箱」→ case —— 盒与箱不可混为一谈")
    void otherCountUnitsCanonicalizeDistinctly() {
        catalog("片", "slice", UnitDimension.COUNT);
        catalog("箱", "case", UnitDimension.COUNT);

        assertEquals("slice", canonical("片"));
        assertEquals("case", canonical("箱"),
                "箱必须归一成 case, 不能和 box 混同 —— 一箱多盒, 混同会算错库存");
    }

    @Test
    @DisplayName("零回归: 重量单位仍照旧归一 (公斤 → kg)")
    void massUnitsStillCanonicalized() {
        catalog("公斤", "kg", UnitDimension.MASS);
        assertEquals("kg", canonical("公斤"));
    }

    @Test
    @DisplayName("已是英文码时原样返回 (box → box)")
    void alreadyCanonicalCodePassesThrough() {
        catalog("box", "box", UnitDimension.COUNT);
        assertEquals("box", canonical("box"));
    }

    @Test
    @DisplayName("权威表认不出的自由文本 → 回落小写, 不抛")
    void unknownUnitFallsBackToLowerCase() {
        when(unitContractService.describe(eq(FACTORY), eq("SomeThing")))
                .thenReturn(Optional.empty());
        assertEquals("something", canonical("SomeThing"));
    }

    @Test
    @DisplayName("空值/空白 → 空串, 不抛")
    void blankUnitReturnsEmpty() {
        assertEquals("", canonical(null));
        assertEquals("", canonical("   "));
    }
}
