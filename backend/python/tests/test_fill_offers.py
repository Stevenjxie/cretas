"""T2 补数据开价的验收。

⛔ 被守的行为是「**开价内容是从 registry 反查算出来的，不是写死的**」。
   变异对照因此打在 `requires` 上 —— 改一条 `requires`，开价内容必须跟着变。
   打在别处（比如改文案模板）证明不了这件事。
"""
import pytest

from smartbi.gold.restaurant import metric_registry as reg
from smartbi.gold.restaurant.fill_offers import (
    build_fill_offers,
    column_label,
    offers_for_estimated,
    offers_for_missing_columns,
    unlocked_by_column,
)
from smartbi.gold.restaurant.provenance import ESTIMATED, MEASURED


def test_reverse_lookup_reaches_derived_metrics_transitively():
    """🔴 最有价值的那句开价靠传递闭包才说得出来。

    `food_cost` 直接解锁的只有 `food_cost` 这个基础指标；
    店长嘴里的词是**毛利率**，而 `gross_margin` → `gross_profit` → (revenue, food_cost)
    要走两层。只做一层的话「补成本卡能算毛利率」永远说不出来。
    """
    unlocked = unlocked_by_column()["agg_restaurant_product_cost.food_cost"]
    assert "food_cost" in unlocked
    assert "gross_profit" in unlocked, "一层都没走 —— 毛利没解锁"
    assert "gross_margin" in unlocked, (
        "传递闭包没做到第二层 —— 店长嘴里的「毛利率」永远开不了价")


def test_offer_text_names_the_metric_not_the_column():
    offers = offers_for_missing_columns(["agg_restaurant_product_cost.food_cost"])
    assert len(offers) == 1
    text = offers[0]["text"]
    assert "成本卡" in text, f"没说人话: {text}"
    assert "毛利率" in text, f"没把最有价值的那个指标说出来: {text}"
    # ⛔ 库表列名不许出现在店长眼前
    assert "agg_restaurant_product_cost" not in text
    assert "food_cost" not in text


def test_column_label_comes_from_registry_only():
    """人话名只从 registry 取。⛔ 本模块不许有映射表。"""
    import inspect

    from smartbi.gold.restaurant import fill_offers

    assert column_label("fact_pos_item.qty") == reg.COLUMN_LABELS["fact_pos_item.qty"]
    assert column_label("表.不存在的列") == ""

    src = inspect.getsource(fill_offers)
    # 模块里除了从 registry import, 不该出现第二处「列 -> 中文」的字面量映射。
    assert src.count("COLUMN_LABELS") <= 3, (
        "本模块里出现了不止一处列名映射 —— 手写映射一旦落地, "
        "新登记的列会悄悄落在表外而不报错")


# ── 变异对照: 打在被守的行为(反查)上, 不是打在文案上 ──────────────────
def test_offer_follows_requires_not_a_hardcoded_string(monkeypatch):
    """🔴 改一条 `requires` → 开价内容必须跟着变。

    这条证明开价是**算出来的**。没有它，上面那些断言只能证明
    「今天这句话长这样」，证明不了它明天还会对。
    """
    before = offers_for_missing_columns(["fact_pos_item.qty"])[0]["unlocks"]

    # 让 `wastage_qty` 也依赖这一列 —— 反查结果必须多出它。
    patched = dict(reg.METRICS)
    original = patched["wastage_qty"]
    patched["wastage_qty"] = type(original)(
        **{**original.__dict__,
           "requires": tuple(original.requires) + ("fact_pos_item.qty",)})
    monkeypatch.setattr(reg, "METRICS", patched)

    after = offers_for_missing_columns(["fact_pos_item.qty"])[0]["unlocks"]
    assert "wastage_qty" in after, (
        "改了 requires 开价却没变 —— 说明它是写死的, 不是反查出来的")
    assert set(before) < set(after)


def test_removing_a_requires_shrinks_the_offer(monkeypatch):
    """反方向也要成立 —— 只会变多不算反查。"""
    patched = dict(reg.METRICS)
    original = patched["revenue"]
    patched["revenue"] = type(original)(
        **{**original.__dict__, "requires": ("fact_pos_transaction.net_amount",)})
    monkeypatch.setattr(reg, "METRICS", patched)

    unlocked = unlocked_by_column().get("fact_pos_item.amount", ())
    assert "revenue" not in unlocked, "把 requires 拿掉了, 反查却还说它能解锁"


# ── 两类文案 ────────────────────────────────────────────────────
def test_estimated_offer_says_from_estimate_to_actual():
    offers = offers_for_estimated(
        ESTIMATED, "行业默认成本率 32%", ["毛利", "毛利率"])
    assert len(offers) == 1
    text = offers[0]["text"]
    assert "行业默认成本率 32%" in text, "没带上限定语里那句 basis, 店长对不上"
    assert "从估变实" in text
    assert "毛利率" in text


def test_measured_never_offers_an_upgrade():
    """阴性对照: 数字本来就是账上的, 不该开「从估变实」的价。"""
    assert offers_for_estimated(MEASURED, "", ["毛利"]) == []
    assert offers_for_estimated(ESTIMATED, "行业默认成本率 32%", []) == []


def test_no_trigger_no_offer():
    """两个触发条件都不满足时一条都不出。"""
    assert build_fill_offers() == []
    assert build_fill_offers(missing_columns=[], provenance=MEASURED) == []


def test_both_triggers_compose():
    offers = build_fill_offers(
        missing_columns=["fact_pos_transaction.tax_amount"],
        provenance=ESTIMATED,
        estimation_basis="行业默认成本率 32%",
        estimated_metric_labels=["毛利率"],
    )
    kinds = [o["kind"] for o in offers]
    assert kinds == ["fill", "upgrade"]


def test_unknown_column_is_skipped_not_faked():
    """说不清楚就不说 —— ⛔ 但这不会静默: 缺人话名由 registry 那道断言当场红。"""
    assert offers_for_missing_columns(["表.不存在的列"]) == []


# ── registry 那道闸自己也要有变异对照 ────────────────────────────
def test_registry_gate_catches_a_column_without_a_human_name(monkeypatch):
    patched = dict(reg.COLUMN_LABELS)
    patched.pop("fact_pos_item.qty")
    monkeypatch.setattr(reg, "COLUMN_LABELS", patched)
    with pytest.raises(AssertionError, match="没有人话名"):
        reg.assert_registry_self_consistent()


def test_registry_gate_catches_an_orphan_label(monkeypatch):
    """反向: 登记了却没人依赖 —— 过期的登记会让开价说「补它有用」。"""
    patched = dict(reg.COLUMN_LABELS)
    patched["fact_pos_transaction.__gone__"] = "已经没人用的列"
    monkeypatch.setattr(reg, "COLUMN_LABELS", patched)
    with pytest.raises(AssertionError, match="没有任何指标依赖它"):
        reg.assert_registry_self_consistent()


def test_registry_is_self_consistent_today():
    """阳性对照: 上面两条变异之所以有意义, 是因为今天它本来是绿的。"""
    reg.assert_registry_self_consistent()


# ── 跑在产品真实入口上的那一条 ────────────────────────────────────
def _cell_with_missing(*columns):
    from smartbi.gold.restaurant.generic_executor import CellResult

    return CellResult(
        metric_key="gross_profit", metric_label="毛利", dimension_key="all",
        aggregation_key="total", unit="money", rows=[],
        missing_columns=tuple(columns),
    )


def test_render_offers_the_fill_at_the_product_entry():
    """🔴 断言跑在**产品真实入口** `generic_answer.render()` 上, 不是直接调开价函数。

    改之前这条路把**裸库表列名**怼给店长: `c.split(".")[-1]` 得到 `food_cost`。
    """
    from smartbi.gold.restaurant.generic_answer import render

    text = render(_cell_with_missing("agg_restaurant_product_cost.food_cost"), "最近30天")

    # ① 缺口本身仍然要说 —— 开价是加值, 不是替换
    assert "还没接进来" in text
    # ② 人话名替掉了裸列名
    assert "成本卡" in text, f"没说人话: {text}"
    assert "food_cost" not in text, f"库表列名怼到店长脸上了: {text}"
    # ③ 开价说出了「补了能算出什么」, 且含店长嘴里那个词
    assert "毛利率" in text, f"没开出最有价值的那句价: {text}"
    # ④ ⛔ 不许同义反复:「补每道菜的食材成本, 能算出**食材成本**」是绕口令。
    #    直接命中的那个 metric 与列本身同义, 闭包解锁的才是卖点。
    assert "还能算出" in text, f"没把「显然的」和「意外之喜」分开: {text}"
    assert "能算出食材成本" not in text, f"同义反复还在: {text}"
    # ⑤ 「字段」是黑话, 店长不说这个词
    assert "字段" not in text, f"黑话漏到店长面前: {text}"


def test_render_offer_follows_requires_at_the_product_entry(monkeypatch):
    """变异对照也打在**产品真实入口**上: 改 `requires` → 正文里那句开价跟着变。

    ⛔ 只在开价函数上做变异, 证明不了「产品那条路真的用了反查」。
    """
    from smartbi.gold.restaurant.generic_answer import render

    # ⛔ 用 registry 取 label, 不写字面量 —— 第一版写死「损耗数量」, 那是**列**的
    #    人话名, 而这里要的是**指标** label(「损耗量」)。变异其实生效了, 是我的
    #    断言在跟一个错的字符串比。形态 D 的微缩版: 同一个概念两个名字。
    # ⛔ 用 registry 取 label, 不写字面量 —— 第一版写死「损耗数量」, 那是**列**的
    #    人话名, 而这里要的是**指标** label(「损耗量」)。变异其实生效了, 是我的
    #    断言在跟一个错的字符串比。形态 D 的微缩版: 同一个概念两个名字。
    wastage_label = reg.METRICS["wastage_qty"].label
    # ⚠️ 落点选 `tax_amount` 而不是 `fact_pos_item.qty`: 后者的**闭包非空**,
    #    正文只说闭包那几个, 直接命中的变化根本不出现在文案里 —— 变异会「不红」,
    #    而那不是断言没用, 是变异打在了正文看不见的地方(形态 C″)。
    #    `tax_amount` 闭包为空, 正文退回直接命中, 改动才可见。
    column = "fact_pos_transaction.tax_amount"
    before = render(_cell_with_missing(column), "最近30天")
    assert wastage_label not in before

    patched = dict(reg.METRICS)
    original = patched["wastage_qty"]
    patched["wastage_qty"] = type(original)(
        **{**original.__dict__, "requires": tuple(original.requires) + (column,)})
    monkeypatch.setattr(reg, "METRICS", patched)

    # 阳性对照: 先证明**变异真的到达了**反查, 再看正文 —— 否则「不红」分不清
    # 是「断言没用」还是「变异没生效」。
    from smartbi.gold.restaurant.fill_offers import unlocked_split_by_column
    assert "wastage_qty" in unlocked_split_by_column()[column]["direct"], (
        "变异没打进反查 —— 后面那条断言无论红不红都没有意义")

    after = render(_cell_with_missing(column), "最近30天")
    assert wastage_label in after, (
        "改了 requires 而产品那条路的开价没变 —— 说明它在别处写死了")


def test_render_still_reports_the_gap_when_no_offer_can_be_made():
    """阴性对照: 开价拿不出来时**不许静默丢掉缺口**。"""
    from smartbi.gold.restaurant.generic_answer import render

    text = render(_cell_with_missing("表.不存在的列"), "最近30天")
    assert "还没接进来" in text, "开价没开出来, 连缺口都不说了"
