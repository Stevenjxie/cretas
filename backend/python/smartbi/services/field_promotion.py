"""字段映射毕业闭环: Capture(候选) → Promote(gate+人审) → Consult(规则层先查)。

绝不静默自动毕业 (Promote 工具 --apply 是人审后显式执行)。curated 冲突时 curated 赢。
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

logger = logging.getLogger(__name__)

PROMOTED_FILE = Path(__file__).parent.parent / "data" / "promoted_field_aliases.json"

# 毕业门槛 (保守: 错规则比调 LLM 更糟)
MIN_CONFIDENCE = 0.9
MIN_FACTORIES = 2


def is_promotable(
    candidate: Dict[str, Any],
    curated: Dict[str, str],
    promoted: Dict[str, str],
) -> Tuple[bool, str]:
    """纯函数: 一个聚合候选能否毕业。candidate 含 column_name/standard_field/
    max_confidence/factory_count。返回 (可否, 原因)。"""
    col = candidate["column_name"]
    std = candidate["standard_field"]
    if col in promoted:
        return False, f"已毕业 ({col}→{promoted[col]})"
    if col in curated:
        if curated[col] != std:
            return False, f"与 curated 冲突 (curated: {col}→{curated[col]})"
        return False, f"已在 curated ({col})"
    if float(candidate.get("max_confidence", 0)) < MIN_CONFIDENCE:
        return False, f"置信不足 (<{MIN_CONFIDENCE})"
    if int(candidate.get("factory_count", 0)) < MIN_FACTORIES:
        return False, f"复现工厂不足 (<{MIN_FACTORIES})"
    return True, "可毕业"


def _load_promoted() -> Dict[str, str]:
    try:
        if PROMOTED_FILE.exists():
            with open(PROMOTED_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            return {str(k): str(v) for k, v in data.items()} if isinstance(data, dict) else {}
    except Exception as e:  # fail-open: 映射绝不因 promoted 文件坏而崩
        logger.warning("load promoted_field_aliases failed (ignored): %s", e)
    return {}


_PROMOTED: Dict[str, str] = _load_promoted()


def consult_promoted(column_name: Optional[str]) -> Optional[str]:
    """规则层先查: 命中返回标准字段 (0 token 确定), 否则 None。"""
    if not column_name:
        return None
    return _PROMOTED.get(column_name.strip())


async def capture_candidate(
    pool, column_name: str, standard_field: str,
    factory_id: Optional[str], method: str, confidence: float,
) -> None:
    """非规则映射 (embedding/llm) 解决一个列时记候选。best-effort:
    任何失败只 warn, 绝不让上传/映射因此报错 (fail-open)。"""
    if not (column_name and standard_field and method in ("embedding", "llm")):
        return
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """INSERT INTO smart_bi_field_mapping_candidates
                       (column_name, standard_field, factory_id, method, confidence)
                   VALUES ($1, $2, $3, $4, $5)
                   ON CONFLICT (column_name, standard_field, factory_id) DO UPDATE
                     SET occurrences = smart_bi_field_mapping_candidates.occurrences + 1,
                         last_seen = now(),
                         confidence = GREATEST(smart_bi_field_mapping_candidates.confidence,
                                               EXCLUDED.confidence)""",
                column_name.strip(), standard_field.strip(),
                factory_id, method, round(float(confidence), 3),
            )
    except Exception as e:
        logger.warning("capture_candidate failed (ignored): %s", e)
