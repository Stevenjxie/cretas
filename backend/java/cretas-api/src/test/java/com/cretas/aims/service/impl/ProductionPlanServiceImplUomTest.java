package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T143 — ProductionPlanServiceImpl.validateMaterialStockSufficient (B1) 单位换算测试.
 *
 * <p>用反射调 private B1 校验, 隔离单位换算逻辑:
 * <ul>
 *   <li>猪舌 case: BOM 217g/份 × 1000份 = 217000g, 库存 200箱 × 10kg/箱 = 2000kg=2000000g →
 *       换算到箱: 21.7箱 < 200箱 充足 → 不抛 (验证无误报"原料不足")</li>
 *   <li>非抄码 + 单位不同 + 无装箱规格 → 抛 409 MATERIAL_UOM_UNCONFIGURED (fail-loud)</li>
 *   <li>抄码料 → 跳过校验 (不抛, 不阻断)</li>
 * </ul>
 */
@DisplayName("T143: ProductionPlanServiceImpl B1 — 箱↔kg 库存校验 + fail-loud + 抄码跳过")
class ProductionPlanServiceImplUomTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PT-ZHUSHE-001";
    private static final String MAT_ZHUSHE = "MT-PORK-TONGUE-001";

    private ProductionPlanServiceImpl newService(MaterialBatchRepository batchRepo,
                                                 BomService bomService,
                                                 MaterialUomConverter converter,
                                                 RawMaterialTypeRepository matRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == MaterialBatchRepository.class) args[i] = batchRepo;
            else if (types[i] == BomService.class) args[i] = bomService;
            else args[i] = null;
        }
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        inject(svc, "materialUomConverter", converter);
        inject(svc, "rawMaterialTypeRepository", matRepo);
        return svc;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = ProductionPlanServiceImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void callValidate(ProductionPlanServiceImpl svc, ProductionPlan plan) throws Throwable {
        Method m = ProductionPlanServiceImpl.class.getDeclaredMethod(
                "validateMaterialStockSufficient", String.class, ProductionPlan.class);
        m.setAccessible(true);
        try {
            m.invoke(svc, FACTORY, plan);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private ProductionPlan plan(BigDecimal qty) {
        ProductionPlan p = new ProductionPlan();
        p.setId("PLAN-1");
        p.setFactoryId(FACTORY);
        p.setProductTypeId(PRODUCT);
        p.setPlannedQuantity(qty);
        return p;
    }

    private BomItem bom(String unit, BigDecimal stdQty, BigDecimal yield) {
        BomItem b = new BomItem();
        b.setMaterialTypeId(MAT_ZHUSHE);
        b.setMaterialName("冷冻猪舌");
        b.setStandardQuantity(stdQty);
        b.setYieldRate(yield);
        b.setUnit(unit);
        return b;
    }

    private MaterialUomConverter converterWith(RawMaterialTypeRepository matRepo,
                                               MaterialPackagingHierarchyRepository pkgRepo) {
        return new MaterialUomConverter(pkgRepo, matRepo, new UnitConversionService());
    }

    @Test
    @DisplayName("猪舌: BOM g 需求换算到箱后充足 → 不抛 (无误报原料不足)")
    void zhushe_sufficientAfterConversion() throws Throwable {
        BomService bomService = mock(BomService.class);
        // 200g/份, yield 100 → 200g/份, 1000份 = 200000g = 200kg
        when(bomService.getBomItemsByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("g", new BigDecimal("200"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        // 库存 200 箱 (库存单位)
        when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(new BigDecimal("200"));

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("冷冻猪舌");
        m.setUnit("箱");
        m.setIsAbacaPackaging(false);
        lenient().when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        MaterialPackagingHierarchy h = new MaterialPackagingHierarchy();
        h.setLevel1Unit("kg");
        h.setLevel1PerLevel2(new BigDecimal("10"));  // 10 kg/箱
        h.setLevel2Unit("箱");
        when(pkgRepo.findByMaterialTypeId(MAT_ZHUSHE)).thenReturn(Optional.of(h));

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomService, conv, matRepo);

        // 200000g → 200kg → ÷10 = 20箱 < 200箱 充足. 之前不换算: 200000(当箱) > 200 → 误报不足.
        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("1000"))));
        assertThat(t).as("换算后库存充足, 不应抛异常").isNull();
    }

    @Test
    @DisplayName("非抄码 + 单位不同 + 无装箱规格 → 409 MATERIAL_UOM_UNCONFIGURED (fail-loud)")
    void missingHierarchy_failsLoud() throws Throwable {
        BomService bomService = mock(BomService.class);
        when(bomService.getBomItemsByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("g", new BigDecimal("200"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(new BigDecimal("200"));

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("冷冻猪舌");
        m.setUnit("箱");
        m.setIsAbacaPackaging(false);
        when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        when(pkgRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());  // 无装箱规格

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomService, conv, matRepo);

        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("1000"))));
        assertThat(t).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) t;
        assertThat(be.getErrorCode()).isEqualTo("MATERIAL_UOM_UNCONFIGURED");
        assertThat(be.getCode()).isEqualTo(409);
        assertThat(be.getMessage()).contains("冷冻猪舌").contains("装箱规格");
    }

    @Test
    @DisplayName("抄码料 → 跳过校验 (不抛, 不阻断)")
    void abaca_skipped() throws Throwable {
        BomService bomService = mock(BomService.class);
        when(bomService.getBomItemsByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("g", new BigDecimal("200"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        // 库存 0 箱 — 若不跳过会误报不足; 抄码跳过则不抛
        lenient().when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(BigDecimal.ZERO);

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("牛肉");
        m.setUnit("箱");
        m.setIsAbacaPackaging(true);
        when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        lenient().when(pkgRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomService, conv, matRepo);

        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("1000"))));
        assertThat(t).as("抄码料应跳过库存校验, 不抛异常").isNull();
    }
}
