package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomProcessSeasoning;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomProcessSeasoningRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 调料配方按工序 (2026-07-13) 的 per-工序 成本核算单测。
 * 直接经反射调私有方法, 只验新逻辑 (成本按工序隔离 / 注射绝对量 / F1 不重复计 / F3 缺量告警),
 * 不跑整条 recordChain。对应 audit Finding 5 (新路径此前零测试)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClerkProcessEntry — 调料成本按工序")
class ClerkProcessEntrySeasoningPerProcessTest {

    private static final String F = "F006";
    private static final String PT = "PT-TROTTER";
    private static final String RECIPE = "R1";

    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private BomRecipeRepository bomRecipeRepo;
    @Mock private BomSeasoningItemRepository bomSeasoningItemRepo;
    @Mock private BomProcessSeasoningRepository bomProcessSeasoningRepository;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    // ── reflection helpers ──
    private BigDecimal computePerProcess(StepEntry st, List<BigDecimal> pots, List<String> warnings) throws Exception {
        Method m = ClerkProcessEntryServiceImpl.class.getDeclaredMethod(
                "computePerProcessSeasoningCost", String.class, String.class, StepEntry.class, List.class, List.class);
        m.setAccessible(true);
        return (BigDecimal) m.invoke(service, F, PT, st, pots, warnings);
    }

    private boolean isSeasoningStep(StepEntry st) throws Exception {
        Method m = ClerkProcessEntryServiceImpl.class.getDeclaredMethod(
                "isSeasoningStep", String.class, String.class, StepEntry.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, F, PT, st);
    }

    // ── builders ──
    private WorkProcess wp(String id, String name, String category) {
        WorkProcess w = new WorkProcess();
        w.setId(id);
        w.setFactoryId(F);
        w.setProcessName(name);
        w.setProcessCategory(category);
        return w;
    }

    private StepEntry step(String name) {
        StepEntry st = new StepEntry();
        st.setProcessName(name);
        st.setProcessOrder(1);
        st.setInputQuantity(new BigDecimal("999")); // 注射绝对量口径下不应被用作 basis
        return st;
    }

    private BomSeasoningItem line(String section, String dosageG, String price) {
        BomSeasoningItem i = new BomSeasoningItem();
        i.setSection(section);
        i.setDosagePerKgG(new BigDecimal(dosageG));
        i.setPriceSource1(new BigDecimal(price));
        i.setCountInSeasoning(true);
        return i;
    }

    private void stubRecipe() {
        BomRecipe r = new BomRecipe();
        r.setId(RECIPE);
        r.setSubsequentPotRatio(new BigDecimal("0.3333"));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, PT))
                .thenReturn(Optional.of(r));
    }

    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("熟制工序: 只算本工序卤料, 第二锅按该工序比例")
    void cookingStep_scopedCookingCost() throws Exception {
        WorkProcess cook = wp("WP-COOK", "熟制", "熟制");
        when(workProcessRepository.findByFactoryIdAndProcessName(F, "熟制")).thenReturn(List.of(cook));
        stubRecipe();
        // cookPerKg = 1000g/kg /1000 × 3 = 3.0
        when(bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(RECIPE, "WP-COOK"))
                .thenReturn(List.of(line("COOKING", "1000", "3")));
        BomProcessSeasoning param = new BomProcessSeasoning();
        param.setSubsequentPotRatio(new BigDecimal("0.5"));
        when(bomProcessSeasoningRepository.findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(RECIPE, "WP-COOK"))
                .thenReturn(Optional.of(param));

        // 两锅各 100kg: 100×3×1 + 100×3×0.5 = 300 + 150 = 450
        BigDecimal cost = computePerProcess(step("熟制"),
                List.of(new BigDecimal("100"), new BigDecimal("100")), new ArrayList<>());
        assertEquals(0, new BigDecimal("450.0000").compareTo(cost), "熟制成本按该工序比例算");
    }

    @Test
    @DisplayName("注射工序: 绝对注射量 × 内容每kg单价 (不用报工生料重)")
    void injectionStep_absoluteAmount() throws Exception {
        WorkProcess inj = wp("WP-INJ", "注射", "注射");
        when(workProcessRepository.findByFactoryIdAndProcessName(F, "注射")).thenReturn(List.of(inj));
        stubRecipe();
        // injPerKg = 1000g/kg /1000 × 2 = 2.0
        when(bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(RECIPE, "WP-INJ"))
                .thenReturn(List.of(line("INJECTION", "1000", "2")));
        BomProcessSeasoning param = new BomProcessSeasoning();
        param.setInjectionAmountKg(new BigDecimal("5")); // 绝对 5kg
        when(bomProcessSeasoningRepository.findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(RECIPE, "WP-INJ"))
                .thenReturn(Optional.of(param));

        // 5 × 2.0 = 10 (与 step.inputQuantity=999 无关 → 证明用绝对量不用生料重)
        BigDecimal cost = computePerProcess(step("注射"), null, new ArrayList<>());
        assertEquals(0, new BigDecimal("10.0000").compareTo(cost), "注射成本=绝对量×每kg单价");
    }

    @Test
    @DisplayName("F3: 注射配了内容但没填注射量 → 记 0 且加 warning, 不静默")
    void injectionStep_noAmount_warnsAndZero() throws Exception {
        WorkProcess inj = wp("WP-INJ", "注射", "注射");
        when(workProcessRepository.findByFactoryIdAndProcessName(F, "注射")).thenReturn(List.of(inj));
        stubRecipe();
        when(bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(RECIPE, "WP-INJ"))
                .thenReturn(List.of(line("INJECTION", "1000", "2")));
        BomProcessSeasoning param = new BomProcessSeasoning(); // injectionAmountKg = null
        when(bomProcessSeasoningRepository.findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(RECIPE, "WP-INJ"))
                .thenReturn(Optional.of(param));

        List<String> warnings = new ArrayList<>();
        BigDecimal cost = computePerProcess(step("注射"), null, warnings);
        assertEquals(0, BigDecimal.ZERO.compareTo(cost), "缺注射量 → 0");
        assertFalse(warnings.isEmpty(), "缺注射量必须有 warning (F3)");
    }

    @Test
    @DisplayName("F1 防重复计: 工序标了熟制但没配调料 → 不认作 per-工序 调味步, 不触发整-SKU 回退")
    void taggedCookingButNoConfig_notPerProcessSeasoning() throws Exception {
        // 一个被重新归类为「熟制」的共享工序 (名字不含 熟/卤/煮…), 但没配任何 per-工序 调料
        WorkProcess shared = wp("WP9", "低温杀菌", "熟制");
        when(workProcessRepository.findByFactoryIdAndProcessName(F, "低温杀菌")).thenReturn(List.of(shared));
        stubRecipe();
        when(bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(RECIPE, "WP9"))
                .thenReturn(List.of()); // 无 per-工序 明细
        when(bomProcessSeasoningRepository.findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(RECIPE, "WP9"))
                .thenReturn(Optional.empty()); // 无参数

        StepEntry st = step("低温杀菌");
        // per-工序 路径不激活 → 返 null → 调用方落原路径(而非把整-SKU 成本再算一遍)
        assertNull(computePerProcess(st, List.of(new BigDecimal("100")), new ArrayList<>()),
                "无配置 → per-工序 返 null");
        // 且不因工序类别=熟制 就被认作调味步 (名字无正则命中, 无 potCount) → false, 防 F1 double-count
        assertFalse(isSeasoningStep(st), "标熟制但没配调料 + 名字不命中正则 → 不认作调味步");
    }

    @Test
    @DisplayName("零回归: 无 BOM 配方时 per-工序 返 null (落原 product_recipes 路径)")
    void noBom_returnsNull() throws Exception {
        WorkProcess cook = wp("WP-COOK", "熟制", "熟制");
        when(workProcessRepository.findByFactoryIdAndProcessName(F, "熟制")).thenReturn(List.of(cook));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, PT))
                .thenReturn(Optional.empty());

        assertNull(computePerProcess(step("熟制"), List.of(new BigDecimal("100")), new ArrayList<>()),
                "无 BOM → per-工序 返 null, 走原路径");
    }
}
