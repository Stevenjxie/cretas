package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingNotApplicableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Python 发现规则响应 → {@link Finding} 的共用映射。
 *
 * <p>损耗与毛利两条链的响应契约**逐字相同**
 * （{@code {rule, applicable, skip_reason, findings[]}}），差别只在调哪个端点。
 * 把映射抽在这里，是为了让「applicable=false 怎么办」「缺 skip_reason 怎么办」
 * 这三态判定**只有一处定义** —— 两个 reader 各写一份的话，迟早有一份会在
 * 「响应不合契约」时 return 空列表，而那会被渲染成「均正常」。
 *
 * <p>⛔ 本类不发 HTTP、不做任何判定。阈值、窗口、同质闸全部在 Python 侧
 * （数据在 smartbi 库，且 Java 的 smartbi 数据源用的 {@code smartbi_user} 没有
 * BYPASSRLS、又是连接池 —— 在池化连接上设 {@code app.factory_id} 会跨租户泄漏）。
 * 在 Java 再算一遍等于同一个指标有两处定义。
 */
@Slf4j
@Component
public class RestaurantFindingPayloadMapper {

    /**
     * @param body Python 端点返回的响应体
     * @param rule 规则名，仅用于异常信息定位
     * @return 结构化发现；空列表表示「真的没有」
     * @throws FindingNotApplicableException 数据不足以判断（诚实跳过，落 skippedRules）
     * @throws IllegalStateException         响应不合契约（当作故障，落 failedRules）
     */
    @SuppressWarnings("unchecked")
    public List<Finding> toFindings(Map<String, Object> body, String rule) {
        if (!Boolean.TRUE.equals(body.get("applicable"))) {
            Object reason = body.get("skip_reason");
            if (reason == null || reason.toString().isBlank()) {
                // applicable 不为 true 又给不出理由 = 响应不合契约。当作故障，
                // 不能当作「数据不足」—— 后者会告诉用户「等数据攒够」，而真正
                // 该做的是去查服务。
                throw new IllegalStateException(
                        "餐饮发现响应缺少 applicable/skip_reason: rule=" + rule);
            }
            throw new FindingNotApplicableException(reason.toString());
        }

        List<Map<String, Object>> raw =
                (List<Map<String, Object>>) body.getOrDefault("findings", List.of());
        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> f : raw) {
            findings.add(new Finding(
                    (String) f.get("code"),
                    "restaurant",
                    toSeverity((String) f.get("severity")),
                    f.get("actionability") instanceof Number n ? n.intValue() : 0,
                    String.valueOf(f.get("subject_id")),
                    (String) f.get("subject_name"),
                    (Map<String, Object>) f.getOrDefault("facts", Map.of())));
        }
        return findings;
    }

    private Finding.Severity toSeverity(String severity) {
        if ("CRITICAL".equals(severity)) {
            return Finding.Severity.CRITICAL;
        }
        if ("WARNING".equals(severity)) {
            return Finding.Severity.WARNING;
        }
        return Finding.Severity.INFO;
    }
}
