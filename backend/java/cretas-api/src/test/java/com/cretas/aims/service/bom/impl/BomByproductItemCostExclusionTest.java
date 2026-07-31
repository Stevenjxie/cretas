package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * BOM 配方内容第四类「副产」行**不进成本池**。
 *
 * <p>副产行是**产出声明** (这个配方预计产出哪个副产 SKU、多少量), 不是投入。
 * 而 {@code recomputeFamilyCosts} 原本对 {@code itemRepo.findByRecipeIdOrderBySortOrderAsc}
 * 返回的每一行**无差别**累加进成本池 —— PACKAGING 只影响 {@code resolveCostTargets} 的作用域,
 * 没有任何一行被排除过。</p>
 *
 * <p>🔴 不加这条排除会怎样 (两种都错, 且第二种更隐蔽):</p>
 * <ol>
 *   <li>副产 SKU 没有采购价 (Task 1 刻意把采购属性与副产大类隔开) → {@code unitPrice} 为 null
 *       → {@code CostRollupUtil.calcItemCost} 返 null → {@code markFamilyCostIncomplete}
 *       → <b>整个 family 的标准成本被清空</b>。加一行副产声明就把成本表打没了。</li>
 *   <li>万一副产 SKU 恰好有价, 那就更糟 —— 它会**悄悄抬高**主产品的标准成本, 方向还是反的
 *       (副产本该抵扣成本, 不是增加成本)。</li>
 * </ol>
 *
 * <p><b>存量影响 = 0</b>: 加这条排除时 DB 里不可能有 BYPRODUCT 行 (V20261029_37 之前
 * chk_bri_category 就禁着), 所以它不可能改变任何已经出过的成本数字。</p>
 */
@ExtendWith(MockitoExtension.class)
class BomByproductItemCostExclusionTest {

    @Mock private BomRecipeRepository recipeRepo;
    @Mock private BomRecipeItemRepository itemRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private RawMaterialTypeRepository materialTypeRepo;
    @Mock private MaterialUomConverter materialUomConverter;
    @Mock private UnitContractService unitContractService;
    @Mock private ProductPackagingSpecRepository packagingSpecRepository;
    @Mock private ProductConfigurationReadinessService readinessService;
    @Mock private BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock private BomItemSubstituteService substituteService;
    @Mock private NestedBomCostService nestedBomCostService;
    @Mock private BomSeasoningItemRepository seasoningItemRepo;
    @Mock private BomProcessInjectionConfigRepository processInjectionConfigRepo;

    @InjectMocks
    private BomRecipeServiceImpl service;

    /**
     * 阳性对照 —— 先证明这套 mock 装配真的能算出成本。
     * 没有它, 下面两条「成本仍是 100」可能只是因为整条路径压根没跑起来。
     */
    @Test
    void baselineWithoutAnyByproductRowCostsTheRawInput() {
        BomRecipe main = mainRecipe();
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-MAIN"))
                .thenReturn(List.of(rawInput()));
        stubCommon(main);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost()).isEqualByComparingTo("100.0000");
    }

    @Test
    void unpricedByproductDeclarationDoesNotBlankTheFamilyStandardCost() {
        BomRecipe main = mainRecipe();
        BomRecipeItem byproduct = byproductDeclaration();
        byproduct.setUnitPrice(null); // 副产 SKU 无采购价 —— 这是常态, 不是异常
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-MAIN"))
                .thenReturn(List.of(rawInput(), byproduct));
        stubCommon(main);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost())
                .as("副产行未定价不该把整个 family 的标准成本判成不完整")
                .isNotNull()
                .isEqualByComparingTo("100.0000");
    }

    @Test
    void pricedByproductDeclarationStillDoesNotInflateMaterialCost() {
        BomRecipe main = mainRecipe();
        BomRecipeItem byproduct = byproductDeclaration();
        byproduct.setUnitPrice(new BigDecimal("4")); // 即使有参考价也不算进投入
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-MAIN"))
                .thenReturn(List.of(rawInput(), byproduct));
        stubCommon(main);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost())
                .as("副产是产出不是投入, 有价也不能加进原料成本 (5×4=20 不该出现)")
                .isEqualByComparingTo("100.0000");
    }

    private void stubCommon(BomRecipe main) {
        when(recipeRepo.findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                "F006", "FAMILY-BP")).thenReturn(List.of(main));
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class))).thenReturn(false);
    }

    private BomRecipe mainRecipe() {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("RECIPE-MAIN");
        recipe.setFactoryId("F006");
        recipe.setBomFamilyId("FAMILY-BP");
        recipe.setSharedRecipeId("RECIPE-MAIN");
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setProductTypeId("FG-A");
        recipe.setWorkflowRevisionId(71L);
        recipe.setTargetTerminalNodeId("TERM-A");
        recipe.setOutputRole(BomRecipe.OutputRole.MAIN);
        recipe.setCostAllocationRatio(new BigDecimal("100"));
        recipe.setOutputQuantityPerUnit(BigDecimal.ONE);
        recipe.setOutputUnit("kg");
        return recipe;
    }

    private BomRecipeItem rawInput() {
        return item("RAW", "SHARED", "10", "10");
    }

    private BomRecipeItem byproductDeclaration() {
        // 副产行: 预计产出 5, costScope 为 null (前端不给副产设作用域)
        return item(BomRecipeItem.CATEGORY_BYPRODUCT, null, "5", "0");
    }

    private BomRecipeItem item(String category, String costScope, String quantity, String unitPrice) {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId("RECIPE-MAIN");
        item.setFactoryId("F006");
        item.setCostScope(costScope);
        item.setMaterialCategory(category);
        item.setStandardQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        return item;
    }
}
