package com.cretas.aims.service.production;


import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 结单的 BOM 白名单必须<b>也</b>认调料，否则系统自己发的领料单自己不认。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测, F006 PLAN-1786954657305)</h2>
 *
 * 系统自己生成的领料单 9 行，{@code settlement-bom-eligibility} 的白名单只认 <b>7</b> 行 ——
 * 差的两行正是 AUXILIARY 的「香辛料」「黄油调味料」。工人按单领了、投了，结单时被告知
 * 「所选原料批次不属于该生产计划 BOM」（{@code PRODUCTION_CONSUMPTION_NOT_IN_BOM}）。
 * 阳性对照：同一次比对里 7 行命中，所以不是 ID 匹配写错了。
 *
 * <p>成因是<b>同一个「什么算 BOM 内物料」有两个定义</b>（本仓形态 D）：
 * <ul>
 *   <li>产出侧 {@code ProcessSheetServiceImpl.buildAutomaticBomRequirements} 读
 *       {@code bom_seasoning_items} 合成这些投料行</li>
 *   <li>消费侧 {@code resolveBomEligibilityForSettlement} 只读 {@code bom_recipe_items}</li>
 * </ul>
 *
 * <p>⚠️ 这半个洞是「领料单补上调料」那个修复（#2803）造出来的 —— 在那之前领料单里没有调料，
 * 没人领得到，也就撞不到这道闸。<b>修一处会把拒答挪到下一处</b>，本仓硬约束 8 的原形。
 *
 * <p>⚠️ 与源码里 2026-08-03 那段注释同族：都是「本来就不挂工艺投入槽的成本行被白名单漏掉」
 * （那次是包材）。
 */
class SettlementBomWhitelistIncludesSeasoningContractTest {

    private static final String FACTORY = "F006";
    private static final String FG = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";
    private static final String RECIPE = "259cb14f-3281-4d54-80c3-f8ce7b144308";
    private static final String RAW_A = "RMT_41e1a2d4-raw-a";
    private static final String PACK = "RMT_c4d198ef-pack";
    private static final String SEASONING_1 = "RMT_055f705f-xiangxinliao";
    private static final String SEASONING_2 = "RMT_1df680a3-huangyou";

    private BomSeasoningItemRepository seasoningRepo;

    private static BomRecipeItem item(String materialTypeId) {
        BomRecipeItem i = new BomRecipeItem();
        i.setId(1L);
        i.setRecipeId(RECIPE);
        i.setMaterialTypeId(materialTypeId);
        return i;
    }

    private static BomSeasoningItem seasoning(String materialTypeId) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setRecipeId(RECIPE);
        s.setFactoryId(FACTORY);
        s.setMaterialTypeId(materialTypeId);
        return s;
    }

    /** 装真的仓库 mock，跑真实实现 —— ⛔ 不打桩被测方法本身。 */
    private ProductionPlanServiceImpl service(List<BomSeasoningItem> seasonings, boolean wireSeasoningRepo) {
        ProductionPlanServiceImpl svc = mock(ProductionPlanServiceImpl.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));

        BomRecipe recipe = new BomRecipe();
        recipe.setId(RECIPE);
        recipe.setFactoryId(FACTORY);
        recipe.setProductTypeId(FG);
        recipe.setVersion(1);

        BomRecipeRepository recipeRepo = mock(BomRecipeRepository.class);
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY, FG))
                .thenReturn(Optional.of(recipe));
        when(recipeRepo.findById(RECIPE)).thenReturn(Optional.of(recipe));

        BomRecipeItemRepository itemRepo = mock(BomRecipeItemRepository.class);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(RECIPE))
                .thenReturn(List.of(item(RAW_A), item(PACK)));

        seasoningRepo = mock(BomSeasoningItemRepository.class);
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(seasonings);

        ReflectionTestUtils.setField(svc, "bomRecipeRepository", recipeRepo);
        ReflectionTestUtils.setField(svc, "bomRecipeItemRepository", itemRepo);
        ReflectionTestUtils.setField(svc, "bomSeasoningItemRepository",
                wireSeasoningRepo ? seasoningRepo : null);
        return svc;
    }

    private static ProductionPlan plan() {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-1");
        p.setProductTypeId(FG);
        // 枚举只有 WORKFLOW 一个值; 非 workflow 计划就是 null (走 selectedBomRecipeId==null 那条路)
        p.setWorkflowSelectionMode(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Set<String> whitelist(ProductionPlanServiceImpl svc) {
        Object eligibility = ReflectionTestUtils.invokeMethod(
                svc, "resolveBomEligibilityForSettlement", FACTORY, plan(), null);
        assertNotNull(eligibility, "拿不到 eligibility, 后面全是恒真");
        return (Set<String>) ReflectionTestUtils.invokeMethod(eligibility, "materialTypeIds");
    }

    @Test
    @DisplayName("阳性对照: 原料和包材本来就在白名单里 (否则下面的断言分不清是修好了还是全空)")
    void rawAndPackagingAreAlreadyWhitelisted() {
        Set<String> ids = whitelist(service(List.of(seasoning(SEASONING_1)), true));
        assertTrue(ids.contains(RAW_A), "原料不在白名单, 仪器坏了: " + ids);
        assertTrue(ids.contains(PACK), "包材不在白名单(2026-08-03 那次修复回归了?): " + ids);
    }

    @Test
    @DisplayName("🔴 BOM 里挂的调料必须进白名单 —— 否则自己发的领料单自己不认")
    void seasoningMaterialsAreWhitelisted() {
        Set<String> ids = whitelist(service(
                List.of(seasoning(SEASONING_1), seasoning(SEASONING_2)), true));
        assertTrue(ids.contains(SEASONING_1), "香辛料不在白名单: " + ids);
        assertTrue(ids.contains(SEASONING_2), "黄油调味料不在白名单: " + ids);
        assertEquals(4, ids.size(), "白名单条数不对(原料+包材+2 调料): " + ids);
    }

    @Test
    @DisplayName("🔴 阴性对照: 没挂调料的 BOM 不许凭空多出条目")
    void noSeasoningMeansNoExtraEntries() {
        Set<String> ids = whitelist(service(List.of(), true));
        assertEquals(2, ids.size(), "BOM 没有调料却多出了条目: " + ids);
        assertFalse(ids.contains(SEASONING_1), "凭空造了调料: " + ids);
    }

    @Test
    @DisplayName("调料的 materialTypeId 为空时跳过, 不许把 null 塞进白名单")
    void blankSeasoningMaterialIdIsSkipped() {
        Set<String> ids = whitelist(service(
                List.of(seasoning(null), seasoning("   "), seasoning(SEASONING_1)), true));
        assertEquals(3, ids.size(), "空 materialTypeId 混进了白名单: " + ids);
        assertFalse(ids.contains(null), "白名单里有 null: " + ids);
    }

    @Test
    @DisplayName("调料仓库没装配时照旧工作(不炸), 只是白名单不含调料")
    void missingSeasoningRepositoryDoesNotBlowUp() {
        Set<String> ids = whitelist(service(List.of(seasoning(SEASONING_1)), false));
        assertEquals(2, ids.size(), "没装配仓库时的行为不对: " + ids);
    }

    @Test
    @DisplayName("接线闸: 真的去查了调料表 —— helper 写对不等于接上了")
    void theSeasoningRepositoryIsActuallyQueried() {
        ProductionPlanServiceImpl svc = service(List.of(seasoning(SEASONING_1)), true);
        whitelist(svc);
        org.mockito.Mockito.verify(seasoningRepo,
                org.mockito.Mockito.atLeastOnce()).findByRecipeIdOrderBySeqAsc(RECIPE);
    }

    @Test
    @DisplayName("调料表读挂了不能让结单整个崩 —— 降级但要留痕")
    void seasoningLookupFailureDoesNotBreakSettlement() {
        ProductionPlanServiceImpl svc = service(List.of(), true);
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(anyString()))
                .thenThrow(new IllegalStateException("db down"));
        Set<String> ids = whitelist(svc);
        assertEquals(2, ids.size(), "调料读挂之后原料包材也丢了: " + ids);
    }
}
