package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.WorkdeskRole;
import com.cretas.aims.ai.tool.impl.material.MaterialStockSummaryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 采购员岗位归属的两层断言：
 * <ol>
 *   <li>这 5 个类<b>确实</b>声明了 PURCHASER（实例断言）</li>
 *   <li>全仓<b>只有</b>这 5 个文件声明 PURCHASER（源码扫描）</li>
 * </ol>
 *
 * <p>第 2 条是必需的：只断言「这 5 个有」抓不住「第 6 个也偷偷声明了」，
 * 而多标一个的后果是采购员的工作台里混进不属于他的活。
 */
class PurchaserWorkdeskRoleDeclarationContractTest {

    /** 与 impl 目录同步的相对路径；surefire 的工作目录是 Maven 模块根。 */
    private static final Path TOOL_IMPL_DIR =
            Path.of("src/main/java/com/cretas/aims/ai/tool/impl");

    private static final Set<String> EXPECTED_PURCHASER_FILES = new TreeSet<>(List.of(
            "PriceHistoryQueryTool.java",
            "RequisitionCreateTool.java",
            "SalesForecast7DayTool.java",
            "StockAlertWorkdeskTool.java",
            "SupplierDeliveryEtaTool.java"));

    @Test
    @DisplayName("UT-PWR-01: 5 个采购员工具各自声明 PURCHASER")
    void fiveToolsDeclarePurchaser() {
        assertEquals(WorkdeskRole.PURCHASER, new PriceHistoryQueryTool().workdeskRole());
        assertEquals(WorkdeskRole.PURCHASER, new RequisitionCreateTool().workdeskRole());
        assertEquals(WorkdeskRole.PURCHASER, new SalesForecast7DayTool().workdeskRole());
        assertEquals(WorkdeskRole.PURCHASER, new StockAlertWorkdeskTool().workdeskRole());
        assertEquals(WorkdeskRole.PURCHASER, new SupplierDeliveryEtaTool().workdeskRole());
    }

    @Test
    @DisplayName("UT-PWR-02: 没有标记的工具默认不属于任何岗位")
    void declaresNothingByDefault() {
        assertNull(new MaterialStockSummaryTool().workdeskRole(),
                "没有 Sprint 8 P4x 标记的工具不得被赋予岗位");
    }

    @Test
    @DisplayName("UT-PWR-03: 🔴 全仓只有这 5 个文件声明 PURCHASER —— 多标一个就红")
    void exactlyFiveFilesDeclarePurchaser() throws IOException {
        Set<String> actual;
        try (Stream<Path> paths = Files.walk(TOOL_IMPL_DIR)) {
            actual = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(PurchaserWorkdeskRoleDeclarationContractTest::declaresPurchaser)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(EXPECTED_PURCHASER_FILES, actual,
                "采购员岗位的工具集合变了。这不是可以顺手改的东西——归属的依据是"
                        + "工具注释里的 `Sprint 8 P4b 采购员 Workdesk` 标记, 改集合前先改标记。");
    }

    private static boolean declaresPurchaser(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains("WorkdeskRole.PURCHASER");
        } catch (IOException e) {
            throw new IllegalStateException("读不出 " + file, e);
        }
    }

    @Test
    @DisplayName("UT-PWR-04: 扫描路径本身有效 —— 防止路径写错导致上一条恒绿")
    void scanDirectoryExists() {
        assertTrue(Files.isDirectory(TOOL_IMPL_DIR),
                "扫描目录不存在, UT-PWR-03 会扫到空集合而不是真的没人声明: "
                        + TOOL_IMPL_DIR.toAbsolutePath());
    }
}
