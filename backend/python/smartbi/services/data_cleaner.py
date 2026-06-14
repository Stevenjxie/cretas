"""
数据清洗器 - LLM识别问题 + 规则执行清洗 + 自动学习

清洗对象: RawExporter 导出的 CSV/MD/JSON 数据（非原始Excel）

流程:
1. 读取导出的数据（CSV/MD/JSON格式）
2. LLM 分析数据，识别需要清洗的问题
3. 根据 LLM 返回的问题类型，调用对应的规则函数
4. 如果规则不存在，LLM 自动生成新规则并执行（自动学习）
5. 新规则自动保存，下次可直接使用
6. 返回清洗后的数据

自动学习机制:
- 当 LLM 建议的规则不存在时，自动请求 LLM 生成规则代码
- 动态执行生成的规则
- 将新规则保存到 learned_rules.py 文件持久化
"""
from __future__ import annotations

import hashlib
import re
import json
import logging
import time
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Tuple
from datetime import datetime
from pathlib import Path

import httpx

from config import get_settings
from services.raw_exporter import RawExporter, RawSheetData

logger = logging.getLogger(__name__)


class CleaningIssueCache:
    """
    Cache for LLM issue detection results.

    Uses column structure + data types + row count bucket as cache key,
    allowing files with the same structure to reuse detected issues.
    """

    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 300):
        self._cache: Dict[str, dict] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries

    @staticmethod
    def _row_count_bucket(n: int) -> str:
        if n < 50:
            return "<50"
        elif n < 200:
            return "50-200"
        elif n < 1000:
            return "200-1000"
        return "1000+"

    def _generate_key(
        self,
        columns: List[str],
        sample_types: List[str],
        row_count: int,
    ) -> str:
        """Generate cache key from column names, sample data types, and row count bucket."""
        sig = (
            "|".join(sorted(c.lower().strip() for c in columns if c))
            + "||" + "|".join(sample_types)
            + "||" + self._row_count_bucket(row_count)
        )
        return hashlib.md5(sig.encode()).hexdigest()[:16]

    def get(
        self,
        columns: List[str],
        sample_types: List[str],
        row_count: int,
    ) -> Optional[List[dict]]:
        """Return cached issues list or None."""
        key = self._generate_key(columns, sample_types, row_count)
        entry = self._cache.get(key)
        if entry is None:
            return None
        if time.time() - entry["created_at"] > self._ttl_seconds:
            del self._cache[key]
            return None
        entry["accessed_at"] = time.time()
        entry["access_count"] += 1
        logger.debug("CleaningIssueCache hit: %s", key)
        return entry["issues"]

    def set(
        self,
        columns: List[str],
        sample_types: List[str],
        row_count: int,
        issues: List[dict],
    ) -> None:
        """Store issues in cache."""
        key = self._generate_key(columns, sample_types, row_count)
        self._cache[key] = {
            "issues": issues,
            "created_at": time.time(),
            "accessed_at": time.time(),
            "access_count": 1,
        }
        if len(self._cache) > self._max_entries:
            self._evict()
        logger.debug("CleaningIssueCache set: %s", key)

    def _evict(self) -> None:
        """LRU eviction — remove oldest 10 %."""
        if len(self._cache) <= self._max_entries:
            return
        sorted_keys = sorted(
            self._cache, key=lambda k: self._cache[k]["accessed_at"]
        )
        for k in sorted_keys[: max(1, len(sorted_keys) // 10)]:
            del self._cache[k]


# Module-level singleton so it survives across requests
_issue_cache = CleaningIssueCache()


@dataclass
class CleaningIssue:
    """清洗问题"""
    issue_type: str           # 问题类型
    description: str          # 问题描述
    affected_columns: List[str]  # 影响的列
    affected_rows: List[int]  # 影响的行（示例）
    suggested_rule: str       # 建议的清洗规则
    examples: List[str]       # 示例数据
    priority: int = 1         # 优先级


@dataclass
class CleaningResult:
    """清洗结果"""
    success: bool
    issues_found: List[CleaningIssue]
    rules_applied: List[str]
    cleaned_data: List[Dict[str, Any]]
    changes_made: int
    error: Optional[str] = None


class DataCleaner:
    """
    数据清洗器

    LLM识别问题 + 规则执行清洗
    """

    def __init__(self):
        self.settings = get_settings()
        self.raw_exporter = RawExporter()

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

        # 注册清洗规则
        self.rules: Dict[str, Callable] = {
            # 基础清洗
            "trim_spaces": self._rule_trim_spaces,
            "remove_indent": self._rule_remove_indent,
            "extract_level": self._rule_extract_level,
            "remove_fullwidth_spaces": self._rule_remove_fullwidth_spaces,

            # 序号处理
            "remove_chinese_number": self._rule_remove_chinese_number,
            "remove_arabic_number_prefix": self._rule_remove_arabic_number_prefix,

            # 数值处理
            "normalize_number": self._rule_normalize_number,
            "round_precision": self._rule_round_precision,
            "remove_thousand_separator": self._rule_remove_thousand_separator,
            "convert_negative_parentheses": self._rule_convert_negative_parentheses,
            "parse_percentage": self._rule_parse_percentage,

            # 日期处理
            "parse_date": self._rule_parse_date,
            "standardize_date": self._rule_standardize_date,
            "extract_month_from_date": self._rule_extract_month_from_date,

            # 空值处理
            "fill_none_zero": self._rule_fill_none_zero,
            "fill_none_empty": self._rule_fill_none_empty,
            "mark_empty_row": self._rule_mark_empty_row,

            # 文本提取
            "extract_number_from_text": self._rule_extract_number_from_text,
        }

        # 加载已学习的规则
        self._load_learned_rules()

    async def analyze_and_clean(
        self,
        raw_data: RawSheetData,
        structure: Dict[str, Any],
        auto_apply: bool = True
    ) -> CleaningResult:
        """
        分析并清洗数据

        Args:
            raw_data: RawExporter导出的原始数据
            structure: LLM结构分析结果
            auto_apply: 是否自动应用清洗规则

        Returns:
            CleaningResult
        """
        try:
            # 1. LLM识别问题
            issues = await self._llm_identify_issues(raw_data, structure)

            # 2. 提取数据
            data_start = structure.get("data_start_row", 0)
            columns = structure.get("columns", [])

            # 构建列字母到列名的映射
            letter_to_name = {}
            col_names = []
            for i, col_info in enumerate(columns):
                letter = col_info.get("letter", self._idx_to_letter(i))
                name = col_info.get("name") or col_info.get("merged_name") or f"列{i}"
                letter_to_name[letter] = name
                letter_to_name[str(i)] = name  # 也支持索引查找
                col_names.append(name)

            # 构建初始数据
            cleaned_data = []
            for row in raw_data.rows[data_start:]:
                row_dict = {}
                for i, cell in enumerate(row.cells):
                    col_name = col_names[i] if i < len(col_names) else f"列{i}"
                    row_dict[col_name] = cell.value
                    row_dict[f"_原始_{col_name}"] = cell.value  # 保留原始值
                cleaned_data.append(row_dict)

            # 3. 应用清洗规则（含自动学习）
            rules_applied = []
            changes_made = 0
            learned_rules = []

            if auto_apply:
                for issue in issues:
                    rule_name = issue.suggested_rule
                    if not rule_name:
                        continue

                    # 将列字母转换为列名
                    target_columns = []
                    for col_ref in issue.affected_columns:
                        if col_ref in letter_to_name:
                            target_columns.append(letter_to_name[col_ref])
                        elif col_ref in col_names:
                            target_columns.append(col_ref)
                        else:
                            logger.warning(f"Unknown column reference: {col_ref}")

                    if not target_columns:
                        continue

                    # 检查规则是否存在
                    if rule_name in self.rules:
                        rule_func = self.rules[rule_name]
                    else:
                        # 自动学习：生成新规则
                        logger.info(f"Rule '{rule_name}' not found, generating via LLM...")
                        rule_func = await self._generate_and_register_rule(
                            rule_name,
                            issue.issue_type,
                            issue.description,
                            issue.examples
                        )
                        if rule_func:
                            learned_rules.append(rule_name)
                        else:
                            logger.warning(f"Failed to generate rule '{rule_name}'")
                            continue

                    # 执行规则
                    try:
                        changes = rule_func(cleaned_data, target_columns)
                        changes_made += changes
                        rules_applied.append(rule_name)
                        logger.info(f"Applied rule '{rule_name}' to columns {target_columns}, {changes} changes")
                    except Exception as e:
                        logger.error(f"Rule '{rule_name}' execution failed: {e}")

            # 保存学习到的新规则
            if learned_rules:
                self._save_learned_rules(learned_rules)

            return CleaningResult(
                success=True,
                issues_found=issues,
                rules_applied=rules_applied,
                cleaned_data=cleaned_data,
                changes_made=changes_made
            )

        except Exception as e:
            logger.error(f"Cleaning failed: {e}", exc_info=True)
            return CleaningResult(
                success=False,
                issues_found=[],
                rules_applied=[],
                cleaned_data=[],
                changes_made=0,
                error=str(e)
            )

    @staticmethod
    def _extract_cache_params(
        raw_data: RawSheetData,
        structure: Dict[str, Any],
    ) -> Tuple[List[str], List[str], int]:
        """Extract (column_names, sample_types, row_count) for cache keying."""
        columns = [
            c.get("name") or c.get("merged_name") or ""
            for c in structure.get("columns", [])
        ]
        data_start = structure.get("data_start_row", 0)
        row_count = max(0, len(raw_data.rows) - data_start)

        # Derive data types from first data row
        sample_types: List[str] = []
        if data_start < len(raw_data.rows):
            first_row = raw_data.rows[data_start]
            for cell in first_row.cells[: len(columns)]:
                v = cell.value
                if v is None:
                    sample_types.append("null")
                elif isinstance(v, (int, float)):
                    sample_types.append("num")
                elif isinstance(v, str):
                    sample_types.append("str")
                else:
                    sample_types.append("other")

        return columns, sample_types, row_count

    async def _llm_identify_issues(
        self,
        raw_data: RawSheetData,
        structure: Dict[str, Any]
    ) -> List[CleaningIssue]:
        """LLM识别数据质量问题"""

        # --- cache lookup ---
        col_names, sample_types, row_count = self._extract_cache_params(raw_data, structure)
        cached = _issue_cache.get(col_names, sample_types, row_count)
        if cached is not None:
            logger.info("Issue detection cache hit (%d issues)", len(cached))
            return [
                CleaningIssue(
                    issue_type=it.get("issue_type", ""),
                    description=it.get("description", ""),
                    affected_columns=it.get("affected_columns", []),
                    affected_rows=it.get("affected_rows", []),
                    suggested_rule=it.get("suggested_rule", ""),
                    examples=it.get("examples", []),
                    priority=it.get("priority", 2),
                )
                for it in cached
            ]

        # 生成Markdown格式的数据预览
        md_content = self.raw_exporter.to_markdown(raw_data, max_rows=15, truncate=False)

        # P0 出境脱敏: 注册敏感列单元格真名 → 出境占位 (15 行预览含真实客户/门店名).
        try:
            from common.llm_redactor import register_values_for_egress, sensitive_type_for_column
            _data_start = structure.get("data_start_row", 0)
            _vbt = {}
            for _i, _col in enumerate(structure.get("columns", [])):
                _tl = sensitive_type_for_column(_col.get("name") or _col.get("merged_name") or "")
                if not _tl:
                    continue
                _bucket = _vbt.setdefault(_tl, [])
                for _row in raw_data.rows[_data_start:_data_start + 30]:
                    if _i < len(_row.cells):
                        _sv = str(_row.cells[_i]).strip()
                        if _sv and 0 < len(_sv) <= 40 and _sv not in _bucket:
                            _bucket.append(_sv)
            register_values_for_egress(_vbt)
        except Exception:
            pass

        prompt = self._build_issue_detection_prompt(raw_data, structure, md_content)

        try:
            if not self.settings.llm_api_key:
                logger.info("LLM API key not configured, using rule-based detection")
                return self._rule_based_detection(raw_data, structure)

            response = await self._call_llm(prompt)
            from common.llm_redactor import restore_in_scope
            response = restore_in_scope(response)  # P0 还原占位 (清洗示例可能含值)
            issues = self._parse_issue_response(response)

            # --- cache store ---
            _issue_cache.set(
                col_names, sample_types, row_count,
                [
                    {
                        "issue_type": i.issue_type,
                        "description": i.description,
                        "affected_columns": i.affected_columns,
                        "affected_rows": i.affected_rows,
                        "suggested_rule": i.suggested_rule,
                        "examples": i.examples,
                        "priority": i.priority,
                    }
                    for i in issues
                ],
            )
            return issues

        except Exception as e:
            logger.error(f"LLM issue detection failed: {e}")
            return self._rule_based_detection(raw_data, structure)

    def _build_issue_detection_prompt(
        self,
        raw_data: RawSheetData,
        structure: Dict[str, Any],
        md_content: str
    ) -> str:
        """构建问题检测Prompt"""

        columns_info = structure.get("columns", [])
        columns_desc = "\n".join([
            f"- {c.get('letter', '')}: {c.get('name', '')} ({c.get('role', '')}, {c.get('data_type', '')})"
            for c in columns_info
        ])

        available_rules = """
可用的清洗规则:

【基础清洗】
- trim_spaces: 去除首尾空格
- remove_indent: 去除缩进空格（如"  主营业务收入" → "主营业务收入"）
- extract_level: 提取层级（根据缩进空格数计算level字段，保留层级语义）
- remove_fullwidth_spaces: 去除全角空格（中文Excel常见问题）

【序号处理】
- remove_chinese_number: 去除中文序号（如"一、营业收入" → "营业收入"）
- remove_arabic_number_prefix: 去除阿拉伯数字序号（如"1、" "2." "(1)"等）

【数值处理】
- normalize_number: 数字字符串转数字类型
- round_precision: 数值四舍五入（默认保留2位小数，适合财务数据）
- remove_thousand_separator: 去除千分位分隔符（如"1,000,000" → 1000000）
- convert_negative_parentheses: 转换会计格式负数（如"(1000)" → -1000）
- parse_percentage: 百分比转数字（如"17.9%" → 0.179）

【日期处理】
- parse_date: 日期字符串保持原格式
- standardize_date: 日期标准化为 YYYY-MM-DD（处理ISO格式、中文格式等）
- extract_month_from_date: 从日期中提取月份（如"2025-01-01" → "2025-01"）

【空值处理】
- fill_none_zero: 空值填充为0（适用于数值列）
- fill_none_empty: 空值填充为空字符串（适用于文本列）
- mark_empty_row: 标记空行（添加_is_empty_row字段）

【文本提取】
- extract_number_from_text: 从文本中提取数字（如"返点实现7025" → 提取7025）
"""

        prompt = f"""请分析以下Excel数据，识别需要清洗的数据质量问题。

## 数据信息
- Sheet名称: {raw_data.sheet_name}
- 数据起始行: {structure.get('data_start_row', 0)}

## 列信息
{columns_desc}

## 数据预览
{md_content}

{available_rules}

## 分析要求

请检查以下数据质量问题：
1. **空格问题**: 首尾空格、缩进空格（用于表示层级）
2. **序号问题**: 中文序号（一、二、三）、数字序号（1. 2. 3.）
3. **格式问题**: 百分比字符串、日期字符串、数字字符串
4. **空值问题**: None值、空字符串
5. **不一致问题**: 同一列数据格式不统一

## 输出格式

请返回JSON格式，列出发现的问题和建议的清洗规则：
{{
    "issues": [
        {{
            "issue_type": "问题类型（如indent_spaces, chinese_number, percentage_string等）",
            "description": "问题描述",
            "affected_columns": ["受影响的列名"],
            "affected_rows": [受影响的行号示例，最多3个],
            "suggested_rule": "建议使用的清洗规则名称",
            "examples": ["问题数据示例"],
            "priority": 1-3（1最高）
        }}
    ],
    "summary": "数据质量总结"
}}

注意：
- 只报告真正需要清洗的问题
- 如果某个"问题"是有意义的（如缩进表示层级），说明是否需要保留
- suggested_rule 优先使用上面列出的可用规则
- 如果没有合适的现有规则，可以建议一个新规则名（使用下划线命名，如 remove_special_chars），系统会自动学习生成该规则
"""
        return prompt

    async def _call_llm(self, prompt: str) -> str:
        """调用LLM API (经免费链 call_chain — 免费 fallback + 熔断保护)。
        INSIGHTS slot 按 provider 注入免费 model, 此处去掉显式 model 字段。"""
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你是一个数据质量分析专家，擅长识别数据清洗需求。请用JSON格式回复。"
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            "temperature": 0.2,
            "max_tokens": 2000,
            "enable_thinking": False
        }

        with llm_caller_context("data_cleaner_issue_detect"):
            result = await call_chain(SLOT.INSIGHTS, payload)
        return result["choices"][0]["message"]["content"]

    def _parse_issue_response(self, response: str) -> List[CleaningIssue]:
        """解析LLM响应"""
        try:
            # 提取JSON
            json_start = response.find('{')
            json_end = response.rfind('}') + 1
            if json_start >= 0 and json_end > json_start:
                json_str = response[json_start:json_end]
                data = json.loads(json_str)
            else:
                raise ValueError("No JSON found")

            issues = []
            for item in data.get("issues", []):
                issues.append(CleaningIssue(
                    issue_type=item.get("issue_type", ""),
                    description=item.get("description", ""),
                    affected_columns=item.get("affected_columns", []),
                    affected_rows=item.get("affected_rows", []),
                    suggested_rule=item.get("suggested_rule", ""),
                    examples=item.get("examples", []),
                    priority=item.get("priority", 2)
                ))

            return issues

        except Exception as e:
            logger.error(f"Failed to parse LLM response: {e}")
            return []

    # ============================================================
    # 自动学习机制
    # ============================================================

    async def _generate_and_register_rule(
        self,
        rule_name: str,
        issue_type: str,
        description: str,
        examples: List[str]
    ) -> Optional[Callable]:
        """
        自动生成新规则并注册

        Args:
            rule_name: 规则名称
            issue_type: 问题类型
            description: 问题描述
            examples: 问题数据示例

        Returns:
            生成的规则函数，失败返回 None
        """
        try:
            # 构建生成规则的 Prompt
            prompt = self._build_rule_generation_prompt(rule_name, issue_type, description, examples)

            # 调用 LLM 生成规则代码
            response = await self._call_llm_for_rule(prompt)

            # 解析并执行规则代码
            rule_func = self._parse_and_execute_rule(rule_name, response)

            if rule_func:
                # 注册新规则
                self.rules[rule_name] = rule_func
                logger.info(f"Successfully generated and registered new rule: {rule_name}")
                return rule_func

            return None

        except Exception as e:
            logger.error(f"Failed to generate rule '{rule_name}': {e}")
            return None

    def _build_rule_generation_prompt(
        self,
        rule_name: str,
        issue_type: str,
        description: str,
        examples: List[str]
    ) -> str:
        """构建规则生成 Prompt"""
        examples_str = "\n".join([f"  - {ex}" for ex in examples[:5]])

        return f"""请生成一个数据清洗规则函数。

## 规则信息
- 规则名称: {rule_name}
- 问题类型: {issue_type}
- 问题描述: {description}

## 问题数据示例
{examples_str}

## 函数签名要求
```python
def rule_function(data: List[Dict], columns: List[str]) -> int:
    '''
    清洗规则函数

    Args:
        data: 数据列表，每个元素是一个字典（一行数据）
        columns: 需要处理的列名列表

    Returns:
        int: 修改的单元格数量
    '''
    changes = 0
    for row in data:
        for col in columns:
            if col in row:
                # 处理逻辑...
                pass
    return changes
```

## 输出要求
1. 只输出 Python 函数代码，不要包含任何 import 语句
2. 函数名必须是: rule_{rule_name}
3. 必须遵循上面的函数签名
4. 可用模块（已预先导入，直接使用即可）:
   - re: 正则表达式，直接使用 re.sub(), re.match() 等
   - datetime: 日期处理
5. 代码必须安全，不能有危险操作

## 重要限制（违反会导致代码无法执行）
- 禁止: import 语句（任何 import 都会失败）
- 禁止: from ... import 语句
- 禁止: eval, exec, compile, open, os, sys 等
- 只能使用基本 Python 语法和上述已导入的模块

请直接输出函数代码，用 ```python 和 ``` 包裹。不要包含 import 语句！
"""

    async def _call_llm_for_rule(self, prompt: str) -> str:
        """调用 LLM 生成规则代码 (经免费链 call_chain — 免费 fallback + 熔断保护)。
        INSIGHTS slot 按 provider 注入免费 model, 此处去掉显式 model 字段。"""
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你是一个 Python 数据清洗专家。请生成简洁、安全、高效的清洗规则函数。"
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            "temperature": 0.1,
            "max_tokens": 1500,
            "enable_thinking": False
        }

        with llm_caller_context("data_cleaner_rule_gen"):
            result = await call_chain(SLOT.INSIGHTS, payload)
        return result["choices"][0]["message"]["content"]

    def _parse_and_execute_rule(self, rule_name: str, response: str) -> Optional[Callable]:
        """解析 LLM 响应并执行规则代码"""
        try:
            # 提取代码块
            code_match = re.search(r'```python\s*(.*?)\s*```', response, re.DOTALL)
            if not code_match:
                # 尝试直接提取 def 开头的代码
                code_match = re.search(r'(def\s+rule_\w+.*?)(?=\n\n|\Z)', response, re.DOTALL)

            if not code_match:
                logger.error("No valid code found in LLM response")
                return None

            code = code_match.group(1).strip()

            # 安全检查
            if not self._validate_rule_code(code):
                logger.error("Rule code failed security validation")
                return None

            # 执行代码（受限沙箱 — 禁止 __builtins__ 访问）
            safe_globals = {
                '__builtins__': {},  # 阻断 __import__, eval, exec 等内置函数
                're': re,
                'datetime': datetime,
                'List': List,
                'Dict': Dict,
                # 仅暴露安全的内置函数
                'len': len, 'str': str, 'int': int, 'float': float, 'bool': bool,
                'list': list, 'dict': dict, 'set': set, 'tuple': tuple,
                'range': range, 'enumerate': enumerate, 'zip': zip,
                'min': min, 'max': max, 'sum': sum, 'abs': abs, 'round': round,
                'sorted': sorted, 'reversed': reversed, 'filter': filter, 'map': map,
                'any': any, 'all': all, 'isinstance': isinstance,
                'None': None, 'True': True, 'False': False,
            }
            local_namespace = {}
            compiled = compile(code, "<llm-rule>", "exec")
            exec(compiled, safe_globals, local_namespace)

            # 获取函数
            func_name = f"rule_{rule_name}"
            if func_name in local_namespace:
                return local_namespace[func_name]

            # 尝试查找任何 rule_ 开头的函数
            for name, obj in local_namespace.items():
                if name.startswith('rule_') and callable(obj):
                    return obj

            logger.error(f"Function '{func_name}' not found in generated code")
            return None

        except Exception as e:
            logger.error(f"Failed to parse/execute rule code: {e}")
            return None

    def _validate_rule_code(self, code: str) -> bool:
        """验证规则代码安全性 — AST 白名单方式（不可被字符串拼接绕过）"""
        import ast

        # 代码长度限制 — 防止超大生成代码
        if len(code) > 5000:
            logger.warning(f"Rule code too large: {len(code)} chars (limit 5000)")
            return False

        # 必须包含函数定义
        if 'def rule_' not in code:
            logger.warning("Rule code must contain 'def rule_' function")
            return False

        # AST 解析
        try:
            tree = ast.parse(code)
        except SyntaxError as e:
            logger.warning(f"Rule code has syntax error: {e}")
            return False

        # 白名单 AST 节点类型 — 仅允许安全的 Python 子集
        ALLOWED_NODES = {
            # 模块/函数结构
            ast.Module, ast.FunctionDef, ast.arguments, ast.arg,
            ast.Return, ast.Pass,
            # 控制流
            ast.If, ast.For, ast.While, ast.Break, ast.Continue,
            # 表达式
            ast.Expr, ast.Call, ast.Name, ast.Load, ast.Store, ast.Del,
            ast.Constant, ast.Num, ast.Str,  # Num/Str for Python 3.7 compat
            ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare,
            ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow,
            ast.USub, ast.UAdd, ast.Not,
            ast.And, ast.Or,
            ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn, ast.Is, ast.IsNot,
            # 数据结构
            ast.List, ast.Tuple, ast.Dict, ast.Set,
            ast.ListComp, ast.DictComp, ast.SetComp, ast.GeneratorExp,
            ast.comprehension,
            # 索引/切片
            ast.Subscript, ast.Index, ast.Slice,
            # 赋值
            ast.Assign, ast.AugAssign, ast.AnnAssign,
            # 字符串格式化
            ast.JoinedStr, ast.FormattedValue,
            # 条件表达式
            ast.IfExp,
            # 星号解包
            ast.Starred,
        }

        # 禁止的函数名 — 即使在白名单 AST 中也不允许调用
        FORBIDDEN_CALLS = {
            'eval', 'exec', 'compile', 'open', 'input', 'raw_input',
            'getattr', 'setattr', 'delattr', 'globals', 'locals', 'vars',
            'dir', 'type', 'super', '__import__', 'breakpoint',
            'memoryview', 'bytearray',
        }

        for node in ast.walk(tree):
            node_type = type(node)

            # 拒绝不在白名单中的节点类型（如 Import, ImportFrom, Attribute 等）
            if node_type not in ALLOWED_NODES:
                # 特例: 允许 ast.Attribute 但只允许安全的属性访问
                if node_type == ast.Attribute:
                    # 禁止 __dunder__ 属性
                    if node.attr.startswith('_'):
                        logger.warning(f"Forbidden private/dunder attribute access: {node.attr}")
                        return False
                    continue
                logger.warning(f"Forbidden AST node type: {node_type.__name__}")
                return False

            # 检查函数调用是否在禁止列表中
            if node_type == ast.Call and isinstance(node.func, ast.Name):
                if node.func.id in FORBIDDEN_CALLS:
                    logger.warning(f"Forbidden function call: {node.func.id}")
                    return False

        return True

    def _save_learned_rules(self, rule_names: List[str]) -> None:
        """保存学习到的规则到文件"""
        try:
            learned_rules_file = Path(__file__).parent / "learned_rules.py"

            # 读取现有规则（如果存在）
            existing_rules = {}
            if learned_rules_file.exists():
                with open(learned_rules_file, 'r', encoding='utf-8') as f:
                    content = f.read()
                    # 简单解析已有规则
                    existing_rules = self._parse_learned_rules_file(content)

            # 添加新规则
            new_rules = {}
            for rule_name in rule_names:
                if rule_name in self.rules and rule_name not in existing_rules:
                    # 获取规则的源代码（如果可能）
                    func = self.rules[rule_name]  # noqa: F841
                    # 标记为已学习
                    new_rules[rule_name] = {
                        'learned_at': datetime.now().isoformat(),
                        'status': 'active'
                    }

            if new_rules:
                # 追加到文件
                with open(learned_rules_file, 'a', encoding='utf-8') as f:
                    f.write(f"\n# Learned rules at {datetime.now().isoformat()}\n")
                    for rule_name, info in new_rules.items():
                        f.write(f"# Rule: {rule_name}, Status: {info['status']}\n")

                logger.info(f"Saved {len(new_rules)} new learned rules")

        except Exception as e:
            logger.error(f"Failed to save learned rules: {e}")

    def _parse_learned_rules_file(self, content: str) -> Dict[str, Any]:
        """解析已学习规则文件"""
        rules = {}
        for line in content.split('\n'):
            if line.startswith('# Rule:'):
                match = re.search(r'# Rule: (\w+)', line)
                if match:
                    rules[match.group(1)] = True
        return rules

    def _load_learned_rules(self) -> None:
        """加载已学习的规则"""
        try:
            learned_rules_file = Path(__file__).parent / "learned_rules.py"
            if learned_rules_file.exists():
                # 动态导入已学习的规则
                import importlib.util
                spec = importlib.util.spec_from_file_location("learned_rules", learned_rules_file)
                if spec and spec.loader:
                    module = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(module)

                    # 注册规则
                    for name in dir(module):
                        if name.startswith('rule_'):
                            rule_name = name[5:]  # 去掉 'rule_' 前缀
                            self.rules[rule_name] = getattr(module, name)
                            logger.info(f"Loaded learned rule: {rule_name}")

        except Exception as e:
            logger.warning(f"Failed to load learned rules: {e}")

    def _rule_based_detection(
        self,
        raw_data: RawSheetData,
        structure: Dict[str, Any]
    ) -> List[CleaningIssue]:
        """基于规则的问题检测（Fallback）"""
        issues = []
        columns = structure.get("columns", [])
        data_start = structure.get("data_start_row", 0)

        # 检查每列
        for col_idx, col_info in enumerate(columns):
            col_name = col_info.get("name", f"列{col_idx}")
            col_role = col_info.get("role", "")  # noqa: F841

            indent_found = False
            chinese_num_found = False
            percentage_found = False

            for row in raw_data.rows[data_start:data_start + 10]:
                if col_idx >= len(row.cells):
                    continue

                val = row.cells[col_idx].value

                # 缩进空格
                if isinstance(val, str) and val.startswith("  ") and not indent_found:
                    issues.append(CleaningIssue(
                        issue_type="indent_spaces",
                        description=f"列'{col_name}'包含缩进空格，可能表示层级关系",
                        affected_columns=[col_name],
                        affected_rows=[row.row_number],
                        suggested_rule="extract_level",
                        examples=[val],
                        priority=2
                    ))
                    indent_found = True

                # 中文序号
                if isinstance(val, str) and re.match(r'^[一二三四五六七八九十]+、', val) and not chinese_num_found:
                    issues.append(CleaningIssue(
                        issue_type="chinese_number",
                        description=f"列'{col_name}'包含中文序号",
                        affected_columns=[col_name],
                        affected_rows=[row.row_number],
                        suggested_rule="remove_chinese_number",
                        examples=[val],
                        priority=2
                    ))
                    chinese_num_found = True

                # 百分比字符串
                if isinstance(val, str) and re.match(r'^[\d.]+%$', val) and not percentage_found:
                    issues.append(CleaningIssue(
                        issue_type="percentage_string",
                        description=f"列'{col_name}'包含百分比字符串",
                        affected_columns=[col_name],
                        affected_rows=[row.row_number],
                        suggested_rule="parse_percentage",
                        examples=[val],
                        priority=1
                    ))
                    percentage_found = True

        return issues

    # ============================================================
    # 清洗规则实现
    # ============================================================

    def _rule_trim_spaces(self, data: List[Dict], columns: List[str]) -> int:
        """去除首尾空格"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    new_val = row[col].strip()
                    if new_val != row[col]:
                        row[col] = new_val
                        changes += 1
        return changes

    def _rule_remove_indent(self, data: List[Dict], columns: List[str]) -> int:
        """去除缩进空格"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    new_val = row[col].lstrip()
                    if new_val != row[col]:
                        row[col] = new_val
                        changes += 1
        return changes

    def _rule_extract_level(self, data: List[Dict], columns: List[str]) -> int:
        """提取层级（根据缩进）"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    original = row[col]
                    # 计算前导空格数
                    stripped = original.lstrip()
                    indent = len(original) - len(stripped)
                    level = indent // 2  # 每2个空格一个层级

                    if indent > 0:
                        row[col] = stripped
                        row[f"{col}_level"] = level
                        changes += 1
        return changes

    def _rule_remove_chinese_number(self, data: List[Dict], columns: List[str]) -> int:
        """去除中文序号"""
        changes = 0
        pattern = re.compile(r'^([一二三四五六七八九十]+)、\s*')

        chinese_to_num = {
            '一': 1, '二': 2, '三': 3, '四': 4, '五': 5,
            '六': 6, '七': 7, '八': 8, '九': 9, '十': 10
        }

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    match = pattern.match(row[col])
                    if match:
                        chinese_num = match.group(1)
                        num = chinese_to_num.get(chinese_num, 0)
                        row[col] = pattern.sub('', row[col])
                        row[f"{col}_order"] = num
                        changes += 1
        return changes

    def _rule_parse_percentage(self, data: List[Dict], columns: List[str]) -> int:
        """百分比字符串转数字"""
        changes = 0
        pattern = re.compile(r'^([\d.]+)%$')

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    match = pattern.match(row[col])
                    if match:
                        row[col] = float(match.group(1)) / 100
                        changes += 1
        return changes

    def _rule_parse_date(self, data: List[Dict], columns: List[str]) -> int:
        """日期字符串转标准格式"""
        changes = 0
        patterns = [
            (r'(\d{4})-(\d{1,2})-(\d{1,2})', '%Y-%m-%d'),
            (r'(\d{4})/(\d{1,2})/(\d{1,2})', '%Y/%m/%d'),
            (r'(\d{4})年(\d{1,2})月(\d{1,2})日', '%Y年%m月%d日'),
        ]

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    for pattern, fmt in patterns:
                        if re.match(pattern, row[col]):
                            try:
                                # 保持字符串格式，但标准化
                                row[col] = row[col]  # 可以转为datetime如果需要
                                changes += 1
                                break
                            except Exception:
                                pass
        return changes

    def _rule_fill_none_zero(self, data: List[Dict], columns: List[str]) -> int:
        """空值填充为0"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and row[col] is None:
                    row[col] = 0
                    changes += 1
        return changes

    def _rule_fill_none_empty(self, data: List[Dict], columns: List[str]) -> int:
        """空值填充为空字符串"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and row[col] is None:
                    row[col] = ""
                    changes += 1
        return changes

    def _rule_normalize_number(self, data: List[Dict], columns: List[str]) -> int:
        """数字字符串转数字类型"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    try:
                        if '.' in row[col]:
                            row[col] = float(row[col])
                        else:
                            row[col] = int(row[col])
                        changes += 1
                    except ValueError:
                        pass
        return changes

    def _rule_round_precision(self, data: List[Dict], columns: List[str], precision: int = 2) -> int:
        """数值四舍五入到指定精度（默认2位小数，适合财务数据）"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], (int, float)):
                    rounded = round(row[col], precision)
                    if rounded != row[col]:
                        row[col] = rounded
                        changes += 1
        return changes

    def _rule_remove_arabic_number_prefix(self, data: List[Dict], columns: List[str]) -> int:
        """去除阿拉伯数字序号前缀（如 1、 2、 3. 等）"""
        changes = 0
        # 匹配: "1、" "2、" "1." "2." "(1)" "(2)" 等
        pattern = re.compile(r'^[\(（]?\d+[\)）]?[、.．]\s*')

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    new_val = pattern.sub('', row[col])
                    if new_val != row[col]:
                        row[col] = new_val
                        changes += 1
        return changes

    def _rule_remove_fullwidth_spaces(self, data: List[Dict], columns: List[str]) -> int:
        """去除全角空格（中文Excel常见问题）"""
        changes = 0
        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    # 全角空格: \u3000, 全角制表符等
                    new_val = row[col].replace('\u3000', ' ').replace('　', ' ')
                    # 多个空格合并为一个
                    new_val = re.sub(r' +', ' ', new_val).strip()
                    if new_val != row[col]:
                        row[col] = new_val
                        changes += 1
        return changes

    def _rule_standardize_date(self, data: List[Dict], columns: List[str]) -> int:
        """日期格式标准化为 YYYY-MM-DD"""
        changes = 0
        patterns = [
            # ISO格式带时间: 2025-01-01T00:00:00
            (r'(\d{4})-(\d{2})-(\d{2})T\d{2}:\d{2}:\d{2}', r'\1-\2-\3'),
            # 中文格式: 2025年1月1日
            (r'(\d{4})年(\d{1,2})月(\d{1,2})日?', lambda m: f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"),
            # 斜杠格式: 2025/01/01
            (r'(\d{4})/(\d{1,2})/(\d{1,2})', lambda m: f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"),
        ]

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    original = row[col]
                    for pattern, replacement in patterns:
                        if re.match(pattern, original):
                            if callable(replacement):
                                new_val = re.sub(pattern, replacement, original)
                            else:
                                new_val = re.sub(pattern, replacement, original)
                            if new_val != original:
                                row[col] = new_val
                                changes += 1
                            break
        return changes

    def _rule_extract_month_from_date(self, data: List[Dict], columns: List[str]) -> int:
        """从日期中提取月份（如 2025-01-01 → 2025-01）"""
        changes = 0
        pattern = re.compile(r'(\d{4}[-/年]?\d{1,2})[-/月]?\d{1,2}')

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    match = pattern.match(row[col])
                    if match:
                        month_str = match.group(1).replace('年', '-').replace('/', '-')
                        if month_str != row[col]:
                            row[col] = month_str
                            row[f"{col}_month"] = month_str
                            changes += 1
        return changes

    def _rule_mark_empty_row(self, data: List[Dict], columns: List[str]) -> int:
        """标记空行（添加 _is_empty_row 字段）"""
        changes = 0
        for row in data:
            non_empty = 0
            for col in columns:
                if col in row and row[col] is not None and row[col] != '':
                    non_empty += 1
            if non_empty == 0:
                row['_is_empty_row'] = True
                changes += 1
            else:
                row['_is_empty_row'] = False
        return changes

    def _rule_extract_number_from_text(self, data: List[Dict], columns: List[str]) -> int:
        """从文本中提取数字（如 "返点实现7025" → 提取 7025）"""
        changes = 0
        pattern = re.compile(r'[\d.]+')

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    numbers = pattern.findall(row[col])
                    if numbers:
                        # 取最后一个数字（通常是金额）
                        try:
                            extracted = float(numbers[-1]) if '.' in numbers[-1] else int(numbers[-1])
                            row[f"{col}_extracted_number"] = extracted
                            changes += 1
                        except ValueError:
                            pass
        return changes

    def _rule_convert_negative_parentheses(self, data: List[Dict], columns: List[str]) -> int:
        """转换会计格式负数：(1000) → -1000"""
        changes = 0
        pattern = re.compile(r'^\(([\d,.]+)\)$')

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    match = pattern.match(row[col].strip())
                    if match:
                        try:
                            num_str = match.group(1).replace(',', '')
                            row[col] = -float(num_str)
                            changes += 1
                        except ValueError:
                            pass
        return changes

    def _rule_remove_thousand_separator(self, data: List[Dict], columns: List[str]) -> int:
        """去除千分位分隔符：1,000,000 → 1000000"""
        changes = 0
        pattern = re.compile(r'^-?[\d,]+\.?\d*$')

        for row in data:
            for col in columns:
                if col in row and isinstance(row[col], str):
                    val = row[col].strip()
                    if ',' in val and pattern.match(val):
                        try:
                            new_val = val.replace(',', '')
                            row[col] = float(new_val) if '.' in new_val else int(new_val)
                            changes += 1
                        except ValueError:
                            pass
        return changes

    def _idx_to_letter(self, idx: int) -> str:
        """列索引转Excel列字母"""
        result = ""
        while idx >= 0:
            result = chr(ord('A') + idx % 26) + result
            idx = idx // 26 - 1
        return result

    def to_dict(self, result: CleaningResult) -> Dict[str, Any]:
        """转换结果为字典"""
        return {
            "success": result.success,
            "error": result.error,
            "issues_found": [
                {
                    "type": i.issue_type,
                    "description": i.description,
                    "columns": i.affected_columns,
                    "rows": i.affected_rows,
                    "rule": i.suggested_rule,
                    "examples": i.examples,
                    "priority": i.priority
                }
                for i in result.issues_found
            ],
            "rules_applied": result.rules_applied,
            "changes_made": result.changes_made,
            "cleaned_data_sample": result.cleaned_data[:5] if result.cleaned_data else []
        }

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""


# 便捷函数
async def clean_data(
    raw_data: RawSheetData,
    structure: Dict[str, Any],
    auto_apply: bool = True
) -> CleaningResult:
    """清洗数据"""
    cleaner = DataCleaner()
    try:
        return await cleaner.analyze_and_clean(raw_data, structure, auto_apply)
    finally:
        await cleaner.close()
