"""字段映射毕业闭环: Capture(候选) → Promote(gate+人审) → Consult(规则层先查)。

绝不静默自动毕业 (Promote 工具 --apply 是人审后显式执行)。curated 冲突时 curated 赢。
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Dict, Tuple

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
