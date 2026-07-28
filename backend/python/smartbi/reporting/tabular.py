"""ECharts ``charts`` → 二维表。

spec §2.1 已经定过结论: **表格不需要后端新数据通道** —— ``charts`` 里的
``xAxis.data`` + ``series[].data`` 就够渲染成表。这个模块就是那句话的实现，
它是纯函数、不碰 DB、不碰 LLM。

支持的三种 chart 形状 (餐饮 resolver 实际产出的全部形状):

1. **类目型** ``{"xAxis": {"data": [...]}, "series": [{"name": .., "data": [..]}]}``
   → 列 = ``[类目, series1, series2, ...]``，行 = 每个类目一行。多 series 并列。
2. **name/value 型** (饼图) ``{"series": [{"data": [{"name":..,"value":..}]}]}``
   → 列 = ``[名称, series 名]``。
3. **无 xAxis 的纯数值 series** → 用 ``#1 #2`` 作为序号类目，不编造标签。

不认识的形状**返回空**而不是猜 —— 少一张表比多一张错表安全。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Tuple

from .model import TableBlock

_CATEGORY_HEADER = "类目"
_NAME_HEADER = "名称"
_VALUE_HEADER = "数值"


def _series_label(series: Dict[str, Any], index: int) -> str:
    name = series.get("name")
    if isinstance(name, str) and name.strip():
        return name.strip()
    return f"{_VALUE_HEADER}{index + 1}"


def _is_name_value_points(data: Any) -> bool:
    return (
        isinstance(data, Sequence)
        and not isinstance(data, (str, bytes))
        and len(data) > 0
        and all(isinstance(p, dict) and "name" in p for p in data)
    )


def _cell(value: Any) -> Any:
    """标量透传，其它 (dict/list) 转字符串。

    ⚠️ ``None`` 原样保留 —— 上游给的就是「这个点没有值」，不能替换成 0。
    xlsx 里写 ``None`` 就是空单元格，pdf 里渲染成 ``-``；两者都表示「无」，
    而不是「零」。
    """
    if value is None or isinstance(value, (int, float, str, bool)):
        return value
    if isinstance(value, dict) and "value" in value:
        return _cell(value.get("value"))
    return str(value)


def _from_name_value(chart: Dict[str, Any], series: List[Dict[str, Any]]) -> Optional[TableBlock]:
    points = series[0].get("data")
    if not _is_name_value_points(points):
        return None
    label = _series_label(series[0], 0)
    rows = tuple(
        (str(p.get("name")), _cell(p.get("value")))
        for p in points
    )
    if not rows:
        return None
    return TableBlock(
        title=str(chart.get("title") or "").strip() or label,
        columns=(_NAME_HEADER, label),
        rows=rows,
    )


def _from_categories(
    chart: Dict[str, Any],
    series: List[Dict[str, Any]],
) -> Optional[TableBlock]:
    x_axis = chart.get("xAxis")
    categories: List[Any] = []
    if isinstance(x_axis, dict):
        raw = x_axis.get("data")
        if isinstance(raw, Sequence) and not isinstance(raw, (str, bytes)):
            categories = [str(c) for c in raw]

    numeric_series = [
        s for s in series
        if isinstance(s.get("data"), Sequence)
        and not isinstance(s.get("data"), (str, bytes))
    ]
    if not numeric_series:
        return None

    # 行数 = 类目数与最长 series 的较大者。刻意**不截短**:
    #   * series 比类目短 → 尾部补 ``None`` (那几期确实没数)，不补 0；
    #   * series 比类目长 → 多出来的点用 ``#n`` 占位序号，数据不丢。
    # ``#n`` 是位置序号不是业务标签，不构成"编数据"。
    width = max([len(s["data"]) for s in numeric_series] + [len(categories)])
    if width == 0:
        return None
    if len(categories) < width:
        categories = categories + [
            f"#{i + 1}" for i in range(len(categories), width)
        ]

    columns: Tuple[str, ...] = (
        _CATEGORY_HEADER,
        *(_series_label(s, i) for i, s in enumerate(numeric_series)),
    )
    rows: List[Tuple[Any, ...]] = []
    for i in range(width):
        row: List[Any] = [categories[i]]
        for s in numeric_series:
            data = s["data"]
            row.append(_cell(data[i]) if i < len(data) else None)
        rows.append(tuple(row))
    if not rows:
        return None
    return TableBlock(
        title=str(chart.get("title") or "").strip() or _CATEGORY_HEADER,
        columns=columns,
        rows=tuple(rows),
    )


def chart_to_table(chart: Any) -> Optional[TableBlock]:
    """单个 chart → 一张表；形状不认识返回 ``None`` (不猜)。"""
    if not isinstance(chart, dict):
        return None
    raw_series = chart.get("series")
    if not isinstance(raw_series, Sequence) or isinstance(raw_series, (str, bytes)):
        return None
    series = [s for s in raw_series if isinstance(s, dict)]
    if not series:
        return None
    if _is_name_value_points(series[0].get("data")):
        return _from_name_value(chart, series)
    return _from_categories(chart, series)


def charts_to_tables(charts: Any) -> Tuple[TableBlock, ...]:
    """一节里的全部 chart → 表列表 (顺序保持不变)。"""
    if not isinstance(charts, Sequence) or isinstance(charts, (str, bytes)):
        return ()
    out: List[TableBlock] = []
    for chart in charts:
        table = chart_to_table(chart)
        if table is not None:
            out.append(table)
    return tuple(out)
