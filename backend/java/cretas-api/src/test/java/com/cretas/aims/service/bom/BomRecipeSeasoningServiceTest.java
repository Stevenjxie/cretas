package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest.SeasoningItemDTO;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.bom.impl.BomRecipeServiceImpl;
import com.cretas.aims.service.uom.MaterialUomConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * U5: BOM 调料配方 CRUD 服务层单元测试.
 *
 * <p>覆盖 saveSeasoning/getSeasoning/getSeasoningByProduct 的 5 个核心场景:
 * DRAFT 全量替换 + ACTIVE 守卫 + 跨租户守卫 + 无效 section 守卫 + 无 BOM 空 Optional.
 */
@DisplayName("U5 BOM 调料配方 CRUD")
@ExtendWith(MockitoExtension.class)
class BomRecipeSeasoningServiceTest {

    @Mock BomRecipeRepository recipeRepo;
    @Mock BomRecipeItemRepository itemRepo;
    @Mock BomSeasoningItemRepository seasoningItemRepo;
    @Mock RawMaterialTypeRepository materialTypeRepo;
    @Mock MaterialUomConverter materialUomConverter;
    @Mock NestedBomCostService nestedBomCostService;

    @InjectMocks BomRecipeServiceImpl service;

    private static final String FACTORY  = "F006";
    private static final String OTHER_F  = "F999";
    private static final String RECIPE_ID = "bom-uuid-1";
    private static final String PRODUCT  = "PT-卤猪蹄";

    /** 工厂匹配的 DRAFT BOM. */
    private BomRecipe draftRecipe() {
        BomRecipe r = new BomRecipe();
        r.setId(RECIPE_ID);
        r.setFactoryId(FACTORY);
        r.setProductTypeId(PRODUCT);
        r.setProductName("卤猪蹄");
        r.setStatus(BomRecipe.Status.DRAFT);
        return r;
    }

    /** ACTIVE BOM (不允许改调料). */
    private BomRecipe activeRecipe() {
        BomRecipe r = draftRecipe();
        r.setStatus(BomRecipe.Status.ACTIVE);
        return r;
    }

    /** 构建合法 saveSeasoning 请求 (一行 INJECTION + 一行 COOKING). */
    private BomSeasoningSaveRequest validRequest() {
        BomSeasoningSaveRequest req = new BomSeasoningSaveRequest();
        req.setCookingPotBaseKg(new BigDecimal("160.000"));
        req.setSubsequentPotRatio(new BigDecimal("0.3333"));
        req.setInjectionRate(new BigDecimal("0.2000"));

        SeasoningItemDTO inj = new SeasoningItemDTO();
        inj.setSection("INJECTION");
        inj.setSeq(0);
        inj.setName("盐水");
        inj.setDosagePerKgG(new BigDecimal("1200.0000"));
        inj.setCountInSeasoning(true);

        SeasoningItemDTO cook = new SeasoningItemDTO();
        cook.setSection("COOKING");
        cook.setSeq(1);
        cook.setName("老汤");
        cook.setDosagePerKgG(new BigDecimal("2000.0000"));
        cook.setCountInSeasoning(false);   // 老汤不计入调料成本

        req.setSeasoningItems(List.of(inj, cook));
        return req;
    }

    // ------------------------------------------------------------------
    // Test 1: DRAFT BOM → 全量替换成功
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveSeasoning: DRAFT BOM — 锅序参数写进 recipe + 调料行软删旧/插新")
    void saveSeasoning_draft_replacesItemsAndSetsPotParams() {
        BomRecipe recipe = draftRecipe();
        when(recipeRepo.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        // 模拟已有 1 条旧调料行
        BomSeasoningItem oldItem = new BomSeasoningItem();
        oldItem.setId(99L);
        oldItem.setRecipeId(RECIPE_ID);
        oldItem.setName("旧调料");
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(RECIPE_ID)).thenReturn(List.of(oldItem));
        when(seasoningItemRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(recipeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BomSeasoningResponse resp = service.saveSeasoning(FACTORY, RECIPE_ID, validRequest());

        // 锅序参数写进 recipe
        assertEquals(new BigDecimal("160.000"), recipe.getCookingPotBaseKg());
        assertEquals(new BigDecimal("0.3333"),  recipe.getSubsequentPotRatio());
        assertEquals(new BigDecimal("0.2000"),  recipe.getInjectionRate());

        // 旧行软删 (saveAll 第一次 = 软删旧行; 第二次 = 插新行)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BomSeasoningItem>> cap = ArgumentCaptor.forClass(List.class);
        verify(seasoningItemRepo, times(2)).saveAll(cap.capture());
        List<BomSeasoningItem> deletedBatch = cap.getAllValues().get(0);
        assertEquals(1, deletedBatch.size());
        assertNotNull(deletedBatch.get(0).getDeletedAt(), "旧行必须被软删 (deletedAt 非 null)");

        // 新行数量 = 2; 老汤 countInSeasoning=false 保留
        List<BomSeasoningItem> newBatch = cap.getAllValues().get(1);
        assertEquals(2, newBatch.size());
        newBatch.forEach(si -> {
            assertEquals(RECIPE_ID, si.getRecipeId());
            assertEquals(FACTORY,   si.getFactoryId());
        });
        BomSeasoningItem oldSoup = newBatch.stream()
                .filter(s -> "老汤".equals(s.getName())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, oldSoup.getCountInSeasoning(), "老汤 countInSeasoning=false 必须保留");

        // 响应字段
        assertEquals(RECIPE_ID, resp.getBomRecipeId());
        assertEquals(PRODUCT,   resp.getProductTypeId());
        assertEquals(BomRecipe.Status.DRAFT, resp.getStatus());
        assertEquals(2, resp.getSeasoningItems().size());
    }

    // ------------------------------------------------------------------
    // Test 2: ACTIVE BOM → 拒绝写 (DRAFT-only guard)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveSeasoning: ACTIVE BOM → IllegalStateException (仅 DRAFT 可改)")
    void saveSeasoning_activeBom_throwsIllegalState() {
        when(recipeRepo.findById(RECIPE_ID)).thenReturn(Optional.of(activeRecipe()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.saveSeasoning(FACTORY, RECIPE_ID, validRequest()));

        assertTrue(ex.getMessage().contains("DRAFT"), "错误信息需提示 DRAFT");
        verify(seasoningItemRepo, never()).saveAll(any());
        verify(recipeRepo, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Test 3: 跨租户 — BOM 属于 OTHER_F
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveSeasoning: 跨租户 BOM → IllegalArgumentException (工厂不匹配)")
    void saveSeasoning_crossFactory_throwsIllegalArgument() {
        BomRecipe otherFactory = draftRecipe();
        otherFactory.setFactoryId(OTHER_F);   // 属于 F999, 不是 FACTORY=F006
        when(recipeRepo.findById(RECIPE_ID)).thenReturn(Optional.of(otherFactory));

        // loadRecipe 内 factoryId 不匹配 → IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                () -> service.saveSeasoning(FACTORY, RECIPE_ID, validRequest()));

        verify(seasoningItemRepo, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // Test 4: 无效 section → BusinessException 400
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveSeasoning: 无效 section BOIL → BusinessException 400")
    void saveSeasoning_invalidSection_throwsBusinessException400() {
        when(recipeRepo.findById(RECIPE_ID)).thenReturn(Optional.of(draftRecipe()));

        BomSeasoningSaveRequest req = new BomSeasoningSaveRequest();
        SeasoningItemDTO bad = new SeasoningItemDTO();
        bad.setSection("BOIL");   // 非法值
        bad.setName("坏行");
        bad.setDosagePerKgG(BigDecimal.ONE);
        req.setSeasoningItems(List.of(bad));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveSeasoning(FACTORY, RECIPE_ID, req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("BOIL"));
        verify(seasoningItemRepo, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // Test 5: getSeasoningByProduct — 无当前 BOM → empty Optional
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getSeasoningByProduct: 产品无当前 BOM → Optional.empty()")
    void getSeasoningByProduct_noBom_returnsEmpty() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY, PRODUCT))
                .thenReturn(Optional.empty());

        Optional<BomSeasoningResponse> result = service.getSeasoningByProduct(FACTORY, PRODUCT);

        assertTrue(result.isEmpty());
        verify(seasoningItemRepo, never()).findByRecipeIdOrderBySeqAsc(any());
    }
}
