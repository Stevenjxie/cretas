package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R3 谜题菜：**单份毛利高于中位、销量低于中位** —— 最赚钱的菜没卖动。
 *
 * <p>发现层餐饮域的第一条**毛利口径**规则。2026-08-06 Steve 拍板：餐饮不该把损耗
 * 放在台面上（工厂按批次追损耗是因为投入产出一一对应；餐饮的浪费最后都体现在
 * 毛利被吃掉），重点是加权毛利 / 总成本 / 总营收 / 总毛利。
 *
 * <p>🔴 为什么是「谜题」而不是「低毛利菜」：2026-08-07 在 prod (MOCK_REST) 上实测过
 * 低毛利那一版，**产出 0 条** —— 低单份毛利的米饭/酸梅汤恰好都在销量中位数以下被
 * 销量闸挡掉；去掉销量闸则只报米饭+酸梅汤，而「米饭不赚钱」不是店长不知道的事。
 * 全店也没有亏本菜。真正有信息量的是谜题象限：罗氏虾单份赚 ¥78.57 全店最高
 * （中位 ¥27.51），销量却在最低档 —— 店长以为它很好（营收全店第一）。
 *
 * <p>单窗口无基线，任何租户第一天可用；也**不受成本快照限制**
 * （{@code agg_restaurant_product_cost} 是当前快照套全历史，任何环比毛利结论都是
 * 假话，这条规则不做环比）。
 *
 * <p>口径与阈值只存在于 Python 侧那一处，阈值全部是中位数这类**相对量** ——
 * 唯一活跃的餐饮租户是假数据，绝对金额阈值是对小说调参。
 */
@Component
@RequiredArgsConstructor
public class RestaurantPuzzleDishProvider implements FindingProvider {

    private final RestaurantMarginFindingReader reader;

    @Override
    public String domain() {
        return "restaurant";
    }

    @Override
    public String ruleName() {
        return "菜品毛利谜题";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        return reader.read(factoryId, "puzzle_dishes");
    }
}
