"""评价门店名 → gold dim_store 模糊匹配 (rules-first, 无 LLM)。

P3 门店评分×营收 (spec §2.2 / §4.1)。评价数据(大众点评导出)门店名与 POS
dim_store.name 0 精确匹配, 靠括号地标 / 品牌 token 重叠模糊匹配。

匹配策略 (rules-first, per .claude/rules/rules-first-llm-fallback):
  1. exact_norm     : normalize_for_dim 相等 → conf 1.0 (预期 0 命中, 防未来对齐)
  2. landmark       : 评价名括号地标 token 命中 dim_store 名
                        唯一 → conf 0.92 (自动可用); 多候选 → conf 0.60 (歧义, 进确认队列)
  3. brand_landmark : 去括号品牌主体 token 与 dim_store 名 jaccard >=0.5 且唯一 → conf 0.85
  4. 全不中         → 返回 [] (调用方写 no_match / 留人工标 unmapped)

置信度由本模块按"唯一性"内定 (调用方不再二次判定置信)。
float() 即可 (本模板非 byte-parity, per python-java-port)。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List, Optional, Tuple

from smartbi.canonical.entity_resolution.agents.deterministic import normalize_for_dim

# 括号地标 (中/英文括号都抓)。取最后一个括号组 (品牌名里偶有前置括号, 地标通常在尾部)。
_BRACKET_RE = re.compile(r"[(（]([^)）]+)[)）]")
# 去地标尾"店"字 (五角场店 → 五角场), 也去常见尾缀以便子串匹配。
_TRAIL_STORE_RE = re.compile(r"店$")

# 品牌噪音 token — 做 brand_landmark jaccard 时剔除, 避免品牌前缀稀释相似度。
_BRAND_STOPWORDS = frozenset({
    "青花椒", "鲜行者", "外卖卫星", "卫星", "外卖", "小馆", "顺德", "店",
})

# brand_landmark jaccard 阈值。
_BRAND_JACCARD_THRESHOLD = 0.5

# 置信度常量。
CONF_EXACT = 1.0
CONF_LANDMARK_UNIQUE = 0.92
CONF_LANDMARK_AMBIGUOUS = 0.60
CONF_BRAND_LANDMARK = 0.85

# 自动入库可直接进 join 的最低置信 (>= 这个值的 auto 行 + 所有 admin 行参与 join)。
AUTO_USABLE_CONFIDENCE = 0.90


@dataclass(frozen=True)
class Candidate:
    store_id: int
    gold_name: str
    confidence: float           # float() OK per python-java-port (本模板非 byte-parity)
    match_method: str           # 'exact_norm'|'landmark'|'brand_landmark'
    landmark: Optional[str]


def extract_landmark(review_store_name: str) -> Optional[str]:
    """从评价门店名抽括号地标 token, 去尾'店'。无括号返 None。

    取**最后**一个括号组 (地标通常在名字末尾, 如 '青花椒·外卖卫星店(五角场店)')。
    """
    matches = _BRACKET_RE.findall(review_store_name or "")
    if not matches:
        return None
    raw = matches[-1].strip()
    return _TRAIL_STORE_RE.sub("", raw) or None


def _strip_brackets(name: str) -> str:
    """去掉所有括号组, 留品牌主体 (用于 brand_landmark token 比较)。"""
    return _BRACKET_RE.sub("", name or "").strip()


def _char_tokens(text: str) -> set:
    """归一后逐字 token 集 (去品牌噪音词的字符)。

    中文无空格分词, 用逐字 token + jaccard 做轻量品牌+地标重叠度量。
    剔除品牌 stopword 的字符避免共同品牌前缀虚高相似度。
    """
    norm = normalize_for_dim(text)
    stop_chars = set("".join(_BRAND_STOPWORDS))
    return {ch for ch in norm if ch.strip() and ch not in stop_chars}


def _jaccard(a: set, b: set) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    union = len(a | b)
    return inter / union if union else 0.0


def match_review_store(
    review_name: str, dim_stores: List[Tuple[int, str]],
) -> List[Candidate]:
    """返回候选列表 (可能 0/1/多)。

    多候选 = 歧义 (同地标多家 POS 店) → 置信降到 CONF_LANDMARK_AMBIGUOUS,
    由调用方据置信决定是否进确认队列 (不参与 join 直到人工确认)。
    """
    if not review_name or not dim_stores:
        return []

    norm_review = normalize_for_dim(review_name)

    # 1. exact_norm — 归一后完全相等。
    exact = [
        Candidate(int(sid), name, CONF_EXACT, "exact_norm", None)
        for sid, name in dim_stores
        if normalize_for_dim(name) == norm_review and norm_review
    ]
    if exact:
        return exact

    # 2. landmark — 括号地标 token 子串命中。
    lm = extract_landmark(review_name)
    if lm:
        lm_norm = normalize_for_dim(lm)
        if lm_norm:
            hits = [
                (int(sid), name)
                for sid, name in dim_stores
                if lm_norm in normalize_for_dim(name)
            ]
            if hits:
                conf = CONF_LANDMARK_UNIQUE if len(hits) == 1 else CONF_LANDMARK_AMBIGUOUS
                return [
                    Candidate(sid, name, conf, "landmark", lm)
                    for sid, name in hits
                ]

    # 3. brand_landmark — 去括号品牌主体 token jaccard >=0.5 且唯一。
    review_body = _strip_brackets(review_name)
    review_tokens = _char_tokens(review_body)
    if review_tokens:
        scored = []
        for sid, name in dim_stores:
            score = _jaccard(review_tokens, _char_tokens(name))
            if score >= _BRAND_JACCARD_THRESHOLD:
                scored.append((score, int(sid), name))
        if len(scored) == 1:
            _score, sid, name = scored[0]
            return [Candidate(sid, name, CONF_BRAND_LANDMARK, "brand_landmark", None)]
        # 多候选 brand_landmark 同样歧义 → 降置信进确认队列 (取 top-N 全返, 由调用方据置信处理)。
        if len(scored) > 1:
            scored.sort(reverse=True)
            return [
                Candidate(sid, name, CONF_LANDMARK_AMBIGUOUS, "brand_landmark", None)
                for _score, sid, name in scored
            ]

    # 4. 全不中。
    return []
