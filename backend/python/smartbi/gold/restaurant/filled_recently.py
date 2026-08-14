"""「你补上去了，X% → Y%」—— 层 1 的全部价值：让「补了 → 数变了」被人看见。

## 🔴 为什么不新建一张「逐日覆盖率」表

第一反应是存时间序列（今天的覆盖率 vs 昨天的）。⛔ 不这么做，两个理由：

1. **默认不加**：新表要迁移、要回填、要有人维护，而它存的东西**可以算出来**。
2. 更要紧的：时间序列答的是「覆盖率变了」，而他想知道的是
   **「我补的那几道起作用了吗」**。这两句话在他补了 3 道、同时又有 5 道新菜
   上架的那天会给出不同的答案 —— 而后者才是我们承诺过的那件事。

⇒ 用**反事实**：`cretas_db.recipes.created_at` 告诉我们哪几道是他刚补的，
  「不算这几道的覆盖率」就是 X%，现在的覆盖率就是 Y%。
  两个数来自**同一批当期数据**，⛔ 不跨天比较，所以没有「昨天卖得不一样」
  这种干扰。

## 承诺必须兑现

T2 说「补这 3 道，40.2% → 47.7%」。这里说的 X% → Y% 必须落在同一条线上 ——
⛔ 不是「上升了」，是**到达我们说的那个数**。

判据在 `tests/test_filled_recently.py`：
`test_the_gain_matches_what_t2_promised`（同一份 `cost_gaps` 算出来的增量，
两边必须一致）。

## 阴性对照

**没补的时候这句话不出现。** 一句无条件冒出来的「你补上去了」是废话，
而且是**会撒谎的废话**（他没补，我们说他补了）。
`test_says_nothing_when_nothing_was_filled` 守这一条，配变异。
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta
from typing import Any, Dict, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

#: 「刚补的」算多久以内。⚠️ 与日结的周期对齐 —— 他昨天补的，今天打烊时该看见。
#: ⛔ 不做成配置：一个能调的窗口意味着「这句话什么时候出现」变成可调的，
#:    而它该由「他什么时候补的」决定。
RECENT_WINDOW_DAYS = 2

#: 运营库那张表。⚠️ **不是**分析库任何一层 —— 那三层都会被 ETL / 平台同步
#: 抹掉（2026-08-14~15 连着三次实测），录入唯一的稳态位置是运营库。
_RECENT_SQL = (
    "SELECT DISTINCT r.product_type_id AS pk, p.name AS name,"
    "       min(r.created_at) AS first_created"
    "  FROM recipes r"
    "  LEFT JOIN product_types p"
    "    ON p.factory_id = r.factory_id AND p.id = r.product_type_id"
    " WHERE r.factory_id = $1::varchar"
    "   AND r.is_active"
    "   AND r.deleted_at IS NULL"
    "   AND r.created_at >= $2::timestamptz"
    " GROUP BY r.product_type_id, p.name"
    " ORDER BY min(r.created_at) DESC"
)


async def recently_filled_dishes(
    cretas_conn, factory_id: str, *, now: Optional[datetime] = None,
) -> Tuple[Dict[str, Any], ...]:
    """他最近补的那几道菜（运营库口径）。

    ⚠️ 判据是 `recipes.created_at`，⛔ 不是 `updated_at` —— 后者会被
       任何一次改动刷新，于是「他三个月前录的菜」今天会被说成「刚补的」。
    """
    since = (now or datetime.now()).astimezone() - timedelta(days=RECENT_WINDOW_DAYS)
    try:
        rows = await cretas_conn.fetch(_RECENT_SQL, factory_id, since)
    except Exception:  # noqa: BLE001 —— 拿不到就当没补, 不让整屏挂掉
        logger.warning("[filled-recently] 运营库查询失败 factory=%s",
                       factory_id, exc_info=True)
        return ()
    return tuple({"pk": r["pk"], "name": r["name"] or r["pk"],
                  "first_created": r["first_created"]} for r in rows or ())


def coverage_without(
    facts: Sequence[Dict[str, Any]], excluded_names: Sequence[str],
) -> Optional[float]:
    """把这几道菜的卡**当作还没补**时的覆盖率。

    ⚠️ 分子分母都来自调用方传进来的**同一批 facts** —— ⛔ 不另取一次数。
       另取一次就是两个覆盖率，而它们的差会被当成「他补出来的增量」。
    """
    total = sum(float(f.get("revenue") or 0) for f in facts)
    if total <= 0:
        return None
    excluded = set(excluded_names)
    covered = sum(
        float(f.get("revenue") or 0) for f in facts
        if f.get("unit_cost") is not None and f.get("name") not in excluded
    )
    return covered / total


def render(
    *,
    filled: Sequence[Dict[str, Any]],
    coverage_now: Optional[float],
    coverage_before: Optional[float],
) -> str:
    """「你补上去了，X% → Y%」。**没补就返回空串。**

    🔴 阴性对照守的就是这个空串：一句无条件冒出来的「你补上去了」是废话，
       而且是**会撒谎的废话** —— 他没补，我们说他补了。
    """
    if not filled:
        return ""
    if coverage_now is None or coverage_before is None:
        return ""
    gain = (coverage_now - coverage_before) * 100
    # ⛔ 提升不到 0.1 个百分点就不说 —— 「从 42.2% 提到 42.2%」读起来像坏了。
    #    ⚠️ 这与 T2 开价那条用**同一个**门槛, 见 `fill_offers`。
    if gain < 0.1:
        return ""
    names = "、".join(str(f.get("name") or "") for f in filled[:3])
    more = f" 等 {len(filled)} 道" if len(filled) > 3 else ""
    return (f"你补上去了（{names}{more}）——"
            f"能算进毛利的营收从 {coverage_before * 100:.1f}% "
            f"到了 {coverage_now * 100:.1f}%。")


def entry_hint(dish_pk: str, dish_name: str) -> Dict[str, str]:
    """缺卡清单里每一道后面的**补录入口**。

    ⚠️ 指向的是**已经存在的**那个屏（`RecipeEditScreen`），它本来就收
       `productTypeId` / `dishName` 两个路由参数并有 `hasPresetDish` ——
       ⛔ 不新建屏、不新建菜单项。层 1 只是把这道菜的 id 递过去。

    ⛔ 不带单价：那个表单里**没有**单价字段（源码级已确认），
       单价是系统的（`dim_ingredient`），让他填会把「配方」和「采购价」
       混成一件事，而后者本来就有来源、且随批次变。
    """
    return {
        "screen": "RecipeEdit",
        "productTypeId": str(dish_pk or ""),
        "dishName": str(dish_name or ""),
    }
