package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingService.Result} 的第三态与向后兼容。 */
class FindingServiceResultTest {

    @Test
    @DisplayName("UT-RES-01: 5 参数重载仍可用，skippedRules 默认为空")
    void fiveArgOverloadKeepsExistingCallSitesCompiling() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("低库存"), 0, Map.of(), List.of());

        assertNotNull(r.skippedRules());
        assertTrue(r.skippedRules().isEmpty());
    }

    @Test
    @DisplayName("UT-RES-02: 🔴 skippedRules 不影响 complete() —— 「判不了」不是「查询失败」")
    void skippedDoesNotMakeResultIncomplete() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("损耗类型集中度"), 0, Map.of(), List.of(),
                List.of(new FindingService.SkippedRule("食材损耗离群", "基线历史不足")));

        assertTrue(r.complete(),
                "complete() 的语义是「没有规则报错」。数据不足是诚实跳过, 不是故障, "
                        + "混进去会让调用方把两种状况当成同一件事");
        assertEquals(1, r.skippedRules().size());
    }

    @Test
    @DisplayName("UT-RES-03: failedRules 才让 complete() 为假")
    void failedRulesMakeResultIncomplete() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of(), 0, Map.of(), List.of("临期"));

        assertFalse(r.complete());
    }

    @Test
    @DisplayName("UT-RES-04: SkippedRule 同时带规则名和理由 —— 只有名字说不出为什么")
    void skippedRuleCarriesReason() {
        FindingService.SkippedRule s =
                new FindingService.SkippedRule("食材损耗离群", "两期食材名单不可比");

        assertEquals("食材损耗离群", s.ruleName());
        assertEquals("两期食材名单不可比", s.reason());
    }

    @Test
    @DisplayName("UT-RES-05: FindingNotApplicableException 是非受检异常")
    void exceptionIsUnchecked() {
        assertTrue(RuntimeException.class.isAssignableFrom(FindingNotApplicableException.class),
                "FindingProvider#detect 不声明 throws, 受检异常会打断全部既有实现");
        assertEquals("基线历史不足", new FindingNotApplicableException("基线历史不足").reason());
    }
}
