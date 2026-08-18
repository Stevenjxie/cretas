package com.cretas.aims.service.unit;

import java.util.Locale;
import java.util.Map;

/**
 * 单位的<b>展示名</b> —— 面向用户的文案里把计数/包装英文码换成中文。
 *
 * <p>🔴 <b>为什么需要它</b>: 库里存的是规范化后的单位码({@code box}/{@code bag}/{@code slice}),
 * 前端有一张 label 映射表负责显示成中文, 但<b>后端拼出来的用户文案没有</b> ——
 * 2026-08-01 prod 走查时报工缺料 409 直接把码丢给客户看:</p>
 *
 * <pre>需要 7box，可用 0box，缺少 7box，请联系仓管补料</pre>
 *
 * <p>这正是 {@code V20261029_32__unit_codes_to_chinese.sql} 开头写的那件事 ——
 * 「用户从来不认识 pcs, 却会在报工缺料提示里看到 "需要 1pcs, 可用 0pcs"」。
 * 那条 migration 改的是<b>数据</b>, 改不到<b>文案</b>; 只要码还在库里(写入侧
 * {@code RawMaterialTypeServiceImpl#normalizeInventoryUnit} 正是归一成码), 文案就得自己翻。</p>
 *
 * <p>⛔ <b>刻意不翻科学计量单位</b>(kg、g、L、ml…): 与 V20261029_32 的取舍一致 ——
 * 它们是国际计量符号, 秤上、单据上、国标上都这么写, 换成「公斤」反而不如现状清楚。
 * 只翻用户读不懂的计数/包装码。</p>
 *
 * <p>取值与权威别名表 {@code UnitContractServiceImpl#systemAliases()} 的第一个中文值一致。
 * 纯静态映射, 不依赖 Spring —— 异常类等无法注入的地方也能用。</p>
 */
public final class UnitDisplayNames {

    /**
     * 码 → 中文展示名。只收<b>用户读不懂的码</b>。
     *
     * <p>⛔ 国际通行的计量<b>符号</b>(kg / g / mg / L / ml / mm / cm / m / km)一律不进这张表 ——
     * 秤上、单据上、国标上都这么写。
     *
     * <p>🔴 2026-08-18 补 {@code jin}: 它虽然挂在质量量纲下, 却<b>不是</b>符号, 是<b>拼音</b> ——
     * 用户输入「斤」会被归一成码 {@code jin}, 再原样丢回给他看。判一个码该不该进这张表的
     * 标准是<b>「用户认不认得这个写法」</b>, 不是「它属于哪个量纲」;
     * 原来的注释写成了后者(「WEIGHT/VOLUME/LENGTH 的符号一律不进这张表」),
     * 拼音码就顺着量纲被放过了。
     *
     * <p>⚠️ 同批<b>克制</b>: {@code t} 没有进表。它是 GB 3100 里吨的法定符号,
     * 既有断言 {@code UnitDisplayNamesTest#keepsScientificSymbolsAsIs} 明确守着它,
     * 那条断言守的是<b>需求</b>不是历史 —— 去黑话只动黑话, 顺手改措辞只会白白弄红既有断言。
     *
     * <p>覆盖面由 {@code UnitDisplayNameCoverageTest} 钉住: 契约里每个非 CJK 的码,
     * 要么在符号白名单上, 要么在这张表里且与契约的 displayName 一致。
     */
    private static final Map<String, String> COUNTING_DISPLAY = Map.ofEntries(
            Map.entry("jin", "斤"),
            Map.entry("pcs", "件"),
            Map.entry("portion", "份"),
            Map.entry("box", "盒"),
            Map.entry("case", "箱"),
            Map.entry("bag", "袋"),
            Map.entry("pack", "包"),
            Map.entry("bottle", "瓶"),
            Map.entry("can", "罐"),
            Map.entry("crate", "框"),
            Map.entry("pail", "桶"),
            Map.entry("roll", "卷"),
            Map.entry("slice", "片"),
            Map.entry("sheet", "张"),
            Map.entry("tray", "托盘"),
            Map.entry("plate", "板"),
            Map.entry("item", "项"));

    private UnitDisplayNames() {
    }

    /**
     * 取展示名。认不出来就<b>原样返回</b> —— 已经是中文的、科学计量符号的、
     * 以及这张表没收的自定义单位都走这条路, 不会被改写也不会变成空。
     *
     * @param unit 库里存的单位(可能是码, 可能已经是中文, 可能为 null)
     * @return 面向用户的展示名; 入参为 null 时返回 null(调用方原本就要处理 null)
     */
    public static String display(String unit) {
        if (unit == null) {
            return null;
        }
        String trimmed = unit.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return COUNTING_DISPLAY.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }
}
