package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.BomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D4 Path B (2026-05-10 customer meeting, PR #309 A2=B) — BomExpansionService 单元测试
 *
 * <p>验证：</p>
 * <ul>
 *   <li>BomItem 存在 → 优先走 BomItem 路径, 不查 RPF</li>
 *   <li>BomItem 不存在 → fallback 到 RPF (MaterialProductConversion) 路径</li>
 *   <li>同一产品两者都配置 → BomItem 路径优先 (Path B 契约)</li>
 *   <li>yieldRate 出成率参与 actual quantity 计算 (BomItem.getActualQuantity)</li>
 *   <li>D3 sourceUnit 激活: BomItem 路径下 MaterialRequirement.sourceUnit = BomItem.unit</li>
 *   <li>RPF fallback 路径下 sourceUnit = null (沿用 1:1 透传向后兼容)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("D4-B: BomExpansionService — BomItem priority + RPF fallback")
class BomExpansionServiceTest {

    @Mock
    private ConversionRepository conversionRepository;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Mock
    private ProductionPlanRepository productionPlanRepository;

    @Mock
    private BomService bomService;

    @Mock
    private BomRecipeRepository bomRecipeRepository;

    @Mock
    private BomRecipeItemRepository bomRecipeItemRepository;

    @InjectMocks
    private BomExpansionService bomExpansionService;

    @BeforeEach
    void wireFieldInjectedDeps() {
        // @InjectMocks 用构造器注入 (BomExpansionService 有 @RequiredArgsConstructor),
        // 不会填 @Autowired(required=false) 字段注入的 recipe repos → 手动注入.
        ReflectionTestUtils.setField(bomExpansionService, "bomRecipeRepository", bomRecipeRepository);
        ReflectionTestUtils.setField(bomExpansionService, "bomRecipeItemRepository", bomRecipeItemRepository);
    }

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "PT-LUWEI-001";
    private static final String MATERIAL_TYPE_ID = "MT-PORK-001";

    // ============ 1. BomItem present → use BomItem path ============

    @Test
    @DisplayName("BomItem 存在 → 使用 BomItem 路径并设置 sourceUnit (激活 D3 单位换算)")
    void bomItemPresent_usesBomItemPathAndSetsSourceUnit() {
        BomItem item = bomItem(200, 58.00, "g"); // 200g 成品, 58% 出成率, 单位 g
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(item));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100"));

        assertThat(result).hasSize(1);
        MaterialRequirement req = result.get(0);
        assertThat(req.getMaterialTypeId()).isEqualTo(MATERIAL_TYPE_ID);
        assertThat(req.getMaterialTypeName()).isEqualTo("猪后腿肉");
        // 实际用量 / 单位成品 = 200 / 0.58 ≈ 344.827586, × 100 = 34482.758621
        // BomItem.getActualQuantity() 内部 setScale(6, HALF_UP), 再 × 100 后 setScale(6, HALF_UP)
        // 344.827586 * 100 = 34482.758600
        assertThat(req.getRequiredQuantity())
                .isCloseTo(new BigDecimal("34482.758600"), within(new BigDecimal("0.01")));
        // D3 激活点
        assertThat(req.getSourceUnit()).isEqualTo("g");
        // wastageRate 由 yieldRate 派生: 100 - 58 = 42
        assertThat(req.getWastageRate()).isEqualByComparingTo(new BigDecimal("42.00"));

        // 必须不查 RPF (Path B 优先, BomItem 命中即返回)
        verify(conversionRepository, never())
                .findByFactoryIdAndProductTypeId(anyString(), anyString());
    }

    // ============ 2. BomItem absent → fallback to RPF ============

    @Test
    @DisplayName("BomItem 不存在 → fallback 到 RPF (MaterialProductConversion), sourceUnit 为 null")
    void bomItemAbsent_fallsBackToRpf() {
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());

        MaterialProductConversion conv = rpfConversion(
                new BigDecimal("2.0"),  // conversionRate (1 kg 原料 → 2 kg 成品)
                new BigDecimal("5.00")  // wastageRate 5%
        );
        when(conversionRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(conv));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100"));

        assertThat(result).hasSize(1);
        MaterialRequirement req = result.get(0);
        assertThat(req.getMaterialTypeId()).isEqualTo(MATERIAL_TYPE_ID);
        // RPF: standardUsage = 1/2 = 0.5, base = 0.5 * 100 = 50, wastage 5% → 50 * 1.05 = 52.5
        assertThat(req.getRequiredQuantity())
                .isCloseTo(new BigDecimal("52.5"), within(new BigDecimal("0.01")));
        // sourceUnit 必须为 null (Rule: RPF 路径不激活 D3 换算, 向后兼容)
        assertThat(req.getSourceUnit()).isNull();
        // wastageRate 直接从 RPF 取
        assertThat(req.getWastageRate()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    // ============ 3. Both present → BomItem priority ============

    @Test
    @DisplayName("BomItem + RPF 同时存在 → BomItem 优先 (Path B 契约)")
    void bothPresent_bomItemWins() {
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem(100, 100.00, "g")));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("10"));

        assertThat(result).hasSize(1);
        // BomItem 路径: actualQuantity per unit = 100 / 1.0 = 100, * 10 = 1000
        assertThat(result.get(0).getRequiredQuantity())
                .isCloseTo(new BigDecimal("1000"), within(new BigDecimal("0.01")));
        assertThat(result.get(0).getSourceUnit()).isEqualTo("g");

        // RPF 不应被查 (BomItem 命中即短路)
        verify(conversionRepository, never())
                .findByFactoryIdAndProductTypeId(anyString(), anyString());
    }

    // ============ 4. yieldRate scaling — 客户原话 case (200g × 58%) ============

    @Test
    @DisplayName("yieldRate scaling — 客户原话 case (200g 成品 × 58% 出成率 → 344.83g 原料 per 件)")
    void yieldRateScaling_customerCase() {
        // 客户 2026-05-10 meeting 提的具体配方: 一份 200g 成品, 出成率 58%, 算原料用量
        BomItem item = bomItem(200, 58.00, "g");
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(item));

        // 生产 1 份成品
        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("1"));

        // 200 / 0.58 = 344.827586... ≈ 344.83g
        assertThat(result.get(0).getRequiredQuantity())
                .isCloseTo(new BigDecimal("344.83"), within(new BigDecimal("0.01")));
    }

    // ============ 5. D3 sourceUnit activation — unit≠target triggers conversion downstream ============

    @Test
    @DisplayName("D3 sourceUnit 激活 — unit=\"g\" 通过 MaterialRequirement 传到 orchestrator 触发 g→kg 换算")
    void d3SourceUnitActivation_gUnitPropagated() {
        // BomItem 配方层用 g, RawMaterialType 仓库层用 kg → orchestrator 应自动 ÷1000 换算
        // 本 test 只验证 sourceUnit 字段被正确填充 (orchestrator convertUnit 已由 ProductionWorkflowOrchestratorUnitConversionTest 覆盖)
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem(500, 100.00, "g")));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100"));

        // sourceUnit 必须传递, 否则 ProductionWorkflowOrchestrator.convertUnit 不会触发
        assertThat(result.get(0).getSourceUnit())
                .as("D3 激活前提: sourceUnit 必须从 BomItem.unit 传递到 MaterialRequirement")
                .isEqualTo("g");
    }

    @Test
    @DisplayName("D3 兼容 — BomItem.unit=null 时 sourceUnit=null (沿用 1:1 透传)")
    void d3SourceUnit_nullUnitBomItem() {
        BomItem item = bomItem(100, 100.00, null); // 历史 BomItem 无 unit 字段
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(item));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("1"));

        assertThat(result.get(0).getSourceUnit()).isNull();
    }

    // ============ 6. Empty edge cases ============

    @Test
    @DisplayName("BomItem 和 RPF 都为空 → 返空 list")
    void bothEmpty_returnsEmptyList() {
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());
        when(conversionRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("BomItem null bomService 返 null → fallback 到 RPF (defensive)")
    void bomServiceReturnsNull_fallsBackToRpf() {
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(null);

        MaterialProductConversion conv = rpfConversion(new BigDecimal("1.0"), BigDecimal.ZERO);
        when(conversionRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(conv));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("10"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSourceUnit()).isNull();
    }

    @Test
    @DisplayName("BomItem 多原料配方 — 每行独立计算并保留 sourceUnit")
    void multipleBomItems_eachWithOwnUnit() {
        BomItem pork = bomItem(200, 58.00, "g");  // 主料
        BomItem salt = bomItem(5, 100.00, "g");   // 辅料
        salt.setMaterialTypeId("MT-SALT-001");
        salt.setMaterialName("食盐");

        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(pork, salt));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("1"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(MaterialRequirement::getMaterialTypeId)
                .containsExactly(MATERIAL_TYPE_ID, "MT-SALT-001");
        assertThat(result).extracting(MaterialRequirement::getSourceUnit)
                .containsExactly("g", "g");
    }

    // ============ 7. 断点3: bom_recipe_items recipe-first (同 #751 口径统一) ============

    @Test
    @DisplayName("断点3: ACTIVE recipe 存在 → 优先用 bom_recipe_items, 不查 BomItem/RPF")
    void recipePresent_usesRecipeFirst_noLegacyQuery() {
        BomRecipe recipe = recipe("REC-1");
        BomRecipeItem ri = recipeItem("MT-PORK-001", "猪后腿肉", 200, 58.00, "g");
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_TYPE_ID, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("REC-1"))
                .thenReturn(List.of(ri));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100"));

        assertThat(result).hasSize(1);
        MaterialRequirement req = result.get(0);
        assertThat(req.getMaterialTypeId()).isEqualTo("MT-PORK-001");
        // actualQuantity per unit = 200 / 0.58 = 344.827586, × 100 = 34482.758600
        assertThat(req.getRequiredQuantity())
                .isCloseTo(new BigDecimal("34482.758600"), within(new BigDecimal("0.01")));
        assertThat(req.getSourceUnit()).isEqualTo("g");          // D3 激活点
        assertThat(req.getWastageRate()).isEqualByComparingTo(new BigDecimal("42.00"));

        // recipe 命中即短路 — 不查 legacy BomItem / RPF (口径统一: 不双源)
        verify(bomService, never()).getBomItemsByProduct(anyString(), anyString());
        verify(conversionRepository, never()).findByFactoryIdAndProductTypeId(anyString(), anyString());
    }

    @Test
    @DisplayName("断点3: 自动级联净需求 = 按钮净需求 (同 recipe 源, actualQuantity 一致)")
    void recipeFirst_autoCascadeEqualsManualButtonNetRequirement() {
        // PurchaseServiceImpl.loadCurrentRecipeItems 与本服务走完全相同的查询 + actualQuantity 公式.
        // 同一 recipe item (123g @ 80%) → 两路净需求必须字节级一致.
        BomRecipe recipe = recipe("REC-2");
        BomRecipeItem ri = recipeItem("MT-PORK-001", "猪舌", 123, 80.00, "g");
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_TYPE_ID, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("REC-2"))
                .thenReturn(List.of(ri));

        BigDecimal soQty = new BigDecimal("50");
        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, soQty);

        // 手动按钮口径: actualQtyPerUnit × soQty (PurchaseServiceImpl 行).
        BigDecimal actualPerUnit = ri.getActualQuantity() != null
                ? ri.getActualQuantity() : ri.calculateActualQuantity();
        BigDecimal expectedNet = actualPerUnit.multiply(soQty).setScale(6, java.math.RoundingMode.HALF_UP);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRequiredQuantity())
                .as("自动级联净需求必须 = 手动开始采购按钮净需求 (同 recipe 源)")
                .isEqualByComparingTo(expectedNet);
    }

    @Test
    @DisplayName("断点3: 无 ACTIVE recipe → fallback 到 legacy BomItem (向后兼容旧品)")
    void noRecipe_fallsBackToBomItem() {
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_TYPE_ID, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.empty());
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem(200, 58.00, "g")));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMaterialTypeId()).isEqualTo(MATERIAL_TYPE_ID);
        assertThat(result.get(0).getSourceUnit()).isEqualTo("g");
        // recipe 查询走过但为空 → 回退 BomItem
        verify(bomService).getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID);
    }

    @Test
    @DisplayName("断点3: recipe 存在但 items 为空 → fallback 到 legacy (空 recipe 不锁死)")
    void recipeExistsButEmpty_fallsBackToLegacy() {
        BomRecipe recipe = recipe("REC-3");
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_TYPE_ID, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("REC-3"))
                .thenReturn(Collections.emptyList());
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem(100, 100.00, "g")));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("1"));

        assertThat(result).hasSize(1);
        verify(bomService).getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID);
    }

    @Test
    @DisplayName("断点3: recipeItem.actualQuantity null → calculateActualQuantity() 实时折算")
    void recipeItem_nullActualQuantity_realtimeCalc() {
        BomRecipe recipe = recipe("REC-4");
        BomRecipeItem ri = recipeItem("MT-PORK-001", "猪后腿肉", 200, 58.00, "g");
        ri.setActualQuantity(null); // 旧 recipe 未回填持久值
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_TYPE_ID, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("REC-4"))
                .thenReturn(List.of(ri));

        List<MaterialRequirement> result =
                bomExpansionService.expandBOM(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("1"));

        // calculateActualQuantity: 200 / 0.58 = 344.827586
        assertThat(result.get(0).getRequiredQuantity())
                .isCloseTo(new BigDecimal("344.83"), within(new BigDecimal("0.01")));
    }

    // ============ Helpers ============

    private BomRecipe recipe(String id) {
        BomRecipe r = new BomRecipe();
        r.setId(id);
        r.setFactoryId(FACTORY_ID);
        r.setProductTypeId(PRODUCT_TYPE_ID);
        r.setStatus(BomRecipe.Status.ACTIVE);
        r.setIsCurrent(true);
        return r;
    }

    private BomRecipeItem recipeItem(String matId, String matName,
                                     double standardGrams, double yieldRatePct, String unit) {
        BomRecipeItem ri = new BomRecipeItem();
        ri.setMaterialTypeId(matId);
        ri.setMaterialName(matName);
        ri.setStandardQuantity(new BigDecimal(String.valueOf(standardGrams)));
        ri.setYieldRate(new BigDecimal(String.valueOf(yieldRatePct)));
        ri.setUnit(unit);
        // 持久 actualQuantity = standardQuantity / (yieldRate/100), 同 entity 公式
        ri.setActualQuantity(ri.calculateActualQuantity());
        return ri;
    }

    private BomItem bomItem(double standardGrams, double yieldRatePct, String unit) {
        BomItem item = new BomItem();
        item.setFactoryId(FACTORY_ID);
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setMaterialTypeId(MATERIAL_TYPE_ID);
        item.setMaterialName("猪后腿肉");
        item.setStandardQuantity(new BigDecimal(String.valueOf(standardGrams)));
        item.setYieldRate(new BigDecimal(String.valueOf(yieldRatePct)));
        item.setUnit(unit);
        return item;
    }

    private MaterialProductConversion rpfConversion(BigDecimal conversionRate, BigDecimal wastageRate) {
        MaterialProductConversion conv = new MaterialProductConversion();
        conv.setFactoryId(FACTORY_ID);
        conv.setMaterialTypeId(MATERIAL_TYPE_ID);
        conv.setProductTypeId(PRODUCT_TYPE_ID);
        conv.setConversionRate(conversionRate);
        conv.setWastageRate(wastageRate);
        conv.setIsActive(true);
        // 计算 standardUsage = 1 / conversionRate (mirror @PrePersist behavior)
        if (conversionRate != null && conversionRate.signum() > 0) {
            conv.setStandardUsage(BigDecimal.ONE.divide(conversionRate, 4, java.math.RoundingMode.HALF_UP));
        }
        // materialType 字段留 null → expandFromConversions 会 fallback 到 materialTypeId 当 name
        // 不模拟 RawMaterialType lazy 关联, 避免 N+1
        return conv;
    }
}
