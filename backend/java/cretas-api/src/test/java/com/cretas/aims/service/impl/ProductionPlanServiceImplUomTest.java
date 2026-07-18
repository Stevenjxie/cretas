package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
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
 * T144/N1 — ProductionPlanServiceImpl.validateMaterialStockSufficient (B1) 称重单位库存预警.
 *
 * <p><b>修正 T143 的箱因子模型:</b> 原料<b>称重入库</b> — 权威库存量是 kg (称重值),
 * 库存校验以 {@link com.cretas.aims.entity.MaterialBatch#getQuantityUnit()} (e.g. kg) 为比较口径,
 * <b>不是</b> {@code RawMaterialType.unit} (箱, 仅采购/展示标签). BOM 克(g) 与 kg 走 converter 的
 * g↔kg 路径 → CONVERTED → 不再误报"原料不足", 也不需要装箱规格 / 409 摩擦.
 *
 * <ul>
 *   <li>猪舌 case (headline): BOM 217g, 库存 200 <b>kg</b> (批次单位 kg) → 0.217kg ≤ 200kg 充足 →
 *       不抛, 不 409 (验证称重材料的误报已消除)</li>
 *   <li>同单位 (个 vs 个) → 直接比较</li>
 *   <li>N1 后真正不可换算维度 (个 BOM vs kg 库存, 无 g↔kg 桥) → 仅预警, 不阻塞开工</li>
 * </ul>
 */
@DisplayName("T144/N1: ProductionPlanServiceImpl B1 — 称重批次单位(kg) g↔kg 库存预警")
class ProductionPlanServiceImplUomTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PT-ZHUSHE-001";
    private static final String MAT_ZHUSHE = "MT-PORK-TONGUE-001";

    private ProductionPlanServiceImpl newService(MaterialBatchRepository batchRepo,
                                                 BomRecipeItemRepository bomRecipeItemRepository,
                                                 MaterialUomConverter converter,
                                                 RawMaterialTypeRepository matRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == MaterialBatchRepository.class) args[i] = batchRepo;

            else args[i] = null;
        }
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        inject(svc, "bomRecipeItemRepository", bomRecipeItemRepository);
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

    private BomRecipeItem bom(String unit, BigDecimal stdQty, BigDecimal yield) {
        BomRecipeItem b = new BomRecipeItem();
        b.setMaterialTypeId(MAT_ZHUSHE);
        b.setMaterialName("冷冻猪舌");
        b.setStandardQuantity(stdQty);
        b.setYieldRate(yield);
        b.setUnit(unit);
        return b;
    }

    private MaterialUomConverter converterWith(RawMaterialTypeRepository matRepo,
                                               MaterialPackagingHierarchyRepository pkgRepo) {
        return new MaterialUomConverter(pkgRepo, matRepo,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
    }

    @Test
    @DisplayName("猪舌(headline): BOM 217g vs 库存 200kg(称重批次单位) → g↔kg 换算后充足 → 不抛 / 无 409")
    void zhushe_weighedKgStock_noFalseShortage() throws Throwable {
        BomRecipeItemRepository bomRecipeItemRepository = mock(BomRecipeItemRepository.class);
        // 217g/份, yield 100 → 217g/份, 1000份 = 217000g = 217kg
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("g", new BigDecimal("217"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        // T144: 库存 200 kg (称重批次单位 = prod 真实数据 冷冻猪舌 receipt_quantity=200 quantity_unit=kg)
        when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(new BigDecimal("200"));
        when(batchRepo.findStockUnitsByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(List.of("kg"));

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("冷冻猪舌");
        m.setUnit("箱");  // 误导标签 — 不再用于比较
        m.setIsAbacaPackaging(false);
        lenient().when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        // 装箱规格不再用于称重原料库存校验; 不配置也无影响.
        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        lenient().when(pkgRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomRecipeItemRepository, conv, matRepo);

        // 217000g → 217kg ≤ 200kg? 217 > 200 → 实际短缺. 用 800份避免短缺干扰: 217×800=173600g=173.6kg ≤ 200kg.
        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("800"))));
        assertThat(t).as("g↔kg 换算后库存充足, 不应抛异常 / 不应 409").isNull();
    }

    @Test
    @DisplayName("N1: 称重原料 BOM-g vs 库存-kg 短缺 → 仅预警, 不抛 409")
    void zhushe_weighedKgStock_shortageWarnsOnly() throws Throwable {
        BomRecipeItemRepository bomRecipeItemRepository = mock(BomRecipeItemRepository.class);
        // 217g × 1000份 = 217kg > 200kg 库存 → 真实短缺 (但走普通缺口路径, 不是 UOM 409)
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("g", new BigDecimal("217"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(new BigDecimal("200"));
        when(batchRepo.findStockUnitsByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(List.of("kg"));

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("冷冻猪舌");
        m.setUnit("箱");
        m.setIsAbacaPackaging(false);
        lenient().when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        lenient().when(pkgRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomRecipeItemRepository, conv, matRepo);

        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("1000"))));
        assertThat(t).as("N1 开工无条件化: 真实短缺只记录预警, 不应抛异常").isNull();
    }

    @Test
    @DisplayName("同单位 (个 vs 个, 包材 吸塑盒): 直接比较, 充足不抛")
    void packaging_sameUnit_directCompare() throws Throwable {
        BomRecipeItemRepository bomRecipeItemRepository = mock(BomRecipeItemRepository.class);
        // 1 个/份 × 500份 = 500 个; 库存 1000 个 → 充足
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("个", new BigDecimal("1"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(new BigDecimal("1000"));
        when(batchRepo.findStockUnitsByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(List.of("个"));

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("吸塑盒");
        m.setUnit("个");
        m.setIsAbacaPackaging(false);
        lenient().when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        lenient().when(pkgRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomRecipeItemRepository, conv, matRepo);

        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("500"))));
        assertThat(t).as("同单位充足, 不应抛异常").isNull();
    }

    @Test
    @DisplayName("N1: BOM 个 vs 库存 kg (维度不可换算) → 仅预警, 不抛 409")
    void incompatibleDimension_warnsOnly() throws Throwable {
        BomRecipeItemRepository bomRecipeItemRepository = mock(BomRecipeItemRepository.class);
        // BOM 单位 = 个, 库存批次单位 = kg, 无 个↔kg 维度换算桥 → UNCONVERTIBLE
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(bom("个", new BigDecimal("2"), new BigDecimal("100"))));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.sumAvailableQuantityByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(new BigDecimal("200"));
        when(batchRepo.findStockUnitsByMaterialType(FACTORY, MAT_ZHUSHE))
                .thenReturn(List.of("kg"));

        RawMaterialTypeRepository matRepo = mock(RawMaterialTypeRepository.class);
        RawMaterialType m = new RawMaterialType();
        m.setName("冷冻猪舌");
        m.setUnit("箱");
        m.setIsAbacaPackaging(false);  // 非抄码 → 走 UNCONVERTIBLE 而非 ABACA_SKIP
        when(matRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));

        MaterialPackagingHierarchyRepository pkgRepo = mock(MaterialPackagingHierarchyRepository.class);
        when(pkgRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());

        MaterialUomConverter conv = converterWith(matRepo, pkgRepo);
        ProductionPlanServiceImpl svc = newService(batchRepo, bomRecipeItemRepository, conv, matRepo);

        Throwable t = catchThrowable(() -> callValidate(svc, plan(new BigDecimal("1000"))));
        assertThat(t).as("N1 开工无条件化: 单位不可换算只记录预警, 不应阻断开工").isNull();
    }
}
