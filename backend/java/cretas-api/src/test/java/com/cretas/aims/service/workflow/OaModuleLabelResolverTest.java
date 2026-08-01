package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待办列表「业务类型」列的中文名解析。
 *
 * <p>成因见 {@link ModuleLabelCoverageTest}: 前端手抄了权威表的一小部分, 其余全落
 * 「未知状态（X）」兜底。修法是让后端按权威表下发 moduleLabel, 前端不再维护第二份表。
 */
class OaModuleLabelResolverTest {

    private OaModuleLabelResolver resolver;

    @BeforeEach
    void setUp() {
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        resolver = new OaModuleLabelResolver(registry);
    }

    @Test
    @DisplayName("常规 moduleCode 取权威表的中文名")
    void resolvesFromAuthority() {
        assertThat(resolver.resolve("PURCHASE_ORDER", null)).isEqualTo("采购订单审批");
        assertThat(resolver.resolve("SALES_ORDER", null)).isEqualTo("销售订单审批");
    }

    @Test
    @DisplayName("BUDGET 一码多用 —— 会计期间结账要细化, 不能泛称「预算审批」")
    void budgetIsRefinedForAccountingPeriod() {
        // 取自 prod 实例 153f4e39-5ac4-4800-864f-a9f1d7c53459 的真实 context
        Map<String, Object> context = Map.of(
                "year", 2026,
                "month", 7,
                "periodId", "b67922a2-e4b9-4143-bd6e-33d42ed98ae0",
                "entityType", "ACCOUNTING_PERIOD");
        assertThat(resolver.resolve("BUDGET", context))
                .as("BUDGET 的 description 写着「预算 + 超预算授权 + 期间结账审批」, "
                        + "泛称对期间结账不够准")
                .isEqualTo("会计期间结账");
    }

    @Test
    @DisplayName("BUDGET 非会计期间时回到权威表的泛称")
    void budgetFallsBackToAuthorityWhenNotAccountingPeriod() {
        assertThat(resolver.resolve("BUDGET", Map.of("entityType", "ANNUAL_BUDGET")))
                .isEqualTo("预算审批");
        assertThat(resolver.resolve("BUDGET", null)).isEqualTo("预算审批");
        assertThat(resolver.resolve("BUDGET", Map.of())).isEqualTo("预算审批");
    }

    @Test
    @DisplayName("只认 entityType 键, 别的字段恰好含该串不算数")
    void onlyEntityTypeKeyCounts() {
        // 若实现是对整段 JSON 做 contains, 这里会被误判成会计期间
        assertThat(resolver.resolve("BUDGET",
                Map.of("remark", "本次不是 ACCOUNTING_PERIOD 的审批")))
                .isEqualTo("预算审批");
    }

    @Test
    @DisplayName("未知 moduleCode 返回 null —— 交给前端兜底, 后端不编造")
    void unknownModuleCodeReturnsNull() {
        assertThat(resolver.resolve("NOT_A_REAL_MODULE", null)).isNull();
        assertThat(resolver.resolve(null, null)).isNull();
        assertThat(resolver.resolve("", null)).isNull();
    }

    @Test
    @DisplayName("registry 缺席时返回 null 而不是抛异常")
    void missingRegistryDegradesGracefully() {
        assertThat(new OaModuleLabelResolver(null).resolve("PURCHASE_ORDER", null)).isNull();
    }
}
