package com.cretas.aims.service.finding;

import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 「顺带提示」出口的文案渲染。**纯模板，零 LLM**——数字全部来自 Finding.facts，
 * 渲染层不做任何计算，也不得引用 facts 里不存在的字段。
 */
@Component
public class FindingTextRenderer {

    /**
     * 方法名刻意叫 {@code renderInline} 而不是 {@code render}：将来日报出口会加
     * {@code renderDigest}。从第一天就按出口命名，可以防止两个出口共用一句话、
     * 需求分叉后开始塞 if。
     */
    public String renderInline(FindingService.Result result) {
        String skipText = renderSkipped(result);

        // 一条规则都没成功跑完 —— 绝不能渲染成「均正常」，那是把故障说成健康
        // （禁止降级处理）。但若有**诚实跳过**的规则，仍要把它说出来：否则
        // 「判不了」和「什么都没发生」对用户是同一个空白，三态就塌回两态了。
        if (result.checkedRules().isEmpty()) {
            return skipText;
        }

        String checked = String.join(" / ", result.checkedRules());

        if (result.findings().isEmpty()) {
            // checkedRules 非空只能证明"至少一条规则跑完了", 不能证明"全部规则
            // 都跑完了"——2+ 条规则时可能一条成功零发现、另一条同时炸了。此时
            // 不能只说"均正常"(那对没跑完的那条是假话), 必须点名跑失败的规则,
            // 让用户知道这不是一次完整的检查结果 (禁止降级处理)。
            if (!result.complete()) {
                return withSkip("⚠️ 已检查 " + checked + "，均正常；另有 "
                        + String.join(" / ", result.failedRules())
                        + " 检查失败，暂无法判断。", skipText);
            }
            return withSkip("✅ 已检查 " + checked + "，均正常。", skipText);
        }

        String lines = result.findings().stream()
                .map(this::renderOne)
                .collect(Collectors.joining("\n"));

        int remaining = result.totalCount() - result.findings().size();
        String more = remaining > 0 ? "\n还有 " + remaining + " 项待查看" : "";

        return withSkip("⚠️ 顺带 " + result.findings().size() + " 件事：\n" + lines + more,
                skipText);
    }

    /**
     * 驾驶舱卡片出口：每态一行，无「顺带」语气（那是对话里的措辞）。
     *
     * <p>🔴 前端**必须**用这里的成品行，不要拿 {@code Finding.facts} 自己拼句子：
     * {@code PriceFieldResponseAdvice} 的 {@code FINANCE_COLUMN_KEY_REGEX} 会把
     * 任何名字含 {@code cost}/{@code 成本}/{@code amount} 且值为数字标量的 Map
     * 条目置 null（它本是给 SmartBI Excel 财务表用的，对 {@code facts.cost} 是
     * 误伤）。前端自己拼就会渲染出空的「¥ 」。服务端渲染发生在该 Advice 之前，
     * 字符串也不在它的抹除范围内。
     *
     * <p>顺带也消掉了「同一句话在 Java 和 Vue 各写一遍」的第二处定义。
     *
     * @return 可直接逐行展示的文案；一条规则都没跑完时返回空列表（绝不说「均正常」）
     */
    /**
     * 只渲染**发现**那几行，不含跳过/失败/「均正常」。
     *
     * <p>🔴 2026-08-07 prod 实测的确定性缺陷：行动建议接口把 {@link #renderDigestLines}
     * 的全部行喂给 LLM（含 {@code ℹ️ ...重合度 52%...} 这种跳过说明），而校验器的合法
     * 数字集只由**发现**的结构化 facts 构成 —— 于是模型忠实引用了 52，被
     * {@code GroundedNumberValidator} 判为无据，接口 **4/4 次全部 409**。
     *
     * <p>判据：**喂给 LLM 的文本与校验用的事实集必须是同一批发现**。两侧口径不一致时，
     * 模型越忠实越会被拒 —— 这不是模型的问题，是两处口径打架。
     *
     * <p>⚠️ 修法是收窄输入而不是放宽校验：跳过规则说的是「判不了」，本来就不该
     * 变成行动建议；它照旧由响应里的 {@code skippedRules} 如实透出，三态不受影响。
     */
    public java.util.List<String> renderFindingLines(FindingService.Result result) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Finding f : result.findings()) {
            lines.add(renderOne(f).replaceFirst("^\s*·\s*", ""));
        }
        return lines;
    }

    public java.util.List<String> renderDigestLines(FindingService.Result result) {
        java.util.List<String> lines = new java.util.ArrayList<>();

        for (Finding f : result.findings()) {
            lines.add(renderOne(f).replaceFirst("^\\s*·\\s*", ""));
        }
        if (lines.isEmpty() && !result.checkedRules().isEmpty()) {
            lines.add("✅ 已检查 " + String.join(" / ", result.checkedRules()) + "，均正常。");
        }
        for (FindingService.SkippedRule s : result.skippedRules()) {
            lines.add("ℹ️ " + s.ruleName() + "：" + s.reason() + "，暂不判断。");
        }
        if (!result.failedRules().isEmpty()) {
            lines.add("⚠️ " + String.join(" / ", result.failedRules()) + " 检查失败，暂无法判断。");
        }
        return lines;
    }

    /**
     * 「数据没采集到」那一态。刻意**不含**「正常」二字 —— 这一行的全部意义就是
     * 告诉用户这条规则这次没给出结论，说成正常就是把缺数据渲染成了健康。
     */
    private String renderSkipped(FindingService.Result result) {
        if (result.skippedRules().isEmpty()) {
            return "";
        }
        return result.skippedRules().stream()
                .map(s -> "ℹ️ " + s.ruleName() + "：" + s.reason() + "，暂不判断。")
                .collect(Collectors.joining("\n"));
    }

    private String withSkip(String base, String skipText) {
        return skipText.isEmpty() ? base : base + "\n" + skipText;
    }

    /**
     * LOW_STOCK 分支不会打印字面 "null"——但**不是**因为「provider 一定会把
     * facts 的每个 key 都填上值」: {@code Map#put(key, null)} 一样会把 key
     * 放进去，{@code String.format("%s", null)} 一样打印 "null"，靠"populate
     * 齐全"防不住 null。真正原因在数据源: {@code unit}/{@code subjectName}
     * 来自 {@code RawMaterialType.unit}/{@code .name}，两者在数据库层都是
     * {@code @Column(nullable = false)}，从根上不存在能被传成 null 的路径。
     * （不加 null-guard——若这个数据库约束将来被放宽，这里应该跟着重新评估，
     * 而不是提前防一个当前不存在的输入。）
     */
    private String renderOne(Finding f) {
        if ("WASTAGE_SHARE_SPIKE".equals(f.code())) {
            // ⛔ 只能说「涨得比全店快」。份额是零和的: 某食材份额上升有一部分是
            //    别的食材下降的机械结果, 说「涨了 N 倍」是把别人的下降算到它头上。
            return String.format(" · %s 近%s天损耗 ¥%s，占全店 %s%%（基线 %s%%），涨得比全店快 %s 倍",
                    f.subjectName(),
                    f.facts().get("windowDays"),
                    f.facts().get("costCur"),
                    f.facts().get("shareCur"),
                    f.facts().get("shareBase"),
                    f.facts().get("amplification"));
        }
        if ("DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME".equals(f.code())) {
            // ⛔ 只能说「最赚钱但没卖动」, 不能说「该涨价」或「该下架」——
            //    单份毛利高 + 销量低有多种成因(定价、菜单位置、服务员话术、
            //    份量、季节), 渲染层不做归因。给事实, 店长自己判。
            // ⚠️ 必须带上 pricedDishCount: 中位数是**按有配方的菜**算的,
            //    不说清有几道就会被读成「全店结论」。
            return String.format(
                    " · %s 每份赚 ¥%s，是有配方的 %s 道菜里的高位（中位 ¥%s）；"
                            + "近%s天卖 %s 份，低于中位 %s 份 —— 最赚钱的菜没卖动",
                    f.subjectName(),
                    f.facts().get("unitMargin"),
                    f.facts().get("pricedDishCount"),
                    f.facts().get("unitMarginMedian"),
                    f.facts().get("windowDays"),
                    f.facts().get("qty"),
                    f.facts().get("qtyMedian"));
        }
        if ("WASTAGE_TYPE_CONCENTRATION".equals(f.code())) {
            return String.format(" · %s损耗近%s天 ¥%s，占全店损耗 %s%%",
                    f.subjectName(),
                    f.facts().get("windowDays"),
                    f.facts().get("cost"),
                    f.facts().get("share"));
        }
        if ("LOW_STOCK".equals(f.code())) {
            Object unit = f.facts().get("unit");
            return String.format(" · %s 剩 %s%s，低于安全线 %s%s（缺 %s%s）",
                    f.subjectName(),
                    f.facts().get("currentStock"), unit,
                    f.facts().get("safetyStock"), unit,
                    f.facts().get("gap"), unit);
        }
        // 兜底：新 code 上线但模板未跟上时，至少说出对象名，不输出 null。
        return " · " + f.subjectName();
    }
}
