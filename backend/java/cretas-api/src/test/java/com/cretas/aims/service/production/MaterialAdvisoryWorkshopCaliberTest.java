package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 计划列表的「原料参考」必须分得清<b>全厂没货</b>和<b>货没到生产仓</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 两端走查实测)</h2>
 *
 * F006 计划 PLAN-1786954657305 (黄油鸡 80 盒)，4 个原料<b>各 200kg 全在原料仓、生产仓 0</b>：
 *
 * <pre>
 * 计划列表「原料参考」  → 「原料库存参考: 暂无缺料预警」   (全厂口径, 200kg 够)
 * 点进逐道录入          → 四行全是「0kg / 原料仓另有 200kg，待调拨入生产仓」  (生产仓口径)
 * </pre>
 *
 * 两个数<b>各自都没算错</b>，错在那句「暂无缺料预警」<b>不报自己的口径</b>：
 * 用户读完「不缺料」，点进去一行都填不了。本仓判据
 * 「报一个数的时候，把这个数是怎么来的一起报」。
 *
 * <h2>这道闸钉三件事</h2>
 * <ol>
 *   <li>全厂够、生产仓不够 ⇒ 出 {@code NOT_IN_WORKSHOP}，说清两个数并指向<b>领料/调拨</b></li>
 *   <li>该条<b>不许</b>说「采购」—— 那是 {@code FACTORY_SHORTAGE} 的下一步，指错会让人白下采购单</li>
 *   <li>量不到生产仓时<b>不许瞎报</b>（没配生产仓的工厂），且汇总语要说明「未核对生产仓」</li>
 * </ol>
 *
 * <p>⚠️ 第 3 条是防误报的那一半 —— 本仓形态 D′「一个会误报的提示比没有提示更糟」。
 * 读不到生产仓时 {@code resolveWorkshopRawStock} 返回 <b>null 而不是 0</b>（形态 A¹⁰：
 * 兜底的 0 会把「我不知道」翻译成「一粒都没有」，于是每个计划都长出一条假的「请先领料」）。
 */
class MaterialAdvisoryWorkshopCaliberTest {

    private static final String F = "F006";
    private static final String PRODUCT = "PT_TEST_BUTTER_CHICKEN";
    private static final String MAT = "RMT_TEST_RAW_A";
    private static final String WORKSHOP = "wh-workshop-id";

    private ProductionPlanServiceImpl service;
    private MaterialBatchRepository batchRepo;
    private WarehouseResolver resolver;

    @BeforeEach
    void setUp() {
        // 这个类有几十个 final 协作者; 本条路径只用到三个, 其余在源码里都是 null-guard 的。
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        BomRecipeItemRepository bomRepo = mock(BomRecipeItemRepository.class);
        when(bomRepo.findCurrentByProduct(eq(F), eq(PRODUCT))).thenReturn(List.of(bomItem()));

        batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.findRawStockUnitsByMaterialType(anyString(), anyString()))
                .thenReturn(List.of("kg"));

        resolver = mock(WarehouseResolver.class);
        when(resolver.resolveWorkshopId(F)).thenReturn(WORKSHOP);

        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "warehouseResolver", resolver);
    }

    private static BomRecipeItem bomItem() {
        BomRecipeItem it = new BomRecipeItem();
        it.setMaterialTypeId(MAT);
        it.setMaterialName("SOP-20260817-01-黄油鸡-原料A");
        it.setStandardQuantity(new BigDecimal("1"));
        it.setYieldRate(new BigDecimal("100"));      // actual = standard / 1 = 1 每单位
        it.setUnit("kg");
        return it;
    }

    private static ProductionPlan plan() {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-test");
        p.setPlanNumber("PLAN-TEST-0818");
        p.setFactoryId(F);
        p.setProductTypeId(PRODUCT);
        p.setPlannedQuantity(new BigDecimal("100"));   // 需要 100kg
        return p;
    }

    /** 直接驱动被测私有方法 —— 公开入口要先查计划, 那是另一件事。 */
    @SuppressWarnings("unchecked")
    private List<ProductionPlanMaterialAdvisoryDTO.Item> advise(String factoryStock, String workshopStock) {
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(F, MAT))
                .thenReturn(new BigDecimal(factoryStock));
        if (workshopStock != null) {
            when(batchRepo.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(F, MAT, WORKSHOP))
                    .thenReturn(new BigDecimal(workshopStock));
        }
        return (List<ProductionPlanMaterialAdvisoryDTO.Item>) ReflectionTestUtils.invokeMethod(
                service, "buildMaterialAdvisoryItems", F, plan());
    }

    @Test
    @DisplayName("阳性对照: 两处都够 → 一条预警都没有 (否则下面的断言全变成恒真)")
    void bothLevelsSufficientYieldsNoWarning() {
        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise("200", "150");
        assertTrue(items.isEmpty(), "两处都够却报了预警: " + items);
    }

    @Test
    @DisplayName("🔴 主断言: 全厂 200kg / 生产仓 0kg → 必须报「货没到生产仓」, 说清两个数")
    void factoryEnoughButWorkshopEmptyIsItsOwnKind() {
        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise("200", "0");
        assertEquals(1, items.size(), "实际 " + items);
        ProductionPlanMaterialAdvisoryDTO.Item it = items.get(0);
        assertEquals(ProductionPlanMaterialAdvisoryDTO.Kind.NOT_IN_WORKSHOP, it.getKind());
        assertEquals(0, it.getWorkshopAvailableQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, it.getAvailableQuantity().compareTo(new BigDecimal("200")));
        String m = it.getMessage();
        assertTrue(m.contains("全厂有 200kg"), "没报全厂在手: " + m);
        assertTrue(m.contains("生产仓只有 0kg"), "没报生产仓在手: " + m);
        assertTrue(m.contains("领料") || m.contains("调拨"), "没指向下一步: " + m);
        // 🔴 阴性对照: 货就在厂里, 绝不能把人支去采购
        assertFalse(m.contains("采购"), "有货没调拨却让人去采购: " + m);
    }

    @Test
    @DisplayName("全厂就不够 → 仍是原来的「缺口」预警, 老行为不许被这次改动带走")
    void factoryShortageKeepsOldBehaviour() {
        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise("30", "0");
        assertEquals(1, items.size(), "实际 " + items);
        ProductionPlanMaterialAdvisoryDTO.Item it = items.get(0);
        assertEquals(ProductionPlanMaterialAdvisoryDTO.Kind.FACTORY_SHORTAGE, it.getKind());
        assertTrue(it.getMessage().startsWith("SOP-20260817-01-黄油鸡-原料A: 需要 100kg"),
                "文案参数顺序被改坏了: " + it.getMessage());
        assertTrue(it.getMessage().contains("缺口 70kg"), it.getMessage());
    }

    @Test
    @DisplayName("🔴 防误报: 没配生产仓时不许报「请先领料」—— 量不到就不说")
    void noWorkshopConfiguredProducesNoFalseWarning() {
        when(resolver.resolveWorkshopId(F)).thenReturn(null);
        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise("200", null);
        assertTrue(items.isEmpty(),
                "没配生产仓却报了预警 —— 那会让每个计划都长出一条假的「请先领料」: " + items);
    }

    @Test
    @DisplayName("🔴 防误报: resolver 抛异常时同样不说 —— 读不到就返回 null, 不兜底成 0")
    void resolverFailureIsNotReadAsZero() {
        when(resolver.resolveWorkshopId(F)).thenThrow(new IllegalStateException("未配置"));
        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise("200", null);
        assertTrue(items.isEmpty(), "resolver 炸了却当成生产仓 0: " + items);
    }

    // ================================================================
    // 「没算成的那部分」—— 一条预警都没有, 不等于每一行都算过
    // ================================================================

    /** BOM 里没有标准用量的行 —— 算不出需求量, 只能跳过。 */
    private static BomRecipeItem bomItemWithoutStandardQty(String name) {
        BomRecipeItem it = new BomRecipeItem();
        it.setMaterialTypeId("RMT_NO_QTY");
        it.setMaterialName(name);
        it.setUnit("kg");
        return it;                                  // standardQuantity 保持 null
    }

    private String summaryWith(java.util.List<BomRecipeItem> items, String factoryStock) {
        BomRecipeItemRepository repo = mock(BomRecipeItemRepository.class);
        when(repo.findCurrentByProduct(eq(F), eq(PRODUCT))).thenReturn(items);
        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", repo);
        ReflectionTestUtils.setField(service, "productionPlanRepository",
                mock(com.cretas.aims.repository.ProductionPlanRepository.class));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), anyString()))
                .thenReturn(new BigDecimal(factoryStock));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(eq(F), anyString(), eq(WORKSHOP)))
                .thenReturn(new BigDecimal(factoryStock));
        Object scan = ReflectionTestUtils.invokeMethod(service, "scanMaterialAdvisory", F, plan());
        @SuppressWarnings("unchecked")
        List<String> unc = (List<String>) ReflectionTestUtils.invokeMethod(scan, "uncomputable");
        return String.join("、", unc);
    }

    @Test
    @DisplayName("阳性对照: 有标准用量的行不算进「没算成」")
    void computableLinesAreNotCountedAsSkipped() {
        assertEquals("", summaryWith(List.of(bomItem()), "500"),
                "有标准用量的行被误算成「未参与核算」");
    }

    @Test
    @DisplayName("🔴 没有标准用量的行必须被记下来 —— 否则「无预警」会盖住「一行都没算」")
    void linesWithoutStandardQuantityAreRecorded() {
        String unc = summaryWith(
                List.of(bomItem(), bomItemWithoutStandardQty("黄油鸡-原料B")), "500");
        assertTrue(unc.contains("黄油鸡-原料B"),
                "跳过的行没有留痕, 界面会说「暂无缺料预警」而其实那一行从来没算过: [" + unc + "]");
    }
}
