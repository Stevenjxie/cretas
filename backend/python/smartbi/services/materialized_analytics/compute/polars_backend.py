"""PolarsBackend — loads upload's full row_data into a polars DataFrame once,
then serves all template queries from memory.

For 200K rows this is 2-5s load + <100ms per template. Templates execute
in lazy pipeline so polars optimizes across them when grouped.
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List

import polars as pl

from .base import ComputeBackend

logger = logging.getLogger(__name__)

# Meta-row labels that pollute aggregates (合计/总计/Total etc). Task W2.0.
#
# ⚠️ 2026-08-10: 这份是**唯一定义**。此前 4 个模板各抄了一份, 且抄漏了 ——
#     business_overview          少 总数/总额/Total 系列
#     member_deep / monthly_anomaly / period_comparison   只有 4 个词
#   后果: 认不出的汇总行会被当成一条明细算进去, **数偏大且没人会发现**。
#   由 tests/test_no_drifted_duplicate_constants.py 盯着, 再抄一份就红。
#   判据: **「哪些行要剔掉」这种判据只能有一处定义**, 抄一次就多一处会漂的地方。
_META_LABELS = {
    '合计', '总计', '小计', '汇总', '总额', '总数',
    'Total', 'TOTAL', 'total', 'Sum', 'SUM', 'sum',
    # ⬇ 收口时从 business_overview_summary 并进来的两个 —— 不能丢词
    "平均", "小结",
}


class PolarsBackend(ComputeBackend):
    def __init__(self, df: pl.DataFrame):
        self._df = df

    @classmethod
    def from_rows(cls, rows: List[Dict[str, Any]]) -> "PolarsBackend":
        """Build from a list of dicts (row_data JSONB deserialized)."""
        if not rows:
            return cls(pl.DataFrame())
        df = pl.from_dicts(rows, infer_schema_length=min(1000, len(rows)))
        return cls(df)

    def row_count(self) -> int:
        return self._df.height

    def columns(self) -> List[str]:
        return self._df.columns

    def dtype(self, column: str) -> str:
        if column not in self._df.schema:
            raise KeyError(f"column not in DataFrame: {column!r}")
        dt = self._df.schema[column]
        name = str(dt).lower()
        if "int" in name:
            return "int"
        if "float" in name or "decimal" in name:
            return "float"
        if "date" in name or "time" in name:
            return "datetime"
        if "bool" in name:
            return "bool"
        return "string"

    def _as_numeric(self, col: str) -> pl.Expr:
        """Cast column to Float64; nulls for non-numeric strings."""
        return pl.col(col).cast(pl.Float64, strict=False)

    @staticmethod
    def _agg_expr(agg: str) -> pl.Expr:
        """Build the aggregation expr on the prepared '_m' column.

        May 30 2026: support 'avg' for intensive measures (星级分/评分/率) so a
        star-rating series shows the mean (~4.8) instead of a meaningless
        accumulating SUM. Default 'sum' keeps every existing call byte-identical.
        """
        if agg == "avg":
            return pl.col("_m").mean().alias("total")
        if agg == "sum":
            return pl.col("_m").sum().alias("total")
        raise ValueError(f"unsupported agg: {agg!r} (expected 'sum' or 'avg')")

    def group_sum(self, group_col: str, measure: str, agg: str = "sum") -> List[Dict[str, Any]]:
        return (
            self._df
            .with_columns(self._as_numeric(measure).alias("_m"))
            .filter(pl.col(group_col).is_not_null() & pl.col("_m").is_not_null())
            .filter(~pl.col(group_col).cast(pl.Utf8).is_in(list(_META_LABELS)))  # W2.0: exclude meta-rows
            .group_by(group_col)
            .agg(self._agg_expr(agg))
            .sort("total", descending=True)
            .rename({group_col: "label"})
            .select(["label", "total"])
            .to_dicts()
        )

    def top_n(self, group_col: str, measure: str, n: int, agg: str = "sum") -> List[Dict[str, Any]]:
        return self.group_sum(group_col, measure, agg=agg)[:n]

    def time_series(self, time_col: str, measure: str, freq: str, agg: str = "sum") -> List[Dict[str, Any]]:
        if freq not in ("D", "W", "M"):
            raise ValueError(f"unsupported freq: {freq}")
        polars_freq = {"D": "1d", "W": "1w", "M": "1mo"}[freq]
        # C1: use str.to_datetime for string columns; cast for already-typed Datetime columns
        dt_col = self._df.schema.get(time_col)
        if dt_col is not None and "str" in str(dt_col).lower():
            t_expr = pl.col(time_col).str.to_datetime(strict=False).alias("_t")
        else:
            t_expr = pl.col(time_col).cast(pl.Datetime, strict=False).alias("_t")
        return (
            self._df
            .with_columns([t_expr, self._as_numeric(measure).alias("_m")])
            .filter(pl.col("_t").is_not_null() & pl.col("_m").is_not_null())
            .sort("_t")  # C2: group_by_dynamic requires pre-sorted input
            .group_by_dynamic("_t", every=polars_freq)
            .agg(self._agg_expr(agg))
            .with_columns(pl.col("_t").dt.strftime("%Y-%m-%d").alias("period"))
            .select(["period", "total"])
            .to_dicts()
        )

    def percentile(self, measure: str, percentiles: List[float]) -> Dict[float, float]:
        series = (
            self._df
            .select(self._as_numeric(measure).alias("_m"))
            .filter(pl.col("_m").is_not_null())
            .get_column("_m")
        )
        return {p: float(series.quantile(p) or 0.0) for p in percentiles}

    def mean_std(self, measure: str) -> Dict[str, float]:
        series = (
            self._df
            .select(self._as_numeric(measure).alias("_m"))
            .filter(pl.col("_m").is_not_null())
            .get_column("_m")
        )
        if series.len() == 0:
            return {"mean": 0.0, "std": 0.0, "min": 0.0, "max": 0.0}
        return {
            "mean": float(series.mean() or 0.0),
            "std": float(series.std() or 0.0),
            "min": float(series.min() or 0.0),
            "max": float(series.max() or 0.0),
        }

    def outliers(self, measure: str, sigma: float = 2.0) -> List[Dict[str, Any]]:
        stats = self.mean_std(measure)
        if stats["std"] == 0:
            return []
        lo = stats["mean"] - sigma * stats["std"]
        hi = stats["mean"] + sigma * stats["std"]
        return (
            self._df
            .with_columns(self._as_numeric(measure).alias("_m"))
            .filter((pl.col("_m") < lo) | (pl.col("_m") > hi))
            .drop("_m")  # I1: drop internal column before returning to caller
            .head(50)
            .to_dicts()
        )
