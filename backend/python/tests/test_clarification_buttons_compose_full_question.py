"""澄清按钮的 `question` 合成成完整问句。

## 为什么

生产日志实测（2026-08-16）：

    zero-token plan-cache hit: authority=validated_plan_cache
    intent=RESTAURANT_OPS_WASTAGE_TOP clarification=False query=本月

一条链问「损耗」→ 点「本月」→ 计划以字符串 `本月` 为键写进进程内计划缓存；
另一条链问「门店排名」→ 点「本月」→ **命中前一条链的计划**。

而「本月」是**产品自己发的按钮**（`_clarification_followups` 里 `question == label`）
⇒ 全系统最高频的缓存键，恰好是含义完全取决于上一轮的那批。

▎最容易被缓存的那批串，恰好是最不该被缓存的那批。

## 判据

按钮发的 `question` 必须是一句**自足**的话 —— 单独拿去问也能得到同一个答案。
"""
import pytest

from smartbi.gold.restaurant.restaurant_intent_service import (
    _compose_clarification_question,
    _split_store_scope,
)
from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_date_range

SEED = "米饭的销量是多少"
STORE_NAMES = ("青花椒南方百联店", "模拟·闵行莘庄社区店")


# ── 正例：合成 ────────────────────────────────────────────────────
@pytest.mark.parametrize("window", ["本月", "上个月", "最近7天", "最近30天"])
def test_time_button_composes_a_full_question(window):
    assert _compose_clarification_question(window, SEED, kind="time") == f"{window}{SEED}"


@pytest.mark.parametrize("window", ["本月", "上个月", "最近7天", "最近30天"])
def test_composed_time_question_resolves_the_clicked_window(window):
    """🔴 承重：合成句单独拿去解析，解出的**正是用户点的那个窗口**。

    ⛔ 不是「解得出某个窗口就行」—— 那样把「本月」拼成「上个月X」也会过。
    """
    composed = _compose_clarification_question(window, SEED, kind="time")
    assert _resolve_sales_date_range(composed)[1] == window, composed


def test_store_button_composes_and_the_scope_is_recoverable():
    composed = _compose_clarification_question(
        "全部门店", SEED, kind="store", store_names=STORE_NAMES)
    assert composed == f"全部门店{SEED}"
    assert _split_store_scope(composed, STORE_NAMES)[0] == "全部门店"


def test_store_name_button_composes():
    composed = _compose_clarification_question(
        "青花椒南方百联店", SEED, kind="store", store_names=STORE_NAMES)
    assert composed == f"青花椒南方百联店{SEED}"
    assert _split_store_scope(composed, STORE_NAMES)[0] == "青花椒南方百联店"


# ── 准入 1：seed 非空 ────────────────────────────────────────────
@pytest.mark.parametrize("seed", ["", "   ", None])
def test_empty_seed_falls_back_to_the_bare_word(seed):
    """⚠️ 这一条**不能靠自足性检查兜**：`seed=''` 时合成句就是「本月」，
    而「本月」确实解得出窗口 —— 自足性检查会放行一个不是完整句子的串。
    """
    assert _compose_clarification_question("本月", seed, kind="time") == "本月"


# ── 准入 2：自足性，**按种类分**────────────────────────────────────
def test_a_time_word_the_parser_does_not_know_falls_back():
    """🔴 让准入 2（时间）真的红一次。

    ⚠️ 现有 4 个按钮词拼上任何 seed 都解得出窗口 ⇒ 这条准入在**当前词表下恒真**。
    它守的是「将来有人往按钮词表里加了解析器不认识的窗口词」。
    没有这条测试，那条准入从未被验证过。
    """
    assert _resolve_sales_date_range(f"第三季度末{SEED}")[1] == "全部历史"  # 前置事实
    assert _compose_clarification_question("第三季度末", SEED, kind="time") == "第三季度末"


def test_the_time_check_is_not_applied_to_store_buttons():
    """🔴 设计卡第一版把「解得出窗口」写成了通用准入 —— 那会让**每一个**
    门店合成句被判不自足，全部退回光秃秃的词，而我会以为改好了。

    实测：`全部门店米饭的销量是多少` 的窗口就是「全部历史」。
    """
    assert _resolve_sales_date_range(f"全部门店{SEED}")[1] == "全部历史"   # 前置事实
    assert _compose_clarification_question(
        "全部门店", SEED, kind="store", store_names=STORE_NAMES) == f"全部门店{SEED}"


def test_a_store_prefix_that_cannot_be_split_back_falls_back():
    """准入 2（门店）能红：店名不在 `store_names` 里就切不回来。"""
    assert _compose_clarification_question(
        "查无此店", SEED, kind="store", store_names=STORE_NAMES) == "查无此店"


# ── 兜底不许把「我不知道」翻译成一个值 ────────────────────────────
def test_unknown_kind_raises():
    """⛔ 不返回兜底值 —— 那会让拼错的 kind 静默退回旧行为，
    长得和「准入没过」一模一样（本仓形态 A¹⁰）。"""
    with pytest.raises(ValueError):
        _compose_clarification_question("本月", SEED, kind="dish")


# ── 阳性对照 ────────────────────────────────────────────────────
def test_composition_actually_changes_the_string():
    """⛔ 少了它，上面那些「退回光秃秃的词」的断言在
    「这个函数什么都不做」时**全部成立**。"""
    assert _compose_clarification_question("本月", SEED, kind="time") != "本月"


# ── M3 对照：窗口必须是用户点的那个，不是「解得出某个窗口」────────
def test_the_window_must_be_the_one_the_user_clicked():
    """M3 对照：`!= "全部历史"` 与 `== head` 的区别只在**解出了别的窗口**时显现。

    ⚠️ `上上上个月` 实测解出「上上个月」—— 解得出窗口(所以 `!=全部历史` 放行),
    但**不是**用户点的那个 ⇒ 必须退回。
    """
    assert _resolve_sales_date_range(f"上上上个月{SEED}")[1] == "上上个月"   # 前置事实
    assert _compose_clarification_question("上上上个月", SEED, kind="time") == "上上上个月"
