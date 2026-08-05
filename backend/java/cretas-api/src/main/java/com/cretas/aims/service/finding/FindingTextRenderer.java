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
        // 一条规则都没成功跑完 —— 什么都不说。绝不能渲染成「均正常」，
        // 那是把故障说成健康（禁止降级处理）。
        if (result.checkedRules().isEmpty()) {
            return "";
        }

        String checked = String.join(" / ", result.checkedRules());

        if (result.findings().isEmpty()) {
            return "✅ 已检查 " + checked + "，均正常。";
        }

        String lines = result.findings().stream()
                .map(this::renderOne)
                .collect(Collectors.joining("\n"));

        int remaining = result.totalCount() - result.findings().size();
        String more = remaining > 0 ? "\n还有 " + remaining + " 项待查看" : "";

        return "⚠️ 顺带 " + result.findings().size() + " 件事：\n" + lines + more;
    }

    private String renderOne(Finding f) {
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
