"""餐饮毛利发现规则 (domain="restaurant")。

⛔ 本模块只产出结构化数字, 不产出话术 —— 渲染由 Java 侧 FindingTextRenderer 负责。
⛔ 本模块**不自己 join 成本**。每道菜的食材成本口径在仓里已有 5 处承载, 而
   `FindingProvider` 接口注释明写「实现禁止新写口径 SQL」。这里调
   `dish_margin.compute_dish_margins` —— 与 `/restaurant-ops/gross-margin` 端点
   同一个函数, 同一份数字。

为什么是毛利口径而不是损耗口径 (2026-08-06 Steve 拍板):
    餐饮不该把损耗放在台面上。工厂按批次追物料损耗是因为投入产出一一对应;
    餐饮的浪费/多打勺/被拿走**最后都体现在毛利被吃掉**。重点是加权毛利、
    总成本、总营收、总毛利。

🔴 阈值全部是**相对量**(中位数), 没有任何绝对金额或绝对销量常数。
   唯一活跃的餐饮租户 MOCK_REST 是假数据(租户名自己写着「假 POS 数据接入验证」:
   30 天营收 ¥7500 万、销量呈 143k/175k 双峰无长尾), 在它上面调出来的绝对阈值
   是对小说调参。中位数这个**形状**在真实长尾数据上一样成立。
"""
from __future__ import annotations

import logging
from statistics import median
from typing import Any, Dict, List

from smartbi.gold.restaurant.dish_margin import compute_dish_margins

logger = logging.getLogger(__name__)

#: 中位数至少要几道菜才有意义。样本太少时中位数会被单个菜牵着走 ——
#: 这是**结构性**下限(分不分得出高低), 不是业务阈值, 与数据真假无关。
_MIN_PRICED_DISHES = 4

#: 有配方的菜要覆盖多少营收, 中位数才代表「全店」。低于此只说判不了 ——
#: 「10 个菜里 3 个有配方」算出来的中位数被读成全店结论就是误导。
_MIN_COVERAGE_REVENUE_RATIO = 0.5

#: 单份毛利达到中位数几倍时升级为 WARNING。相对量, 不是金额。
_PUZZLE_WARNING_MULTIPLE = 2.0

#: 「今天就能动手」的程度。高于损耗两条规则(60/70): 推荐位、服务员话术、
#: 套餐搭配都是当天生效的杠杆, 而且全在店长权限内。
_PUZZLE_ACTIONABILITY = 75


def _skip(rule: str, reason: str) -> Dict[str, Any]:
    """「数据没采集到 / 判不了」—— 与「真的没有」(applicable=True, findings=[]) 严格区分。"""
    return {"rule": rule, "applicable": False, "skip_reason": reason, "findings": []}


def _ok(rule: str, findings: List[Dict[str, Any]]) -> Dict[str, Any]:
    return {"rule": rule, "applicable": True, "skip_reason": None, "findings": findings}


async def detect_puzzle_dishes(
    pool, factory_id: str, *, days: int = 30,
) -> Dict[str, Any]:
    """谜题菜: **单份毛利高于中位、销量低于中位** —— 最赚钱的菜没卖动。

    触发 = unitMargin >= 中位数 AND qty < 销量中位数。单窗口无基线, 任何租户
    第一天可用, 且**不受成本快照限制**(`agg_restaurant_product_cost` 是当前快照
    套全历史, 任何环比毛利结论都是假话 —— 这条规则不做环比)。

    ⛔ 只看 `hasCost` 的菜。无配方的菜走行业默认成本率, 对它们成本率是**同一个
    常数**, 拿它算出来的单份毛利与售价成正比 —— 排出来的是一份「看着像毛利分析、
    实则是价格榜」的东西。这条纪律沿用 gross-margin 里 `priced` 的原话。

    ── 为什么是「谜题」而不是「亏本菜/低毛利菜」 ──────────────────
    2026-08-07 在 prod (MOCK_REST) 上实测过低毛利那一版: **产出 0 条**。
    低单份毛利的米饭/酸梅汤恰好都在销量中位数以下被销量闸挡掉; 去掉销量闸则
    只报米饭+酸梅汤 —— 而「米饭不赚钱」不是店长不知道的事, 是噪音。
    全店也**没有亏本菜**(unitMargin 全为正), CRITICAL 那一档是空的。
    真正有信息量的是谜题象限: 罗氏虾单份赚 ¥78.57 全店最高(中位 ¥27.51),
    销量却在最低档 —— 店长以为它很好(营收全店第一), 实际是最赚钱的菜没卖动。
    """
    rule = "puzzle_dishes"
    try:
        data = await compute_dish_margins(pool, factory_id, days=days)
    except Exception as exc:
        # 上抛 → Java 侧 failedRules → 「检查失败, 暂无法判断」。
        # 绝不 return _ok(rule, []) —— 那会被渲染成「均正常」, 把故障说成健康。
        raise RuntimeError(f"菜品毛利口径不可用: factory={factory_id}") from exc

    dishes = data.get("dishes") or []
    coverage = data.get("coverage") or {}
    priced = [
        d for d in dishes
        if d.get("hasCost") and (d.get("qty") or 0) > 0
    ]

    if len(priced) < _MIN_PRICED_DISHES:
        return _skip(
            rule,
            f"有配方的菜只有 {len(priced)} 道(需 >= {_MIN_PRICED_DISHES} 道), "
            f"中位数分不出高低",
        )

    coverage_ratio = float(coverage.get("revenueRatio") or 0.0)
    if coverage_ratio < _MIN_COVERAGE_REVENUE_RATIO:
        return _skip(
            rule,
            f"有配方的菜只覆盖 {coverage_ratio:.0%} 的营收"
            f"(需 >= {_MIN_COVERAGE_REVENUE_RATIO:.0%}), "
            f"这些菜的中位数代表不了全店",
        )

    unit_margins = [d["grossProfit"] / d["qty"] for d in priced]
    quantities = [float(d["qty"]) for d in priced]
    um_median = median(unit_margins)
    qty_median = median(quantities)

    if min(unit_margins) == max(unit_margins):
        # 所有菜单份毛利一样 → 中位数把它们全划到同一侧, 分不出谁是谜题。
        # 这不是「没有谜题菜」, 是「这份数据判不了」。
        return _skip(rule, "所有有配方的菜单份毛利相同, 中位数分不出高低")

    findings: List[Dict[str, Any]] = []
    for d in priced:
        unit_margin = d["grossProfit"] / d["qty"]
        if unit_margin < um_median or d["qty"] >= qty_median:
            continue
        multiple = (unit_margin / um_median) if um_median > 0 else 0.0
        findings.append({
            "code": "DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME",
            "subject_id": d["name"],
            "subject_name": d["name"],
            "severity": "WARNING" if multiple >= _PUZZLE_WARNING_MULTIPLE else "INFO",
            "actionability": _PUZZLE_ACTIONABILITY,
            "facts": {
                "unitMargin": round(unit_margin, 2),
                "unitMarginMedian": round(um_median, 2),
                # ⚠️ 用 int 不用 round(x, 0): 后者返回 **float**, 渲染出来是
                # 「卖 143188.0 份」—— 2026-08-07 首次上线后在 prod 落地页上
                # 肉眼看到的。份数天然是整数, 类型就该是整数。
                "qty": int(round(d["qty"])),
                "qtyMedian": int(round(qty_median)),
                "windowDays": data.get("windowDays") or days,
                # 一起给出「有配方的菜有几道 / 占多少营收」, 让渲染层能把
                # 「10 个菜里 3 个」和「全店结论」区分开(gross-margin 端点
                # 对 menuEngineering 的中位数也提了同样的要求)。
                "pricedDishCount": len(priced),
                "coverageRevenueRatio": round(coverage_ratio * 100, 1),
            },
        })

    # 最赚钱的排前面 —— 店长先看该推哪道。
    findings.sort(key=lambda f: f["facts"]["unitMargin"], reverse=True)
    return _ok(rule, findings)
