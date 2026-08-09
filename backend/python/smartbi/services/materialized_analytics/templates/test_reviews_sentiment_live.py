"""Live smoke — load real qhj 大众点评 review Excel and run
reviews_sentiment_summary.compute(). Verifies end-to-end:
  - 具体门店 store column is detected (Apr 24 2026 fix)
  - 菜品标签 top-N extraction works
  - KPIs populated correctly
  - Chinese KPI keys (not English)

Run via:
    cd backend/python
    python -m pytest smartbi/services/materialized_analytics/templates/test_reviews_sentiment_live.py -v
"""
from __future__ import annotations

import os
import pytest
import polars as pl

from smartbi.services.materialized_analytics.templates.reviews_sentiment_summary import (
    ReviewsSentimentSummary,
)
from smartbi.services.materialized_analytics.schema import DataSchema, Field, FieldRole, Domain


_HERE = os.path.dirname(os.path.abspath(__file__))
# backend/python/smartbi/services/materialized_analytics/templates → project root
_PROJECT_ROOT = os.path.abspath(os.path.join(_HERE, "..", "..", "..", "..", "..", ".."))
QHJ_REVIEW_Q3 = os.path.join(
    _PROJECT_ROOT,
    "smartbi维度分析", "大众点评", "真实餐饮连锁数据", "青花椒",
    "评价下载2025.07.01-2025.09.30_1328220_1773721054386.xlsx",
)


class _FakeBackend:
    """Minimal ComputeBackend stand-in — template only reads ._df."""

    def __init__(self, df):
        self._df = df


@pytest.fixture(scope="module")
def qhj_reviews_df():
    path = os.path.abspath(QHJ_REVIEW_Q3)
    if not os.path.exists(path):
        pytest.skip(f"qhj Q3 review xlsx not found at {path}")
    # Use pandas + openpyxl (already on this env) and convert to polars —
    # avoids the optional fastexcel dep that pl.read_excel requires.
    #
    # ⚠️ 2026-08-10: `pl.from_pandas` 需要 pyarrow, 而**生产环境没有装它**
    #    (实测 prod venv: polars 1.43.0 / pandas 1.5.3 / pyarrow 缺席), 仓里的
    #    requirements 也只声明了 polars。这不是缺陷 —— 全仓 grep `from_pandas` /
    #    `to_polars`, 非测试代码里**一次都没出现**: 生产只在原生 polars 上工作,
    #    pandas→polars 这一步只存在于本用例读 Excel 夹具的路径上。
    #    所以正解是 importorskip(测试专属依赖缺席 → 跳过), 不是给生产装 pyarrow。
    #    判据(台账原话): 「先确认生产环境是否也依赖它 —— 若生产不装, 那这条代码
    #    路径在线上本来就是坏的, 那是另一个问题」。查完: 生产根本不走这条路。
    pytest.importorskip(
        "pyarrow", reason="pl.from_pandas 需要 pyarrow; 仅本用例读 Excel 夹具时用到")
    import pandas as pd
    pdf = pd.read_excel(path, engine="openpyxl")
    return pl.from_pandas(pdf)


def _schema_for(df):
    # Best-effort role — template only needs name presence, not strict role
    fields = []
    for c in df.columns:
        dtype_str = str(df[c].dtype).lower()
        if "int" in dtype_str:
            role, dt = FieldRole.MEASURE, "int"
        elif "float" in dtype_str:
            role, dt = FieldRole.MEASURE, "float"
        elif "date" in dtype_str or "time" in dtype_str or c in ("评价时间", "最新回复时间", "投诉时间"):
            role, dt = FieldRole.TIME, "datetime"
        elif "bool" in dtype_str:
            role, dt = FieldRole.DIMENSION, "bool"
        else:
            role, dt = FieldRole.DIMENSION, "string"
        fields.append(Field(name=c, role=role, dtype=dt))
    return DataSchema(
        upload_id=0, factory_id="F001", domain=Domain.RESTAURANT,
        fields=tuple(fields), row_count=df.height,
    )


def test_applies_returns_true(qhj_reviews_df):
    tpl = ReviewsSentimentSummary()
    schema = _schema_for(qhj_reviews_df)
    assert tpl.applies(schema), "template should apply — 星级 col present"


def test_compute_returns_populated_result(qhj_reviews_df):
    tpl = ReviewsSentimentSummary()
    schema = _schema_for(qhj_reviews_df)
    backend = _FakeBackend(qhj_reviews_df)
    result = tpl.compute(backend, schema)

    assert result.code == "reviews_sentiment_summary"
    assert result.data["total_reviews"] > 1000  # Q3 has ~12.9k
    assert 1.0 <= result.data["avg_star"] <= 5.0
    # Star distribution sums to total_reviews (ignoring nulls)
    dist_sum = sum(s["count"] for s in result.data["star_distribution"])
    assert dist_sum > 0 and dist_sum <= result.data["total_reviews"]


def test_store_column_detected_via_具体门店(qhj_reviews_df):
    """Apr 24 2026 fix — user's xlsx uses '具体门店' not '评价门店'."""
    tpl = ReviewsSentimentSummary()
    backend = _FakeBackend(qhj_reviews_df)
    result = tpl.compute(backend, _schema_for(qhj_reviews_df))
    # top_stores should populate once 具体门店 is recognized
    assert len(result.data["top_stores"]) > 0, "top_stores empty — store col not detected"
    top = result.data["top_stores"][0]
    assert "store" in top and top["store"], "store name missing in top_stores entry"
    # qhj store names contain 青花椒 or X顺娥
    assert any("青花椒" in s["store"] or "顺娥" in s["store"] for s in result.data["top_stores"][:3])


def test_top_dish_tags_extracted(qhj_reviews_df):
    """菜品标签 col contains comma-separated dishes; explode + count → top N."""
    tpl = ReviewsSentimentSummary()
    backend = _FakeBackend(qhj_reviews_df)
    result = tpl.compute(backend, _schema_for(qhj_reviews_df))
    top_dishes = result.data.get("top_dish_tags", [])
    assert len(top_dishes) > 0, "top_dish_tags empty — 菜品标签 not parsed"
    # Top dish should have reasonable mention count
    assert top_dishes[0]["mentions"] >= 10


def test_kpi_keys_are_chinese(qhj_reviews_df):
    """User-visible KPI keys should be Chinese (not English)."""
    tpl = ReviewsSentimentSummary()
    backend = _FakeBackend(qhj_reviews_df)
    result = tpl.compute(backend, _schema_for(qhj_reviews_df))
    assert "评价总数" in result.kpis
    assert "平均星级" in result.kpis
    assert "投诉率" in result.kpis
    # English keys from old version should NOT be present
    assert "total_reviews" not in result.kpis
    assert "avg_star" not in result.kpis


def test_insight_mentions_top_dishes(qhj_reviews_df):
    tpl = ReviewsSentimentSummary()
    backend = _FakeBackend(qhj_reviews_df)
    result = tpl.compute(backend, _schema_for(qhj_reviews_df))
    # insight should mention at least one real dish name from the top list
    top1 = result.data["top_dish_tags"][0]["dish"]
    assert top1 in result.insight_text or "评价中最常提及的菜品" in result.insight_text
