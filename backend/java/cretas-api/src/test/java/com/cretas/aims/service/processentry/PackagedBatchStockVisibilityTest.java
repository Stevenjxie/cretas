package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.service.processentry.impl.ProductionStockAllocationServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 按<b>包装单位</b>存量的批次要在「过期提醒 / 别处还有」里看得见, 但<b>不进可投量</b>。
 *
 * <p><b>缺陷现场</b> (F006, 2026-08-03 prod 实测): SHH0713羊排 原料仓
 * {@code MT-20260716-3809} 存着 <b>10 箱</b>, 而 {@code material_packaging_specs} 明确写着
 * 1 箱 = 10 kg —— 也就是 <b>100 kg</b>。但 {@code unitMatches} 对 kg 输入只认 g/kg,
 * 「箱」归一后还是「箱」→ <b>整批被跳过</b>: 既不进可投量, 也不进过期提醒, 更不进
 * 「原料仓另有」。不是显示成 0, 是根本不在集合里, 仓管无从知道那批货存在。
 *
 * <p><b>为什么展示放开而分配不放开</b>: 扣减侧 {@code kgToStorageQuantity} 只做 g↔kg,
 * 对「箱」是原样返回 —— 把 100kg 的分配落到只有 10 箱的批次上会<b>超扣 10 倍</b>。
 * 所以这个不对称是<b>有意的</b>: 让仓管看得见「有这批货但当前不能直接投」,
 * 好过让它彻底消失; 真要能投, 得先让扣减侧也走包装规格反算。
 *
 * <p>本测试用<b>真实的</b> {@link UnitContractServiceImpl} + 真实的包装规格实体, 只 mock 仓储 ——
 * 换算规则住在那个实现里, 自造一份假换算就测不出「按真规格会发生什么」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("按包装单位存量的批次 — 展示看得见, 可投量不含")
class PackagedBatchStockVisibilityTest {

    private static final String FACTORY = "F006";
    private static final String MATERIAL = "RMT_YANGPAI";

    @Mock private com.cretas.aims.repository.config.UnitOfMeasurementRepository unitRepository;
    @Mock private com.cretas.aims.repository.unit.ProductUnitConversionRepository conversionRepository;
    @Mock private com.cretas.aims.repository.MaterialPackagingHierarchyRepository hierarchyRepository;
    @Mock private com.cretas.aims.repository.material.MaterialPackagingSpecRepository specRepository;
    @Mock private com.cretas.aims.repository.MaterialBatchRepository materialBatchRepository;
    @Mock private com.cretas.aims.repository.ProductionInputAllocationRepository allocationRepository;
    @Mock private com.cretas.aims.repository.ProductionPlanRepository productionPlanRepository;
    @Mock private com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;
    @Mock private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    private ProductionStockAllocationServiceImpl service;

    @BeforeEach
    void setUp() {
        UnitContractService contract = new UnitContractServiceImpl(
                unitRepository, conversionRepository, hierarchyRepository, specRepository);
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());
        when(allocationRepository.sumPendingQuantityByMaterialBatchId(anyString(), any()))
                .thenReturn(BigDecimal.ZERO);
        service = new ProductionStockAllocationServiceImpl(
                materialBatchRepository, allocationRepository, productionPlanRepository,
                warehouseResolver, contract, rawMaterialTypeRepository);
    }

    /** 给该物料登记 1 箱 = 10 kg (与 prod 上 SHH0713羊排 的规格一致)。 */
    private void registerBoxSpec() {
        MaterialPackagingSpec spec = new MaterialPackagingSpec();
        spec.setId("SPEC-1");
        spec.setFactoryId(FACTORY);
        spec.setMaterialTypeId(MATERIAL);
        spec.setPackageUnit("箱");
        spec.setBaseUnit("kg");
        spec.setConversionFactor(new BigDecimal("10"));
        when(specRepository
                .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                        anyString(), anyString()))
                .thenReturn(List.of(spec));
    }

    private MaterialBatch boxBatch(BigDecimal boxes) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("B-1");
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("MT-20260716-3809");
        batch.setMaterialTypeId(MATERIAL);
        batch.setQuantityUnit("箱");
        batch.setReceiptQuantity(boxes);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        return batch;
    }

    private Object call(String method, Class<?>[] types, Object... args) throws Throwable {
        Method m = ProductionStockAllocationServiceImpl.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        try {
            return m.invoke(service, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private boolean strictMatch(MaterialBatch batch) throws Throwable {
        return (boolean) call("unitMatches",
                new Class<?>[]{String.class, MaterialBatch.class, String.class, boolean.class},
                FACTORY, batch, "kg", true);
    }

    private boolean displayMatch(MaterialBatch batch) throws Throwable {
        return (boolean) call("unitMatchesForDisplay",
                new Class<?>[]{String.class, MaterialBatch.class, String.class, boolean.class},
                FACTORY, batch, "kg", true);
    }

    @Test
    @DisplayName("🔴 展示侧看得见 —— 10 箱按 1箱=10kg 折成 100kg")
    void displayIncludesPackagedBatch() throws Throwable {
        registerBoxSpec();
        MaterialBatch batch = boxBatch(new BigDecimal("10"));

        assertThat(displayMatch(batch)).as("过期提醒/别处还有 必须报出这批").isTrue();

        BigDecimal available = (BigDecimal) call("batchAvailable",
                new Class<?>[]{String.class, MaterialBatch.class, boolean.class},
                FACTORY, batch, true);
        assertThat(available).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("🔴 可投量不含 —— 放进去会让 100kg 的分配去扣只有 10 箱的批次, 超扣 10 倍")
    void allocationExcludesPackagedBatch() throws Throwable {
        registerBoxSpec();
        assertThat(strictMatch(boxBatch(new BigDecimal("10"))))
                .as("扣减侧 kgToStorageQuantity 只会 g↔kg, 现在还折不回「箱」")
                .isFalse();
    }

    @Test
    @DisplayName("🔴 这个不对称是有意的 —— 同一批次: 展示 true / 分配 false")
    void displayIsWiderThanAllocationOnPurpose() throws Throwable {
        registerBoxSpec();
        MaterialBatch batch = boxBatch(new BigDecimal("10"));
        assertThat(displayMatch(batch)).isTrue();
        assertThat(strictMatch(batch)).isFalse();
    }

    @Test
    @DisplayName("没有包装规格的批次仍然两边都不认 —— 没有换算就不该猜")
    void withoutSpecStillInvisible() throws Throwable {
        when(specRepository
                .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                        anyString(), anyString()))
                .thenReturn(List.of());
        MaterialBatch batch = boxBatch(new BigDecimal("10"));

        assertThat(displayMatch(batch)).isFalse();
        assertThat(strictMatch(batch)).isFalse();
    }

    @Test
    @DisplayName("零回归: kg / g 批次照旧, g 仍折成 kg")
    void massBatchesUnchanged() throws Throwable {
        MaterialBatch kg = boxBatch(new BigDecimal("7"));
        kg.setQuantityUnit("kg");
        assertThat(strictMatch(kg)).isTrue();
        assertThat(call("batchAvailable",
                new Class<?>[]{String.class, MaterialBatch.class, boolean.class}, FACTORY, kg, true))
                .isEqualTo(new BigDecimal("7"));

        MaterialBatch g = boxBatch(new BigDecimal("2500"));
        g.setQuantityUnit("g");
        assertThat(strictMatch(g)).isTrue();
        assertThat((BigDecimal) call("batchAvailable",
                new Class<?>[]{String.class, MaterialBatch.class, boolean.class}, FACTORY, g, true))
                .isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("折不出 kg 且被当成质量批次取数时, 仍抛明确错误而不是静默算错")
    void unconvertibleStillThrows() throws Throwable {
        when(specRepository
                .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                        anyString(), anyString()))
                .thenReturn(List.of());
        MaterialBatch batch = boxBatch(new BigDecimal("10"));

        assertThatThrownBy(() -> call("massStockToKg",
                new Class<?>[]{String.class, MaterialBatch.class}, FACTORY, batch))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("不能换算为 kg");
    }
}
