"""P4a·卡3 别名 → 标准菜品 ID resolver (spec §2.4, Wave 1 行为兼容).

行为契约 (resolve_dish_reference, 卡3 验收要求"行为兼容"):
  - factory (+可选 store) 下有 status='confirmed' 的别名映射
      → 返回 {"kind": "canonical_id", "canonical_dish_id": ..., "canonical_name": ...,
              "store_scoped": bool}。
  - 否则 (无映射 / 只有 pending / 只有 rejected)
      → 返回 {"kind": "original_name", "name": original_name}, 与今天完全一致的
        行为 —— 调用方按 kind 分支, 沿用现有"按原文名查询"路径, 零回归。
        pending 候选**绝不**影响线上答案 (fail-closed, per migration V20260728_02
        头注 + MEMORY #364 人工闸门纪律)。

两段匹配 (店级优先, 回落租户级), 均只认 confirmed:
  1. store_id 给定时, 先查 (factory_id, store_id, original_name, status='confirmed')。
  2. 未命中 → 查租户级 (factory_id, original_name, store_id IS NULL, status='confirmed')。

⛔ Wave 1 边界 (与 migration 头注一致): 写入侧唯一性仍是 (factory_id, original_name),
所以同一 original_name 在同一 factory 内当前最多一行 —— 店级/租户级两条件不会同时
存在于同一 original_name。本 resolver 已按 Wave 2 的最终两段匹配形态实现只读查询,
Wave 2 打开店级写入 (需同步改 alias_normalizer 的 ON CONFLICT 子句) 后无需再改
resolver 本身。

调用方 (Wave 2, gold/restaurant_intent.py) 应先 SET app.factory_id 后调用本模块——
restaurant_dish_alias 无 FORCE RLS, 但仍显式按 factory_id 过滤 (defense in depth,
不依赖 RLS 兜底)。

TODO(Wave 2, 卡2 restaurant_intent.py): 把菜品名查询链路接到 resolve_dish_reference()
—— 优先用 canonical_dish_id 聚合跨店同菜销量/营收, kind='original_name' 时继续走
现有按原文名查询路径 (本卡不改 restaurant_intent.py 主文件, 接入点留给 Wave 2)。
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, Dict, Optional

if TYPE_CHECKING:
    import asyncpg


_STORE_SCOPED_SQL = """
    SELECT canonical_dish_id, canonical_name
      FROM restaurant_dish_alias
     WHERE factory_id = $1 AND store_id = $2 AND original_name = $3
       AND status = 'confirmed' AND canonical_dish_id IS NOT NULL
"""

_TENANT_SCOPED_SQL = """
    SELECT canonical_dish_id, canonical_name
      FROM restaurant_dish_alias
     WHERE factory_id = $1 AND store_id IS NULL AND original_name = $2
       AND status = 'confirmed' AND canonical_dish_id IS NOT NULL
"""


async def resolve_dish_alias(
    conn: "asyncpg.Connection",
    factory_id: str,
    original_name: str,
    store_id: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """底层查询: 命中 confirmed 别名→ID 映射返回 dict, 否则 None. 不做回落包装.

    两段匹配: store_id 给定时先查店级, 未命中回落租户级 (store_id IS NULL)。
    """
    if not factory_id or not original_name:
        return None

    if store_id:
        row = await conn.fetchrow(_STORE_SCOPED_SQL, factory_id, store_id, original_name)
        if row is not None:
            return {
                "canonical_dish_id": int(row["canonical_dish_id"]),
                "canonical_name": row["canonical_name"],
                "store_scoped": True,
            }

    row = await conn.fetchrow(_TENANT_SCOPED_SQL, factory_id, original_name)
    if row is not None:
        return {
            "canonical_dish_id": int(row["canonical_dish_id"]),
            "canonical_name": row["canonical_name"],
            "store_scoped": False,
        }
    return None


async def resolve_dish_reference(
    conn: "asyncpg.Connection",
    factory_id: str,
    original_name: str,
    store_id: Optional[str] = None,
) -> Dict[str, Any]:
    """行为兼容包装: 有 confirmed 映射走 ID, 否则回落原文名 (今天行为不变)。"""
    match = await resolve_dish_alias(conn, factory_id, original_name, store_id)
    if match is not None:
        return {
            "kind": "canonical_id",
            "canonical_dish_id": match["canonical_dish_id"],
            "canonical_name": match["canonical_name"],
            "store_scoped": match["store_scoped"],
        }
    return {"kind": "original_name", "name": original_name}
