package com.cretas.aims.service.factory;

import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 领料单必须把<b>调料</b>也列出来。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 调料不在 {@code bom_recipe_items} 里，它们有自己的表 {@code bom_seasoning_items}
 * （按「每 kg 投入多少克」登记）。而领料单 {@code generateFromPlan} 原来<b>只展开 BOM 行</b>。
 * 报工那一侧却<b>会</b>读调料表并向生产仓要货：
 *
 * <pre>
 * 文员按计划生成领料单 → 7 行（4 原料 + 3 包材），没有调料
 * 仓管照单拣货 → 转运 → 收货
 * 报工          → 409「需要 1.6kg，可用 0kg，请联系仓管补料」
 *                  香辛料 0.8kg / 黄油调味料 0.8kg（= 10 g/kg × 80kg 投入）
 * </pre>
 *
 * 而全厂原料仓那两样各有 20kg —— <b>货在，只是领料单上根本没有这一行</b>。
 * 用户无从知道要把它们领进生产仓 ⇒「凡是拦住人的地方都要告诉他下一步」被违反。
 *
 * <p>普遍性：F006 有 21 条调料行、LIUSHANMEN 15 条，不是个例。
 *
 * <h2>用量为什么留空</h2>
 * 调料按「每 kg <b>投入</b>」计量，领料单只知道<b>计划产出</b>；workflow 驱动的 BOM 原料行
 * 又没有标准用量，推不出投入 kg。⇒ 与本方法对原料/辅料的既有口径一致：<b>留空而不是拦单</b>。
 * 防短料的闸在 {@code transferToFactory}，不在这个参考数字上。
 */
class RequisitionIncludesSeasoningContractTest {

    private static final String F = "F006";
    private static final String RECIPE = "recipe-butter-chicken";
    private static final String SPICE = "RMT_055f705f-f7a7-4370-8724-189c4de34d6b";
    private static final String BUTTER = "RMT_1df680a3-39d3-4a0d-9be9-37e2dac0625e";
    private static final String RAW_A = "RMT_41e1a2d4-ae36-4ad3-9d0f-2b943816a2aa";

    private FactoryMaterialRequisitionServiceImpl service;
    private BomSeasoningItemRepository seasoningRepo;

    @BeforeEach
    void setUp() {
        service = mock(FactoryMaterialRequisitionServiceImpl.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        seasoningRepo = mock(BomSeasoningItemRepository.class);
        ReflectionTestUtils.setField(service, "bomSeasoningItemRepository", seasoningRepo);
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", null);
        ReflectionTestUtils.setField(service, "materialBatchRepository", null);
    }

    private static BomRecipeItem bomItem(String materialTypeId) {
        BomRecipeItem it = new BomRecipeItem();
        it.setRecipeId(RECIPE);
        it.setMaterialTypeId(materialTypeId);
        it.setMaterialName("原料A");
        return it;
    }

    private static BomSeasoningItem seasoning(String materialTypeId, String name, String dosage) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setRecipeId(RECIPE);
        s.setFactoryId(F);
        s.setMaterialTypeId(materialTypeId);
        s.setName(name);
        s.setDosagePerKgG(dosage == null ? null : new BigDecimal(dosage));
        return s;
    }

    private static FactoryMaterialRequisition emptyMr() {
        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setFactoryId(F);
        mr.setItems(new ArrayList<>());
        return mr;
    }

    private List<FactoryMaterialRequisitionItem> run(List<BomSeasoningItem> seasonings,
                                                     List<BomRecipeItem> bomItems) {
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(seasonings);
        FactoryMaterialRequisition mr = emptyMr();
        ReflectionTestUtils.invokeMethod(service, "appendSeasoningItems", F, bomItems, mr);
        return mr.getItems();
    }

    @Test
    @DisplayName("阳性对照: 配方有调料时确实被读到 (否则下面的断言全是恒真)")
    void seasoningRepoIsActuallyQueried() {
        List<FactoryMaterialRequisitionItem> items = run(
                List.of(seasoning(SPICE, "香辛料", "10")), List.of(bomItem(RAW_A)));
        assertEquals(1, items.size(), "调料一条都没进来: " + items);
    }

    @Test
    @DisplayName("🔴 主断言: 两条调料都要进领料单, 归为辅料, 用量留空不拦单")
    void bothSeasoningsAreListed() {
        List<FactoryMaterialRequisitionItem> items = run(
                List.of(seasoning(SPICE, "香辛料", "10"), seasoning(BUTTER, "黄油调味料", "10")),
                List.of(bomItem(RAW_A)));
        assertEquals(2, items.size(), "实际 " + items.size());
        for (FactoryMaterialRequisitionItem it : items) {
            assertEquals(FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY,
                    it.getMaterialCategory(), "调料没归到辅料");
            assertNull(it.getRequiredQty(), "调料按 g/kg 投入计, 推不出计划用量, 该留空");
            assertTrue(it.getUnit() != null && !it.getUnit().isBlank(), "单位不能空");
        }
    }

    @Test
    @DisplayName("🔴 阴性对照: 没绑物料档案的调料行不进单 —— 那只是配方文本, 领不出实物")
    void seasoningWithoutMaterialTypeIsSkipped() {
        List<FactoryMaterialRequisitionItem> items = run(
                List.of(seasoning(null, "只有名字的调料", "5"), seasoning("", "空字符串", "5")),
                List.of(bomItem(RAW_A)));
        assertTrue(items.isEmpty(), "造出了一条领不动的行: " + items);
    }

    @Test
    @DisplayName("🔴 不重复: 该物料已在 BOM 行里出现过就不再加一遍")
    void alreadyPresentMaterialIsNotDuplicated() {
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(anyString()))
                .thenReturn(List.of(seasoning(RAW_A, "原料A也被登记成调料", "10")));
        FactoryMaterialRequisition mr = emptyMr();
        FactoryMaterialRequisitionItem existing = new FactoryMaterialRequisitionItem();
        existing.setMaterialTypeId(RAW_A);
        mr.getItems().add(existing);
        ReflectionTestUtils.invokeMethod(service, "appendSeasoningItems", F, List.of(bomItem(RAW_A)), mr);
        assertEquals(1, mr.getItems().size(), "同一物料被加了两行: " + mr.getItems().size());
    }

    @Test
    @DisplayName("🔴 接线闸: generateFromPlan 必须真的调它 —— helper 写对了不等于接上了")
    void generateFromPlanActuallyCallsIt() throws Exception {
        // ⚠️ 上面几条都直接反射调 helper, 打在调用点上的变异它们一条都看不见(形态 C″)。
        //    本仓形态 B「机制在、没接上」—— 这一条专门钉接线。
        String raw = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/factory/impl/FactoryMaterialRequisitionServiceImpl.java"));
        // 先剥注释: 否则会数到讲这件事的那几段说明
        String src = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int genAt = src.indexOf("public FactoryMaterialRequisition generateFromPlan");
        assertTrue(genAt > 0, "找不到 generateFromPlan, 这道闸在读空气");
        int saveAt = src.indexOf("repository.save(mr)", genAt);
        assertTrue(saveAt > genAt, "找不到 generateFromPlan 里的 save");
        String body = src.substring(genAt, saveAt);
        assertTrue(body.contains("appendSeasoningItems("),
                "generateFromPlan 没有调 appendSeasoningItems —— 调料又不会进领料单了");
    }

    @Test
    @DisplayName("调料读不到时不能把整张领料单拦掉 —— 原料那几行照样有用")
    void seasoningFailureDoesNotBlockTheWholeRequisition() {
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(anyString()))
                .thenThrow(new IllegalStateException("repo 炸了"));
        FactoryMaterialRequisition mr = emptyMr();
        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(service, "appendSeasoningItems", F,
                        List.of(bomItem(RAW_A)), mr));
    }
}
