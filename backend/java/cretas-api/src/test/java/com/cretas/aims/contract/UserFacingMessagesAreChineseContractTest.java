package com.cretas.aims.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户看得见的报错必须是中文 —— 棘轮闸: 存量欠账只许减, 不许增。
 *
 * <h3>为什么有这个文件(2026-08-14 实测)</h3>
 *
 * 在清空后的 F006 上走真实流程建生产计划, 界面上直接甩出整段英文:
 *
 * <pre>No active BOM family covers the exact Workflow revision and terminal set
 * Activate exactly one complete BOM family for this published Workflow revision</pre>
 *
 * 使用者是工厂总监, 整个界面是中文。owner 当场的要求是「不能出现任何英文」——
 * 单位那一侧已经改掉(码改成中文字本身), 报错文案这一侧同样得管。
 *
 * <h3>为什么是棘轮而不是一次全绿</h3>
 *
 * service 层实测还有几十条完全英文的用户可见文案(绝大多数在 ProductionPlanServiceImpl
 * 的工艺结算链路上)。一次性翻译 40+ 条是另一轮工作量, 而且大批量机翻自身有风险 ——
 * 报错文案要能指导用户下一步做什么, 不是逐字对译。
 *
 * <p>所以先把<b>增长</b>堵死: 当前欠账记在 {@link #BASELINE}, 新增会让本测试变红。
 * 每翻译一条就把基线减一 —— 数字只能往下走。
 *
 * <h3>三个自己踩过的坑, 都写在这里防止重蹈</h3>
 *
 * <p>⚠️ <b>判据是「完全不含中文字符」, 不是「含有英文单词」</b>——
 * 「taskId 不是有效的 UUID」这类中英混排是正常的(字段名本来就是英文), 不该算欠账。
 * 第一版正则没做这个区分, 把 191 条中文文案也算了进来, 数字整整大了四倍。
 *
 * <p>🔴 <b>必须覆盖三元</b>。只匹配「错误码后紧跟字面量」会<b>看不见三元分支</b>——
 * 而触发本文件存在的那条(BOM 家族)恰恰是三元的:
 * <pre>new BusinessException(409, matches.isEmpty() ? "No active..." : "Multiple...")</pre>
 * 实测后果: 我把它翻成中文了, 而闸的读数纹丝不动。<b>一个数不到自己成因的闸等于没有。</b>
 *
 * <p>⚠️ <b>也不能反过来用「括号配对取全部字面量」偷懒</b>: 那会把 {@code .withHint(...)}
 * 与字符串拼接的碎片(如 {@code " + x + "})一并算进来 —— 实测从 45 膨胀成 305 条噪音。
 * 精确的两条模式(直接 / 三元)才是对的粒度。
 */
@DisplayName("用户可见报错必须是中文(棘轮)")
class UserFacingMessagesAreChineseContractTest {

    /**
     * 当前存量欠账。翻译一条就减一 —— <b>只许减不许增</b>。
     *
     * <p>⛔ 若本地跑出更大的数字, 先查是不是提取逻辑变了, <b>不要直接把这个数字改大</b>——
     * 改大就是把棘轮松开, 等于这道闸不存在。
     */
    // 2026-08-18: 45 -> 41, 结算链路上 BYPRODUCT_NRV_REQUIRED /
    // WORKFLOW_OUTPUT_UNIT_MISMATCH / PINNED_BOM_OUTPUT_POLICY_INCOMPLETE /
    // OUTPUT_COST_ALLOCATION_RATIO_REQUIRED 四条已翻成中文并补上下一步。
    private static final int BASELINE = 41;

    private static final Path SERVICE_ROOT =
            Path.of("src/main/java/com/cretas/aims/service");

    /** 形态一: 错误码后紧跟字面量。 */
    private static final Pattern DIRECT = Pattern.compile(
            "new BusinessException\\(\\s*\\d+\\s*,\\s*\"([^\"]{12,})\"");

    /**
     * 形态二: 错误码后是三元表达式的两个分支(BOM 家族那条就是这个形状)。
     *
     * ⚠️ 条件里<b>会有括号</b>({@code matches.isEmpty()}), 中间段不能写成 {@code [^"()]} ——
     * 第一版就是这么写的, 结果变异(把文案改回英文)<b>没能把闸打红</b>, 因为正则跨不过
     * {@code isEmpty()} 那对括号。只排除引号 + 非贪婪 + 长度上限。
     */
    private static final Pattern TERNARY = Pattern.compile(
            "new BusinessException\\(\\s*\\d+\\s*,\\s*[^\"]{0,160}?\\?\\s*"
                    + "\"([^\"]{12,})\"\\s*:\\s*\"([^\"]{12,})\"");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /** 取每个 BusinessException 的 message 位文案(覆盖直接字面量与三元两分支)。 */
    private static List<String> messagesIn(String src) {
        List<String> out = new ArrayList<>();
        Matcher d = DIRECT.matcher(src);
        while (d.find()) {
            out.add(d.group(1));
        }
        Matcher t = TERNARY.matcher(src);
        while (t.find()) {
            out.add(t.group(1));
            out.add(t.group(2));
        }
        return out;
    }

    private static List<Path> serviceSources() throws IOException {
        try (Stream<Path> files = Files.walk(SERVICE_ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> allMessages() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path f : serviceSources()) {
            all.addAll(messagesIn(Files.readString(f, StandardCharsets.UTF_8)));
        }
        return all;
    }

    private static List<String> englishOnlyMessages() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path f : serviceSources()) {
            for (String msg : messagesIn(Files.readString(f, StandardCharsets.UTF_8))) {
                if (!CJK.matcher(msg).find()) {
                    found.add(f.getFileName() + ": " + msg);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("阳性对照: 真的扫到了文案 —— 否则「零英文」只是因为没扫到")
    void positiveControl() throws IOException {
        assertThat(allMessages())
                .as("一条 BusinessException 文案都没扫到, 说明正则失配, 本文件的断言全部无效")
                .hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("阳性对照二: 三元那条模式真的能抓到东西 —— 否则它形同虚设")
    void ternaryPatternActuallyMatches() throws IOException {
        int ternaryHits = 0;
        for (Path f : serviceSources()) {
            Matcher t = TERNARY.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (t.find()) {
                ternaryHits++;
            }
        }
        assertThat(ternaryHits)
                .as("三元模式一条都没匹配到 —— 它就是为了 BOM 家族那条加的, 抓不到说明写坏了")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("🔒 完全英文的用户可见文案不许变多 (棘轮: 只许减)")
    void englishOnlyMessagesMustNotGrow() throws IOException {
        List<String> english = englishOnlyMessages();

        assertThat(english.size())
                .as("新增了完全英文的用户可见文案。中文界面上甩英文报错, 使用者读不懂也不知道下一步做什么。"
                        + " 当前清单: " + String.join(" | ", english))
                .isLessThanOrEqualTo(BASELINE);
    }

    @Test
    @DisplayName("基线不许高于实际 —— 否则棘轮是松的, 可以偷偷加英文")
    void baselineMustNotBeLooserThanReality() throws IOException {
        assertThat(BASELINE)
                .as("基线比实际欠账还大 = 棘轮留了空档")
                .isEqualTo(englishOnlyMessages().size());
    }
}
