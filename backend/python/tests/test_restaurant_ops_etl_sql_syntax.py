"""Regression tests for #539 — SQL syntax errors in restaurant_ops_etl Gold templates.

Background:
  `_AGG_PRODUCT_COST_SQL` had `# noqa: E501` embedded inside the Python triple-quoted
  string. PostgreSQL received the literal text `# noqa: E501` as part of the SQL and
  errored with `syntax error at or near ":"` (the colon before `E501`). All
  `R_*_REAL` restaurant tenants failed Gold materialization in prod (single
  transaction in `materialize_gold_daily_ops` rolls back ALL 7 aggregations when
  the 7th — product_cost — fails).

Approach:
  - test_no_python_lint_markers_in_sql_constants: exact-bug regression — assert no
    `# noqa: ...` / `# type: ...` / `# TODO` etc. embedded in any module-level
    `_AGG_*_SQL` constant.
  - test_no_lone_colons_in_agg_sql_constants: broader class — PostgreSQL `:` is only
    valid as part of `::` (cast) or `:=` (assign). Lone `:` in raw SQL = syntax
    error. Catches this bug AND any future variant (e.g. someone pastes a docstring
    snippet with a colon).
  - test_no_pound_chars_in_agg_sql_constants: another angle — `#` is not a SQL
    comment character. Any `#` in the SQL is suspicious (likely Python comment leak).

  Each test imports the actual module constants — so the test is locked to the
  shipped code path. CI failure = bug shipped.
"""
from __future__ import annotations

import re

import smartbi.gold.restaurant.restaurant_ops_etl as etl


def _collect_sql_constants() -> dict[str, str]:
    """Return all module-level string constants whose name ends in _SQL."""
    return {
        name: value
        for name, value in vars(etl).items()
        if name.endswith("_SQL") and isinstance(value, str)
    }


def test_sql_constants_discovered():
    """Sanity check — bail out loudly if the constants were renamed."""
    sql_constants = _collect_sql_constants()
    assert sql_constants, "no _AGG_*_SQL constants found in restaurant_ops_etl"
    # The 7 known templates as of #539, plus _AGG_WASTAGE_COST_SQL (the
    # per-ingredient money axis behind "损耗金额排名").
    expected = {
        "_AGG_PRODUCT_COST_SQL",
        "_AGG_REQUISITION_QTY_SQL",
        "_AGG_REQUISITION_COST_SQL",
        "_AGG_WASTAGE_QTY_SQL",
        "_AGG_WASTAGE_COST_SQL",
        "_AGG_WASTAGE_COST_BY_TYPE_SQL",
        "_AGG_STOCK_SHORTAGE_SQL",
        "_AGG_DAILY_TOTALS_SQL",
    }
    missing = expected - set(sql_constants)
    assert not missing, f"expected SQL constants are missing: {missing}"


def test_no_python_lint_markers_in_sql_constants():
    """Regression for #539 — Python lint annotation leaked into SQL string.

    Original bug: line 542 had `  # noqa: E501` at end-of-source-line but the line
    was inside a triple-quoted string, so the marker became SQL content. PG choked
    on the `:` in `: E501`.
    """
    lint_marker = re.compile(r"#\s*(noqa|type\s*:|pyright|TODO|FIXME|XXX|HACK)\b")

    failures: dict[str, str] = {}
    for name, sql in _collect_sql_constants().items():
        m = lint_marker.search(sql)
        if m:
            pos = m.start()
            ctx = sql[max(0, pos - 40):pos + 40].replace("\n", "\\n")
            failures[name] = f"matched {m.group()!r} at pos {pos}: ...{ctx}..."
    assert not failures, (
        f"Python lint markers leaked into SQL constants — see issue #539:\n{failures}"
    )


def test_no_lone_colons_in_agg_sql_constants():
    """PostgreSQL ':' is valid only as part of '::' (cast) or ':=' (assign).

    A lone ':' triggers `PostgresSyntaxError: syntax error at or near ":"`.
    This test is broader than the noqa-specific check — catches any future
    pattern that drops a stray ':' into a SQL template.
    """
    lone_colon = re.compile(r"(?<!:):(?!:|=)")

    failures: dict[str, str] = {}
    for name, sql in _collect_sql_constants().items():
        matches = list(lone_colon.finditer(sql))
        if matches:
            pos = matches[0].start()
            ctx = sql[max(0, pos - 40):pos + 40].replace("\n", "\\n")
            failures[name] = f"{len(matches)} lone ':' (first at pos {pos}): ...{ctx}..."
    assert not failures, (
        f"Lone ':' chars found in SQL constants (would trigger PG syntax error):\n{failures}"
    )


def test_no_pound_chars_in_agg_sql_constants():
    """'#' is not a SQL comment marker. Any '#' in raw SQL is a smell.

    Concrete cases this catches:
      - Python `# comment` leaked into a triple-string (the #539 bug class)
      - MySQL `#` line comment copy-pasted into PG SQL (PG doesn't recognize it)

    Whitelist exception: hex literals like `\\x23` or `E'\\x23'` if any template
    legitimately encodes the hash byte (none currently do).
    """
    failures: dict[str, str] = {}
    for name, sql in _collect_sql_constants().items():
        if "#" in sql:
            idx = sql.index("#")
            ctx = sql[max(0, idx - 40):idx + 40].replace("\n", "\\n")
            failures[name] = f"'#' at pos {idx}: ...{ctx}..."
    assert not failures, (
        f"'#' chars in SQL constants — PG doesn't treat '#' as a comment:\n{failures}"
    )


def test_stocktaking_cost_is_summed_through_abs():
    """盘点金额必须 SUM(ABS(...)) —— 删掉 ABS 参考租户就会答出负的盘亏。

    `difference_cost` 的符号在写入侧就不统一 (2026-08-01 实测):
      - 源库 cretas_prod_db.stocktaking_records: DEMO_REST / F002 / RES_3101_009
        的盘亏行是**正**金额共 14 行, R_XMX_CHAIN 是**负**的 1 行;
      - 模拟器产出、只存在于 smartbi 侧的 MOCK_REST: 650 行**全为负**。
    Silver 那句 `difference_cost -- |difference_qty| × unit_price` 的注释是错的,
    ETL 原样搬了带符号的值。Java 侧也是靠 ABS 兜 (StocktakingRecordRepository 第 64 行,
    而同一个 repository 第 96 行没兜 —— 同一件事两套写法)。

    直接 SUM 的后果是 MOCK_REST 答「盘亏 -6999.04」而 DEMO_REST 看着完全正常 ——
    偏偏 MOCK_REST 正是每日能力审计打 19/19 的参考租户。
    """
    constants = _collect_sql_constants()
    cost_sqls = {
        name: sql for name, sql in constants.items()
        if "difference_cost" in sql
    }
    assert cost_sqls, "没有任何 SQL 聚合 difference_cost —— 金额口径掉了?"

    for name, sql in cost_sqls.items():
        for line in sql.splitlines():
            if "difference_cost" not in line or line.strip().startswith("--"):
                continue
            assert "ABS(" in line, (
                f"{name} 有一行聚合 difference_cost 却没走 ABS():\n  {line.strip()}\n"
                "符号在写入侧就不统一, 不取绝对值会让参考租户 MOCK_REST 答出负的盘亏。"
            )
