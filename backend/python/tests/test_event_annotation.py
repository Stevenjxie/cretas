"""T3「标事件」—— 触发、入库、以及**它永远不进实测**的那道闸。

owner 2026-08-14 原话：**事件标注是主观数据，有自己的 provenance，
绝不和实测混算。** 这个文件把那句话变成可判定的。
"""
from __future__ import annotations

import ast
import inspect
import pathlib

import pytest

from smartbi.gold.restaurant import event_annotation as ea
from smartbi.gold.restaurant import provenance as prov


# ── 🔴 承重：它永远不进实测那一侧 ─────────────────────────────────────────
def test_annotations_never_enter_measured_computation():
    """🔴🔴 判据七的核心：**结构性**地进不去，不靠自觉。

    三道保障各查一条：
      ① `REPORTED_BY_USER` 不在 `VALID_PROVENANCE` 里
         ⇒ 拿它构造 `CellResult` 会当场炸
      ② 库里 `CHECK (provenance = 'REPORTED_BY_USER')`
      ③ 本模块**不导出任何返回数值的函数**
    """
    # ① 它不是一个合法的数值格子出处
    assert prov.REPORTED_BY_USER not in prov.VALID_PROVENANCE, (
        "`REPORTED_BY_USER` 进了 VALID_PROVENANCE —— "
        "那样就能拿它标一个数值格子, 主观数据混进实测")
    with pytest.raises(prov.ProvenanceError):
        prov.validate(prov.REPORTED_BY_USER)

    # 阳性对照: 合法的两个**不会**炸 —— 否则上面那条在「validate 永远炸」时也绿
    prov.validate(prov.MEASURED)
    prov.validate(prov.ESTIMATED, "成本卡的理论用量")

    # 真的拿它构造 CellResult 也要炸(走产品那侧的类, ⛔ 不只测 validate)
    from smartbi.gold.restaurant.generic_executor import CellResult
    with pytest.raises(prov.ProvenanceError):
        CellResult("gross_profit", "毛利", "all", "summary", "money",
                   [{"gross_profit": 1.0}], (), "", prov.REPORTED_BY_USER, "x")

    # ② 库侧: 迁移里那条 CHECK 在
    mig = (pathlib.Path(__file__).resolve().parents[1] / "smartbi" / "database"
           / "migrations" / "V20261101_14__restaurant_event_annotation.sql")
    sql = mig.read_text(encoding="utf-8")
    assert "CHECK (provenance = 'REPORTED_BY_USER')" in sql, (
        "迁移里没有那条 CHECK —— 库里就能写进一行 MEASURED 的标注")
    assert "ENABLE  ROW LEVEL SECURITY" in sql and "FORCE" in sql, "RLS 没开"

    # ③ 本模块不导出返回数值的函数 —— 用 AST 查返回语句里有没有算术
    src = inspect.getsource(ea)
    tree = ast.parse(src)
    numeric = []
    for fn in tree.body:
        if not isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        if fn.name.startswith("_"):
            continue
        for node in ast.walk(fn):
            if isinstance(node, ast.Return) and isinstance(
                    node.value, (ast.BinOp, ast.Compare)):
                # `is_trace_exhausted` 返回布尔判断, 那不是数值
                if fn.name != "is_trace_exhausted":
                    numeric.append(fn.name)
    assert not numeric, (
        f"这些导出函数在 return 里做了运算: {numeric} —— "
        f"标注模块一旦返回数值, 它就有机会被算进去")


def test_provenance_is_not_a_parameter_of_record_answer():
    """🔴 入库函数**不接受** provenance 入参。

    留成参数 = 留了一条「标成 MEASURED」的路, 而库里那条 CHECK 要到运行时
    才炸 —— 那时已经在 prod 上了。
    """
    sig = inspect.signature(ea.record_answer)
    assert "provenance" not in sig.parameters, (
        f"`record_answer` 有 provenance 参数: {list(sig.parameters)}")
    assert "REPORTED_BY_USER" in inspect.getsource(ea.record_answer)


# ── 触发判据：无痕层 ──────────────────────────────────────────────────────
def test_t3_fires_only_when_the_trace_is_exhausted():
    """🔴 判据只有一条：候选下钻维度为空。

    ⛔ 不掺样本量（那条挂账）—— 掺进来 T3 会在**还能继续往下查**的时候
       冒出来，等于用一个问句顶替一次查询。
    """
    assert ea.is_trace_exhausted(()) is True
    assert ea.is_trace_exhausted(("date",)) is False
    # 阳性对照: 它只看维度, 不看别的
    assert ea.is_trace_exhausted([]) is True


def test_t3_button_lands_on_the_same_label_question_contract():
    """T3 落回 `{label, question}`，与 T1/T2 同一份契约。"""
    from smartbi.gold.restaurant.follow_up_actions import (
        TYPE_PRIORITY, to_followups)

    action = ea.build_action(when="8 月 12 日", subject="罗氏虾",
                             phenomenon="卖了平时的 3 倍", anchor="正文")
    assert action["type"] == "T3"
    assert action["label"] == ea.T3_LABEL
    assert "这天有没有做什么活动" in action["question"]
    assert "8 月 12 日" in action["question"] and "罗氏虾" in action["question"]

    got = to_followups([action])
    assert got == [{"label": ea.T3_LABEL, "question": action["question"],
                    "type": "T3"}], got
    # 优先级: T2 在前, T3 其次, T1 最后
    assert TYPE_PRIORITY["T2"] < TYPE_PRIORITY["T3"] < TYPE_PRIORITY["T1"]


def test_the_question_refuses_to_be_built_without_specifics():
    """⛔ 不带具体时间和现象就不许问 —— 他不知道你在问哪一天的哪件事。"""
    with pytest.raises(ValueError):
        ea.build_question(when="", subject="罗氏虾", phenomenon="卖了 3 倍")
    with pytest.raises(ValueError):
        ea.build_question(when="8 月 12 日", subject="罗氏虾", phenomenon="  ")
    # 阳性对照: 齐了就建得出来
    assert ea.build_question(when="8 月 12 日", subject="", phenomenon="涨了")


# ── 入库 ─────────────────────────────────────────────────────────────────
class _FakeConn:
    def __init__(self):
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append((sql, args))
        return {"id": 42}

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        return []


def _run(coro):
    import asyncio
    return asyncio.new_event_loop().run_until_complete(coro)


def test_answer_is_stored_with_the_subjective_provenance():
    """🔴 用户答了能入库，且写进去的 provenance 就是那一个。"""
    conn = _FakeConn()
    rid = _run(ea.record_answer(
        conn, factory_id="MOCK_REST", event_date=__import__("datetime").date(2026, 8, 12),
        subject_kind="dish", subject_name="罗氏虾",
        asked_question="8 月 12 日罗氏虾卖了平时的 3 倍，这天有没有做什么活动？",
        answer_text="那天抖音团购上了个套餐", answered_by_role="factory_super_admin"))
    assert rid == 42
    sql, args = conn.calls[-1]
    assert "fact_restaurant_event_annotation" in sql
    assert prov.REPORTED_BY_USER in args, (
        f"入库参数里没有 REPORTED_BY_USER: {args}")
    assert "那天抖音团购上了个套餐" in args, "存的不是他的原话"


def test_an_empty_answer_is_not_stored():
    """⛔ 空回答不入库 —— 存一条空的 = 把「没解释」记成「已解释」, 下次不再问。"""
    conn = _FakeConn()
    assert _run(ea.record_answer(
        conn, factory_id="F", event_date=__import__("datetime").date(2026, 8, 12),
        subject_kind="all", subject_name="", asked_question="Q",
        answer_text="   ")) is None
    assert not conn.calls, "空回答还是发了 SQL"


def test_quote_reads_as_a_quotation_not_as_causation():
    """⚠️ 引述, ⛔ 不是当因果用。"""
    text = ea.quote({"answer_text": "那天抖音团购上了个套餐"})
    assert text.startswith("你上次说：")
    for banned in ("因为", "所以", "导致"):
        assert banned not in text, (
            f"引述里出现了 {banned!r} —— 那把一句主观的话变成了推理的前提")
