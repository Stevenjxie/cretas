package com.cretas.aims.service.unit;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 钉住 {@code storageUnit} 的落库口径 (Steve 2026-08-03 拍板「存中文」, 分两步: 先自定义单位)。
 *
 * <p><b>本测试刻意使用真实的 {@link UnitContractServiceImpl}, 只把 4 个仓储 mock 掉。</b>
 * 内置单位表和别名表就住在那个实现里 —— 自己编一份假 catalog 喂给 mock 的
 * {@code UnitContractService}, 就测不出「按真别名表会发生什么」。
 * 2026-08-03 有过实例: 一个新测试用 {@code catalog("盒","box",COUNT)} 自造目录,
 * 因此对 {@code alias("pcs","pcs","件","个","只")} 把「只」塌成 pcs 完全无感, 全绿放行,
 * 而那正是 LIUSHANMEN 2026-07-30 的线上事故 (#1976)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("单位落库口径 storageUnit —— 内置存码 / 自定义存中文 / 多中文写法保字面")
class UnitStorageValueContractTest {

    private static final String FACTORY = "F006";

    @Mock private com.cretas.aims.repository.config.UnitOfMeasurementRepository unitRepository;
    @Mock private com.cretas.aims.repository.unit.ProductUnitConversionRepository conversionRepository;
    @Mock private com.cretas.aims.repository.MaterialPackagingHierarchyRepository hierarchyRepository;
    @Mock private com.cretas.aims.repository.material.MaterialPackagingSpecRepository specRepository;

    private UnitContractService service;

    @BeforeEach
    void setUp() {
        service = new UnitContractServiceImpl(
                unitRepository, conversionRepository, hierarchyRepository, specRepository);
    }

    /** 给工厂登记一个自定义单位 (码是按中文名生成的拼音, 不在内置表里)。 */
    private void registerCustomUnit(String unitCode, String unitName, String category) {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setFactoryId(FACTORY);
        unit.setUnitCode(unitCode);
        unit.setUnitName(unitName);
        unit.setBaseUnit(unitCode);
        unit.setCategory(category);
        unit.setIsActive(true);
        unit.setIsSystem(false);
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of(unit));
    }

    @Nested
    @DisplayName("规则 4: 内置单位存英文码 —— 存量 2400 行不动")
    class BuiltInStaysCode {

        @Test
        @DisplayName("「盒」→ box, 「片」→ slice, 「箱」→ case (中文录入归一成存量用的码)")
        void chinesePackagingCanonicalizesToCode() {
            assertEquals("box", service.storageUnit(FACTORY, "盒"));
            assertEquals("slice", service.storageUnit(FACTORY, "片"));
            assertEquals("case", service.storageUnit(FACTORY, "箱"));
        }

        @Test
        @DisplayName("已是英文码时原样 (box → box), 大小写折平 (KG → kg)")
        void codesPassThrough() {
            assertEquals("box", service.storageUnit(FACTORY, "box"));
            assertEquals("kg", service.storageUnit(FACTORY, "KG"));
        }

        @Test
        @DisplayName("质量/体积照常归一 —— 公斤/千克 同码是对的, 它们之间有恒定换算")
        void massAndVolumeCanonicalize() {
            assertEquals("kg", service.storageUnit(FACTORY, "公斤"));
            assertEquals("kg", service.storageUnit(FACTORY, "千克"));
            assertEquals("g", service.storageUnit(FACTORY, "克"));
            assertEquals("l", service.storageUnit(FACTORY, "升"));
        }
    }

    @Nested
    @DisplayName("规则 2: 一个码挂多个中文写法 → 保用户字面 (#1976 / LIUSHANMEN 2026-07-30)")
    class AmbiguousChineseStaysLiteral {

        @Test
        @DisplayName("🔴「只」必须存「只」—— 存 pcs 或存「件」都是把事故放回来")
        void countUnitStaysLiteral() {
            assertEquals("只", service.storageUnit(FACTORY, "只"),
                    "用户选「只」就该存「只」; 改写成 pcs 会让报工按字面比较时看不见这批货");
            assertNotEquals("pcs", service.storageUnit(FACTORY, "只"));
            assertNotEquals("件", service.storageUnit(FACTORY, "只"),
                    "换成存中文并不能解决塌陷 —— 归一到「件」和归一到 pcs 一样是替工厂断定两者相同");
        }

        @Test
        @DisplayName("「只」「个」「件」三者互不相等 —— 它们共用 pcs 码, 归一即塌陷")
        void countAliasesDoNotCollapse() {
            String zhi = service.storageUnit(FACTORY, "只");
            String ge = service.storageUnit(FACTORY, "个");
            String jian = service.storageUnit(FACTORY, "件");
            assertNotEquals(zhi, ge, "一只不等于一个");
            assertNotEquals(ge, jian, "一个不等于一件");
            assertNotEquals(zhi, jian, "一只不等于一件");
        }

        @Test
        @DisplayName("英文码 pcs 仍存 pcs —— 存量 72 行档案不受影响")
        void englishCodeInAmbiguousGroupStillStored() {
            assertEquals("pcs", service.storageUnit(FACTORY, "pcs"));
        }

        @Test
        @DisplayName("「盒」不在此列 —— box 只挂一个中文写法, 归一是纯翻译")
        void singleChineseWritingIsNotAmbiguous() {
            assertEquals("box", service.storageUnit(FACTORY, "盒"));
        }
    }

    @Nested
    @DisplayName("规则 3: 工厂自定义单位存中文名")
    class CustomUnitStoresChinese {

        @Test
        @DisplayName("🔴「半只」(拼音码 banzhi) 必须存「半只」而不是 banzhi")
        void customUnitStoresDisplayName() {
            registerCustomUnit("banzhi", "半只", "COUNT");
            assertEquals("半只", service.storageUnit(FACTORY, "半只"));
            // ⚠️ 上面那条<b>分辨不出规则 3 是否真跑了</b> —— 工厂目录为空时「半只」也会按规则 1
            // 原样返回「半只」, 结果一模一样。真正能分辨的是<b>从拼音码进来</b>: 只有该自定义单位
            // 确实进了目录, banzhi 才查得到并翻成中文; 目录为空时它只会原样返回 banzhi。
            // (本测试第一版把仓储方法名 mock 错了, 正是这条把假绿抓出来的。)
            assertEquals("半只", service.storageUnit(FACTORY, "banzhi"),
                    "从拼音码进来也该落成中文 —— 存 banzhi 既不可读也不可追溯");
        }

        @Test
        @DisplayName("自定义单位没填中文名时退回码, 不写空")
        void customUnitWithoutNameFallsBackToCode() {
            registerCustomUnit("banzhi", null, "COUNT");
            assertEquals("banzhi", service.storageUnit(FACTORY, "banzhi"));
        }

        @Test
        @DisplayName("工厂给内置码改了显示名, 仍存内置码 —— 存量口径不因改名而漂移")
        void factoryRenamedBuiltInStillStoresCode() {
            registerCustomUnit("box", "小盒", "PACKAGE");
            assertEquals("box", service.storageUnit(FACTORY, "小盒"));
            assertEquals("box", service.storageUnit(FACTORY, "box"));
        }
    }

    @Nested
    @DisplayName("规则 1 与边界")
    class UnknownAndBlank {

        @Test
        @DisplayName("权威表认不出的自由文本原样保留, 不判非法也不小写化")
        void unknownStaysLiteral() {
            assertEquals("两头鲍", service.storageUnit(FACTORY, " 两头鲍 "));
        }

        @Test
        @DisplayName("空/空白 → 空串, 不抛")
        void blankReturnsEmpty() {
            assertEquals("", service.storageUnit(FACTORY, null));
            assertEquals("", service.storageUnit(FACTORY, "   "));
        }
    }
}
