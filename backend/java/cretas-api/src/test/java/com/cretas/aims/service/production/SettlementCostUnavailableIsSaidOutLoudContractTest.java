package com.cretas.aims.service.production;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 仓库确认入库这条路径上，<b>算不出成本要说出来</b>，<b>给用户看的话要是中文</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 纯 App 报工的计划走通结单 + 入库之后，读回来的响应是：
 * <pre>
 * "postingStatus": "POSTED",
 * "message": "Warehouse confirmed every pinned Workflow terminal output line",   ← 英文, 用户可见
 * "warnings": ["Completed 1 production batch records"],                          ← 英文, 用户可见
 * "outputLines": [{ ..., "allocatedCost": null, "unitCost": null }]              ← 成本空着, 没有任何说明
 * </pre>
 *
 * 成本为空的根因（<b>本闸不负责修，那是成本口径</b>）：结单把领用事实写进
 * {@code production_settlement_consumptions}（实测 4 行 RAW_MATERIAL，各 20kg），
 * 而 {@code OrderCostBreakdownService} 按 {@code material_consumptions.production_batch_id} 归集
 * —— <b>两张表不相交</b>（实测全表 47 行里只有 1 行有 production_batch_id）⇒ totalCost=0 ⇒
 * {@code resolvePlanTotalCost} 返回 null ⇒ 原来直接 {@code return}，成本一路留空。
 *
 * <p>本仓原则「禁止降级处理，不返回假数据，明确显示错误」+ 判据「凡是拦住人的地方都要告诉下一步」：
 * 算不出可以，<b>但必须说出来，并且说下一步</b>。
 *
 * <h2>钉三条</h2>
 * <ol>
 *   <li>算不出时返回一句给用户的原因，而不是静默 return</li>
 *   <li>那句话不许把「算不出」说成「是 0」（会让下游按零成本结转 COGS）</li>
 *   <li>这条路径上给用户看的文案是中文</li>
 * </ol>
 */
class SettlementCostUnavailableIsSaidOutLoudContractTest {

    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");

    /** 剥掉注释再看代码 —— 否则会数到讲这件事的说明本身 (本仓形态 A⁗)。 */
    private static String code() throws Exception {
        return Files.readString(SRC)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    /** 取 confirmWorkflowOutputReceipt 的方法体 —— 范围必须钉死，否则会匹配到别的腿。 */
    private static String receiptLeg(String src) {
        int at = src.indexOf("private ProductionWarehouseReceiptResponse confirmWorkflowOutputReceipt(");
        assertTrue(at > 0, "找不到 confirmWorkflowOutputReceipt —— 这道闸的范围钉不住");
        // ⛔ 收尾不能用「我要断言的那个字符串」当标记 —— 那样 leg 恰好被截在它前面, 断言必然失败。
        //    用「下一个方法签名」当边界。
        int end = nextMethodBoundary(src, at);
        assertTrue(end > at, "找不到这条腿的收尾");
        return src.substring(at, end);
    }

    @Test
    @DisplayName("阳性对照: 那句给用户的原因确实存在且非空 (否则下面全是恒真)")
    void theWarningStringExistsAndIsNotBlank() {
        String w = ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING;
        assertTrue(w != null && w.trim().length() >= 20, "原因文案缺失或太短: " + w);
    }

    @Test
    @DisplayName("🔴 算不出成本必须【说出来】—— 断言行为, 不是断言源码里有那行字")
    void costUnavailableIsSurfacedNotSwallowed() {
        // 🔴 第一版这条写的是 src.contains("warnings.add(costWarning)"), 变异
        //    `if (false && costWarning != null)` 把行为改掉了、那行字符却还在 ⇒ 闸纹丝不动。
        //    所以改成直接调纯函数, 断言它的【输出】(本仓形态 C⁸)。
        List<String> withCost = ProductionPlanServiceImpl.buildReceiptWarnings(
                1, ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING);
        assertTrue(withCost.contains(ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING),
                "成本算不出的原因被吞掉了, 用户看不到任何说明: " + withCost);

        // 阳性对照: 成本正常时不该多喊一句(否则「总是喊」和「该喊才喊」分不开)
        List<String> withoutCost = ProductionPlanServiceImpl.buildReceiptWarnings(1, null);
        assertFalse(withoutCost.contains(ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING),
                "成本明明算出来了还在喊算不出: " + withoutCost);
        assertEquals(1, withoutCost.size(), "正常路径的提示条数不对: " + withoutCost);

        // 阴性对照: 什么都没有时不许凭空造提示
        assertTrue(ProductionPlanServiceImpl.buildReceiptWarnings(0, null).isEmpty(),
                "无事可报却造了提示");
    }

    @Test
    @DisplayName("接线闸: 这条腿真的把分摊结果接到了提示组装上")
    void receiptLegWiresCostWarningIntoTheAssembler() throws Exception {
        String leg = receiptLeg(code());
        assertTrue(leg.contains("allocateWorkflowOutputCosts("), "这条腿没有分摊成本这一步");
        assertTrue(leg.contains("buildReceiptWarnings(completedBatchCount, costWarning)"),
                "分摊结果没有交给提示组装 —— helper 写对了不等于接上了");
    }

    @Test
    @DisplayName("🔴 「算不出」不许被说成「是 0」(下游会按零成本结转 COGS)")
    void unavailableIsNotDescribedAsZero() {
        String w = ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING;
        assertFalse(w.contains("成本为 0") || w.contains("成本为0") || w.contains("成本是 0"),
                "把「算不出」说成了「是 0」: " + w);
        assertTrue(w.contains("留空"), "没说清是留空而不是零: " + w);
    }

    @Test
    @DisplayName("🔴 那句话要告诉下一步 (判据: 凡拦人处都要给下一步)")
    void theWarningTellsTheUserWhatToDoNext() {
        String w = ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING;
        assertTrue(w.contains("下一步"), "没有下一步指引: " + w);
    }

    @Test
    @DisplayName("🔴 这条腿上给用户看的文案必须是中文 —— 原来是两句英文")
    void userFacingTextOnThisLegIsChinese() throws Exception {
        String src = code();
        // 两处都要扫: 这条腿里的 postingMessage + 提示组装函数里的 warnings.add 字面量
        String scope = receiptLeg(src) + methodBody(src, "buildReceiptWarnings");
        Pattern p = Pattern.compile("(?:setPostingMessage|warnings\\.add)\\(\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(scope);
        int checked = 0;
        while (m.find()) {
            String text = m.group(1);
            checked++;
            assertTrue(text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF),
                    "用户可见文案不是中文: \"" + text + "\"");
        }
        // 阳性对照: 真的扫到了东西, 否则「全是中文」只是因为一条都没扫到
        assertTrue(checked >= 1, "一条用户可见文案都没扫到 —— 闸在守空气, 实际扫到 " + checked);
    }

    @Test
    @DisplayName("阴性对照: 那两句原来的英文不许再出现在这条腿里")
    void theOldEnglishStringsAreGone() throws Exception {
        String leg = receiptLeg(code()) + methodBody(code(), "buildReceiptWarnings");
        assertFalse(leg.contains("Warehouse confirmed every pinned"),
                "英文 postingMessage 又回来了");
        assertFalse(leg.contains("production batch records"),
                "英文 warning 又回来了");
    }

    @Test
    @DisplayName("阳性对照: 剥注释这一步没有把整个方法体也剥掉")
    void strippingCommentsDoesNotEatTheMethodBody() throws Exception {
        String leg = receiptLeg(code());
        assertTrue(leg.length() > 400, "方法体只剩 " + leg.length() + " 字符, 剥注释剥过头了");
        assertEquals(1, countOccurrences(leg, "productionSettlementRepository.save(settlement)"),
                "方法体范围不对");
    }

    /** 下一个同缩进方法签名的位置 —— 用它当方法体边界。 */
    private static int nextMethodBoundary(String src, int from) {
        int a = src.indexOf("\n    private ", from + 1);
        int b = src.indexOf("\n    public ", from + 1);
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    /** 取一个具名方法的方法体(到下一个同缩进方法签名为止)。 */
    private static String methodBody(String src, String methodName) {
        int at = src.indexOf(" " + methodName + "(int");
        assertTrue(at > 0, "找不到方法 " + methodName);
        int next = nextMethodBoundary(src, at);
        String body = next > at ? src.substring(at, next) : src.substring(at);
        assertTrue(body.length() > 80, "方法体只取到 " + body.length() + " 字符, 范围不对");
        return body;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
