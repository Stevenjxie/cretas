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
            // checkedRules 非空只能证明"至少一条规则跑完了", 不能证明"全部规则
            // 都跑完了"——2+ 条规则时可能一条成功零发现、另一条同时炸了。此时
            // 不能只说"均正常"(那对没跑完的那条是假话), 必须点名跑失败的规则,
            // 让用户知道这不是一次完整的检查结果 (禁止降级处理)。
            if (!result.complete()) {
                return "⚠️ 已检查 " + checked + "，均正常；另有 "
                        + String.join(" / ", result.failedRules()) + " 检查失败，暂无法判断。";
            }
            return "✅ 已检查 " + checked + "，均正常。";
        }

        String lines = result.findings().stream()
                .map(this::renderOne)
                .collect(Collectors.joining("\n"));

        int remaining = result.totalCount() - result.findings().size();
        String more = remaining > 0 ? "\n还有 " + remaining + " 项待查看" : "";

        return "⚠️ 顺带 " + result.findings().size() + " 件事：\n" + lines + more;
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
