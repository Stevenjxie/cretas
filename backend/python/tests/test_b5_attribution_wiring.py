"""B-5 接线闸 —— 断言跑在产品真实入口 `_maybe_append_attribution` 上。

拆解那道闸（`b5_attribution_gate`）只证明「算得对、说得对」。
它对**没人调它**是盲的 —— 而这一整轮反复栽的就是那个形态。

## 这里守什么

1. 只有 `analysis_action == "diagnose"` 且是门店级概览意图时才归因
2. 基线取的是**上周同一天**（⛔ 不是昨天）—— 直接断言传给取数的窗口
3. 拆不出来时**照样把那段话拼上**（裁定三：明说算不出，⛔ 不静默退回报数）
4. 失败 fail-open 但**留痕**
5. **AST 守调用点**：结果必须赋回 `answer_text`，⛔ 不能算了就丢
"""
import asyncio
import datetime
import logging

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc

SUMMARY = "RESTAURANT_OPS_SALES_SUMMARY"
MARGIN = "RESTAURANT_OPS_GROSS_MARGIN"
TODAY = datetime.date(2026, 8, 16)
BASE = "今天营收 ¥8,000。"


class _Spec:
    def __init__(self, intent=SUMMARY, action="diagnose", date_range=(TODAY, TODAY)):
        self.intent = intent
        self.analysis_action = action
        self.date_range = date_range


class _Conn:
    async def execute(self, *a, **k):
        return None


class _Pool:
    def acquire(self):
        conn = _Conn()

        class _Ctx:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *a):
                return False

        return _Ctx()


class _Cell:
    def __init__(self, rows):
        self.rows = rows


@pytest.fixture
def stub_cells(monkeypatch):
    """桩掉取数, ⛔ 只桩外部 IO —— 判定逻辑走真的。

    ⚠️ 形状按 `execute_cell` 真实产出: `.rows[0][metric_key]`。
    """
    calls = []
    # 今天: 8000 / 80 单；上周同一天: 10000 / 100 单 ⇒ 纯客流下降
    table = {("revenue", TODAY): 8000.0, ("orders", TODAY): 80.0,
             ("revenue", TODAY - datetime.timedelta(days=7)): 10000.0,
             ("orders", TODAY - datetime.timedelta(days=7)): 100.0}

    async def _fake(conn, *, factory_id, metric_key, dimension_key,
                    aggregation_key, date_range):
        calls.append((metric_key, date_range))
        return _Cell([{metric_key: table.get((metric_key, date_range[0]))}])

    monkeypatch.setattr(
        "smartbi.gold.restaurant.generic_executor.execute_cell", _fake)
    return calls


def _run(spec):
    return asyncio.run(svc._maybe_append_attribution(_Pool(), "MOCK_REST", spec, BASE))


def test_diagnose_on_summary_appends_attribution(stub_cells):
    out = _run(_Spec())
    assert out.startswith(BASE), "⛔ 原答案不许被顶掉 —— 归因是追加"
    assert "主要是客流" in out, out
    assert "上周同一天" in out


def test_baseline_is_last_week_same_day_not_yesterday(stub_cells):
    """🔴 基线选错，归因的方向就是反的 —— 直接断言传下去的窗口。"""
    _run(_Spec())
    ranges = {r for _m, r in stub_cells}
    want_base = (TODAY - datetime.timedelta(days=7),) * 2
    assert (TODAY, TODAY) in ranges
    assert want_base in ranges, f"基线窗口不是上周同一天: {ranges}"
    assert (TODAY - datetime.timedelta(days=1),) * 2 not in ranges, "⛔ 用了昨天"


def test_no_attribution_for_plain_lookup(stub_cells):
    out = _run(_Spec(action="lookup"))
    assert out == BASE
    assert stub_cells == [], "⛔ 不问为什么时不该白查四次库"


def test_no_attribution_for_dish_level_intent(stub_cells):
    """归因拆的是门店级恒等式 ⛔ 不能拿它解释一道菜。"""
    out = _run(_Spec(intent=MARGIN))
    assert out == BASE
    assert stub_cells == []


def test_missing_window_is_skipped_with_a_trace(caplog):
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(date_range=(None, None)))
    assert out == BASE
    assert any("attribution skipped" in r.message for r in caplog.records)


def test_uncomputable_still_says_so(monkeypatch, caplog):
    """裁定三：拆不出来**照样拼那段话**，⛔ 不静默退回报数。"""
    async def _empty(conn, *, factory_id, metric_key, dimension_key,
                     aggregation_key, date_range):
        return _Cell([{metric_key: None}])

    monkeypatch.setattr(
        "smartbi.gold.restaurant.generic_executor.execute_cell", _empty)
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec())
    assert "算不出「为什么」" in out, "⛔ 静默退回报数 = 把「我不知道」伪装成回答"
    assert any("not computable" in r.message for r in caplog.records)


def test_failure_is_fail_open_but_leaves_a_trace(monkeypatch, caplog):
    async def _boom(conn, **k):
        raise RuntimeError("pg down")

    monkeypatch.setattr(
        "smartbi.gold.restaurant.generic_executor.execute_cell", _boom)
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec())
    assert out == BASE, "归因失败 ⛔ 不许让一次问答失败"
    assert any("attribution failed" in r.message for r in caplog.records)


def test_long_window_now_gets_a_calendar_baseline(stub_cells):
    """🔴 这条**推翻了它自己的上一版**（`..._is_skipped_because_baseline_would_overlap`）。

    上一版守的是「窗口 >7 天就跳过」——那是当时的取舍：-7 天基线在长窗口上
    会与主窗口重叠（上半年 174/181 天），硬算出来的主因是噪音假装成洞察。

    ⇒ 现在基线按**窗口形状**挑（`attribution_baseline.pick_baseline`），
      长窗口有了不重叠的对照期，跳过的理由就不成立了。
      ⚠️ 断言守的是**需求**（别拿重叠的两段比），不是那条临时取舍。
    """
    long_range = (datetime.date(2026, 1, 1), datetime.date(2026, 6, 30))
    out = _run(_Spec(date_range=long_range))
    assert out != BASE, "上半年这种完整半年现在应当能归因"
    assert "上一个半年" in out, out
    # 基线必须真的传下去了 —— 上一个半年 = 2025-07-01 ~ 2025-12-31
    ranges = {r for _m, r in stub_cells}
    assert (datetime.date(2025, 7, 1), datetime.date(2025, 12, 31)) in ranges, ranges


def test_baseline_that_cannot_be_picked_is_skipped_with_a_trace(caplog):
    """挑不出不重叠的基线 ⇒ **明说跳过**并留痕，⛔ 不硬算。

    ⚠️ 阴性对照：证明「跳过」这条路还在 —— 上面那条只证明长窗口能归因了，
       两条一起才说明「该归因的归因、该跳过的跳过」。
    """
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(date_range=(datetime.date(2026, 8, 16),
                                     datetime.date(2026, 8, 1))))   # 起止颠倒
    assert out == BASE
    assert any("no baseline" in r.message for r in caplog.records)


def test_seven_day_window_still_attributes(stub_cells):
    """阳性对照: 7 天窗口(基线恰好不重叠)**仍然**归因 —— 上面那条不是「一刀切关掉」。"""
    week = (TODAY - datetime.timedelta(days=6), TODAY)
    out = _run(_Spec(date_range=week))
    assert out != BASE, "7 天窗口被误伤了 ⇒ 上面那条断言不区分好坏"


def test_the_helper_is_actually_called_and_its_result_is_kept():
    """🔴 上面七条**全都直接调** helper —— 接线被删掉它们照样绿。

    ⛔ 用 AST 数真正的 `Call` 节点，⛔ 不用字符串计数（docstring 里提到函数名
    的那几行会被文本 grep 数进去）。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(svc))
    targets = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        value = node.value
        if isinstance(value, ast.Await):
            value = value.value
        if not (isinstance(value, ast.Call) and isinstance(value.func, ast.Name)):
            continue
        if value.func.id != "_maybe_append_attribution":
            continue
        targets.extend(t.id for t in node.targets if isinstance(t, ast.Name))

    assert targets == ["answer_text"], (
        f"归因结果被赋给了 {targets or '(没有调用点)'} 而不是 answer_text —— 算了但丢了")
