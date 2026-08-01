package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「发起人不能审批自己」这条规则在本仓有多个承载点, 历史上采购单修了而销售/调拨没跟,
 * 同一条规则长出了三种行为。本契约锁三件事:
 *
 * <ol>
 *   <li>统一语义的三处必须委托同一个 {@link SelfApprovalPolicy};</li>
 *   <li>承载点总数不得悄悄增加 —— 新增一处时强制先做「统一 or 独立」的归类决定;</li>
 *   <li>刻意保持独立语义的几处不得被顺手统一。</li>
 * </ol>
 *
 * <p>⚠️ 方法论: 这些承载点<b>不能靠中文措辞 grep</b>。盘点那两处写的是
 * 「发起人<b>、盘点录入人或提交人</b>不能审批自己」, 中间插了字, 用
 * {@code grep 发起人不能审批} 会漏掉一半。要按 error code 后缀
 * {@code SELF_APPROVAL_FORBIDDEN} 找。
 */
class SelfApprovalCarrierContractTest {

    private static final Path SRC = Path.of("src/main/java");

    /** 统一语义: 必须委托 SelfApprovalPolicy。 */
    private static final List<String> UNIFIED = List.of(
            "com/cretas/aims/service/inventory/impl/SalesServiceImpl.java",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java",
            "com/cretas/aims/service/inventory/impl/TransferServiceImpl.java");

    /** 刻意独立: 不得委托(要改先改 spec)。 */
    private static final List<String> INDEPENDENT = List.of(
            "com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java",
            "com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java");

    /** 已登记的承载点总数: 采购/销售/调拨各 1 + 盘点 2 + 冲销 1。 */
    private static final int REGISTERED_CARRIERS = 6;

    /** 只匹配真实抛错构造, 避开注释里提到该码的情况。 */
    private static final Pattern CARRIER_PATTERN =
            Pattern.compile("\\.withCode\\(\"[A-Z_]*SELF_APPROVAL_FORBIDDEN\"\\)");

    private String read(String relative) throws IOException {
        return Files.readString(SRC.resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("统一语义的三处都委托同一策略")
    void unifiedCarriersDelegateToPolicy() throws IOException {
        for (String relative : UNIFIED) {
            assertThat(read(relative))
                    .as(relative + " 必须委托 SelfApprovalPolicy.allowsSelfApproval, "
                            + "否则同一条规则又会各处各行为")
                    .contains("selfApprovalPolicy.allowsSelfApproval");
        }
    }

    @Test
    @DisplayName("采购单不再保留私有例外实现")
    void purchaseNoLongerKeepsPrivateCopy() throws IOException {
        assertThat(read("com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java"))
                .as("isExplicitCurrentNodeApprover 已移动到 SelfApprovalPolicy, "
                        + "此处不应残留私有副本 —— 它正是另两处无法复用的原因")
                .doesNotContain("private boolean isExplicitCurrentNodeApprover");
    }

    @Test
    @DisplayName("自审校验承载点总数未悄悄增加")
    void carrierCountUnchanged() throws IOException {
        int found;
        try (Stream<Path> walk = Files.walk(SRC)) {
            found = walk.filter(p -> p.toString().endsWith(".java"))
                    .mapToInt(this::countMarkers)
                    .sum();
        }
        assertThat(found)
                .as("自审校验承载点数变了(登记 %d, 实际 %d)。新增一处时必须先决定它属于"
                        + "『统一语义』(委托 SelfApprovalPolicy) 还是『独立业务语义』"
                        + "(如盘点仅在盘盈亏时以 409 拦截), 再同步本契约与 spec "
                        + "docs/superpowers/specs/2026-08-01-oa-self-approval-and-budget-design.md",
                        REGISTERED_CARRIERS, found)
                .isEqualTo(REGISTERED_CARRIERS);
    }

    @Test
    @DisplayName("独立语义的几处未被顺手统一")
    void independentCarriersNotAbsorbed() throws IOException {
        for (String relative : INDEPENDENT) {
            assertThat(read(relative))
                    .as(relative + " 是独立业务语义, 不应委托 SelfApprovalPolicy。"
                            + "若确实要统一, 先改 spec 再改这里")
                    .doesNotContain("selfApprovalPolicy.allowsSelfApproval");
        }
    }

    /**
     * 只数真实的抛错构造 {@code .withCode("...SELF_APPROVAL_FORBIDDEN")}, 不数注释。
     *
     * <p>⚠️ 第一版这里数的是裸字符串 {@code SELF_APPROVAL_FORBIDDEN} 出现次数, 结果把
     * {@link SelfApprovalPolicy} 自己 Javadoc 里提到的两次也算了进去(6 变 8)。
     * 计数契约要数<b>代码构造</b>而不是<b>字符出现</b>, 否则谁写篇注释提到它就假红。
     */
    private int countMarkers(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            return (int) CARRIER_PATTERN.matcher(source).results().count();
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
    }
}
