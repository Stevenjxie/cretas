package com.cretas.aims.service.finding.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.service.finding.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    /**
     * 三态映射抽到共用件（2026-08-07 加毛利链时）。运行时行为逐字不变，但**多了
     * 一个构造参数** —— 手工装配的单测会因此 NPE，{@code RestaurantWastageFindingReaderTest}
     * 已改成用 {@code @Spy} 装真实 mapper（不是 mock：那些断言测的正是这段映射）。
     *
     * <p>抽的理由：两条链（损耗 / 毛利）的响应契约逐字相同，各写一份的话迟早有一份
     * 在「响应不合契约」时 return 空列表，而那会被渲染成「均正常」。
     */
    private final RestaurantFindingPayloadMapper payloadMapper;

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
        return payloadMapper.toFindings(body, rule);
    }
}
