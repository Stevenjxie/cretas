package com.cretas.aims.service.production;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个「开工」入口必须产出同一种状态 —— 否则计划会卡进「开工了但没有批次」。
 *
 * <h2>2026-08-01 prod 实测的死锁</h2>
 *
 * <p>系统里有两个都叫「开工」的入口，做的事却不一样：
 *
 * <table>
 *   <tr><th></th><th>建批次</th><th>spawn 报工任务</th><th>置 IN_PROGRESS</th></tr>
 *   <tr><td>{@code createBatchFromPlan}（转批次 / 逐道录入前置）</td><td>✅</td><td>✅</td><td>✅</td></tr>
 *   <tr><td>{@code startProduction}（「开工」按钮）</td><td>❌</td><td>❌</td><td>✅</td></tr>
 * </table>
 *
 * <p>两个都只放行 {@code PENDING}，所以先点「开工」之后 {@code createBatchFromPlan}
 * <b>永远进不去</b>。而报工和结单都需要批次 → 计划只能作废重建。
 *
 * <p>触发它的是<b>最自然的点击顺序</b>：先「开工」再「逐道录入」。前端
 * {@code views/production/plans/list.vue} 的 {@code openProcessEntry} 只在
 * {@code status === 'PENDING'} 时才自动补建批次 —— 它把「状态是 IN_PROGRESS」
 * 当成了「已经有批次」的证据，而 {@code startProduction} 恰恰打破了这个假设。
 *
 * <p>prod 实测 7 个计划卡在这个状态（F001 6 个，最早 2026-03-12；六膳门 1 个）。
 *
 * <h2>为什么钉在源码上而不是跑一遍</h2>
 *
 * <p>{@code ProductionPlanServiceImpl} 的构造函数有几十个依赖，跑通一次真实
 * {@code startProduction} 需要把 workflow 解析、任务 spawn、库存校验全部搭起来 ——
 * 而那样搭出来的用例，一旦有人把 {@code createBatchFromPlan} 调用删掉，
 * mock 会安静地返回 null，用例照样绿。
 *
 * <p>这里要守的性质是「{@code startProduction} 必须走到建批次那条路」，
 * 它是<b>结构性</b>的，所以直接对源码断言。同类做法见
 * {@code BlockingErrorsCarryActionHintTest}。
 *
 * <p>⚠️ 本文件本身可被验伪：把 {@code startProduction} 里那行
 * {@code createBatchFromPlan(...)} 删掉，{@link #startProductionMustCreateBatch()} 立刻变红。
 */
@DisplayName("两个「开工」入口必须收敛 —— 开工不建批次 = 计划当场死锁")
class ProductionStartEntryPointsConvergeTest {

    private static final Path SERVICE = Paths.get(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");
    private static final Path PLAN_LIST_VUE = Paths.get(
            "../../../web-admin/src/views/production/plans/list.vue");

    private String source() throws IOException {
        return Files.readString(SERVICE, StandardCharsets.UTF_8);
    }

    /** 取 startProduction 方法体（到下一个 {@code @Override} 为止）。 */
    private String startProductionBody() throws IOException {
        String src = source();
        int begin = src.indexOf("public ProductionPlanDTO startProduction(");
        assertThat(begin).as("没找到 startProduction, 后面的断言全部无效").isGreaterThan(0);
        int end = src.indexOf("@Override", begin);
        assertThat(end).as("没找到方法结尾").isGreaterThan(begin);
        return src.substring(begin, end);
    }

    @Test
    @DisplayName("阳性对照: 源码读得到且两个方法都在")
    void positiveControl() throws IOException {
        String src = source();
        assertThat(src).contains("public ProductionPlanDTO startProduction(");
        assertThat(src).contains("public ProductionBatch createBatchFromPlan(");
    }

    @Test
    @DisplayName("🔴 startProduction 必须建批次 —— 只翻状态就是把计划做死")
    void startProductionMustCreateBatch() throws IOException {
        assertThat(startProductionBody())
                .as("开工只置 IN_PROGRESS 不建批次 → 报工/结单/补建三条路全堵死, "
                        + "而 createBatchFromPlan 也只放行 PENDING, 用户只能作废重建")
                .contains("materializeBatchForPlan(factoryId, plan)");
    }

    @Test
    @DisplayName("🔴 两个入口必须调同一个物化实现 —— 各写一半正是本 bug 的成因")
    void bothEntryPointsShareOneImplementation() throws IOException {
        String src = source();
        int declarations = src.split("private ProductionBatch materializeBatchForPlan\\(", -1).length - 1;
        assertThat(declarations).as("物化实现必须唯一").isEqualTo(1);

        int callSites = src.split("materializeBatchForPlan\\(factoryId, plan\\)", -1).length - 1;
        assertThat(callSites)
                .as("两个「开工」入口(startProduction / createBatchFromPlan)各调一次")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("⛔ 不许经 createBatchFromPlan 绕一圈 —— 那会在同事务里二次取悲观锁")
    void startProductionMustNotReenterCreateBatchFromPlan() throws IOException {
        assertThat(startProductionBody())
                .as("调 createBatchFromPlan 会第二次 findByIdForUpdate(同事务重入, 无害但多一次"
                        + "SELECT FOR UPDATE 往返), 且破坏 R6 用例「取锁至多一次」的断言")
                .doesNotContain("createBatchFromPlan(");
    }

    @Test
    @DisplayName("⛔ 不许绕过 createBatchFromPlan 自己 new 一个批次 —— 那会漏掉 spawn 报工任务")
    void startProductionMustNotHandRollItsOwnBatch() throws IOException {
        String body = startProductionBody();
        assertThat(body)
                .as("自己拼批次会漏掉 spawnTasks / workflow pin 校验 / 产线与主管映射, "
                        + "又变成第二套实现 —— 这正是本 bug 的成因")
                .doesNotContain("ProductionBatch.builder()");
    }

    @Test
    @DisplayName("WIP 扣减刻意留在 startProduction —— 下沉会让逐工序录入二次扣减")
    void wipDeductionStaysOutOfCreateBatch() throws IOException {
        String src = source();
        int startBegin = src.indexOf("public ProductionPlanDTO startProduction(");
        int createBegin = src.indexOf("public ProductionBatch createBatchFromPlan(");
        int deduct = src.indexOf("deductForSecondaryPlan(");

        assertThat(deduct).as("找不到 WIP 扣减调用").isGreaterThan(0);
        assertThat(deduct)
                .as("扣减必须在 startProduction 里, 不能挪进 createBatchFromPlan —— "
                        + "逐工序录入(ClerkProcessEntryServiceImpl)已按 edges 精确扣过一次, "
                        + "两处都扣就是幽灵超扣")
                .isGreaterThan(startBegin)
                .isLessThan(createBegin);
    }

    @Test
    @DisplayName("R6 并发守卫仍在: createBatchFromPlan 不许放行「IN_PROGRESS 且无批次」去补建")
    void createBatchStillRefusesStartedPlanWithoutBatch() throws IOException {
        assertThat(source())
                .as("放宽这道门会让并发第二个请求在第一笔事务提交前建出第二个批次(R6)。"
                        + "本轮是从 startProduction 那头收敛, 不是把这道门拆掉")
                .contains("该计划已开工但没有生产批次");
    }

    /**
     * 前端那半 —— 它是这个 bug 的另一个承载点。
     *
     * <p>{@code openProcessEntry} 用 {@code status === 'PENDING'} 当作「还没有批次」的判据。
     * 后端收敛之后这个假设重新成立（开工必然带批次），但断言把它钉住：
     * 谁要是再让某条路径产生「IN_PROGRESS 且无批次」，这条注释就是现场说明。
     */
    @Test
    @DisplayName("前端那半: 逐道录入的自动补建仍以 PENDING 为判据 —— 后端必须保证它成立")
    void frontendStillAssumesPendingMeansNoBatch() throws IOException {
        if (!Files.exists(PLAN_LIST_VUE)) {
            return; // 只跑后端模块时（无 web-admin 工作副本）跳过, 不制造假红
        }
        String vue = Files.readString(PLAN_LIST_VUE, StandardCharsets.UTF_8);
        assertThat(vue).as("阳性对照: 读到的是计划列表页").contains("openProcessEntry");
        assertThat(vue)
                .as("前端仍靠 PENDING 判断要不要补建批次 —— 这就是后端必须保证"
                        + "「非 PENDING ⇒ 一定有批次」的原因")
                .contains("=== 'PENDING'");
    }
}
