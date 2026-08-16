"""归因层 2 · 「为什么今天卖的这么少」—— 把营收变化拆成**客流**和**客单价**。

⛔ 本模块一个数都不取。取数交给 `generic_executor.execute_cell`（唯一的结构化
出数处），这里只做**拆解**和**排版**。

## 他哪句话要求了这个

> 「就可能会问，**为什么今天卖的这么少啊**？」

取舍排序里排第一的那类。「今天少了 18%」不能让他做决定；
「**少的是客流不是客单价**」可以 —— 客流问题去看渠道/天气/活动，
客单价问题去看菜单/折扣，是两套动作。

## 三个裁定（原本挂账等裁定，2026-08-16 由本 chat 裁）

### 裁定一 · 基线 = **上周同一天**，⛔ 不是昨天

餐饮有强周内效应：周一和周六完全是两门生意。跟昨天比，会把
「周末结束了」报成「生意变差了」—— 那是一条**方向就错**的归因。

⚠️ 上周同一天**没有数据**时 ⇒ 明说「没有可比的基线」，
⛔ **不许悄悄换一个能比的日子**（静默换窗口，仓里 T6② 已定不许）。

### 裁定二 · 文案**由代码渲染**，⛔ 不交给 LLM 生成

挂账时卡在「LLM 输出没法验收」。解法不是想办法验 LLM，是**不让 LLM 干这件事**：
拆解结论由本模块确定性地写出来，跟 B-1 那张表同一条路子。
⇒ 于是闸可以逐格断言数字，变异可以精确命中。

### 裁定三 · 拆不出来时**明说拆不出来**

⛔ 不许退回「今天营收 X 元」—— 那是把「我不知道为什么」伪装成回答。

## 拆解的恒等式（这条是本模块的承重墙）

    营收 = 单量 × 客单价

    ΔR = ΔQ·P₀ + Q₀·ΔP + ΔQ·ΔP
         └客流贡献┘ └客单价贡献┘ └交叉项┘

三项之和**恰好**等于 ΔR。⛔ 不许「差不多」——对不上就是拆错了，闸会红。
⚠️ 交叉项单列，⛔ 不摊进前两项：摊进去就没法说「哪个是主因」了，
   而那正是他要的那句话。
"""
from typing import Any, Dict, Optional

#: 交叉项占 |ΔR| 的比例超过它，就不说「主因是谁」——两个因素都在动，
#: 归成一个是编的。
_CROSS_TERM_NOISE = 0.30


def _money(v: float) -> str:
    return f"¥{v:,.2f}"


def _pct(v: float) -> str:
    return f"{v * 100:.1f}%"


def decompose(
    *,
    revenue_now: Optional[float],
    orders_now: Optional[float],
    revenue_base: Optional[float],
    orders_base: Optional[float],
) -> Dict[str, Any]:
    """把营收变化拆成客流 / 客单价 / 交叉项。

    ⚠️ 任一输入是 `None` ⇒ 返回 `ok=False` 并说清缺哪个，
    ⛔ 不兜底成 0：`revenue=None`（没取到）和 `revenue=0`（没营业）
    对这次归因是**相反**的意思，而 0 会被当成真实读数往下传。
    """
    missing = [name for name, v in (
        ("今天营收", revenue_now), ("今天单量", orders_now),
        ("基线营收", revenue_base), ("基线单量", orders_base),
    ) if v is None]
    if missing:
        return {"ok": False, "reason": "missing_inputs", "missing": missing}

    q0, q1 = float(orders_base), float(orders_now)
    r0, r1 = float(revenue_base), float(revenue_now)
    if q0 <= 0 or q1 <= 0:
        # 客单价 = 营收/单量，单量为 0 时它不存在。⛔ 不填 0 也不填均值。
        return {"ok": False, "reason": "no_orders",
                "detail": f"基线单量={q0:g} 今天单量={q1:g}"}

    p0, p1 = r0 / q0, r1 / q1
    dq, dp = q1 - q0, p1 - p0
    traffic = dq * p0          # 客流贡献
    ticket = q0 * dp           # 客单价贡献
    cross = dq * dp            # 交叉项
    total = r1 - r0

    # 🔴 承重墙: 三项之和必须**恰好**等于 ΔR。
    #    ⛔ 不是断言「差不多」—— 对不上说明拆错了，而拆错的归因比不归因更糟。
    identity_holds = abs((traffic + ticket + cross) - total) < 0.01

    driver = None
    if abs(total) > 0.01 and abs(cross) / abs(total) <= _CROSS_TERM_NOISE:
        driver = "traffic" if abs(traffic) >= abs(ticket) else "ticket"

    return {
        "ok": True,
        "revenue_now": r1, "revenue_base": r0, "delta_revenue": total,
        "orders_now": q1, "orders_base": q0, "delta_orders": dq,
        "avg_ticket_now": p1, "avg_ticket_base": p0, "delta_avg_ticket": dp,
        "traffic_contribution": traffic,
        "ticket_contribution": ticket,
        "cross_term": cross,
        "identityHolds": identity_holds,
        # ⚠️ `driver=None` 有**两种**成因，措辞里必须分开：
        #    ① 营收几乎没变 ② 两个因素都在动（交叉项太大），归成一个是编的
        "driver": driver,
    }


def render(d: Dict[str, Any], *, base_label: str) -> str:
    """确定性文案。⛔ 不交给 LLM —— 见模块头「裁定二」。"""
    if not d.get("ok"):
        # 裁定三：拆不出来就说拆不出来。
        if d.get("reason") == "missing_inputs":
            return ("📐 **算不出「为什么」** —— 缺" + "、".join(d["missing"])
                    + f"（基线取的是{base_label}）。"
                    "⇒ 这几个数补齐之前，我只能告诉你今天是多少，说不了为什么。")
        return (f"📐 **算不出「为什么」** —— {base_label}或今天的单量是 0"
                f"（{d.get('detail', '')}），客单价没有定义，拆不出客流和客单价。")

    up = d["delta_revenue"] >= 0
    word = "多" if up else "少"
    head = (f"📐 跟{base_label}比，今天营收{word}了 {_money(abs(d['delta_revenue']))}"
            f"（{_money(d['revenue_base'])} → {_money(d['revenue_now'])}）。")

    # ⚠️ 变化量为 0 时说「高了 ¥0.00」读起来像坏了 —— 明说「没变」。
    def _moved(delta, up, down, fmt):
        if abs(delta) < 0.005:
            return "没变"
        return f"{up if delta > 0 else down}了 {fmt(abs(delta))}"

    body = (f"拆开看：客流"
            f"{_moved(d['delta_orders'], '多', '少', lambda v: f'{v:g} 单')}"
            f"（{d['orders_base']:g} → {d['orders_now']:g}），"
            f"客单价{_moved(d['delta_avg_ticket'], '高', '低', _money)}"
            f"（{_money(d['avg_ticket_base'])} → {_money(d['avg_ticket_now'])}）。")

    parts = (f"这 {_money(abs(d['delta_revenue']))} 里，"
             f"客流带来 {_money(d['traffic_contribution'])}、"
             f"客单价带来 {_money(d['ticket_contribution'])}、"
             f"两者叠加 {_money(d['cross_term'])}。")

    if d["driver"] == "traffic":
        tail = ("⇒ **主要是客流**，不是客单价。"
                "客流的事去看渠道、天气、周边活动；⛔ 先别动菜单和价格。")
    elif d["driver"] == "ticket":
        tail = ("⇒ **主要是客单价**，不是客流。"
                "人没少，是每单花得少了 —— 去看折扣力度、菜品结构、有没有少点。")
    elif abs(d["delta_revenue"]) <= 0.01:
        tail = "⇒ 营收基本没变，没有需要归因的差额。"
    else:
        tail = ("⚠️ **客流和客单价都在动**，两者叠加的部分占了差额的一大块 ——"
                "这种情况下说「主因是某一个」是编的。⇒ 两条都要看。")

    return "\n\n".join([head, body, parts, tail])
