"""多域学习毕业闭环: capture(候选) → promote(两级 gate + 人审) → consult(行业分支优先, 全局主干兜底)。

泛化自 v1 field_promotion: learning_type ∈ {field_mapping, classification, data_cleaning, ...}。
核心不变量: 绝不静默自动毕业(promote --apply 人审); curated/具体优先; capture/consult 双层 fail-open。
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

logger = logging.getLogger(__name__)

_DATA = Path(__file__).parent.parent / "data"
TRUNK_FILE = _DATA / "promoted_learnings.json"          # 全局主干
BRANCH_FILE = _DATA / "promoted_learnings_by_industry.json"  # 行业分支

# 毕业门槛(保守: 错规则比调 LLM 更糟)
MIN_CONFIDENCE = 0.9
MIN_FACTORIES = 2
MIN_INDUSTRIES = 2
CAPTURE_METHODS = ("embedding", "llm", "user_correction")


def _meets_consensus(group: Dict[str, Any]) -> bool:
    """跨工厂共识阈值: ≥2 工厂且 conf≥0.9(LLM 共识), 或 纠正+再1工厂任意证据(折中)。"""
    fc = int(group.get("factory_count", 0))
    mc = float(group.get("max_confidence", 0))
    has_corr = bool(group.get("has_correction", False))
    if fc >= MIN_FACTORIES and mc >= MIN_CONFIDENCE:
        return True
    if has_corr and fc >= MIN_FACTORIES:
        return True
    return False


def is_branch_promotable(
    group: Dict[str, Any], branch: Dict[str, Any], trunk: Dict[str, Any]
) -> Tuple[bool, str]:
    """一个聚合候选能否升"行业分支"。group 含 learning_type/source_key/target_value/
    business_type/max_confidence/factory_count/has_correction。unknown 业态不走分支
    (无行业可 scope), 改由 is_trunk_promotable 的 unknown-共识 直升主干。"""
    lt = group["learning_type"]
    src = group["source_key"]
    tgt = group["target_value"]
    bt = group["business_type"]
    if bt == "unknown":
        return False, "unknown 业态不升分支(走主干共识)"
    if branch.get(lt, {}).get(bt, {}).get(src) == tgt:
        return False, "已在行业分支"
    if _meets_consensus(group):
        return True, "行业内共识"
    fc = int(group.get("factory_count", 0))
    mc = float(group.get("max_confidence", 0))
    return False, f"未达标(工厂{fc}/置信{mc})"


def is_trunk_promotable(
    learning_type: str, source_key: str, target_value: str,
    branch_state: Dict[str, Any], unknown_group: Optional[Dict[str, Any]] = None
) -> Tuple[bool, str]:
    """升全局主干, 两条路径:
    (a) 同一 (lt, src, tgt) 已在 ≥MIN_INDUSTRIES 个不同行业分支(跨行业收敛); 或
    (b) unknown 业态候选达跨工厂共识(无行业可 scope → 直升主干, 等价 v1 扁平)。"""
    lt_branches = branch_state.get(learning_type, {})
    n = sum(1 for _bt, m in lt_branches.items() if m.get(source_key) == target_value)
    if n >= MIN_INDUSTRIES:
        return True, f"跨{n}行业一致"
    if unknown_group and _meets_consensus(unknown_group):
        return True, "unknown 共识直升主干"
    return False, f"仅{n}行业"


def consult_in(
    branch: Dict[str, Any], trunk: Dict[str, Any],
    learning_type: str, source_key: Optional[str], business_type: Optional[str],
) -> Tuple[Optional[str], Optional[str]]:
    """纯函数 consult: 行业分支优先(具体), 全局主干兜底(通用)。返回 (target, method) 或 (None, None)。"""
    if not source_key:
        return None, None
    k = source_key.strip()
    if business_type:
        v = branch.get(learning_type, {}).get(business_type, {}).get(k)
        if v:
            return v, "promoted_industry"
    v = trunk.get(learning_type, {}).get(k)
    if v:
        return v, "promoted"
    return None, None


def _load(path: Path) -> Dict[str, Any]:
    try:
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, dict) else {}
    except Exception as e:  # fail-open: 映射绝不因 promoted 文件坏而崩
        logger.warning("load %s failed (ignored): %s", path, e)
    return {}


_TRUNK: Dict[str, Any] = _load(TRUNK_FILE)
_BRANCH: Dict[str, Any] = _load(BRANCH_FILE)


def consult_promoted(
    learning_type: str, source_key: Optional[str], business_type: Optional[str] = None
) -> Tuple[Optional[str], Optional[str]]:
    """规则层先查(0 token 确定): 命中返回 (target, method), 否则 (None, None)。"""
    return consult_in(_BRANCH, _TRUNK, learning_type, source_key, business_type)


async def consult_promoted_db(
    pool,
    learning_type: str,
    source_key: Optional[str],
    business_type: Optional[str] = None,
) -> Tuple[Optional[str], Optional[str]]:
    """DB 实时 consult: 行业分支优先, 全局主干兜底; 异常时回退 JSON promoted。"""
    if pool is None or not source_key:
        return consult_promoted(learning_type, source_key, business_type)
    k = source_key.strip()
    try:
        async with pool.acquire() as conn:
            if business_type:
                row = await conn.fetchrow(
                    """SELECT target_value
                         FROM smart_bi_learning_candidates
                        WHERE learning_type = $1
                          AND source_key = $2
                          AND business_type = $3
                          AND factory_id IS NULL
                          AND occurrences >= 3
                          AND confidence >= 0.9
                        ORDER BY confidence DESC, occurrences DESC, last_seen DESC
                        LIMIT 1""",
                    learning_type,
                    k,
                    business_type,
                )
                if row:
                    return row["target_value"], "promoted_industry"

            row = await conn.fetchrow(
                """SELECT target_value
                     FROM smart_bi_learning_candidates
                    WHERE learning_type = $1
                      AND source_key = $2
                      AND factory_id IS NULL
                      AND business_type = 'unknown'
                      AND occurrences >= 5
                      AND confidence >= 0.92
                    ORDER BY confidence DESC, occurrences DESC, last_seen DESC
                    LIMIT 1""",
                learning_type,
                k,
            )
            if row:
                return row["target_value"], "promoted"
    except Exception as e:
        logger.warning("consult_promoted_db failed (fallback to JSON): %s", e)
        return consult_promoted(learning_type, source_key, business_type)
    return None, None


async def capture_candidate(
    pool, learning_type: str, source_key: str, target_value: str,
    factory_id: Optional[str], method: str, confidence: float,
    business_type: str = "unknown",
) -> None:
    """非规则映射(embedding/llm/user_correction)解决一个学习单元时记候选。
    best-effort: 任何失败只 warn, 绝不让调用方因此报错(fail-open)。"""
    if not (source_key and target_value and method in CAPTURE_METHODS):
        return
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """INSERT INTO smart_bi_learning_candidates
                       (learning_type, source_key, target_value, factory_id,
                        method, confidence, business_type)
                   VALUES ($1, $2, $3, $4, $5, $6, $7)
                   ON CONFLICT (learning_type, source_key, target_value, factory_id) DO UPDATE
                     SET occurrences = smart_bi_learning_candidates.occurrences + 1,
                         last_seen = now(),
                         confidence = GREATEST(smart_bi_learning_candidates.confidence,
                                               EXCLUDED.confidence),
                         business_type = EXCLUDED.business_type""",
                learning_type, source_key.strip(), target_value.strip(),
                factory_id, method, round(float(confidence), 3), business_type or "unknown",
            )
    except Exception as e:
        logger.warning("capture_candidate failed (ignored): %s", e)
