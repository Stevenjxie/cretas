"""飞轮 B：拒答队列 —— 答不上的那一半，按【拒答原因】归类，人工看它去补能力。

设计卡: docs/decisions/2026-08-18-飞轮B拒答队列-设计卡.md
owner 定稿: docs/decisions/2026-08-18-餐饮AI架构-完整版-owner定稿.md 六之二节

```
飞轮 A（答上了）  →  按频次审 Top-50  →  晋升 ledger  →  下次 0 token 命中
                     用途: 更便宜 / 更快 / 不抖         ← restaurant_intent_promotion.py
飞轮 B（答不上）  →  按【拒答原因】归类  →  人工看这张表去【补能力】   ← 本模块
                     用途: 决定下一步开发什么
                     ⛔ 不进晋升 —— 它现在还答不了, 晋升进去就是固化一个错的
```

## 形状为什么与飞轮 A 一样（⛔ 不另起一套）

存储、RLS、聚合、fail-open 全部沿用 `restaurant_intent_promotion` 的形状：
写走 `llm_fallback_logger.log_template_hit`（同一张 `smart_bi_llm_fallback_log`、
同一个 `agg_meta` JSONB 约定），读走 `GROUP BY trim(query)` + 那个模块的
`_set_rls_guc`。⛔ 不新建表、⛔ 不新建 migration、⛔ 不写 repo 文件。

三条理由（设计卡第 2 节，按权重）：

1. 飞轮 A 的 miss 记录（`log_intent_miss`）就接在**同一个函数**里
   （`gold_reads.post_restaurant_tiered_answer`，`should_delegate` 为假那一支）。
2. 零 migration ⇒ 零 DDL 风险。
3. 「JSONB 上没有索引」是可以后补的；「两张表两套聚合口径」是结构性的
   —— 形态 D：同一件事两份，一定会漂。

⛔ **不能走 repo JSON 账本**（飞轮 A 的 `LEDGER_FILE` 那条路）：拒答是运行时
高频写，而 Python 部署是 `rsync` 代码树 —— 活进程写到磁盘上的东西下次部署就
被覆盖。那条路只配放「人审判断」这种低频、要进 git 的东西。

## 分类只用结构性信号，⛔ 不做文本匹配

每一类的信号见 `classify_refusal` 的 docstring 和设计卡第 3 节表格。
⛔ **本模块任何地方都不读 `answer_text`** —— 有一道 AST 闸
（`test_classification_never_reads_answer_text`）钉住这件事。
理由：按答案文本分类，等于让「文案改一个字」把整张待办表的口径改掉，
而那种漂移不报错。

🔴 「没有判定标准」这一类**今天没有任何产出点** —— 读路径上不存在
「标准没配所以拒答」这个结构性信号（`plan_alert.threshold_value` 是预警规则，
`generic_executor._threshold` 是算出来的均值，两个都不是）。
⇒ 常量保留（它是 owner 定的分类），但没有代码写它，并有一道闸钉住这件事。
⛔ **不拿文本匹配硬凑**（任务卡第 3 条）。

## 写只发生在两个地方，且都不是自动晋升

* `record_refusal` —— fire-and-forget 记一行拒答。它**只写日志**，
  与 `log_intent_miss` 同一条纪律。
* 晋升：本模块**一个字都不写**。`promotion_entries` 是纯函数，产出的是
  一份**给人审的文件**（形状恰好是 `apply_promotions` 已经接受的那个），
  真正落盘发生在人跑 CLI `--apply` 的那一刻。
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)


# ─── 拒答原因分类（owner 定稿六之二节的五类 + 缺口清单第 12 项的域外） ──────

#: 没有数据 —— 两个结构性来源，见 `classify_refusal`。
REASON_NO_DATA = "no_data"
#: 没有维度 —— `asked − supported` 非空（读 `_supported_dimensions` 唯一定义）。
REASON_NO_DIMENSION = "no_dimension"
#: 没有判定标准。
#:
#: 🔴 **今天没有任何代码写它。** 读路径上不存在「标准没配所以拒答」这个信号。
#:    保留它是因为它是 owner 定的分类，不是因为它已经能被判出来。
#:    等缺口清单第 5 项（能力表登记「为什么不能」三态）落地，产出点在那里。
#: ⛔ 在那之前**不许**用关键词/文本匹配去凑一个假的产出点。
REASON_NO_STANDARD = "no_standard"
#: 信息不足（已反问）。
REASON_NEED_MORE_INFO = "need_more_info"
#: 域外（天气/新闻/股票）。⛔ 分一类，但**不进飞轮 B 的待办表**
#: —— 它不是能力缺口（缺口清单第 12 项）。见 `aggregate_refusals`。
REASON_OUT_OF_DOMAIN = "out_of_domain"
#: 其它 —— 落到拒答形状但以上都判不出（典型：答案契约未过）。
REASON_OTHER = "other"

#: 全部合法取值。⛔ 显式枚举，不接受任意字符串（同 `MISS_STATUS_VALUES` 的纪律：
#: 自由文本会让「按原因归类」这件事本身失效）。
REFUSAL_REASONS: Tuple[str, ...] = (
    REASON_NO_DATA,
    REASON_NO_DIMENSION,
    REASON_NO_STANDARD,
    REASON_NEED_MORE_INFO,
    REASON_OUT_OF_DOMAIN,
    REASON_OTHER,
)

#: 给人看的中文名 —— 与 owner 定稿六之二节的措辞逐字一致。
REASON_LABELS: Dict[str, str] = {
    REASON_NO_DATA: "没有数据",
    REASON_NO_DIMENSION: "没有维度",
    REASON_NO_STANDARD: "没有判定标准",
    REASON_NEED_MORE_INFO: "信息不足(已反问)",
    REASON_OUT_OF_DOMAIN: "域外",
    REASON_OTHER: "其它",
}

#: 这一类**不算能力缺口** —— 补什么都不会让它变成能答的。
_NOT_A_CAPABILITY_GAP = frozenset({REASON_OUT_OF_DOMAIN})

#: 哨兵 template_code。与飞轮 A 的 `RESTAURANT_OPS_MISS` 同一个套路：
#: 让晋升侧的 `tier='llm' AND served='true' AND contract_pass='true'` 谓词
#: 天然吞不进这些行。
REFUSAL_SENTINEL = "RESTAURANT_OPS_REFUSAL"

#: 哪些返回形状**是**拒答。
#:
#: ⛔ `"unavailable"` 不在里面。它是「LLM 额度不可用 / 执行链挂了」，
#:    是系统故障不是能力缺口 —— 把它记进这张表会让**故障在指标上看起来像
#:    产品行为**（`restaurant_intent_service.py` 那一处的注释自己就是这么写的）。
_REFUSAL_KINDS = frozenset({"clarification"})

#: 这些 code 即使 `kind == "answer"` 也是拒答（它们「答」的内容就是「答不了」）。
_REFUSAL_CODES = frozenset({
    "RESTAURANT_OPS_DATA_GAP",
    "RESTAURANT_OPS_OUT_OF_DOMAIN",
})


# ─── 分类（纯函数，无 IO，⛔ 不读 answer_text） ─────────────────────────────


def is_refusal(result: Optional[Dict[str, Any]]) -> bool:
    """这次返回是不是一次拒答。

    ⚠️ 判的是**返回的形状**，⛔ 不是答案文本 —— 「算不出来」这四个字既可能出现
       在拒答里，也可能出现在一段解释里（`partial_coverage_answer` 就是后者：
       它明说某一项算不出，同时**给出了**能算的那些数，那是一次成功的回答）。
    """
    if not isinstance(result, dict):
        return False
    if str(result.get("code") or "") in _REFUSAL_CODES:
        return True
    return str(result.get("kind") or "") in _REFUSAL_KINDS


def _dimension_gap(spec: Any) -> Tuple[str, ...]:
    """`asked − supported` —— 这次问句要求的分组里，选中的算法拆不出来的那些。

    ⛔ **读 `_supported_dimensions` 这唯一一处定义**（`restaurant_intent_service`），
       与 `_execution_mismatch`（判据）和 `_dimension_gap_advice`（拒答文案）同源。
       各算一份迟早漂成「闸说不支持、文案却说支持」，而那种不一致没有任何报错。

    懒 import：本模块被 `gold_reads` 在请求路径上导入，而
    `restaurant_intent_service` 是个重模块 —— 也避免任何潜在的 import 环。

    fail-open：算不出来就返回 ()，让调用方落到下一档分类。⛔ 绝不让一次分类
    失败连累到那次拒答本身（本模块整条路径都是 fire-and-forget）。
    """
    try:
        from smartbi.gold.restaurant.metric_registry import canonical_dimensions
        from smartbi.gold.restaurant.restaurant_intent_service import (
            _supported_dimensions,
        )

        asked = set(canonical_dimensions(tuple(getattr(spec, "dimensions", ()) or ())))
        if not asked:
            return ()
        supported = _supported_dimensions(
            tuple(getattr(spec, "planned_intents", ()) or ())
        )
        return tuple(sorted(asked - set(supported)))
    except Exception as exc:  # noqa: BLE001 — 分类失败不该连累拒答本身
        logger.warning("[refusal-queue] 维度差算不出来(按下一档分类): %s", exc)
        return ()


def classify_refusal(
    result: Optional[Dict[str, Any]],
    spec: Any = None,
) -> Optional[Dict[str, Any]]:
    """把一次拒答归类。不是拒答 → `None`。

    返回 `{"reason": ..., "missing": (...)}`。`missing` 就是 owner 定稿要求的
    「缺的是什么」—— 它是**结构化的键**（表名 / 能力码 / 维度键），
    ⛔ 不是从文案里抠出来的词。

    ## 判定顺序与它的理由

    顺序不是随意的，它跟着**读路径上闸的先后**走 —— 先开火的那道闸赢：

    1. `code == RESTAURANT_OPS_OUT_OF_DOMAIN` → 域外。
       ⚠️ 必须最先判：它的 `kind` 是 `"answer"`，靠 kind 判不出来。
    2. `meta.data_gap is True` → 没有数据。
       这是 `data_gaps.honest_gap_answer` 的出口，它**真查了表**且
       `count == 0` 才会返回（查不动 / 有行都返回 None）——
       所以这个标记的含义是「这张表本租户确实一行都没有」，
       ⛔ 不是「我猜没有」。缺的是什么 = `meta.missing_table`。
    3. 不是拒答形状 → `None`。
    4. `spec.unsupported_requirements` 非空 → 没有数据。
       `_UNSUPPORTED_REQUIREMENT_LABELS` 每一条括号里写的都是
       「缺少 &lt;字段&gt;」（翻台率缺桌台/开台时间…）—— 缺的是**采集**。
       ⚠️ 排在 5 前面，因为能力拒答那道闸（`should_use_capability_refusal`）
          在**执行之前**就开火了，那时维度闸根本没跑到。
    5. 维度差非空 → 没有维度。缺的是什么 = 那几个拆不出来的维度键。
    6. `spec.clarification_needed` → 信息不足(已反问)。
       缺的是什么 = `spec.missing_slot`（"time" / "store" / None）。
    7. 其余 → 其它。典型是答案契约未过那一支。

    ⛔ 没有任何一步读 `result["answer_text"]`。有 AST 闸钉着。
    """
    if not isinstance(result, dict):
        return None

    code = str(result.get("code") or "")
    if code == "RESTAURANT_OPS_OUT_OF_DOMAIN":
        return {"reason": REASON_OUT_OF_DOMAIN, "missing": ()}

    meta = result.get("meta")
    if isinstance(meta, dict) and meta.get("data_gap") is True:
        table = str(meta.get("missing_table") or "").strip()
        return {"reason": REASON_NO_DATA, "missing": (table,) if table else ()}

    if not is_refusal(result):
        return None

    if spec is None:
        spec = result.get("spec")

    unsupported = tuple(getattr(spec, "unsupported_requirements", ()) or ())
    if unsupported:
        return {"reason": REASON_NO_DATA, "missing": tuple(sorted(unsupported))}

    extra = _dimension_gap(spec)
    if extra:
        return {"reason": REASON_NO_DIMENSION, "missing": extra}

    if bool(getattr(spec, "clarification_needed", False)):
        slot = str(getattr(spec, "missing_slot", "") or "").strip()
        return {
            "reason": REASON_NEED_MORE_INFO,
            "missing": (slot,) if slot else (),
        }

    return {"reason": REASON_OTHER, "missing": ()}


# ─── 写入（fire-and-forget，与 `log_intent_miss` 同一条纪律） ───────────────


async def record_refusal(
    pool,
    *,
    factory_id: str,
    query: str,
    result: Optional[Dict[str, Any]],
    spec: Any = None,
    java_tool_name: Optional[str] = None,
) -> Optional[int]:
    """把一次拒答落进队列。不是拒答 → 不写，返回 `None`。

    与 `log_intent_miss` 逐条同形：同一张 `smart_bi_llm_fallback_log`、
    走 `log_template_hit`、`agg_meta.served = False`、失败只 WARNING 不抛。

    ⚠️ 调用方一律用 `asyncio.create_task(record_refusal(...))` —— 这次记账
       对用户是零延迟。⛔ 不要把它挪到回答之前。

    ⚠️ 域外**照样写**（它是一个分类，频次仍然值得知道），
       由 `aggregate_refusals` 决定它进不进那张待办表。
       ⛔ 判「进不进飞轮 B」只在一处做，两处各判一次就会漂。
    """
    try:
        classified = classify_refusal(result, spec)
        if classified is None:
            return None
        if spec is None and isinstance(result, dict):
            spec = result.get("spec")

        agg_meta: Dict[str, Any] = {
            "served": False,
            "source": "refusal_queue",
            "refusal_reason": classified["reason"],
            # ⛔ 存结构化的键，不存文案。人审看的时候由 `REASON_LABELS` /
            #    各自的登记表翻成中文 —— 文案改了不该让历史数据的口径跟着变。
            "refusal_missing": list(classified["missing"]),
            "refusal_kind": str((result or {}).get("kind") or ""),
            "refusal_code": str((result or {}).get("code") or ""),
        }
        if spec is not None:
            # 与 `log_intent_miss` 记的那几项对齐，方便两张队列并排读。
            agg_meta["tier"] = getattr(spec, "source_tier", None)
            agg_meta["confidence"] = getattr(spec, "confidence", None)
            agg_meta["spec_intent"] = getattr(spec, "intent", None)
            agg_meta["clarification_needed"] = bool(
                getattr(spec, "clarification_needed", False))
            agg_meta["planned_intents"] = list(
                getattr(spec, "planned_intents", ()) or ())
            agg_meta["dimensions"] = list(getattr(spec, "dimensions", ()) or ())
        if java_tool_name:
            agg_meta["java_tool_name"] = java_tool_name

        if pool is None:
            from smartbi.config import get_pg_pool
            pool = await get_pg_pool()
        if pool is None:
            return None

        from smartbi.services.llm_fallback_logger import log_template_hit
        return await log_template_hit(
            pool, query, factory_id, None,
            REFUSAL_SENTINEL,
            "", 0, agg_meta=agg_meta,
        )
    except Exception as exc:  # noqa: BLE001 — 记账失败绝不连累回答
        logger.warning("[refusal-queue] 拒答记账失败(非致命): %s", exc)
        return None


# ─── 聚合（只读，人工看的那张表） ─────────────────────────────────────────

#: 「这条问法**从来没成功过**」。
#:
#: 🔴 这条谓词是飞轮 A 花了一轮才学到的：`aggregate_misses` 的 `HAVING` 注释里
#:    写着 —— 不加它时清单 66 组，其中 **46 组曾经成功过**，那些失败是瞬时抖动 /
#:    上下文缺失 / 供应商池干了，**不是能力缺口**。留着它们会让「按被问次数排
#:    优先级」指向已经能答的东西。
#:
#: ⚠️ 「成功」的定义必须含判定层：只看 `served='true'` 会把「答了但答非所问」
#:    当成成功。
#:
#: ⚠️ 登记：这与 `restaurant_intent_promotion.aggregate_misses` 的 HAVING 是
#:    **两份**（形态 D）。抽成一份要动那个文件，本轮它有并发风险 ——
#:    两条线合流后抽成共享常量。见设计卡第 6 节欠账 #3。
_NEVER_ANSWERED = """
        NOT EXISTS (
            SELECT 1 FROM smart_bi_llm_fallback_log s
             WHERE trim(s.query) = trim(l.query)
               AND s.factory_id IS NOT DISTINCT FROM l.factory_id
               AND (s.agg_meta->>'served') = 'true'
               AND COALESCE(s.agg_meta->>'answered_judgment', '') <> 'false'
        )
"""


async def aggregate_refusals(
    pool,
    *,
    factory_id: Optional[str] = None,
    limit: int = 200,
    include_out_of_domain: bool = False,
    only_never_answered: bool = True,
) -> List[Dict[str, Any]]:
    """飞轮 B 那张表：按 (拒答原因, 原句) 分组，带频次和首次/最近出现时间。

    owner 定稿要求每条落这些：
    `原句 / 拒答原因分类 / 缺的是什么 / 出现频次 / 首次与最近出现时间` —— 一一对应
    `query` / `reason` / `missing` / `occurrence_count` / `first_seen` / `last_seen`。

    `factory_id=None` → 平台管理员通道（跨租户读全部）。⛔ 这**不等于**「不设
    GUC」—— 连接池的 setup 回调会把 `app.factory_id` 设成 `'__internal__'`，
    那个值一条 RLS 分支都匹配不上，于是**静默返回零行**。所以这里显式复用
    `restaurant_intent_promotion._set_rls_guc`（⛔ 不自己再写一份 set_config）。

    `include_out_of_domain=False`（默认）：⛔ 域外不进这张表 —— 问天气/股票不是
    能力缺口（缺口清单第 12 项）。它们仍然**在库里**，把参数打开就能查频次。

    `only_never_answered=True`（默认）：剔掉「曾经答对过」的问法，见
    `_NEVER_ANSWERED` 的注释。

    排序：**频次降序** —— owner 的用途是「人工看这张表去补能力」，
    频次高的就是下一个该开发的（定稿例 17 原话）。

    fail-open：任何 DB 错误返回 `[]`（与本模块、与 `aggregate_misses` 同一条纪律）。
    """
    where = [
        "l.source = 'template'",
        f"l.template_code = '{REFUSAL_SENTINEL}'",
        "l.agg_meta->>'refusal_reason' IS NOT NULL",
    ]
    if not include_out_of_domain:
        where.append(
            f"l.agg_meta->>'refusal_reason' <> '{REASON_OUT_OF_DOMAIN}'")
    if only_never_answered:
        where.append(_NEVER_ANSWERED)

    sql = f"""
        SELECT l.agg_meta->>'refusal_reason'                 AS reason,
               trim(l.query)                                 AS norm_query,
               COUNT(*)                                      AS occurrence_count,
               MIN(l.created_at)                             AS first_seen,
               MAX(l.created_at)                             AS last_seen,
               COUNT(DISTINCT l.factory_id)                  AS tenant_count,
               array_agg(DISTINCT l.agg_meta->>'refusal_missing')
                   FILTER (WHERE l.agg_meta->>'refusal_missing' IS NOT NULL)
                                                             AS missing_raw
          FROM smart_bi_llm_fallback_log l
         WHERE {' AND '.join(where)}
         GROUP BY l.agg_meta->>'refusal_reason', trim(l.query)
         ORDER BY COUNT(*) DESC, MAX(l.created_at) DESC
         LIMIT $1
    """
    try:
        from smartbi.gold.restaurant.restaurant_intent_promotion import _set_rls_guc

        async with pool.acquire() as conn:
            await _set_rls_guc(conn, factory_id)
            rows = await conn.fetch(sql, limit)
    except Exception as exc:  # noqa: BLE001
        logger.warning("[refusal-queue] aggregate_refusals 查询失败(fail-open): %s", exc)
        return []

    out: List[Dict[str, Any]] = []
    for r in rows or ():
        query = (r["norm_query"] or "").strip()
        if not query:
            continue
        reason = str(r["reason"] or REASON_OTHER)
        out.append({
            "query": query,
            "reason": reason,
            "reason_label": REASON_LABELS.get(reason, reason),
            "missing": _merge_missing(r["missing_raw"]),
            "occurrence_count": int(r["occurrence_count"] or 0),
            "first_seen": r["first_seen"],
            "last_seen": r["last_seen"],
            "tenant_count": int(r["tenant_count"] or 0),
            # ⛔ 不算能力缺口的那些（域外）即使被显式包含进来也标出来，
            #    免得下游把它们当成待办。
            "is_capability_gap": reason not in _NOT_A_CAPABILITY_GAP,
        })
    return out


def _merge_missing(raw: Optional[Sequence[Any]]) -> List[str]:
    """把同一组里各行的 `refusal_missing`（JSON 数组文本）并成一个去重列表。

    ⚠️ 解析不出来的**丢掉而不是塞原文** —— 队列里混进一条 `'[not json'`
       会让「缺的是什么」这一栏从结构化数据退化成自由文本。
    """
    merged: List[str] = []
    for item in raw or ():
        if not item:
            continue
        try:
            parsed = json.loads(item)
        except (TypeError, ValueError):
            continue
        if not isinstance(parsed, list):
            continue
        for entry in parsed:
            text = str(entry or "").strip()
            if text and text not in merged:
                merged.append(text)
    return sorted(merged)


def reason_breakdown(rows: Sequence[Dict[str, Any]]) -> Dict[str, int]:
    """按原因数一遍 —— 「下一步开发什么」的第一眼读数。

    ⚠️ 数的是**被问过多少次**（`occurrence_count` 求和），不是「有多少条不同问法」。
       后者会让一条被问了 40 次的缺口和一条只被问过一次的排在同一权重上。
    """
    out: Dict[str, int] = {reason: 0 for reason in REFUSAL_REASONS}
    for row in rows or ():
        reason = str(row.get("reason") or REASON_OTHER)
        out[reason] = out.get(reason, 0) + int(row.get("occurrence_count") or 0)
    return out


# ─── 闭环：补完能力之后，把这批原句原封不动晋升进飞轮 A ───────────────────


def promotion_entries(
    rows: Sequence[Dict[str, Any]],
    *,
    code: str,
    reason: Optional[str] = None,
) -> List[Dict[str, str]]:
    """把飞轮 B 的行翻成飞轮 A 的 `--apply` 入参形状。**纯函数，⛔ 不写任何东西。**

    owner 定稿：「开发把某个能力补完之后，把飞轮 B 里那批原句**原封不动**晋升进
    飞轮 A —— 一次性做完，⛔ 不用等它们下次再被问到。」

    返回的形状恰好是 `restaurant_intent_promotion.apply_promotions` /
    `apply_route_promotions` 已经接受的 `[{"query": ..., "code": ...}]`
    —— ⛔ 不另起一套晋升通道（形态 D）。

    ⛔ **本函数不写盘、不写库。** 与飞轮 A 同一条纪律：写只发生在人跑 CLI
       `--apply` 的那一刻（`apply_promotions` 的 docstring：「绝不静默自动毕业」）。
       这里产出的是一份**给人审的文件内容**。

    ⛔ **域外条目一律剔掉**，即使调用方把它们塞了进来 —— 「补完能力」这个前提
       对域外根本不成立，晋升它等于对所有租户永久关门
       （`promotion_llm_review` 已经为这件事红过一次）。

    `reason=None` → 不按原因过滤（调用方已经自己挑过了）。
    """
    out: List[Dict[str, str]] = []
    seen = set()
    for row in rows or ():
        row_reason = str(row.get("reason") or "")
        if row_reason in _NOT_A_CAPABILITY_GAP:
            continue
        if reason is not None and row_reason != reason:
            continue
        query = str(row.get("query") or "").strip()
        if not query or query in seen:
            continue
        seen.add(query)
        out.append({"query": query, "code": code})
    return out
