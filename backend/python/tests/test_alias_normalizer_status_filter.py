"""Regression for 卡3 fable 终审 B1 (阻断): `_load_confirmed_aliases` 不过滤 status
会让机器 pending 候选立即污染线上 POS 归一结果.

调用链 (修复前): dish_alias_matcher.propose_dish_alias_candidates(dry_run=False) 写
一条 status='pending' 行 → RestaurantMenuNormalizer._load_confirmed_aliases()
(继承自 AliasNormalizer, 未过滤 status) 把它读进缓存 → apply() 在每次 POS 分析时
用它改写 商品名称 列 —— 机器未审建议直接影响线上答案, 违反 migration
V20260728_02 头注 + dish_alias_resolver 承诺的 "pending 绝不影响线上答案"
(fail-closed) 原则。

本文件断言修复后:
  1. pending 行不进 _load_confirmed_aliases() 缓存, confirmed / 历史遗留
     (status IS NULL) 行才进。
  2. apply() 端到端不会用 pending 候选改写 POS DataFrame。
  3. 没有 status 列的别名表 (HAS_STATUS_COLUMN 默认 False, 工厂侧 factory_sku_alias
     Phase 2 才建) 不会被误加 status 过滤 —— 防止这次修复打挂工厂侧。
"""
from __future__ import annotations

import pandas as pd
import pytest

from smartbi.services.restaurant.menu_normalizer import RestaurantMenuNormalizer
from smartbi.shared.alias_normalizer import AliasNormalizer

FACTORY = "RES_3101_TEST"


# ── Fake SQLAlchemy Session ─────────────────────────────────────────────────
# 模拟真实 DB 行为: 只有当执行的 SQL 文本里带 status 过滤子句时才按 status 过滤
# fake 行 —— 这样断言能证明"数据确实变了", 而不只是"SQL 字符串长得像过滤了"。

class _FakeRow:
    def __init__(self, original_name: str, canonical_name: str):
        self.original_name = original_name
        self.canonical_name = canonical_name


class _FakeResult:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows


class _FakeSession:
    def __init__(self, rows):
        # rows: [{"factory_id", "original_name", "canonical_name", "status"}, ...]
        self._rows = rows
        self.executed_sql: list[str] = []

    def execute(self, clause, params):
        sql = str(clause)
        self.executed_sql.append(sql)
        fid = params["fid"]
        candidates = [r for r in self._rows if r["factory_id"] == fid]
        if "status = 'confirmed'" in sql:
            candidates = [
                r for r in candidates
                if r.get("status") is None or r["status"] == "confirmed"
            ]
        return _FakeResult(
            [_FakeRow(r["original_name"], r["canonical_name"]) for r in candidates]
        )


def _row(original_name, canonical_name, status):
    return {
        "factory_id": FACTORY,
        "original_name": original_name,
        "canonical_name": canonical_name,
        "status": status,
    }


# ── 1. _load_confirmed_aliases: pending 排除, confirmed/NULL 保留 ───────────

def test_pending_row_excluded_confirmed_row_included():
    session = _FakeSession([
        _row("招牌青花椒鱼(两吃)", "招牌青花椒鱼", "pending"),   # 机器初匹配候选, 未审
        _row("宫保鸡丁(小份)", "宫保鸡丁", "confirmed"),          # 已人审
        _row("米饭#", "米饭", None),                              # 历史遗留行 (迁移前写入)
        _row("凉拌黄瓜(大份)", "凉拌黄瓜", "rejected"),           # 已人工拒绝
    ])
    normalizer = RestaurantMenuNormalizer(factory_id=FACTORY, db_session=session)

    aliases = normalizer._load_confirmed_aliases()

    assert "招牌青花椒鱼(两吃)" not in aliases, "pending 候选绝不能进缓存 (B1 核心断言)"
    assert "凉拌黄瓜(大份)" not in aliases, "rejected 候选也不该进缓存"
    assert aliases["宫保鸡丁(小份)"] == "宫保鸡丁"
    assert aliases["米饭#"] == "米饭", "status IS NULL 的历史遗留行仍应兼容 (零成本保险分支)"
    assert len(aliases) == 2

    # 实际执行的 SQL 带 status 过滤 (RestaurantMenuNormalizer.HAS_STATUS_COLUMN=True)
    assert any("status = 'confirmed'" in sql for sql in session.executed_sql)
    assert any("status IS NULL" in sql for sql in session.executed_sql)


def test_only_pending_rows_yields_empty_cache():
    """factory 下只有 pending 候选、无任何 confirmed 行 → 缓存应为空 (不是报错也不是漏判)."""
    session = _FakeSession([
        _row("招牌青花椒鱼(两吃)", "招牌青花椒鱼", "pending"),
        _row("#招牌青花椒鱼(单人份)#", "招牌青花椒鱼", "pending"),
    ])
    normalizer = RestaurantMenuNormalizer(factory_id=FACTORY, db_session=session)
    assert normalizer._load_confirmed_aliases() == {}


# ── 2. apply(): 端到端 — pending 候选不会改写 POS DataFrame ──────────────────

def test_apply_does_not_rewrite_pos_data_using_pending_alias():
    session = _FakeSession([
        _row("招牌青花椒鱼(两吃)", "招牌青花椒鱼", "pending"),
    ])
    normalizer = RestaurantMenuNormalizer(factory_id=FACTORY, db_session=session)
    df = pd.DataFrame({"商品名称": ["招牌青花椒鱼(两吃)", "米饭"]})

    result = normalizer.apply(df, name_column="商品名称")

    # pending 候选未生效 —— 原始列保持不变 (这正是 resolver/migration 承诺的
    # "pending 绝不影响线上答案" 在旧读路径上的对应断言)。
    assert list(result["商品名称"]) == ["招牌青花椒鱼(两吃)", "米饭"]


def test_apply_still_rewrites_using_confirmed_alias():
    """回归防呆: 修复没有连 confirmed 别名的正常生效路径也一起关掉."""
    session = _FakeSession([
        _row("招牌青花椒鱼(两吃)", "招牌青花椒鱼", "confirmed"),
    ])
    normalizer = RestaurantMenuNormalizer(factory_id=FACTORY, db_session=session)
    df = pd.DataFrame({"商品名称": ["招牌青花椒鱼(两吃)", "米饭"]})

    result = normalizer.apply(df, name_column="商品名称")

    assert list(result["商品名称"]) == ["招牌青花椒鱼", "米饭"]


# ── 3. 防呆: 没有 status 列的别名表不被误加过滤 (不打挂工厂侧) ────────────────

class _FakeFactorySkuNormalizer(AliasNormalizer):
    """模拟工厂侧未来的 factory_sku_alias 归一器 (Phase 2 才建, 无 status 列).

    HAS_STATUS_COLUMN 不覆写 → 沿用基类默认 False, 验证 _load_confirmed_aliases
    不会给它加 status 过滤子句 (否则真实 DB 上会直接 UndefinedColumn)。
    """

    def normalize_by_rules(self, name: str) -> str:
        return name.strip()

    def get_alias_table_name(self) -> str:
        return "factory_sku_alias"

    def get_proposal_type(self) -> str:
        return "merge_skus"


def test_factory_side_normalizer_without_status_column_is_not_filtered():
    session = _FakeSession([
        {"factory_id": FACTORY, "original_name": "SKU-A", "canonical_name": "SKU标准名",
         "status": None},
    ])
    normalizer = _FakeFactorySkuNormalizer(
        factory_id=FACTORY, domain="factory", db_session=session
    )

    aliases = normalizer._load_confirmed_aliases()

    assert aliases == {"SKU-A": "SKU标准名"}
    # 关键: 执行的 SQL 里不能出现 status 过滤 —— 没有 status 列的表加了会直接报错。
    assert session.executed_sql
    assert all("status" not in sql for sql in session.executed_sql)


def test_base_class_default_has_status_column_is_false():
    """AliasNormalizer 基类默认 HAS_STATUS_COLUMN=False; 只有显式覆写的子类才过滤."""
    assert AliasNormalizer.HAS_STATUS_COLUMN is False
    assert RestaurantMenuNormalizer.HAS_STATUS_COLUMN is True
