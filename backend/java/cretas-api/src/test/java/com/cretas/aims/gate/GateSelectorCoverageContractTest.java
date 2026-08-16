package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码扫描型闸<b>必须落在 CI 选择器的匹配面里</b>，否则它只是被编译，不会被执行。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>CI 的 {@code java-build-test} 在 push 上只跑一个窄选择器（见 {@code .github/workflows/ci.yml}）。
 * 仓里有 1277 个测试类，选择器命中的只是其中一小撮。<b>编译错误抓得到，断言红了看不见。</b>
 *
 * <p>{@code *StartupGuardTest} 这个后缀当初就是为此立的约定 —— ci.yml 里写着
 * 「以后新写的启动期守卫按这个后缀命名就自动被捞进来，不用再改 CI（改 CI 这件事没人会记得，
 * 于是新闸又不跑）」。但约定只有<b>人记得</b>才生效，而这正是它想解决的那个问题。
 *
 * <p>实测：2026-08-15 全仓 46 个源码扫描型闸里，<b>29 个</b>不匹配任何约定后缀 ——
 * 一条都没在 CI 上跑过。同一天新写的 {@code PurchaseOrderIdempotency…} 闸也是其中之一，
 * 它是「把选择器从 3 项扩到 4 项」之后<b>仍然</b>不会被执行的那一个。
 *
 * <h2>这条闸守什么</h2>
 *
 * <p>守的是<b>耦合</b>：「一个测试会去读 {@code src/main} 下的源码」⟺「它的类名落在选择器的
 * 匹配面里」。前者是「它是一道闸」的机械信号（不是启发式判断「它重不重要」），
 * 后者是「它会被执行」的必要条件。
 *
 * <h2>为什么是棘轮而不是硬红</h2>
 *
 * <p>建闸当天存量就有 29 个。一上来硬红，就是一道当天被加 {@code @Disabled} 的闸
 * —— 那时它的覆盖率归零。所以冻结存量、<b>只禁增长</b>：名单里的慢慢还，
 * 名单外新增的当场红。
 *
 * <p>⚠️ 名单是<b>债务登记</b>，不是豁免。它同时守两个方向：既不许新增，
 * 也不许让已删除的条目烂在名单里（还清了就必须从名单里划掉）。
 */
@DisplayName("闸的选择器覆盖：源码扫描型闸必须能被 CI 执行")
class GateSelectorCoverageContractTest {

    private static final Path TEST_ROOT = Paths.get("src/test/java");

    /**
     * 与 {@code .github/workflows/ci.yml} 的 {@code TARGET_TESTS} 逐字对应。
     * ⚠️ 改那边必须同时改这边 —— 这两份是同一个东西的两个副本，抽不成一份（一份是 YAML，
     * 一份是 Java），所以由本闸钉住它们一致的那个后果：名单外的闸跑不到就红。
     */
    private static final List<String> SELECTOR_SUFFIXES = List.of(
            "RepositoryQueryValidationTest",
            "StartupGuardTest",
            "ContractTest");

    /** 选择器里逐字列出的单个类（非后缀通配）。 */
    private static final Set<String> SELECTOR_EXACT = Set.of("FlywayVersionUniquenessTest");

    /**
     * 选择器里被<b>显式排除</b>的类，连同理由。<b>当前为空 —— 这是它应有的状态。</b>
     *
     * <p>2026-08-15 建闸时这里登记过 {@code SkuUnitStorageIsCodeContractTest}
     * （它有 2 条一直红的断言，守的是已被有意改掉的行为）。2026-08-16 已还清并摘掉：
     * 那条闸守的性质改成「两侧都走同一个权威出口 {@code storageUnit}」，恢复进选择器。
     *
     * <p>⛔ 往这里加一个类 = 让它退回「编译得到、断言看不见」，正是本闸要解决的问题。
     * 只有在「该类的红是存量欠账且短期修不了」时才可临时登记，并必须写明由谁、何时还。
     */
    private static final Set<String> SELECTOR_EXCLUDED = Set.of();

    /**
     * 存量欠账：会读 {@code src/main} 但类名进不了选择器的闸。<b>冻结于 2026-08-15，只许变短。</b>
     */
    private static final Set<String> KNOWN_UNCOVERED = Set.of(
            "ApprovedToolSensitiveLoggingTest",
            "BlockingErrorsCarryActionHintTest",
            "BomDraftLineageGuardPublishPathTest",
            "BomRecipeCostNullCaliberTest",
            "BomSeasoningBindingIntegrityTest",
            "BomWorkflowSlotOrphanTest",
            "DeactivateRestaurantTenantsMigrationTest",
            "DemoIdentityDisabledTest",
            "FactoryCapabilityPackArchitectureTest",
            "FindingNavigationTest",
            "FormAssistantSuccessNotSelfReportedTest",
            "MissingCountUnitSeedMigrationTest",
            "MobileToolPromptsDoNotAskForIdsTest",
            "PackagingLegacyPricingUnlockTest",
            "ProductionStartEntryPointsConvergeTest",
            "PublishBomSyncOrderTest",
            "PurchaserWorkdeskRoleDeclarationTest",
            "RestaurantAgentWorkflowLabelMigrationParityTest",
            "RestaurantDepartmentPermissionMigrationTest",
            "RestaurantTenantDetectionTest",
            "SalesDeliveryProductFkAbsenceTest",
            "SkuImportUnitCanonicalizationTest",
            "StringConstantStatusEqualityGuardTest",
            "ToolSimilarityGateRunsTest",
            "TransferVoucherGeneratedOnConfirmTest",
            "TransferVoucherVoidOnTerminationTest",
            "UnitAuthorityConsistencyTest",
            "UserFacingMessagesAreChineseTest",
            "VoucherBackfillScopeTest");

    /** 一个测试类只要在源码里出现 {@code "src/main}，就认定它在扫产品源码。 */
    private static final String SOURCE_SCANNING_MARKER = "\"src/main";

    private Set<String> sourceScanningGates() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(TEST_ROOT)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .toList()) {
                if (Files.readString(p, StandardCharsets.UTF_8).contains(SOURCE_SCANNING_MARKER)) {
                    String name = p.getFileName().toString();
                    found.add(name.substring(0, name.length() - ".java".length()));
                }
            }
        }
        return found;
    }

    private boolean coveredBySelector(String className) {
        if (SELECTOR_EXCLUDED.contains(className)) {
            return false;
        }
        if (SELECTOR_EXACT.contains(className)) {
            return true;
        }
        return SELECTOR_SUFFIXES.stream().anyMatch(className::endsWith);
    }

    @Test
    @DisplayName("阳性对照：真的找得到源码扫描型闸（找不到最像「一切正常」）")
    void positiveControl() throws IOException {
        Set<String> gates = sourceScanningGates();
        assertThat(gates)
                .as("一个都没找到 —— 那是仪器坏了, 不是仓里没有闸")
                .isNotEmpty();
        assertThat(gates)
                .as("本闸自己必须在结果里, 否则扫描口径已经漂了")
                .contains("GateSelectorCoverageContractTest");
    }

    @Test
    @DisplayName("🔴 新增的源码扫描型闸必须能被 CI 选择器执行")
    void newSourceScanningGatesMustBeSelectable() throws IOException {
        Set<String> uncovered = new TreeSet<>(sourceScanningGates());
        uncovered.removeIf(this::coveredBySelector);
        uncovered.removeAll(KNOWN_UNCOVERED);
        uncovered.removeAll(SELECTOR_EXCLUDED);

        assertThat(uncovered)
                .as("""
                        这些闸会读 src/main 的源码, 但类名进不了 CI 选择器 —— 它们只会被编译, 不会被执行。
                        改名成 *ContractTest / *StartupGuardTest 之一即可(不用改 CI)。
                        ⛔ 不要把它们塞进 KNOWN_UNCOVERED —— 那份名单已冻结, 只许变短。""")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 欠账名单只许变短：已还清的条目必须从名单里划掉")
    void debtListMustNotRot() throws IOException {
        Set<String> gates = sourceScanningGates();
        Set<String> stale = new TreeSet<>(KNOWN_UNCOVERED);
        stale.removeIf(name -> gates.contains(name) && !coveredBySelector(name));

        assertThat(stale)
                .as("这些条目已经不再是「未覆盖的源码扫描型闸」(改名了/删了/不再扫源码), "
                        + "请从 KNOWN_UNCOVERED 里删掉 —— 名单烂掉之后就分不清还欠多少")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 显式排除的类必须真的存在，理由过期要一起清")
    void exclusionsMustStillExist() throws IOException {
        // ⚠️ 空集上 containsAll 恒真 —— 所以先把「现在到底排除了几个」显式读出来,
        //    不让这条断言在空集时静默变成恒真式。
        if (SELECTOR_EXCLUDED.isEmpty()) {
            assertThat(SELECTOR_EXCLUDED).as("当前没有任何排除项 —— 这是应有状态").isEmpty();
            return;
        }
        assertThat(sourceScanningGates())
                .as("SELECTOR_EXCLUDED 里的类已经不在了, 排除项和它的理由应当一并删除")
                .containsAll(SELECTOR_EXCLUDED);
    }
}
