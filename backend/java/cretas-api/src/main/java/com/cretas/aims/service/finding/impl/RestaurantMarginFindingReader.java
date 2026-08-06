package com.cretas.aims.service.finding.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.service.finding.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 餐饮**毛利**发现规则的「调 Python → 转成 Finding」。
 *
 * <p>⛔ 本类不做任何判定。阈值全在 Python 侧
 * （{@code smartbi/gold/restaurant/margin_findings.py}），而且那边的规则**不自己
 * join 成本** —— 它调 {@code dish_margin.compute_dish_margins}，与
 * {@code /restaurant-ops/gross-margin} 端点同一个函数。每道菜的食材成本口径在
 * 仓里已有 5 处承载，发现层不做第 6 处。
 *
 * <p>与 {@link RestaurantWastageFindingReader} 分开而不是加个参数：两条链打的是
 * 不同端点，而且损耗链的路径里带 {@code wastage} 字样 —— 把毛利规则挂在那个路径下，
 * 下一个人读日志会以为毛利问题出在损耗链上。共用的三态映射在
 * {@link RestaurantFindingPayloadMapper}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantMarginFindingReader {

    private final PythonSmartBIClient pythonSmartBIClient;
    private final RestaurantFindingPayloadMapper payloadMapper;

    public List<Finding> read(String factoryId, String rule) {
        Map<String, Object> body;
        try {
            body = pythonSmartBIClient.getRestaurantMarginFindings(factoryId, rule);
        } catch (Exception e) {
            // 上抛 → FindingService 的 failedRules → 「检查失败，暂无法判断」。
            // 绝不 return List.of()：那会被渲染成「均正常」，把故障说成健康。
            throw new IllegalStateException(
                    "餐饮毛利发现调用失败: rule=" + rule + ", factoryId=" + factoryId, e);
        }
        return payloadMapper.toFindings(body, rule);
    }
}
