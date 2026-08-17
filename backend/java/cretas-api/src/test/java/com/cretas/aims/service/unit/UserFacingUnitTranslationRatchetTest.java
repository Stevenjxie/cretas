package com.cretas.aims.service.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 棘轮 —— 后端拼给用户看的文案里, 直接拼裸单位的地方<b>只许变少, 不许变多</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 实测)</h2>
 *
 * 判据: 「不许出现英文单位，用户看到的必须是中文」。前端各有一张 label 表负责翻译,
 * <b>后端自己拼的文案没有</b> —— {@link UnitDisplayNames} 的 javadoc 讲的就是这件事,
 * 而全仓 61 处「在 BusinessException / withHint 里拼单位」的地方, <b>一处都没走它</b>。
 *
 * <p>库里确实还躺着码 (2026-08-18 全库逐列扫, 101 个单位列):
 * <pre>
 * work_processes.unit              20  unitless
 * bom_recipes.output_unit           6  box
 * material_packaging_specs.package_unit  4  case / jin / ton
 * bom_recipe_items.{unit,price_unit,natural_unit}  6  pcs
 * production_plans.source_display_unit   2  box / case
 * sales_order_items.unit            2  box / case
 * material_packaging_hierarchy.level2_unit  1  ton
 * </pre>
 * 只要其中一行流进上面那类文案, 客户就会读到 "还差 3box"。
 *
 * <h2>为什么是棘轮而不是硬闸</h2>
 * 一次性改 52 处是个大扫除, 而<b>大扫除做出来的闸当天就会被关掉</b>(形态 E)。
 * 本轮只修了<b>有实测数据证明今天就会打出英文码</b>的 9 处
 * (销售发货/预留/物料不足、改价、结单单位不一致、采购到货包装), 其余冻结。
 *
 * <h2>⚠️ 这是代理判据</h2>
 * 静态分析判不出「这个串会不会走到用户面前」。代理 = 「出现在
 * BusinessException / withHint / withActionHint / EntityNotFoundException 的构造里」
 * ＋「拼了一个名字像 unit 的表达式」。它会有假阳(内部日志式文案)和假阴(f-string 式拼装、
 * 经 String.format 的)。所以它<b>只钉增长</b>, 不声称覆盖完整。
 */
class UserFacingUnitTranslationRatchetTest {

    /**
     * 冻结值 —— 2026-08-18 实测。⛔ 只许调小。
     *
     * <p>调大之前先问一句: 新增的那处文案, 用户读到裸码 (box / pcs / jin) 时看得懂吗?
     */
    private static final int FROZEN_BARE_SITES = 52;

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern FACING = Pattern.compile(
            "(BusinessException\\s*\\(|\\.withHint\\s*\\(|\\.withActionHint\\s*\\(|EntityNotFoundException\\s*\\()");
    /** 拼了一个名字里带 unit 的表达式, e.g. {@code + item.getUnit()} / {@code + rawUnit}。 */
    private static final Pattern UNIT_EXPR = Pattern.compile(
            "\\+\\s*[A-Za-z_][A-Za-z0-9_.()]*[Uu]nit[A-Za-z0-9_]*\\s*(\\(\\))?");
    private static final Pattern WRAPPED = Pattern.compile("UnitDisplayNames\\s*\\.\\s*display\\s*\\(");

    private record Scan(List<String> bare, List<String> wrapped) { }

    private static Scan scan() throws IOException {
        List<String> bare = new ArrayList<>();
        List<String> wrapped = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                Matcher m = FACING.matcher(text);
                while (m.find()) {
                    int end = text.indexOf(';', m.start());
                    if (end < 0) {
                        continue;
                    }
                    String stmt = text.substring(m.start(), end);
                    if (stmt.length() > 2000 || !UNIT_EXPR.matcher(stmt).find()) {
                        continue;
                    }
                    int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    String where = MAIN.relativize(p).toString().replace('\\', '/') + ":" + line;
                    (WRAPPED.matcher(stmt).find() ? wrapped : bare).add(where);
                }
            }
        }
        return new Scan(bare, wrapped);
    }

    @Test
    @DisplayName("阳性对照: 扫描器找得到东西, 且认得出「已翻译」那一类")
    void scannerActuallyFindsSites() throws IOException {
        Scan s = scan();
        int total = s.bare().size() + s.wrapped().size();
        assertTrue(total >= 40,
                "只找到 " + total + " 处 —— 扫描器多半没读到源码 (CWD? 正则?), 下面的棘轮就是恒真的");
        assertFalse(s.wrapped().isEmpty(),
                "一处「已走 UnitDisplayNames」都认不出来 ⇒ WRAPPED 正则失效, 棘轮会把已修的也算成裸的");
    }

    @Test
    @DisplayName("🔴 棘轮: 拼裸单位的用户文案只许变少")
    void bareUnitSitesDoNotGrow() throws IOException {
        List<String> bare = scan().bare();
        assertTrue(bare.size() <= FROZEN_BARE_SITES,
                "拼裸单位的用户可见文案从 " + FROZEN_BARE_SITES + " 涨到了 " + bare.size()
                        + "。新增的那处请改用 UnitDisplayNames.display(unit) —— "
                        + "库里还躺着 box/case/pcs/jin/ton, 客户会读到「还差 3box」。\n新清单:\n  "
                        + String.join("\n  ", bare));
    }

    @Test
    @DisplayName("⛔ 冻结值别忘了往下调 —— 修完一批要把 FROZEN 跟着降, 否则棘轮松了")
    void frozenValueTracksReality() throws IOException {
        int bare = scan().bare().size();
        assertTrue(FROZEN_BARE_SITES - bare <= 5,
                "已经修到 " + bare + " 处, 而冻结值还停在 " + FROZEN_BARE_SITES
                        + " —— 中间这段空隙让棘轮不再守任何东西, 请把 FROZEN_BARE_SITES 调到 " + bare);
    }
}
