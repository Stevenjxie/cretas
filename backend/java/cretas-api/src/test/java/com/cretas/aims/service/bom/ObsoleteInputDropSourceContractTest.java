package com.cretas.aims.service.bom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * reconcileUpgradedInputSkeletons 的丢弃语义。
 *
 * 这段逻辑是私有方法、依赖整条 workflow 修订解析链, 本仓的 IT 又不在 PR 上跑,
 * 所以用源码级断言把三条不变量钉住。它守的是**行为**而不是某一行写法:
 * 每条都配了能让它红的变异(见 PR 里的变异对照)。
 */
@DisplayName("旧工艺遗留投入的丢弃语义(源码级)")
class ObsoleteInputDropSourceContractTest {

    private static String source;

    @BeforeAll
    static void loadSource() throws Exception {
        Path p = Path.of("src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java");
        source = Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 剥掉注释, 免得断言命中的是解释这段逻辑的注释本身。 */
    private static String code() {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("默认仍然抛 409 —— 加了确认参数不等于默认变成静默丢弃")
    void stillThrowsWithoutConfirmation() {
        String c = code();
        int guard = c.indexOf("if (!dropObsoleteConfirmed) {");
        assertTrue(guard > 0, "缺少「未确认则抛」的分支");
        int thrown = c.indexOf("BOM_WORKFLOW_UPGRADE_OBSOLETE_INPUT");
        assertTrue(thrown > guard, "409 必须落在未确认分支之内, 否则确认后也会抛");
    }

    @Test
    @DisplayName("删的是服务端自己算出的 obsoleteBoundItems, 不接受客户端送来的 id 列表")
    void deletesTheServerComputedSet() {
        String c = code();
        int at = c.indexOf("if (!dropObsoleteConfirmed) {");
        String tail = c.substring(at, Math.min(at + 1200, c.length()));
        assertTrue(tail.contains("for (BomRecipeItem stale : obsoleteBoundItems)"),
                "必须遍历服务端算出的 obsoleteBoundItems");
        assertTrue(tail.contains("stale.softDelete()"), "必须是软删, 不是硬删");
        assertFalse(tail.contains("request.get") || tail.contains("itemIds"),
                "不许按客户端送来的 id 删 —— 判定权不能外移");
    }

    @Test
    @DisplayName("🔒 带确认的那条路只可能作用在 DRAFT 上 —— 绝不从 ACTIVE 里删行")
    void confirmedPathCanOnlyTouchADraft() {
        String c = code();
        // 这是本次改动安全性的**关键性质**: 删除作用于传入的 family, 而带 dropObsoleteInputs
        // 的两条调用路径都只可能拿到 DRAFT ——
        //   · rebindDraftFamilyToExactRevision 顶部就断言 status != DRAFT → 抛;
        //   · ensureDraft 的另一条分支操作的本就是 draft 对象。
        // 从 ACTIVE 配方里删行会直接改动生产成本, 那正是我最初想放宽 deleteItem 时
        // 差点做错的事。这条断言防止将来有人把 4 参重载接到某条 ACTIVE 路径上。
        // 单行锚点: 3 参重载是 "Long targetRevisionId) {", 只有 4 参那个以逗号结尾。
        int rebind = c.indexOf("Long targetRevisionId,");
        assertTrue(rebind > 0, "找不到带确认参数的 rebind 重载");
        String body = c.substring(rebind, Math.min(rebind + 500, c.length()));
        assertTrue(body.contains("requested.getStatus() != BomRecipe.Status.DRAFT"),
                "带确认的 rebind 必须保留「只有草稿可切换修订」这道闸 —— 它是"
                        + "「确认丢弃只作用于 DRAFT」的结构保证");
    }

    @Test
    @DisplayName("激活路径不吃这个确认 —— 生效时遇到孤儿行必须照旧拦住")
    void activationPathStaysStrict() {
        String c = code();
        int activate = c.indexOf("requireDraftMatchesEnabledWorkflow(factoryId, family, publishingWorkflowId);");
        assertTrue(activate > 0, "找不到激活路径锚点");
        String tail = c.substring(activate, Math.min(activate + 400, c.length()));
        assertTrue(tail.contains("reconcileUpgradedInputSkeletons(factoryId, family);"),
                "激活必须走 2 参(严格)重载, 不许把用户确认带进生效");
    }
}
