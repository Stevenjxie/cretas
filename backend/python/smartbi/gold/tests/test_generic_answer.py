"""通用执行器接线层 —— 承重的是「不动现有 20 个 resolver」。

⛔ 这条路径是**并行**的：只在没有手写 resolver 时才走。
   一旦它开始接管已有的码，现有任何一条能答的问法都可能悄悄换了口径。
"""
import datetime
from dataclasses import dataclass, field
from typing import Sequence, Tuple

import pytest

from smartbi.gold.restaurant.generic_answer import render, spec_to_cell
from smartbi.gold.restaurant.generic_executor import CellResult


@dataclass
class _Spec:
    requested_metrics: Sequence[str] = field(default_factory=tuple)
    dimensions: Sequence[str] = field(default_factory=tuple)
    analysis_action: str = ""
    date_range: Tuple[datetime.date, datetime.date] = (
        datetime.date(2026, 8, 1), datetime.date(2026, 8, 9))
    window_label: str = "本月"


def test_the_target_case_translates_to_the_right_cell():
    """「客单价最高的店」—— 今天真实答错的那条, 必须翻译到正确的格子。"""
    spec = _Spec(requested_metrics=("orders",), dimensions=("store",),
                 analysis_action="rank")
    assert spec_to_cell(spec) == ("orders", "store", "rank")

    spec2 = _Spec(requested_metrics=("gross_margin",), dimensions=("dish",),
                  analysis_action="rank")
    assert spec_to_cell(spec2) == ("gross_margin", "product", "rank")


def test_non_query_specs_return_none_so_the_original_path_continues():
    """🔴 承重: 翻译不出来返回 None 是**正常出口**, 不是失败。

    预测/建议/归因都会走到这里, 它们必须继续走原路径 ——
    把 None 当成「答不出来」会让这条并行路径吃掉本该由别人答的问题。
    """
    assert spec_to_cell(_Spec()) is None                                   # 没指标
    assert spec_to_cell(_Spec(requested_metrics=("staffing",))) is None    # 未登记的指标
    assert spec_to_cell(_Spec(requested_metrics=("net_profit",))) is None  # 数据缺口


def test_rank_without_a_dimension_falls_back_to_summary_not_refusal():
    """用户问「哪个最高」却没说按什么分 —— 给个总数比什么都不给强。"""
    spec = _Spec(requested_metrics=("revenue",), analysis_action="rank")
    assert spec_to_cell(spec) == ("revenue", "all", "summary")


def test_missing_columns_are_told_truthfully_never_zero():
    """🔴 承重: 缺列的措辞必须说「没接入」, ⛔ 不许出现一个 0。

    「你的平台抽佣是 ¥0」比「这项数据你还没接入」危险得多。
    """
    r = CellResult("revenue", "营收", "store", "rank", "money", [],
                   ("fact_pos_transaction.net_amount",), "")
    text = render(r, "本月")
    assert "还没有接入" in text
    assert "¥0" not in text and "0.00" not in text
    assert "没有用其他数据替代" in text


def test_empty_result_is_distinguished_from_missing_columns():
    """「查出来是空的」和「缺数据」是两件事, 措辞不能混。"""
    r = CellResult("revenue", "营收", "store", "rank", "money", [], (), "")
    text = render(r, "本月")
    assert "没有可用的" in text
    assert "还没有接入" not in text, "把「空结果」说成了「没接入」"


def test_rank_narration_marks_the_top_row():
    r = CellResult("revenue", "营收", "store", "rank", "money",
                   [{"dim_label": "A店", "revenue": 100},
                    {"dim_label": "B店", "revenue": 90}], (), "")
    text = render(r, "本月")
    assert "**A店**" in text, "排行第一没有加重"
    assert "¥100.00" in text and "¥90.00" in text


def test_narration_is_template_never_a_model_call():
    """⛔ 叙述层不许调模型 —— 那会把「数字不经模型」从后门破掉。"""
    import io
    import pathlib

    src = io.open(pathlib.Path(__file__).resolve().parents[1]
                  / "restaurant" / "generic_answer.py", encoding="utf-8").read()
    for banned in ("call_chain", "SLOT.", "llm_router"):
        assert banned not in src, f"叙述层引入了模型调用: {banned}"


def test_dispatch_only_falls_through_when_no_handwritten_resolver():
    """🔴 承重: 通用路径挂在 `resolver is None` 之后 ——
    现有 20 个格子一条都不该被它接管。"""
    import io
    import pathlib

    src = io.open(pathlib.Path(__file__).resolve().parents[1]
                  / "restaurant" / "restaurant_ops_router.py", encoding="utf-8").read()
    idx_guard = src.find("resolver = _RESOLVERS.get(code)")
    idx_generic = src.find("try_generic_answer")
    assert idx_guard != -1 and idx_generic != -1
    assert idx_guard < idx_generic, "通用路径跑到了手写 resolver 之前 —— 会接管已有格子"
    between = src[idx_guard:idx_generic]
    assert "if resolver is None:" in between, (
        "通用路径没有被 `resolver is None` 守住")
