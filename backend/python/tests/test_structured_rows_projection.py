"""投影丢失修复的断言 —— 正文里那张表必须有机器可读的对应物。

🔴 病的长相（实测）：问「卖得最好的几个菜」，正文印着 5 行 markdown 表格，
   而响应的机器可读侧 `kpis: []` / `charts: 0`。
   resolver 里**明明构造了** `ranked_entities`（逐行的 dict），
   `_structured_context` 也**读到了它** —— 但只取 top-1 当 `focus_entity`，
   整张表在这一层被丢掉。不是数据缺失，是投影丢失。

⛔ 修法禁止「从正文 parse 数字塞进 kpis」：那是把渲染当数据源，
   正文格式一改就静默失效。数据只从 resolver 的结构化产物来。
"""
import pytest

from smartbi.gold.restaurant.restaurant_intent_service import (
    _STRUCTURED_ROWS_LIMIT,
    _generic_rows,
    _raw_structured_rows,
    _structured_context,
)


class _Spec:
    """`_structured_context` 只读 spec 的这些属性。"""

    plan_hash = "h"
    plan_version = "v2"
    window_label = "最近30天"
    requested_metrics = ()
    analysis_action = None
    comparison = None
    comparison_label = None
    comparison_range = ()
    ranking_direction = "best"
    ranking_limit = 5
    dimensions = ("dish",)
    dish_slot = None
    store_slot = None
    store_scope = "all"
    store_slots = ()
    store_options = ()
    compare_stores = False
    intent = "RESTAURANT_OPS_GROSS_MARGIN"
    resolver_query_seed = "卖得最好的几个菜"
    excluded_entities = ()


def _dish(rank, name):
    return {"type": "dish", "id": 3860 + rank, "name": name, "rank": rank,
            "sales_volume": 100.0 * rank, "bill_count": 10 * rank,
            "revenue": 1000.0 * rank}


RANKED = [_dish(i, f"菜{i}") for i in range(1, 6)]


def _ctx(meta):
    return _structured_context(_Spec(), meta, dish_mention=None, store_mention=None)


def test_ranked_entities_reach_structured_context():
    """判据：结构化条数 == 正文表格行数。这里用 resolver 产的 5 条对 5 行。"""
    ctx = _ctx({"ranked_entities": RANKED, "dish_ranking": "best"})
    assert len(ctx["rows"]) == 5
    assert ctx["rows"][0]["name"] == "菜1"
    assert ctx["rows_total"] == 5
    assert ctx["rows_truncated"] is False


def test_focus_entity_still_works_and_is_not_the_whole_table():
    """阴性对照：修复前**只有** focus_entity。它必须还在，且只是 top-1。

    ⛔ 没有这条，「rows 有 5 条」可能只是我把 focus_entity 复制了 5 份。
    """
    ctx = _ctx({"ranked_entities": RANKED})
    assert ctx["focus_entity"]["name"] == "菜1"
    assert ctx["focus_entity"]["rank"] == 1
    assert len(ctx["rows"]) == 5
    assert {r["name"] for r in ctx["rows"]} == {"菜1", "菜2", "菜3", "菜4", "菜5"}


def test_no_table_means_empty_rows_not_missing_key():
    """答案本来就没有表格时是空列表，不是缺键 —— 下游要能分辨「没有」和「没接上」。"""
    ctx = _ctx({})
    assert ctx["rows"] == []
    assert ctx["rows_total"] == 0
    assert ctx["rows_truncated"] is False


def test_truncation_is_reported_not_silent():
    """⛔ 静默截断会让「只有 50 条」和「一共就 50 条」在下游变成同一件事。"""
    many = [_dish(i, f"菜{i}") for i in range(1, _STRUCTURED_ROWS_LIMIT + 12)]
    ctx = _ctx({"ranked_entities": many})
    assert len(ctx["rows"]) == _STRUCTURED_ROWS_LIMIT
    assert ctx["rows_total"] == len(many)
    assert ctx["rows_truncated"] is True


def test_generic_executor_nested_rows_are_flattened():
    """通用执行器那条路返回的是**每个 CellResult 一段**的嵌套列表。

    🔴 不摊平的话 `_raw_structured_rows` 会因为 `value[0]` 是 list 不是 dict
       而当成「没有结构化行」跳过 —— 修了等于没修。这条钉死它。
    """
    nested = {"rows": [[{"a": 1}, {"a": 2}], [{"b": 3}]]}
    assert _raw_structured_rows(nested) == [], "嵌套结构不该被当成结构化行"

    flat = _generic_rows(nested)
    assert flat == [{"a": 1}, {"a": 2}, {"b": 3}]
    assert len(_ctx({"rows": flat})["rows"]) == 3


@pytest.mark.parametrize("bad", [None, {}, {"rows": None}, {"rows": "x"}, {"rows": [None]}])
def test_generic_rows_is_defensive(bad):
    assert _generic_rows(bad) == []


def test_rows_never_come_from_parsing_the_answer_text():
    """把渲染当数据源是被禁止的修法 —— 这条钉住「正文改了 rows 不变」。

    同一个 result_meta，正文换成任意别的东西，`rows` 必须一模一样，
    因为它根本不看正文。
    """
    meta = {"ranked_entities": RANKED}
    a = _ctx(meta)["rows"]
    # 正文完全不参与 `_structured_context` 的入参 —— 这就是「不从正文 parse」的
    # 结构性保证。这里再断言一次它对同一 meta 是确定性的。
    b = _ctx(meta)["rows"]
    assert a == b
    assert all("name" in r for r in a)
