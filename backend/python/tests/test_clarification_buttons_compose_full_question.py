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


def test_an_empty_prefix_does_not_turn_the_seed_into_the_question():
    """🔴 准入 1 的 `not head` 这一半**是承重的** —— 而它差点没有测试。

    `_split_store_scope(x, names)[0]` 在切不出前缀时返回**空串**, 于是
    `prefix=""` 时 `[0] == head` **恒成立** ⇒ 没有准入 1 就会把**原问句本身**
    当成门店按钮的 question 发出去, 门店范围凭空消失。

    ⚠️ 生产上 `spec.store_options` 混进一个空串就会走到这里。

    ⚠️ 另一半 (`not body`) 不承重: 它只在 `prefix` 带首尾空格时改变输出,
    而那时它返回的是**未 strip 的** prefix。⇒ 它是防御性的, 不是判据。
    """
    assert _compose_clarification_question(
        "", SEED, kind="store", store_names=STORE_NAMES) == ""
    assert _compose_clarification_question(
        "   ", SEED, kind="store", store_names=STORE_NAMES) == "   "


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


# ══════════════════════════════════════════════════════════════════
# 接线：`_clarification_followups` 真的用上了合成函数
# ══════════════════════════════════════════════════════════════════
from smartbi.gold.restaurant.restaurant_intent import (          # noqa: E402
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    RestaurantQuerySpec,
)
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    _SWITCHABLE_WINDOWS,
    _clarification_followups,
)


def _spec(**overrides) -> RestaurantQuerySpec:
    """⚠️ 用**真的** RestaurantQuerySpec，⛔ 不用 SimpleNamespace ——
    桩的自由度会让我构造出真实那侧永远不产出的形状（本仓形态 B‴）。

    🔴 它有 **12 个必填字段**，⛔ 不能只传 `clarification_question`
    （实测 `TypeError`）。下面这份 defaults 抄自
    `tests/test_restaurant_intent_service.py:41` —— 同一个被测模块的既有 helper，
    ⛔ 不要自己另编一套默认值。
    """
    defaults = dict(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        domain="restaurant",
        date_range=(None, None),
        window_label="全部历史",
        relative_window=False,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="keyword",
        clarification_needed=True,
        clarification_question=None,
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


def test_time_clarification_buttons_carry_the_whole_question():
    spec = _spec(clarification_question=TIME_CLARIFICATION_QUESTION)
    got = _clarification_followups(spec, SEED)
    assert [b["question"] for b in got] == [f"{w}{SEED}" for w in _SWITCHABLE_WINDOWS]
    # label 仍是短词 —— 按钮上显示的东西⛔ 不变
    assert [b["label"] for b in got] == list(_SWITCHABLE_WINDOWS)


def test_store_clarification_buttons_carry_the_whole_question():
    spec = _spec(clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,
                 store_options=list(STORE_NAMES))
    got = _clarification_followups(spec, SEED)
    assert got[0]["question"] == f"全部门店{SEED}"
    assert got[1]["question"] == f"{STORE_NAMES[0]}{SEED}"
    assert got[0]["label"] == "全部门店"


def test_no_bare_window_word_is_ever_sent_as_a_question():
    """🔴 承重：这是缺陷本身。任何按钮的 `question` 都不许**等于**一个窗口词。"""
    spec = _spec(clarification_question=TIME_CLARIFICATION_QUESTION)
    for b in _clarification_followups(spec, SEED):
        assert b["question"] not in _SWITCHABLE_WINDOWS, b


def test_empty_seed_still_produces_buttons():
    """阳性对照 + 退化路径：seed 为空时**仍然出按钮**（发光秃秃的词），
    ⛔ 不是「一个按钮都没有」—— 那会把澄清 UI 整个弄没。"""
    spec = _spec(clarification_question=TIME_CLARIFICATION_QUESTION)
    got = _clarification_followups(spec, "")
    assert [b["question"] for b in got] == list(_SWITCHABLE_WINDOWS)


def test_llm_authored_options_are_untouched():
    """⛔ 不动第三支：自撰选项是「先看营收」这种任意短语，
    不存在自足性概念，前置它没有意义。"""
    spec = _spec(clarification_options=["先看营收", "先看毛利"])
    got = _clarification_followups(spec, SEED)
    assert [b["question"] for b in got] == ["先看营收", "先看毛利"]


def test_the_window_list_has_exactly_one_definition():
    """形态 D：`_SWITCHABLE_WINDOWS` 与澄清按钮的窗口清单原本是**同一份字面量抄两遍**
    （`:1006` 的注释自己写着「两处一漂」，而没有闸钉住）。

    抽成一份之后这条断言由构造成立 —— 它守的是**别再抄回去**。
    """
    import ast
    import inspect

    import smartbi.gold.restaurant.restaurant_intent_service as svc

    src = inspect.getsource(svc._clarification_followups)
    tree = ast.parse(inspect.cleandoc(src))
    literals = {n.value for n in ast.walk(tree)
                if isinstance(n, ast.Constant) and isinstance(n.value, str)}
    assert not (set(_SWITCHABLE_WINDOWS) & literals), (
        f"窗口词又被抄进函数体了: {set(_SWITCHABLE_WINDOWS) & literals}")
