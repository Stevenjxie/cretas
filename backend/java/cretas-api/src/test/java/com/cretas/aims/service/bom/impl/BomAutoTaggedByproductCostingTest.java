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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 「自动编号出来的 BY_PRODUCT」不按副产品计价 —— 补上 PR #2080 只修了一半的那一半。
 *
 * <p><b>背景</b>: ACTUAL_IO(实际产出语义)下 {@code BomWorkflowRevisionService} 会按顺序
 * <b>自动</b>给产出编号 ({@code terminalIndex == 0 ? "MAIN" : "BY_PRODUCT"}, 比例同时定成
 * {@code 100 / 0}), 那段代码自注为「compatibility-only storage metadata,
 * <b>not authored or shown to users</b>」。</p>
 *
 * <p>#2080 给<b>生效闸</b> {@code validateByProductCreditRules} 加了 ACTUAL_IO 豁免, 于是客户
 * 不再被「副产品缺少单位可变现净值」拦住。但 {@code targetProducedUnderActualIoSemantics}
 * 在本类中<b>只被调用过那一处</b> —— {@code recomputeFamilyCosts} 完全没引用它。</p>
 *
 * <p>🔴 后果(2026-07-31 实测): 缺 NRV 时 {@code byproductGrossNrv} 返 null →
 * {@code markFamilyCostIncomplete} 把 <b>family 全体</b>的成本置 NULL。而线上
 * {@code bom_recipes} 61 行的 {@code byproduct_nrv_unit_price} <b>全是 NULL</b>。
 * 失败模式因此从「响亮地拦住」变成「静默地把成本表清空」—— 后者更糟, 没有任何提示。</p>
 *
 * <p><b>本修复不发明成本口径</b>: 豁免后这些产出按 workflow 快照里<b>已经声明的</b>分摊比例
 * 计价(未授权时就是自动编号给的 100/0)。用户在 workflow 里真填了比例会走授权路径覆盖它。
 * 比整张表空白更有信息量, 且不锁死任何东西。</p>
 */
@ExtendWith(MockitoExtension.class)
class BomAutoTaggedByproductCostingTest {

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
     * 客户 2026-07-31 现场那个 family(拓扑成品C + 拓扑成品D, 两个都是正经成品)。
     * 自动标的 BY_PRODUCT + 无 NRV —— 修复前整个 family 成本被清空。
     */
    @Test
    void autoTaggedByproductWithoutNrvNoLongerBlanksTheFamily() {
        BomRecipe main = recipe("R-C", BomRecipe.OutputRole.MAIN, "100");
        BomRecipe auto = recipe("R-D", BomRecipe.OutputRole.BY_PRODUCT, "0");
        auto.setByproductNrvUnitPrice(null);
        stubFamily(main, auto);
        // 两个产出都产自 ACTUAL_IO 工序 → BY_PRODUCT 只是占位值
        when(bomWorkflowRevisionService.targetProducedUnderActualIoSemantics(anyString(), any()))
                .thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost())
                .as("修复前这里是 null —— 整张成本表被静默清空")
                .isNotNull()
                .isEqualByComparingTo("100.0000");
        // 按快照里声明的比例(自动编号给的 100/0)计价, 不是按 NRV
        assertThat(auto.getTotalMaterialCost()).isNotNull().isEqualByComparingTo("0.0000");
    }

    /**
     * 阳性对照 + 不许放松真副产品那条路: 用户<b>真标</b>的副产品(非 ACTUAL_IO)缺 NRV 时,
     * 仍然按原样判定成本不完整。本次修的是「把占位值当真」, 不是「让副产品不用填价」。
     */
    @Test
    void genuineByproductWithoutNrvStillMarksFamilyIncomplete() {
        BomRecipe main = recipe("R-C", BomRecipe.OutputRole.MAIN, "100");
        BomRecipe real = recipe("R-D", BomRecipe.OutputRole.BY_PRODUCT, "0");
        real.setByproductNrvUnitPrice(null);
        stubFamily(main, real);
        when(bomWorkflowRevisionService.targetProducedUnderActualIoSemantics(anyString(), any()))
                .thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost()).isNull();
        assertThat(main.getTotalCost()).isNull();
    }

    /** 回归: 用户真标的副产品填了 NRV 时, 抵扣照旧生效 (成本池 100, 副产净值 2 → 主产品 98)。 */
    @Test
    void genuineByproductWithNrvStillCreditsTheMainOutput() {
        BomRecipe main = recipe("R-C", BomRecipe.OutputRole.MAIN, "100");
        BomRecipe real = recipe("R-D", BomRecipe.OutputRole.BY_PRODUCT, "0");
        real.setByproductNrvUnitPrice(new BigDecimal("2"));
        stubFamily(main, real);
        when(bomWorkflowRevisionService.targetProducedUnderActualIoSemantics(anyString(), any()))
                .thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost()).isEqualByComparingTo("98.0000");
        assertThat(real.getTotalMaterialCost()).isEqualByComparingTo("2.0000");
    }

    private void stubFamily(BomRecipe main, BomRecipe other) {
        when(recipeRepo.findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                "F006", "FAM")).thenReturn(List.of(main, other));
        // 主产出挂一条**已定价**原料行, 排除「未定价 → 成本不完整」这条更早的短路
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("R-C")).thenReturn(List.of(pricedRawItem()));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("R-D")).thenReturn(List.of());
        lenient().when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        lenient().when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class))).thenReturn(false);
    }

    private BomRecipe recipe(String id, BomRecipe.OutputRole role, String ratio) {
        BomRecipe r = new BomRecipe();
        r.setId(id);
        r.setFactoryId("F006");
        r.setBomFamilyId("FAM");
        r.setSharedRecipeId("R-C");
        r.setStatus(BomRecipe.Status.DRAFT);
        r.setProductTypeId("PT-" + id);
        r.setWorkflowRevisionId(71L);
        r.setTargetTerminalNodeId("TERM-" + id);
        r.setOutputRole(role);
        r.setCostAllocationRatio(new BigDecimal(ratio));
        r.setOutputQuantityPerUnit(BigDecimal.ONE);
        r.setOutputUnit("kg");
        return r;
    }

    private BomRecipeItem pricedRawItem() {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId("R-C");
        item.setFactoryId("F006");
        item.setCostScope("SHARED");
        item.setMaterialCategory("RAW");
        item.setStandardQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("10"));
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        return item;
    }
}
