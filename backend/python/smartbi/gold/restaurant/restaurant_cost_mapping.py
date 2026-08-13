"""Tenant-scoped POS dish name to recipe-cost key resolution.

The Cretas operational database remains the primary source of product names.
SmartBI also keeps a small read model because historical/demo Gold cost rows can
outlive the operational seed rows that originally produced them.  Callers merge
this fallback only for names that the primary product_types/alias lookup did not
resolve.  Ambiguous names deliberately remain unresolved.

═══════════════════════════════════════════════════════════════════════════
🔴 本模块是「菜名 → 成本键」的**唯一**解析处 (owner 2026-08-13 裁定条件 2)
═══════════════════════════════════════════════════════════════════════════

为什么必须只有一处: 2026-08-13 实测, 日结走 SmartBI 桥接表、问答走 cretas
`product_types` —— 青花椒有 9 道菜的映射**只存在于运营库**, 桥接表
`exact=0 / ci=0`。于是同一天同一家店, 日结毛利 103,370.22 而问答 124,071.85,
**差 20,701.63**。两条路够得着的成本映射不一样, 结构上就不可能给出同一个数。

⛔ 谁都不许再抄一份「大致等价」的解析:
   少一级 fallback / 多一次 lower() —— 差异会表现成「日结算了这道菜, 问答没算」,
   而那个差额没有任何东西会报错。
"""
from __future__ import annotations

import logging
from collections import defaultdict
from typing import Dict, Iterable, List, Mapping, Optional, Tuple

logger = logging.getLogger(__name__)


class CostKeySourceUnavailable(RuntimeError):
    """权威成本键来源 (cretas 运营库) 够不着。

    🔴 owner 2026-08-13 裁定条件 3: **必须显式失败, 绝不许静默当成
       「这些菜没有成本卡」。**

    ⛔ 静默降级会精确地重现刚修掉的 `COALESCE(food_cost, 0)`: 没有成本卡的菜
       按零成本计入 → 毛利虚高。而且比原来那个更难发现 —— 它是**间歇性**的
       (池子抖一次错一次), 答案每次都长得很正常, 没有任何痕迹说明这一次的数
       是在少了一个数据源的情况下算出来的。
    """


def normalize_dish_name(name) -> str:
    """成本键映射的**唯一**规范化。

    ⚠️ 只在 Python 里做。SQL 那一侧**不许**写 `lower()`/`btrim()` 去凑 ——
       两种实现对全角空格、Unicode 大小写折叠的处理并不一致, 而不一致的表现
       是「有几道菜只在一条路上匹配得上」, 正是本模块要根治的东西。
       执行器传给 SQL 的是**已配对好的原样名字**, join 用纯等值。
    """
    return str(name or "").strip().lower()


def cost_key_of(mapping: Optional[Mapping[str, str]], name) -> Optional[str]:
    """按规范化后的名字取成本键。⛔ 两条路都走这里, 不许直接 `mapping.get(name)`。"""
    if not mapping:
        return None
    return mapping.get(normalize_dish_name(name))


def _claim(merged: Dict[str, str], ambiguous: set, name, source_pk, *, source: str,
           factory_id: str) -> None:
    """把一条映射记进 merged。先到先得, 冲突则**整条作废**。

    ⛔ 同一个规范化名字对上两个不同成本键时不许挑一个 —— 猜错会直接污染 COGS,
       而且是安静的。宁可这道菜算不出毛利。
    """
    key = normalize_dish_name(name)
    pk = str(source_pk or "").strip()
    if not key or not pk or key in ambiguous:
        return
    existing = merged.get(key)
    if existing is None:
        merged[key] = pk
        return
    if existing != pk:
        ambiguous.add(key)
        merged.pop(key, None)
        logger.warning(
            "[cost-key] 同名不同键, 该菜不解析 factory=%s name=%s 冲突源=%s",
            factory_id, key, source,
        )


def _tier(rows, name_col: str, pk_col: str, *, source: str, factory_id: str) -> Dict[str, str]:
    """一层来源自己的映射。同一层内同名冲突 → 这道菜作废。"""
    out: Dict[str, str] = {}
    ambiguous: set = set()
    for row in rows or ():
        _claim(out, ambiguous, row[name_col], row[pk_col],
               source=source, factory_id=factory_id)
    return out


async def resolve_cost_keys(
    smartbi_conn,
    factory_id: str,
    *,
    cretas_pool=None,
) -> Dict[str, str]:
    """这个租户的 **规范化菜名 → 成本键** 全量映射。三层, 高层覆盖低层:

      ① cretas `product_types`      —— 权威。运营库里的菜就是这家店真正在卖的菜。
      ② cretas `dim_product_alias`  —— 商户手工绑定的 POS 别名 (括号/空格漂移)。
      ③ smartbi `dim_restaurant_cost_product` —— **存量兜底**。历史/演示租户的
         Gold 成本行可能比产生它的运营库种子活得久, 那些菜只在这一层有。

    ⛔ ①②③ 少任何一层, 两条路就会看到不同的菜。日结原来只有 ③, 差 20,701.63。

    :raises CostKeySourceUnavailable: 拿不到 cretas 池 / ① 查询失败。
        **不返回部分结果** —— 少了权威层的映射与「这些菜没有成本卡」在数值上
        完全一样, 而后者会被当成真话端给店长。
    """
    if not factory_id:
        raise CostKeySourceUnavailable("factory_id 为空, 无法解析成本键")

    if cretas_pool is None:
        # ⛔ 复用服务已有的那个单例池, 不新建第二个 (owner 裁定条件 1)。
        from smartbi.config import get_cretas_pool
        cretas_pool = await get_cretas_pool()
    if cretas_pool is None:
        raise CostKeySourceUnavailable(
            "cretas 池不可用 (food_kb_db_url 未配置或建池失败)")

    try:
        async with cretas_pool.acquire() as cretas:
            name_rows = await cretas.fetch(
                "SELECT id, name FROM product_types WHERE factory_id = $1",
                factory_id,
            )
            # 别名表在旧 schema 上可能不存在 —— 那是**已知的可选层**, 不是故障。
            try:
                alias_rows = await cretas.fetch(
                    "SELECT pos_name, product_type_id FROM dim_product_alias "
                    " WHERE factory_id = $1",
                    factory_id,
                )
            except Exception as exc:  # noqa: BLE001
                if "does not exist" not in str(exc):
                    logger.warning(
                        "[cost-key] 别名层查询失败 factory=%s: %s", factory_id, exc)
                alias_rows = []
    except CostKeySourceUnavailable:
        raise
    except Exception as exc:  # noqa: BLE001
        raise CostKeySourceUnavailable(
            f"cretas product_types 查询失败 factory={factory_id}: {exc}") from exc

    primary = _tier(name_rows, "name", "id",
                    source="product_types", factory_id=factory_id)
    alias = _tier(alias_rows, "pos_name", "product_type_id",
                  source="dim_product_alias", factory_id=factory_id)

    # ③ 存量兜底。这一层拿不到**不致命** —— 它补的是历史行, 缺了只会少算几道菜,
    #    而 ① 缺了是整条权威链断掉。两者不同档, 所以处置不同。
    fallback_rows = []
    try:
        rows = await smartbi_conn.fetch(
            """
            SELECT normalized_name, product_source_pk
              FROM dim_restaurant_cost_product
             WHERE factory_id = $1
               AND is_active = TRUE
            """,
            factory_id,
        )
        fallback_rows = rows or []
    except Exception as exc:  # noqa: BLE001
        if "does not exist" not in str(exc):
            logger.warning(
                "[cost-key] 存量兜底层查询失败 factory=%s: %s", factory_id, exc)
    fallback = _tier(fallback_rows, "normalized_name", "product_source_pk",
                     source="dim_restaurant_cost_product", factory_id=factory_id)

    merged: Dict[str, str] = dict(fallback)
    merged.update(alias)      # 别名压过存量兜底
    merged.update(primary)    # 权威压过一切
    logger.info(
        "[cost-key] factory=%s 权威=%d 别名=%d 存量=%d 合计=%d",
        factory_id, len(primary), len(alias), len(fallback), len(merged),
    )
    return merged


async def cost_bridge_pairs(
    smartbi_conn,
    factory_id: str,
    *,
    cretas_pool=None,
) -> Tuple[List[str], List[str]]:
    """给执行器用的 (菜名, 成本键) 两个平行数组。

    🔑 数组里的名字是 `dim_product.normalized_name` 的**原样值** ——
       于是 SQL 那边可以写纯等值 join, 一个规范化函数都不用。
       规范化只发生在这里 (`cost_key_of`), 与问答那条路是同一份实现。
    """
    mapping = await resolve_cost_keys(smartbi_conn, factory_id,
                                      cretas_pool=cretas_pool)
    rows = await smartbi_conn.fetch(
        "SELECT DISTINCT normalized_name FROM dim_product WHERE factory_id = $1",
        factory_id,
    )
    names: List[str] = []
    keys: List[str] = []
    for row in rows or ():
        raw = row["normalized_name"]
        key = cost_key_of(mapping, raw)
        if raw and key:
            names.append(raw)
            keys.append(key)
    if mapping and not names:
        # ⚠️ 解析出了映射却一道菜都配不上 —— 数组传空的后果是「一张卡都桥不上」,
        #    也就是**成本恒为 0、毛利率 100%**, 而且不报错。
        #    最常见的成因是 `dim_product` 这一读被 RLS 挡了(GUC 没设/设成了
        #    事务级而连接在 autocommit)。留痕, 别让它安静地过去。
        logger.warning(
            "[cost-key] factory=%s 解析到 %d 条映射, 但 dim_product 一条都没配上 "
            "—— 检查 app.factory_id 是否是**会话级**(第三个参数 false)",
            factory_id, len(mapping))
    return names, keys


async def merge_cost_product_mapping(
    smartbi_pool,
    factory_id: str,
    normalized_names: Iterable[str],
    primary: Optional[Mapping[str, str]] = None,
) -> Dict[str, str]:
    """Return a merged ``normalized_name -> product_source_pk`` mapping.

    ``primary`` always wins.  The SmartBI fallback is tenant-scoped through both
    the SQL predicate and the RLS GUC.  If a normalized name maps to more than
    one source key, it is not selected: guessing would corrupt COGS and margin.
    Older schemas without the dimension fail closed and keep the primary map.
    """
    merged: Dict[str, str] = dict(primary or {})
    missing = sorted({
        str(name).strip()
        for name in normalized_names
        if name and str(name).strip() and str(name).strip() not in merged
    })
    if not factory_id or not missing:
        return merged

    try:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)",
                factory_id,
            )
            rows = await conn.fetch(
                """
                SELECT normalized_name, product_source_pk
                  FROM dim_restaurant_cost_product
                 WHERE factory_id = $1
                   AND normalized_name = ANY($2::text[])
                   AND is_active = TRUE
                 ORDER BY normalized_name, product_source_pk
                """,
                factory_id,
                missing,
            )
    except Exception as exc:
        if "does not exist" not in str(exc):
            logger.warning(
                "[cost-product-map] SmartBI fallback lookup failed factory=%s: %s",
                factory_id,
                exc,
            )
        return merged

    candidates = defaultdict(set)
    for row in rows:
        name = str(row["normalized_name"] or "").strip()
        source_pk = str(row["product_source_pk"] or "").strip()
        if name and source_pk:
            candidates[name].add(source_pk)

    for name, source_pks in candidates.items():
        if len(source_pks) == 1:
            merged[name] = next(iter(source_pks))
        else:
            logger.warning(
                "[cost-product-map] ambiguous mapping left unresolved "
                "factory=%s name=%s candidates=%s",
                factory_id,
                name,
                sorted(source_pks),
            )
    return merged


async def merge_cost_product_names(
    smartbi_pool,
    factory_id: str,
    product_source_pks: Iterable[str],
    primary: Optional[Mapping[str, str]] = None,
) -> Dict[str, str]:
    """Return ``product_source_pk -> product_name`` with SmartBI fallback."""
    merged: Dict[str, str] = dict(primary or {})
    missing = sorted({
        str(source_pk).strip()
        for source_pk in product_source_pks
        if source_pk
        and str(source_pk).strip()
        and str(source_pk).strip() not in merged
    })
    if not factory_id or not missing:
        return merged
    try:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)",
                factory_id,
            )
            rows = await conn.fetch(
                """
                SELECT product_source_pk, product_name
                  FROM dim_restaurant_cost_product
                 WHERE factory_id = $1
                   AND product_source_pk = ANY($2::text[])
                   AND is_active = TRUE
                """,
                factory_id,
                missing,
            )
    except Exception as exc:
        if "does not exist" not in str(exc):
            logger.warning(
                "[cost-product-map] SmartBI name lookup failed factory=%s: %s",
                factory_id,
                exc,
            )
        return merged
    for row in rows:
        source_pk = str(row["product_source_pk"] or "").strip()
        product_name = str(row["product_name"] or "").strip()
        if source_pk and product_name:
            merged[source_pk] = product_name
    return merged
