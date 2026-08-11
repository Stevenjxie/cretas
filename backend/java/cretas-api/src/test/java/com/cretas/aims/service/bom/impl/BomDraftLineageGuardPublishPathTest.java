package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 🔴 2026-08-11 真机: 「自动同步并发布」在 prod 被自己的版本线闸拒绝, 而拒绝提示写的是
 * 「请回到画布点『自动同步并发布』」—— 出口指向的正是被堵死的那个动作。
 *
 * <h2>为什么会这样</h2>
 * 版本线闸 {@code requireDraftMatchesEnabledWorkflow} 是 2026-08-09 为**手动「生效该草稿」**
 * 加的, 判据取「当前启用的工艺记录」。但发布链路上它跑在切换启用记录**之前**:
 *
 * <pre>
 * publishAndActivate
 *   └─ publishInternal
 *        └─ synchronizeForPublish → synchronizeActiveBomToWorkflowRevision
 *             └─ activateRecipe → requireDraftMatchesEnabledWorkflow   ← 闸在这里
 *   └─ workflowActivationService.activate(published.getId())            ← 启用记录在这里才切
 * </pre>
 *
 * 改画布会分叉出新的 workflow 记录(158 → 162), 同步草稿被重钉到 162, 而此刻启用的仍是 158
 * ⇒ 闸必然判不等。**凡是「已有启用记录 + 重新发布」都会被拦**, 首次发布因为没有启用记录反而能过。
 *
 * <p>prod 实证: 闸落地(2026-08-09 00:33)之后成功的发布只有 2 条, 全部 {@code definition_version=1}
 * (首发), 重发布 0 条; 同时 3 个产品卡着发不出去的草稿(F006 ×1 / LIUSHANMEN ×2)。
 *
 * <h2>修法</h2>
 * 放行集合 = {当前启用的} ∪ {本次事务正在发布的那条}。发布成功后启用记录立刻被切到后者
 * (同一 {@code @Transactional} 内), 所以放行之后读取侧看到的是一致状态; 发布失败则整体回滚。
 *
 * <p>⚠️ 这个类测的是**闸的判断本身**(直接调私有方法), 不是整条发布链路。链路上「有没有真的
 * 把正在发布的那条 id 传进来」由 {@link com.cretas.aims.service.workflow.PublishBomSyncOrderTest}
 * 的源码顺序契约钉住 —— 两者缺一不可。
 */
@DisplayName("🔴 版本线闸: 发布链路必须放行「正在发布的那条工艺」, 手动生效路径照旧拦住")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomDraftLineageGuardPublishPathTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "0a095c3d-bd1e-4f30-8758-ff11367b0889";
    /** 当前启用的工艺记录 (prod 实值)。 */
    private static final Long ENABLED_WORKFLOW = 158L;
    /** 本次正在发布的工艺记录 (prod 实值, 画布改动分叉出来的新记录)。 */
    private static final Long PUBLISHING_WORKFLOW = 162L;
    /** 既不是启用的也不是正在发布的 —— 真正的旧版本线。 */
    private static final Long STALE_WORKFLOW = 154L;

    @Mock
    private ProductProcessWorkflowActivationRepository workflowActivationRepo;

    /**
     * 用 {@code @InjectMocks} 而不是手写构造 —— 这个类的构造参数有二十多个, 手写等于把一张
     * 会漂移的清单抄进测试里(判据里出现手写清单就是缺陷源)。闸只碰 activation 仓储, 其余留 null。
     */
    @org.mockito.InjectMocks
    private BomRecipeServiceImpl service;

    @BeforeEach
    void setUp() {
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setActiveWorkflowId(ENABLED_WORKFLOW);
        activation.setEnabled(true);
        when(workflowActivationRepo.findByFactoryIdAndProductTypeId(FACTORY, PRODUCT))
                .thenReturn(Optional.of(activation));
    }

    private BomRecipe pinnedTo(Long workflowId) {
        BomRecipe recipe = new BomRecipe();
        recipe.setProductTypeId(PRODUCT);
        recipe.setWorkflowId(workflowId);
        return recipe;
    }

    private void invokeGuard(BomRecipe recipe, Long publishingWorkflowId) {
        ReflectionTestUtils.invokeMethod(
                service, "requireDraftMatchesEnabledWorkflow",
                FACTORY, List.of(recipe), publishingWorkflowId);
    }

    @Test
    @DisplayName("发布链路: 草稿钉着【正在发布的】那条 ⇒ 放行 (否则出口指向被自己堵死的动作)")
    void publishPathAcceptsTheWorkflowBeingPublished() {
        assertThatCode(() -> invokeGuard(pinnedTo(PUBLISHING_WORKFLOW), PUBLISHING_WORKFLOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("变异对照: 发布链路里草稿钉着【第三条】旧工艺 ⇒ 仍然拦住")
    void publishPathStillRejectsAGenuinelyStaleLineage() {
        assertThatThrownBy(() -> invokeGuard(pinnedTo(STALE_WORKFLOW), PUBLISHING_WORKFLOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo("BOM_DRAFT_STALE_WORKFLOW_LINEAGE"));
    }

    @Test
    @DisplayName("变异对照: 手动「生效该草稿」(无正在发布的工艺) ⇒ 2026-08-09 那道闸原样保留")
    void manualActivationKeepsTheOriginalGuard() {
        assertThatThrownBy(() -> invokeGuard(pinnedTo(PUBLISHING_WORKFLOW), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo("BOM_DRAFT_STALE_WORKFLOW_LINEAGE"));
    }

    /**
     * 上面三条只证明「闸拿到 publishingWorkflowId 之后判得对」。链路上还有一半:
     * 发布路径有没有**真的把它传进来**。传 null 的话闸原样判死, 上面三条一条都不会红 ——
     * 这正是「写入侧全绿 ≠ 这条路能走通」。这里把两个发布侧调用点钉住。
     */
    @Test
    @DisplayName("接线: 发布链路的两个激活调用点都必须把「正在发布的那条工艺」传下去")
    void bothPublishSideCallSitesPassTheWorkflowBeingPublished() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java"));

        // 同步既有 ACTIVE BOM 到新修订(重发布走这条)
        assertThat(src)
                .as("synchronizeActiveBomToWorkflowRevision 的激活必须带上 targetRevision.getWorkflowId()")
                .contains("factoryId, syncDraft.getId(), operatorId, targetRevision.getWorkflowId()");
        // 没有 ACTIVE BOM 时的投影(有启用记录却被归档/删过时会走到)
        assertThat(src)
                .as("projectActiveBomFromRevision 的激活同样在发布事务内, 也必须带上")
                .contains("factoryId, draft.getId(), operatorId, targetRevision.getWorkflowId()");

        // 对外入口必须继续传 null —— 手动「生效该草稿」不能借这个口子绕过 2026-08-09 那道闸。
        assertThat(src)
                .as("public activateRecipe 必须显式传 null, 否则手动生效路径会被一起放行")
                .contains("return activateRecipe(factoryId, recipeId, operatorId, null);");
    }
}
