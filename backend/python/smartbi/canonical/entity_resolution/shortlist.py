"""把「老板打的那几个字」对上库里的全名 —— 唯一 / 多条 / 零条 **三态**。

▎人不会说全称，他只记住能唯一区分的那几个字：
▎    库里叫「模拟·宝山大场社区店」  →  他说「宝山店」
▎    库里叫「鲜花椒大人二人套餐」    →  他说「青花椒二人套餐」

📏 2026-08-18 prod 实测（MOCK_REST 10 家真实门店，简称由真名**机械派生**，
   ⛔ 不是我挑的），量的是两条**真实在用**的匹配路径：

     `_validated_llm_store_names` 的 `name not in available`   0/50  = 0.0%
     `_canonicalize_store_mention` 的 SQL LIKE 双向包含        30/50 = 60.0%
        其中 **20 条零候选**，全部落在「头两字/头三字 + 店」这一类
        （宝山店 / 徐汇店 / 浦东店 …）—— 正是人最常用的那种说法

   ⇒ 精确成员判定**从原理上做不了**这件事，而它今天的失败方式是**静默丢弃**。

## 为什么是两段（⛔ 不是直接上向量）

同一批 20 条零候选上对打（读数见设计卡）：

  | 段 | 做法 | 批1 门店简称(20) | 批2 owner 定稿原文(2) | 代价 |
  |----|------|------------------|----------------------|------|
  | A  | 去尾缀 + 归一 + 双向包含 | **20/20 唯一** | 1/2 | 纯字符串, 0ms |
  | B  | embedding 余弦 top-1     | 20/20          | **2/2** | 冷 172ms / 热 10ms |

  批2 的「青花椒二人套餐 → 鲜花椒大人二人套餐」与候选**没有任何子串关系**
  （sim=0.860, margin=0.375）—— A 段从原理上做不到，只有向量能。
  反过来，A 段能覆盖的那 20 条不该去付向量的钱。

  ⇒ 先 A 后 B。这与 `make_default_orchestrator` 的 deterministic → embedding
    同一个形状；⛔ 本模块**不走 orchestrator**，理由见下。

## ⛔ 为什么不直接用 `make_default_orchestrator`

三条，每条都是实测或读代码得来的**结构性**理由：

1. `EmbeddingAgent.ship_threshold = 0.90`，而 prod 实测 20 条正确命中的
   cosine 落在 **0.608 ~ 0.796**，一条都到不了 → 整条链会一路降到
   `admin_queue`，**没有一次会 ship**。（实测，见设计卡「批1」表）
2. `EntityResolutionOrchestrator.resolve()` 会往 `entity_resolution_history`
   和 `entity_resolution_admin_queue` **写行**。那是数据治理的审计链路，
   ⛔ 不该挂在一次问答的读路径上（每问一句写一行待人工审核）。
3. 它按 `dim_<entity>` 自己查库；而读路径**手上已经有**候选清单
   （`_load_store_options` 刚读出来的租户门店全集）。再查一次是第二份来源。

⇒ 本模块**复用** `EmbeddingAgent._cosine` / `EmbeddingCache` /
  `normalize_for_dim`（形态 D：同一个东西两份一定会漂），只是不复用编排。

## 阈值哪来的（⛔ 不是拍的）

prod 实测三组 cosine 分布：

    正确候选 top-1      0.608 ~ 0.796   (n=20)
    正确候选的次优      0.421 ~ 0.642
    完全无关的词 top-1  0.198 / 0.232   (阴性对照)

  `SIM_FLOOR = 0.55`  落在「最低正确 0.608」与「最高无关 0.232」之间的空隙里。
  `SIM_MARGIN = 0.08` 用来分「唯一」和「多条」。

⚠️ **`SIM_MARGIN` 在实测样本上一次都没被触发过**（批1 的 20 条全被 A 段接住，
   批2 两条 margin 都 > 0.33）。它是保守侧设定，⛔ 没有实测支撑 ——
   这一条必须写在这里，否则下一个人会以为它也是量出来的。
   能让它红的对照在 `test_entity_shortlist.py::test_two_equally_close_candidates_ask`。

## 三态里最重要的是第三态

    unique    → 归一成库里的**全名**（抬头用全名，⛔ 不用老板打的字）
    ambiguous → **确认式反问**，列候选让他选（⛔ 不静默挑一个）
    none      → 什么都不做，走今天的路（fail-open，⛔ 绝不静默丢掉）

⛔ 「静默丢弃」不在这三态里 —— 它正是本模块要消除的那个东西。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Awaitable, Callable, List, Optional, Sequence, Tuple

from smartbi.canonical.entity_resolution._embedding_cache import (
    EmbeddingCache,
    EmbedFn,
)

VERDICT_UNIQUE = "unique"
VERDICT_AMBIGUOUS = "ambiguous"
VERDICT_NONE = "none"

STAGE_EXACT = "exact"
STAGE_CONTAINMENT = "containment"
STAGE_EMBEDDING = "embedding"
STAGE_EMBEDDING_SKIPPED = "embedding_skipped"

#: cosine 下界。低于它一律判 none（fail-open），⛔ 不猜。
#: 实测: 正确命中最低 0.608 / 无关词最高 0.232 —— 0.55 在空隙里。
SIM_FLOOR = 0.55

#: top-1 与 top-2 的最小间距。小于它 ⇒ 分不清 ⇒ **反问**，⛔ 不替他选。
#: ⚠️ 保守侧设定，实测样本上未被触发（见模块 docstring）。
SIM_MARGIN = 0.08

#: 候选池上限。超过就**不做**向量段 —— 一次问答要为每个候选算一次 embedding，
#: 候选无界时那是无界的墙钟。租户门店数实测 10 / 27 / 38 / 132。
#: ⛔ 超限时返回 none（走今天的路），⛔ 不是截断候选后照算 ——
#:    截断会让「他要的那家恰好排在 60 名之后」变成一个**看不见**的错答。
MAX_EMBED_CANDIDATES = 60

#: 反问里最多列几个候选。列太多等于没在帮他选。
MAX_ASK_CANDIDATES = 4

#: 门店类尾缀。去掉之后「宝山店」→「宝山」才与「模拟·宝山大场社区店」有子串关系。
#: ⚠️ 顺序敏感：长的在前，否则「门店」会被「店」先吃掉一半。
ENTITY_SUFFIXES: Tuple[str, ...] = ("门店", "分店", "餐厅", "店")

#: 共享向量缓存。⛔ 不按 factory 分键 —— 缓存的是**文本→向量**，与租户无关；
#: 租户隔离由调用方给的 `candidates` 清单负责（它是 RLS 读出来的）。
_CACHE = EmbeddingCache(max_size=20_000)

_WS_RE = re.compile(r"[\s　]+")


@dataclass(frozen=True)
class ShortlistDecision:
    """一次消解的结果。⛔ 三态齐全，没有「静默丢弃」这一态。"""

    mention: str
    verdict: str
    canonical: Optional[str]
    candidates: Tuple[str, ...]
    stage: str
    detail: str

    @property
    def is_unique(self) -> bool:
        return self.verdict == VERDICT_UNIQUE and bool(self.canonical)

    @property
    def is_ambiguous(self) -> bool:
        return self.verdict == VERDICT_AMBIGUOUS and len(self.candidates) >= 2


def _none(mention: str, stage: str, detail: str) -> ShortlistDecision:
    return ShortlistDecision(
        mention=mention, verdict=VERDICT_NONE, canonical=None,
        candidates=(), stage=stage, detail=detail,
    )


def strip_entity_suffix(mention: str) -> str:
    """去掉「店/门店/分店/餐厅」这类尾缀。

    ⛔ 只去**一个**：「宝山店店」这种不是人话，多去一层只会把真名削短。
    ⛔ 整个词就是尾缀时不动它（「门店」不该变成空串）。
    """
    text = (mention or "").strip()
    for suffix in ENTITY_SUFFIXES:
        if text.endswith(suffix) and len(text) > len(suffix):
            return text[: -len(suffix)]
    return text


def _normalized(text: str) -> str:
    """繁简 + 标点 + 空白归一。⛔ 复用 `DeterministicAgent` 那一份，不另写。

    「模拟·徐汇美罗城店」里的「·」不去掉，子串比较就永远差那一个字符。
    """
    from smartbi.canonical.entity_resolution.agents.deterministic import (
        normalize_for_dim,
    )

    return _WS_RE.sub("", normalize_for_dim(text or ""))


def containment_shortlist(
    mention: str,
    candidates: Sequence[str],
) -> Tuple[str, ...]:
    """A 段：去尾缀 + 归一后**双向包含**的候选，短的在前。

    双向的理由：老板既可能说得比库名短（宝山店 ⊂ 宝山大场社区店），
    也可能说得比库名长（模拟·徐汇美罗城店旗舰 ⊃ 模拟·徐汇美罗城店）。

    ⛔ 归一后为空的 mention 直接返回空 —— 空串是任何字符串的子串，
       不挡住它就会**命中全部候选**（这条不是理论：`""  in "任何"` 为 True）。
    """
    needle = _normalized(strip_entity_suffix(mention))
    if not needle:
        return ()
    hits: List[Tuple[int, str]] = []
    for cand in candidates:
        hay = _normalized(cand)
        if not hay:
            continue
        if needle in hay or hay in needle:
            hits.append((len(hay), cand))
    hits.sort(key=lambda pair: (pair[0], pair[1]))
    return tuple(cand for _, cand in hits)


async def _embed(embed_fn: EmbedFn, text: str) -> Optional[List[float]]:
    return await _CACHE.get_or_embed(embed_fn, text, key=("shortlist", text))


def _default_embed_fn() -> EmbedFn:
    from smartbi.services.llm_fallback_logger import get_embedding

    return get_embedding


async def embedding_shortlist(
    mention: str,
    candidates: Sequence[str],
    *,
    embed_fn: Optional[Callable[[str], Awaitable[Optional[List[float]]]]] = None,
) -> Tuple[Tuple[str, float], ...]:
    """B 段：cosine 降序的 `(候选, 相似度)`。embedding 拿不到就返回空。

    ⛔ 拿不到向量返回**空元组**，⛔ 不返回 0.0 分的候选 ——
       「我不知道」和「相似度是 0」对下游是两件完全不同的事（形态 A¹⁰）。
    """
    from smartbi.canonical.entity_resolution.agents.embedding import EmbeddingAgent

    fn = embed_fn or _default_embed_fn()
    query = await _embed(fn, mention)
    if query is None:
        return ()
    scored: List[Tuple[str, float]] = []
    for cand in candidates:
        if not cand:
            continue
        vec = await _embed(fn, cand)
        if vec is None:
            continue
        scored.append((cand, EmbeddingAgent._cosine(query, vec)))
    scored.sort(key=lambda pair: -pair[1])
    return tuple(scored)


def decide_from_scores(
    mention: str,
    scored: Sequence[Tuple[str, float]],
    *,
    floor: float = SIM_FLOOR,
    margin: float = SIM_MARGIN,
) -> ShortlistDecision:
    """把一串 `(候选, 相似度)` 判成三态。**纯函数** —— 闸就钉在这里。"""
    if not scored:
        return _none(mention, STAGE_EMBEDDING, "embedding 没有可比的候选")
    top_name, top_sim = scored[0]
    if top_sim < floor:
        return _none(
            mention, STAGE_EMBEDDING,
            f"最像的也只有 {top_sim:.3f} < 下界 {floor:.2f}，不猜",
        )
    survivors = [(name, sim) for name, sim in scored if sim >= floor]
    if len(survivors) == 1:
        return ShortlistDecision(
            mention=mention, verdict=VERDICT_UNIQUE, canonical=top_name,
            candidates=(top_name,), stage=STAGE_EMBEDDING,
            detail=f"唯一过下界: sim={top_sim:.3f}",
        )
    gap = survivors[0][1] - survivors[1][1]
    if gap >= margin:
        return ShortlistDecision(
            mention=mention, verdict=VERDICT_UNIQUE, canonical=top_name,
            candidates=(top_name,), stage=STAGE_EMBEDDING,
            detail=f"sim={top_sim:.3f}，比次优高 {gap:.3f} ≥ {margin:.2f}",
        )
    return ShortlistDecision(
        mention=mention, verdict=VERDICT_AMBIGUOUS, canonical=None,
        candidates=tuple(name for name, _ in survivors[:MAX_ASK_CANDIDATES]),
        stage=STAGE_EMBEDDING,
        detail=f"top1 {top_sim:.3f} 与次优只差 {gap:.3f} < {margin:.2f}，分不清",
    )


async def resolve_mention(
    mention: str,
    candidates: Sequence[str],
    *,
    embed_fn: Optional[Callable[[str], Awaitable[Optional[List[float]]]]] = None,
    allow_embedding: bool = True,
    floor: float = SIM_FLOOR,
    margin: float = SIM_MARGIN,
) -> ShortlistDecision:
    """一个 mention 对一份候选清单 → 三态。

    ⚠️ `candidates` 必须已经是**这个租户的**清单（调用方用 RLS 读出来）。
       本函数不碰库，也就不可能自己去越权。
    """
    text = (mention or "").strip()
    pool = tuple(c for c in (candidates or ()) if c and str(c).strip())
    if not text or not pool:
        return _none(mention, STAGE_EXACT, "mention 或候选清单为空")

    if text in pool:
        return ShortlistDecision(
            mention=text, verdict=VERDICT_UNIQUE, canonical=text,
            candidates=(text,), stage=STAGE_EXACT, detail="库里就叫这个名字",
        )

    hits = containment_shortlist(text, pool)
    if len(hits) == 1:
        return ShortlistDecision(
            mention=text, verdict=VERDICT_UNIQUE, canonical=hits[0],
            candidates=hits, stage=STAGE_CONTAINMENT,
            detail="去尾缀归一后唯一包含",
        )
    if len(hits) > 1:
        return ShortlistDecision(
            mention=text, verdict=VERDICT_AMBIGUOUS, canonical=None,
            candidates=hits[:MAX_ASK_CANDIDATES], stage=STAGE_CONTAINMENT,
            detail=f"去尾缀归一后有 {len(hits)} 家都包含它",
        )

    if not allow_embedding:
        return _none(mention, STAGE_EMBEDDING_SKIPPED, "调用方关掉了向量段")
    if len(pool) > MAX_EMBED_CANDIDATES:
        # ⛔ 不截断候选后照算 —— 那会把「他要的那家排在上限之外」变成一个看不见的错答。
        return _none(
            mention, STAGE_EMBEDDING_SKIPPED,
            f"候选 {len(pool)} 个超过上限 {MAX_EMBED_CANDIDATES}，不做向量",
        )

    scored = await embedding_shortlist(text, pool, embed_fn=embed_fn)
    return decide_from_scores(text, scored, floor=floor, margin=margin)


def clear_shortlist_cache() -> None:
    """清空共享向量缓存。跨样本批量读数前调它，⛔ 别拼属性名（形态 C⁵）。"""
    _CACHE._cache.clear()  # noqa: SLF001 - 同包内, 缓存本身没有 public clear


def shortlist_cache_size() -> int:
    """当前缓存条目数 —— 让「清了没有」变成一个**可观测**的数。"""
    return len(_CACHE)


__all__ = [
    "ENTITY_SUFFIXES",
    "MAX_ASK_CANDIDATES",
    "MAX_EMBED_CANDIDATES",
    "SIM_FLOOR",
    "SIM_MARGIN",
    "STAGE_CONTAINMENT",
    "STAGE_EMBEDDING",
    "STAGE_EMBEDDING_SKIPPED",
    "STAGE_EXACT",
    "VERDICT_AMBIGUOUS",
    "VERDICT_NONE",
    "VERDICT_UNIQUE",
    "ShortlistDecision",
    "clear_shortlist_cache",
    "containment_shortlist",
    "decide_from_scores",
    "embedding_shortlist",
    "resolve_mention",
    "shortlist_cache_size",
    "strip_entity_suffix",
]
