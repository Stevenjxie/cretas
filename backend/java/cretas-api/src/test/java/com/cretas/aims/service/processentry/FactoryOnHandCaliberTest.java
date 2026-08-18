package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProductionStockAllocationServiceImpl;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.unit.UnitDimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸 —— 「全厂在手」这个数必须按<b>事实</b>算, 不是按「系统能不能自动扣」算。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18)</h2>
 *
 * 短缺提示会根据全厂在手量二选一地给出下一步动作:
 * <pre>
 * 在手 &gt; 可投  →「全厂在手 100kg，只是还没领到生产仓 → 去『生产管理 → 领料』」
 * 在手 = 0      →「全厂在手也是 0 → 需要先采购入库，找仓管补料没用」
 * </pre>
 *
 * 而在手量原来用的是**严格版** {@code unitMatches} —— 它刻意不认「按箱/袋存量」的批次
 * (因为扣减侧对「箱」原样返回, 放进可投量会超扣 10 倍)。于是:
 *
 * <p>原料仓实实在在躺着 10 箱 × 10kg/箱 = <b>100kg</b>, 系统却说
 * 「全厂在手也是 0 → 需要先采购入库」, <b>把人支去下采购单</b>。
 *
 * <p>⚠️ 这是上一轮加成因标注时自己造出来的: 原来那句话是模糊的
 * (「请联系仓管补料」), 改完变成一句<b>确定的错话</b> —— 比模糊更糟,
 * 因为用户会照着它去做。
 *
 * <h2>口径 (这道闸钉的就是这一条)</h2>
 * <ul>
 *   <li><b>可投量</b>回答「现在能自动扣哪些批次」⇒ 严格版, 不许放宽 (会超扣)</li>
 *   <li><b>全厂在手</b>回答「货在不在这个厂里」⇒ 展示版, 必须认包装规格</li>
 * </ul>
 * 两个数<b>刻意不同口径</b>, 因为它们回答的不是同一个问题。
 * 展示版 ⊇ 严格版且全厂 ⊇ 生产仓 ⇒ 在手恒 ≥ 可投, 成因比较不会翻负。
 */
class FactoryOnHandCaliberTest {

    private static final String F = "F006";
    private static final String MT = "RMT_TEST_LAMB";

    private MaterialBatchRepository batchRepo;
    private ProductionInputAllocationRepository allocationRepo;
    private UnitContractService unitContract;
    private ProductionStockAllocationServiceImpl service;

    @BeforeEach
    void setUp() {
        batchRepo = mock(MaterialBatchRepository.class);
        allocationRepo = mock(ProductionInputAllocationRepository.class);
        unitContract = mock(UnitContractService.class);

        // 契约认得 kg 是质量单位; 「箱」不是质量/体积 ⇒ canonicalNativeUnit 回落字面
        when(unitContract.describe(anyString(), anyString())).thenReturn(Optional.empty());
        when(unitContract.describe(anyString(), eq("kg"))).thenReturn(Optional.of(
                new CanonicalUnit("kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 3)));

        // 1 箱 = 10 kg —— 与 prod 的 material_packaging_specs 同形
        when(unitContract.convert(any(BigDecimal.class), any(UnitConversionContext.class)))
                .thenAnswer(inv -> {
                    BigDecimal qty = inv.getArgument(0);
                    UnitConversionContext ctx = inv.getArgument(1);
                    if ("箱".equals(ctx.fromUnit()) && "kg".equals(ctx.toUnit())) {
                        return new UnitConversionResult(UnitConversionStatus.CONVERTED,
                                qty.multiply(new BigDecimal("10")), "箱", "kg",
                                List.of("箱", "kg"), null, null, null);
                    }
                    return new UnitConversionResult(UnitConversionStatus.PRODUCT_CONVERSION_MISSING,
                            null, ctx.fromUnit(), ctx.toUnit(), List.of(), null, null, "no spec");
                });

        when(allocationRepo.sumPendingQuantityByMaterialBatchId(anyString(), anyString()))
                .thenReturn(null);   // nz() → 0

        service = new ProductionStockAllocationServiceImpl(
                batchRepo, allocationRepo,
                mock(ProductionPlanRepository.class), mock(WarehouseResolver.class),
                unitContract, mock(RawMaterialTypeRepository.class));
    }

    private static MaterialBatch batch(String number, String unit, String receipt) {
        MaterialBatch b = new MaterialBatch();
        b.setId("id-" + number);
        b.setBatchNumber(number);
        b.setFactoryId(F);
        b.setMaterialTypeId(MT);
        b.setQuantityUnit(unit);
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        return b;
    }

    private BigDecimal factoryOnHand(String inputUnit, boolean massInput, MaterialBatch... batches) {
        when(batchRepo.findAvailableBatchesFEFO(F, MT)).thenReturn(List.of(batches));
        return ReflectionTestUtils.invokeMethod(
                service, "factoryOnHandFor", F, MT, inputUnit, massInput);
    }

    @Test
    @DisplayName("阳性对照: kg 批次要数得到 —— 否则下面的断言全变成恒真")
    void massBatchIsCounted() {
        BigDecimal onHand = factoryOnHand("kg", true, batch("MT-KG", "kg", "5"));
        assertEquals(0, onHand.compareTo(new BigDecimal("5")),
                "连 kg 批次都没数到, 这道闸在读空气: 实际 " + onHand);
    }

    @Test
    @DisplayName("🔴 主断言: 按「箱」存量的批次也是在手货 —— 10 箱 × 10kg = 100kg, 不许说成 0")
    void packagedBatchCountsAsOnHand() {
        BigDecimal onHand = factoryOnHand("kg", true, batch("MT-BOX", "箱", "10"));
        assertTrue(onHand.signum() > 0,
                "10 箱货躺在原料仓, 在手量却算成 " + onHand
                        + " ⇒ 提示会说「全厂在手也是 0 → 需要先采购入库」, 把人支去下采购单");
        assertEquals(0, onHand.compareTo(new BigDecimal("100")),
                "换算率没按规格算 (1 箱 = 10kg): 实际 " + onHand);
    }

    @Test
    @DisplayName("两类批次并存时相加, 且在手恒 ≥ 可投 (成因比较不会翻负)")
    void mixedBatchesSumUp() {
        BigDecimal onHand = factoryOnHand("kg", true,
                batch("MT-KG", "kg", "5"), batch("MT-BOX", "箱", "10"));
        assertEquals(0, onHand.compareTo(new BigDecimal("105")), "实际 " + onHand);
    }

    @Test
    @DisplayName("阴性对照: 非质量投料仍按字面严格匹配 —— 包装折算只在 kg 侧放宽")
    void nonMassInputStaysStrict() {
        // 投料单位「片」, 批次是「箱」: 不同字面, 且 massInput=false ⇒ 不该被折算进来
        BigDecimal onHand = factoryOnHand("片", false, batch("MT-BOX", "箱", "10"));
        assertEquals(0, onHand.compareTo(BigDecimal.ZERO),
                "非质量投料被跨单位折算了, 会把「箱」当成「片」相加: 实际 " + onHand);
    }

    @Test
    @DisplayName("没有包装规格的批次仍然不算在手 —— 认不出就不认, 不许兜底成 1:1")
    void unconvertiblePackagedBatchIsNotCounted() {
        // 「桶」在 stub 里没有规格 ⇒ convert 返回 PRODUCT_CONVERSION_MISSING
        BigDecimal onHand = factoryOnHand("kg", true, batch("MT-PAIL", "桶", "10"));
        assertEquals(0, onHand.compareTo(BigDecimal.ZERO),
                "没配规格却折出了数, 那是硬折 (把 10 桶当成 10kg): 实际 " + onHand);
    }
}
