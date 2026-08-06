package com.cretas.aims.service.finding.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingNotApplicableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 两个餐饮损耗 provider 共用的「调 Python → 转成 Finding」。
 *
 * <p>⛔ 本类**不做任何判定**。阈值、窗口、同质闸全部在 Python 侧
 * ({@code smartbi/gold/restaurant/wastage_findings.py})，因为数据在
 * smartbi 库、而 Java 的 smartbi 数据源用的 {@code smartbi_user} 没有
 * BYPASSRLS，且是连接池 —— 在池化连接上设 {@code app.factory_id} 会跨租户泄漏。
 * 在这里再算一遍等于同一个指标有两处定义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantWastageFindingReader {

    private final PythonSmartBIClient pythonSmartBIClient;

    @SuppressWarnings("unchecked")
    public List<Finding> read(String factoryId, String rule) {
        Map<String, Object> body;
        try {
            body = pythonSmartBIClient.getRestaurantWastageFindings(factoryId, rule);
        } catch (Exception e) {
            // 上抛 → FindingService 的 failedRules → 「检查失败，暂无法判断」。
            // 绝不 return List.of()：那会被渲染成「均正常」，把故障说成健康。
            throw new IllegalStateException(
                    "餐饮损耗发现调用失败: rule=" + rule + ", factoryId=" + factoryId, e);
        }

        if (!Boolean.TRUE.equals(body.get("applicable"))) {
            Object reason = body.get("skip_reason");
            if (reason == null || reason.toString().isBlank()) {
                // applicable 不为 true 又给不出理由 = 响应不合契约。当作故障，
                // 不能当作「数据不足」——后者会告诉用户「等数据攒够」，而真正
                // 该做的是去查服务。
                throw new IllegalStateException(
                        "餐饮损耗发现响应缺少 applicable/skip_reason: rule=" + rule);
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
