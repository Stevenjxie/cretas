"""Customer-facing text safety for restaurant AI responses."""
from __future__ import annotations

import re
from typing import Optional

_INTERNAL_IDENTIFIER = re.compile(r"\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\b")
_API_PATH = re.compile(r"/api/[A-Za-z0-9_./?=&%\-]+")
_TOOL_EXPLANATION = re.compile(
    r"(?:通过|经由)?\s*(?:调用|使用)\s*[^，。；\n]{0,80}?(?:工具|接口|数据表)(?:来|进行|获取|查询)?"
)
_TECH_ONLY = re.compile(
    r"^[\s，。；：、]*(?:(?:来源|内部意图|意图|与|和|来自)[\s，。；：、]*)*$"
)


def sanitize_customer_ai_text(value: Optional[str]) -> str:
    """Remove implementation details while preserving business names and facts."""
    if not value:
        return ""
    cleaned_lines = []
    for raw_line in str(value).splitlines():
        line = _TOOL_EXPLANATION.sub("", raw_line)
        line = _API_PATH.sub("", line)
        line = _INTERNAL_IDENTIFIER.sub("", line)
        line = re.sub(r"\bGold\b", "", line, flags=re.IGNORECASE)
        line = re.sub(r"\bmaterialize\b", "数据准备", line, flags=re.IGNORECASE)
        line = re.sub(r"\bETL\b", "数据整理", line, flags=re.IGNORECASE)
        line = re.sub(r"\bLLM\b", "智能分析", line, flags=re.IGNORECASE)
        line = re.sub(r"\bJSON\b", "数据格式", line, flags=re.IGNORECASE)
        line = re.sub(r"\bPOS\b", "收银", line, flags=re.IGNORECASE)
        line = re.sub(r"(?:内部)?意图(?:代码)?\s*[：:]?\s*", "", line)
        line = re.sub(r"(?:来源|读取自|查询自)\s*(?=[，。；\n]|$)", "", line)
        line = re.sub(r"[ \t]+([，。；：])", r"\1", line).strip(" ，；：")
        if line and not _TECH_ONLY.fullmatch(line):
            cleaned_lines.append(line)
    cleaned = "\n".join(cleaned_lines).strip()
    return cleaned or "分析已完成，请查看业务结果。"
