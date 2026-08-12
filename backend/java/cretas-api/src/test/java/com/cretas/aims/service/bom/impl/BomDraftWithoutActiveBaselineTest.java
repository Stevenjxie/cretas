package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴 2026-08-12 (Steve 真机 + 拍板): 「哪怕没有 active 版本也应该可以改 BOM ——
 * 因为 BOM 是和 workflow 版本在一起的, 不该有自己独立的版本设计。」
 *
 * <h2>症状</h2>
 * 画布七个节点全搭好了, 点「加辅料」被 409 拒绝:
 * 「该产品没有唯一的当前生效 BOM, 无法安全创建新版本 / 请先修复版本状态」——
 * 而「修复版本状态」这个动作**没有任何界面**, 用户做不到。
 *
 * <h2>那道闸拦错了东西</h2>
 * 它防的是「该克隆哪一个 ACTIVE」的歧义。但 <b>&gt;1 的歧义在 ensureDraft 上方就已经
 * 用 BOM_CURRENT_ACTIVE_AMBIGUOUS 拦掉了</b>, 能走到那句 {@code != 1} 的**只可能是 0 个** ——
 * 而 0 个根本没有歧义: 没有可克隆的生产基线。
 *
 * <h2>正解</h2>
 * 「没有基线」与「一条版本都没有」是同一种局面 —— 都照**当前画布 revision** 投一份新草稿。
 * 这正是「画布是权威、BOM 是投影」的口径: 基线缺失时权威仍在画布上, 没有要猜的东西。
 *
 * <p>⚠️ 这不是「放宽闸」: 新草稿**不从归档版克隆**(那才是原用例名
 * 「instead of guessing a source」担心的事), 而是走绑定画布 revision 的投影路径。
 */
@DisplayName("🔴 没有 ACTIVE 基线时, BOM 草稿照画布投影而不是拒绝")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomDraftWithoutActiveBaselineTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PT_F006_LSM_ebd67589bf0d1acc";
    private static final Long REVISION = 4321L;

    @Mock private BomRecipeRepository recipeRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock private UnitContractService unitContractService;

    @InjectMocks private BomRecipeServiceImpl service;

    /** 历史上有一版, 但它已归档 —— 当前没有任何 ACTIVE/current。 */
    private BomRecipe archivedV1;

    @BeforeEach
    void setUp() {
        ProductType product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setName("叮咚好食光红烧猪蹄 250g");
        product.setUnit("box");
        // 净含量: validateProductOutputMetadata 要求它有效, 否则在到达本用例要证明的
        // 那个分叉之前就被拦下(实测: 少了它拿到的是「SKU 未配置有效净含量」)。
        product.setGramsPerUnit(new java.math.BigDecimal("250"));
        when(productTypeRepo.findByIdAndFactoryIdForUpdate(PRODUCT, FACTORY)).thenReturn(Optional.of(product));

        archivedV1 = new BomRecipe();
        archivedV1.setId("RECIPE-V1-ARCHIVED");
        archivedV1.setFactoryId(FACTORY);
        archivedV1.setProductTypeId(PRODUCT);
        archivedV1.setVersion(1);
        archivedV1.setStatus(BomRecipe.Status.ARCHIVED);
        archivedV1.setIsCurrent(false);

        when(recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc(FACTORY, PRODUCT))
                .thenReturn(List.of(archivedV1));
        when(recipeRepo.countByFactoryIdAndProductTypeId(FACTORY, PRODUCT)).thenReturn(1L);
        when(recipeRepo.countByRecipeCodePrefix(eq(FACTORY), any())).thenReturn(1L);
        when(recipeRepo.saveAndFlush(any(BomRecipe.class))).thenAnswer(invocation -> {
            BomRecipe saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId("RECIPE-PROJECTED");
            return saved;
        });
        // 单位归一: 让 box 被认成合法的包装单位, 否则在到达本用例要证明的分叉之前
        // 就会因为 outputUnit 认不出来而 NPE。
        CanonicalUnit box = new CanonicalUnit(
                "box", UnitDimension.PACKAGE, "box", java.math.BigDecimal.ONE,
                "盒", 0, java.util.Set.of(), null, true);
        when(unitContractService.normalize(any(), any()))
                .thenAnswer(inv -> new UnitNormalizationResult(inv.getArgument(1), "box", box));

        // 绑定这一步之后是整条家族初始化, 与本用例要证明的事无关 —— 用哨兵在这里停住,
        // 「绑定被调用」本身就是「走了投影路径」的证据。
        when(bomWorkflowRevisionService.bindExactRevision(any(), any(), any()))
                .thenThrow(new IllegalStateException("__BOUND_TO_CANVAS__"));
    }

    @Test
    @DisplayName("有历史版本但 0 个 ACTIVE ⇒ 不再抛 BOM_CURRENT_ACTIVE_REQUIRED")
    void doesNotRejectWhenNoActiveBaseline() {
        assertThatThrownBy(() -> service.ensureDraft(FACTORY, PRODUCT, REVISION))
                .satisfies(thrown -> {
                    if (thrown instanceof BusinessException business) {
                        assertThat(business.getErrorCode()).isNotEqualTo("BOM_CURRENT_ACTIVE_REQUIRED");
                    }
                })
                .hasMessageContaining("__BOUND_TO_CANVAS__");
    }

    @Test
    @DisplayName("走的是投影路径: 绑定到画布 revision, 且版本号接着历史往下(不撞号)")
    void projectsAgainstTheCanvasRevision() {
        assertThatThrownBy(() -> service.ensureDraft(FACTORY, PRODUCT, REVISION))
                .hasMessageContaining("__BOUND_TO_CANVAS__");

        ArgumentCaptor<BomRecipe> draft = ArgumentCaptor.forClass(BomRecipe.class);
        verify(bomWorkflowRevisionService).bindExactRevision(eq(FACTORY), draft.capture(), eq(REVISION));
        assertThat(draft.getValue().getVersion()).isEqualTo(2);          // max(1)+1, 不能从 1 重来
        assertThat(draft.getValue().getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(draft.getValue().getIsCurrent()).isFalse();
    }

    /**
     * ⛔ 原用例名「instead of guessing a source」担心的正是这件事 —— 那个顾虑仍然成立,
     * 只是解法不是拒绝, 而是**根本不克隆**。这条守住它。
     */
    @Test
    @DisplayName("⛔ 绝不从归档版克隆 —— 原顾虑的守卫")
    void neverClonesTheArchivedVersion() {
        assertThatThrownBy(() -> service.ensureDraft(FACTORY, PRODUCT, REVISION))
                .hasMessageContaining("__BOUND_TO_CANVAS__");

        // cloneRecipe 会去读被克隆版本的明细; 一次都不该发生。
        verify(recipeRepo, never()).findById(archivedV1.getId());
    }
}
