"""通用 metric 注册 + 阈值引擎 + playbook 自动触发

读 knowledge/{domain}/diagnostics_registry.yaml 注册的 metric, 实测对比 benchmark,
触发对应 playbook YAML, 不需要写 Python 代码新增 metric.

设计原则:
  - 一个 DiagnosticsEngine 实例对应一个 (domain, sub_sector) 组合
  - threshold_source 引用 benchmark YAML, threshold_inline 跨子行业通用
  - playbook 自动加载 (复用 knowledge/loader.py 的 IndustryKnowledgeBase)
  - 输出结构化 Diagnosis 对象 (含建议文案 + severity + playbook 引用)

使用示例:
    >>> engine = DiagnosticsEngine(domain="restaurant", sub_sector="火锅")
    >>> diagnoses = engine.run({
    ...     "food_cost_ratio": 0.46,    # 46% (高于火锅 38-48 范围上限附近)
    ...     "labor_cost_ratio": 0.34,   # 34%
    ...     "cost_rigidity": 0.56,      # 弹性 0.56 (邓总场景)
    ... })
    >>> for d in diagnoses:
    ...     print(d.metric_name_zh, d.status, d.severity)
    成本弹性指数 critical critical
    人力成本率 warning warning
    ...
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal, Optional

import yaml

logger = logging.getLogger(__name__)


# ── Type definitions ────────────────────────────────────────

Domain = Literal["restaurant", "factory"]
Status = Literal["健康", "偏低", "正常", "偏高", "严重偏高", "严重偏低", "异常"]
Severity = Literal["info", "warning", "critical"]


@dataclass
class RxAction:
    """Structured prescription action from a playbook.

    Replaces the pre-3.7 free-form text suggestion lists. Every field
    is required (validated at YAML load time). Frontend renders these
    as rich action cards with owner badge, timeframe chip, priority
    indicator, effort stars.
    """
    id: str                         # e.g. "CR-A01" (playbook prefix + sequence)
    title: str                      # short action name
    description: str                # detailed rationale
    owner: str                      # 负责角色: 店长 / 运营经理 / 中央厨房 / 等
    timeframe: str                  # e.g. "本周内", "2 周", "Q2 结束前"
    priority: str                   # "P0" | "P1" | "P2"
    effort: str                     # "low" | "medium" | "high"
    expected_impact: str            # natural language expected outcome

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "description": self.description,
            "owner": self.owner,
            "timeframe": self.timeframe,
            "priority": self.priority,
            "effort": self.effort,
            "expectedImpact": self.expected_impact,
        }


@dataclass
class Diagnosis:
    """单个 metric 的诊断结果"""
    metric_key: str                          # 例: 'food_cost_ratio'
    metric_name_zh: str                      # 例: '食材成本率'
    actual_value: float
    benchmark_median: Optional[float]
    benchmark_range: Optional[tuple[float, float]]
    threshold_source: str                    # 'benchmark' 或 'inline'
    higher_is_worse: bool
    status: Status
    severity: Severity
    delta_pp: float                          # 与中位数偏差 (绝对值, 单位: 百分点 if metric is %, else absolute)
    delta_pct: float                         # 与中位数相对偏差 (百分比)
    description_zh: str                      # 给客户看的诊断说明
    suggestion_zh: list[str]                 # 行动建议 (来自 playbook, 向后兼容文本格式)
    playbook_id: Optional[str] = None
    playbook_url_zh: Optional[str] = None
    sub_sector_notes: list[str] = field(default_factory=list)
    formula_zh: Optional[str] = None         # 公式说明 (来自 registry)
    rx_actions: list[RxAction] = field(default_factory=list)  # P3.7 结构化处方行动

    def to_dict(self) -> dict:
        return {
            "metricKey": self.metric_key,
            "metricNameZh": self.metric_name_zh,
            "actualValue": self.actual_value,
            "benchmarkMedian": self.benchmark_median,
            "benchmarkRange": list(self.benchmark_range) if self.benchmark_range else None,
            "thresholdSource": self.threshold_source,
            "higherIsWorse": self.higher_is_worse,
            "status": self.status,
            "severity": self.severity,
            "deltaPp": self.delta_pp,
            "deltaPct": self.delta_pct,
            "descriptionZh": self.description_zh,
            "suggestionZh": self.suggestion_zh,
            "playbookId": self.playbook_id,
            "playbookUrlZh": self.playbook_url_zh,
            "subSectorNotes": self.sub_sector_notes,
            "formulaZh": self.formula_zh,
            "rxActions": [a.to_dict() for a in self.rx_actions],
        }


# ── Main engine ─────────────────────────────────────────────


class DiagnosticsEngine:
    """跨 domain 通用诊断引擎

    每个 (domain, sub_sector) 组合应该实例化一个 engine, 共享 metric 注册表 + benchmark 缓存.
    """

    def __init__(
        self,
        domain: Domain,
        sub_sector: str = "",
        kb_root: Optional[Path] = None,
    ):
        if domain not in ("restaurant", "factory"):
            raise ValueError(
                f"domain 必须是 'restaurant' 或 'factory', 收到 {domain!r}. "
                f"跨 domain 诊断会污染数据."
            )

        self.domain: Domain = domain
        self.sub_sector = sub_sector
        self.kb_root = kb_root or (Path(__file__).parent.parent / "knowledge" / domain)

        self._registry: dict = {}
        self._benchmarks: dict = {}
        self._playbooks_cache: dict[str, dict] = {}
        self._load_registry()
        if sub_sector:
            self._load_benchmarks()

    # ── Public API ─────────────────────────────────────

    def run(self, metrics: dict[str, float]) -> list[Diagnosis]:
        """对一组实测 metric 跑全量诊断

        Args:
            metrics: {metric_key: actual_value}, 例 {"food_cost_ratio": 0.46}

        Returns:
            按 severity 降序排列的 Diagnosis 列表 (critical → warning → info, 健康项不返回)
        """
        diagnoses: list[Diagnosis] = []
        for metric_key, actual in metrics.items():
            d = self.evaluate_one(metric_key, actual)
            if d is not None:
                diagnoses.append(d)

        severity_order = {"critical": 0, "warning": 1, "info": 2}
        diagnoses.sort(key=lambda d: (severity_order.get(d.severity, 99), -abs(d.delta_pct)))
        return diagnoses

    def evaluate_one(self, metric_key: str, actual_value: float) -> Optional[Diagnosis]:
        """单 metric 诊断, 返回 None 当 metric 健康或未注册

        Args:
            metric_key: 指标 key, 必须在 diagnostics_registry.yaml 注册
            actual_value: 实测值

        Returns:
            Diagnosis 或 None (健康/无需诊断)
        """
        metric_def = self._registry.get("metrics", {}).get(metric_key)
        if metric_def is None:
            logger.debug(f"[{self.domain}] metric {metric_key} 未在 registry 中注册")
            return None

        name_zh = metric_def.get("name_zh", metric_key)
        formula = metric_def.get("formula", "")
        higher_is_worse = metric_def.get("higher_is_worse", True)
        playbook_id = metric_def.get("playbook")
        description = metric_def.get("description_zh", "")

        # 解析阈值: 优先 inline, 退回 benchmark
        threshold_inline = metric_def.get("threshold_inline")
        threshold_source_def = metric_def.get("threshold_source")

        if threshold_inline:
            status, severity = self._evaluate_inline_threshold(
                actual_value, threshold_inline, higher_is_worse
            )
            benchmark_median = None
            benchmark_range = None
            threshold_source = "inline"
        elif threshold_source_def:
            benchmark_metric = self._resolve_benchmark_metric(metric_key, threshold_source_def)
            if benchmark_metric is None:
                logger.warning(
                    f"[{self.domain}] {metric_key}: threshold_source 指向不存在的 benchmark"
                )
                return None
            status, severity = self._evaluate_benchmark_threshold(
                actual_value, benchmark_metric, higher_is_worse
            )
            benchmark_median = benchmark_metric.get("median")
            range_list = benchmark_metric.get("range")
            benchmark_range = tuple(range_list) if range_list and len(range_list) == 2 else None
            threshold_source = "benchmark"
        else:
            logger.warning(
                f"[{self.domain}] {metric_key}: 既无 threshold_inline 又无 threshold_source, 跳过"
            )
            return None

        # 健康项不返回 (减少噪音)
        if severity == "info" and status in ("健康", "正常"):
            return None

        # 算偏差
        if benchmark_median is not None:
            delta_pp = actual_value - benchmark_median
            delta_pct = (
                (actual_value - benchmark_median) / benchmark_median * 100
                if benchmark_median != 0
                else 0
            )
        else:
            delta_pp = 0.0
            delta_pct = 0.0

        # 加载 playbook
        playbook_data: Optional[dict] = None
        suggestion_list: list[str] = []
        sub_sector_notes: list[str] = []
        playbook_url: Optional[str] = None
        rx_actions: list[RxAction] = []

        # 🔴 偏离方向决定 playbook 能不能挂。
        #
        # playbook 是**单向**的: `ingredient_waste_rate_high` 里全是「削减过量采购」
        # 「减少过期报废」这类降损耗的话。而 metric 偏离到**好**的一侧时(损耗率
        # 3.75% 低于行业区间 [8,25]), 原样挂上去就是在劝一个已经做得比标杆好的人
        # 继续压这个指标 —— 2026-08-06 prod 实测 MOCK_REST 正是如此:
        # status="偏低" / severity="info", 却带着一整套降损耗建议。
        #
        # 根因是这里挂 playbook 时不看方向, 只看 playbook_id 存不存在。
        #
        # ⚠️ 不能简单地跟"健康/正常"一样 return None: 对 higher_is_worse 的指标,
        # **异常低往往不是管得好, 而是没登记**(损耗没录、报废没走系统)。把它当成
        # 优秀直接吞掉, 就是把"没采集到"渲染成了"正常" —— 所以照常返回诊断,
        # 只是换成方向正确的话, 并明确提示先核对采集完整性。
        deviates_favourably = (
            (higher_is_worse and status in ("偏低", "严重偏低"))
            or (not higher_is_worse and status in ("偏高", "严重偏高"))
        )

        if playbook_id and not deviates_favourably:
            playbook_data = self._load_playbook(playbook_id)
            if playbook_data:
                suggestion_list = self._extract_suggestions(playbook_data, severity)
                sub_sector_notes = self._extract_sub_sector_notes(playbook_data)
                playbook_url = f"playbooks/{playbook_id}.yaml"
                rx_actions = self._extract_rx_actions(playbook_data)
        elif deviates_favourably:
            # 方向正确时给方向正确的话。不挂 playbook_url / rx_actions ——
            # 那些处方是为"指标变差"写的, 挂上去等于给了一份不该执行的动作清单。
            suggestion_list = [
                f"{name_zh}优于行业区间，当前无需针对性动作。",
                "先核对该指标的数据采集是否完整——异常优于同行时，"
                "更常见的原因是漏记录而不是管理更好。",
            ]

        # 渲染描述文案
        description_zh = self._render_description(
            metric_def, name_zh, actual_value, benchmark_median, status, severity, description
        )

        return Diagnosis(
            metric_key=metric_key,
            metric_name_zh=name_zh,
            actual_value=actual_value,
            benchmark_median=benchmark_median,
            benchmark_range=benchmark_range,
            threshold_source=threshold_source,
            higher_is_worse=higher_is_worse,
            status=status,
            severity=severity,
            delta_pp=round(delta_pp, 4),
            delta_pct=round(delta_pct, 2),
            description_zh=description_zh,
            suggestion_zh=suggestion_list,
            playbook_id=playbook_id,
            playbook_url_zh=playbook_url,
            sub_sector_notes=sub_sector_notes,
            formula_zh=formula,
            rx_actions=rx_actions,
        )

    def list_registered_metrics(self) -> list[str]:
        """列出所有已注册 metric (调试用)"""
        return list(self._registry.get("metrics", {}).keys())

    # ── 内部: 加载 registry / benchmarks / playbooks ───

    def _load_registry(self) -> None:
        """加载 diagnostics_registry.yaml"""
        path = self.kb_root / "diagnostics_registry.yaml"
        if not path.exists():
            logger.warning(f"diagnostics_registry.yaml 不存在: {path}")
            self._registry = {"metrics": {}}
            return
        try:
            with open(path, encoding="utf-8") as f:
                self._registry = yaml.safe_load(f) or {"metrics": {}}
            # 用 debug 级别避免 startup 外 request-context 的 correlation_id KeyError
            logger.debug(
                f"[{self.domain}] 加载 {len(self._registry.get('metrics', {}))} 个 metric "
                f"from {path.name}"
            )
        except Exception as e:
            logger.error(f"加载 diagnostics_registry.yaml 失败: {e}")
            self._registry = {"metrics": {}}

    def _load_benchmarks(self) -> None:
        """加载 benchmarks/_common.yaml + sub_sector.yaml, 合并"""
        common_path = self.kb_root / "benchmarks" / "_common.yaml"
        sub_path = self.kb_root / "benchmarks" / f"{self.sub_sector}.yaml"

        common = {}
        sub = {}

        if common_path.exists():
            try:
                with open(common_path, encoding="utf-8") as f:
                    common = yaml.safe_load(f) or {}
            except Exception as e:
                logger.error(f"加载 _common.yaml 失败: {e}")

        if sub_path.exists():
            try:
                with open(sub_path, encoding="utf-8") as f:
                    sub = yaml.safe_load(f) or {}
            except Exception as e:
                logger.error(f"加载 {self.sub_sector}.yaml 失败: {e}")

        # 合并: sub_sector 覆盖 common
        merged_metrics = {}
        for key, val in (common.get("metrics") or {}).items():
            merged_metrics[key] = dict(val)
        for key, val in (sub.get("metrics") or {}).items():
            if key in merged_metrics:
                merged_metrics[key].update(val)
            else:
                merged_metrics[key] = dict(val)

        self._benchmarks = {
            "sub_sector": self.sub_sector,
            "metrics": merged_metrics,
        }
        logger.debug(
            f"[{self.domain}] 加载 {len(merged_metrics)} 个 benchmark metric "
            f"for sub_sector={self.sub_sector}"
        )

    def _load_playbook(self, playbook_id: str) -> Optional[dict]:
        """加载 playbooks/{playbook_id}.yaml (带缓存)"""
        if playbook_id in self._playbooks_cache:
            return self._playbooks_cache[playbook_id]

        path = self.kb_root / "playbooks" / f"{playbook_id}.yaml"
        if not path.exists():
            logger.warning(f"playbook 不存在: {path}")
            self._playbooks_cache[playbook_id] = None
            return None

        try:
            with open(path, encoding="utf-8") as f:
                playbook = yaml.safe_load(f) or {}
            self._playbooks_cache[playbook_id] = playbook
            return playbook
        except Exception as e:
            logger.error(f"加载 playbook {playbook_id} 失败: {e}")
            return None

    # ── 内部: 阈值评估 ─────────────────────────────────

    def _resolve_benchmark_metric(
        self, metric_key: str, threshold_source: str
    ) -> Optional[dict]:
        """解析 threshold_source 字符串, 返回对应 benchmark metric 定义

        threshold_source 格式: "benchmarks/{sub_sector}.yaml#metrics.food_cost_ratio"
        但实际我们直接从已合并的 self._benchmarks 找
        """
        # 简单处理: 直接从 metric_key 找
        # threshold_source 字符串末尾通常就是 metrics.{metric_key}
        match = re.search(r"metrics\.(\w+)$", threshold_source)
        target_key = match.group(1) if match else metric_key

        return self._benchmarks.get("metrics", {}).get(target_key)

    def _evaluate_benchmark_threshold(
        self,
        actual: float,
        benchmark_metric: dict,
        higher_is_worse: bool,
    ) -> tuple[Status, Severity]:
        """根据 benchmark range 评估 status + severity

        逻辑:
            higher_is_worse=True (越高越糟, 如食材成本率):
              actual > range_high + 5pp → 严重偏高 (critical)
              actual > range_high       → 偏高 (warning)
              actual ∈ range            → 正常 (info)
              actual < range_low        → 偏低 (info, 可能是好事)

            higher_is_worse=False (越低越糟, 如翻台率):
              actual < range_low - 1.0  → 严重偏低 (critical)
              actual < range_low        → 偏低 (warning)
              actual ∈ range            → 正常 (info)
              actual > range_high       → 偏高 (info, 通常是好事)
        """
        range_list = benchmark_metric.get("range")
        if not range_list or len(range_list) != 2:
            return ("正常", "info")

        low, high = float(range_list[0]), float(range_list[1])
        range_width = high - low
        # 严重阈值: 超过/低于 range 端点 + 范围宽度的 25% (适配不同量级)
        severe_offset = max(range_width * 0.25, 1.0)

        if higher_is_worse:
            if actual > high + severe_offset:
                return ("严重偏高", "critical")
            if actual > high:
                return ("偏高", "warning")
            if actual < low:
                return ("偏低", "info")
            return ("正常", "info")
        else:
            if actual < low - severe_offset:
                return ("严重偏低", "critical")
            if actual < low:
                return ("偏低", "warning")
            if actual > high:
                return ("偏高", "info")
            return ("正常", "info")

    def _evaluate_inline_threshold(
        self,
        actual: float,
        threshold_inline: dict,
        higher_is_worse: bool = False,
    ) -> tuple[Status, Severity]:
        """根据 inline 阈值表达式评估

        threshold_inline 示例:
            healthy: ">= 0.85"
            warning: ">= 0.5 and < 0.85"
            critical: "< 0.5"

        status 文案派生方向必须跟随 higher_is_worse:
            higher_is_worse=True (越高越糟, 如外卖依赖度/折扣率):
              warning  → '偏高'      (值太高)
              critical → '严重偏高'
            higher_is_worse=False (越低越糟, 如成本弹性/渠道收款率):
              warning  → '偏低'      (值太低)
              critical → '严重偏低'

        历史 bug: 旧实现硬编码 warning→'偏低' / critical→'严重偏低'|'异常',
        不看 higher_is_worse → higher-is-worse 指标卡片显 '偏低' 与诊断标题
        "外卖依赖度偏高" 直接矛盾 (误导客户). 现按 higher_is_worse 派生.

        Returns:
            (status, severity)
        """
        warning_status: Status = "偏高" if higher_is_worse else "偏低"
        critical_status: Status = "严重偏高" if higher_is_worse else "严重偏低"

        # 按严重度顺序检查 (先 critical, 再 warning, 再 healthy)
        for severity_name in ("critical", "warning", "healthy"):
            condition = threshold_inline.get(severity_name)
            if not condition:
                continue
            if self._eval_condition(actual, condition):
                if severity_name == "critical":
                    return (critical_status, "critical")
                elif severity_name == "warning":
                    return (warning_status, "warning")
                else:
                    return ("健康", "info")

        return ("正常", "info")

    def _eval_condition(self, actual: float, condition: str) -> bool:
        """安全求值条件表达式

        支持: ">= X", "< X", ">= X and < Y", "> X or < Y"
        不支持: 任意 Python 表达式 (避免 eval 安全问题)
        """
        condition = condition.strip()

        # 拆 'and' / 'or'
        if " and " in condition:
            parts = condition.split(" and ")
            return all(self._eval_simple_condition(actual, p.strip()) for p in parts)
        if " or " in condition:
            parts = condition.split(" or ")
            return any(self._eval_simple_condition(actual, p.strip()) for p in parts)

        return self._eval_simple_condition(actual, condition)

    def _eval_simple_condition(self, actual: float, cond: str) -> bool:
        """求值单个比较表达式, 例 '>= 0.85'"""
        cond = cond.strip()
        match = re.match(r"^(>=|<=|>|<|==|!=)\s*([+-]?\d+(?:\.\d+)?)$", cond)
        if not match:
            logger.warning(f"无法解析条件 {cond!r}")
            return False
        op, num_str = match.groups()
        num = float(num_str)
        if op == ">=":
            return actual >= num
        if op == "<=":
            return actual <= num
        if op == ">":
            return actual > num
        if op == "<":
            return actual < num
        if op == "==":
            return actual == num
        if op == "!=":
            return actual != num
        return False

    # ── 内部: 文案/建议提取 ───────────────────────────

    def _extract_suggestions(
        self, playbook: dict, severity: Severity
    ) -> list[str]:
        """从 playbook 提取行动建议 (按 severity 过滤)

        优先级:
            - critical → 取所有 P0 actions
            - warning → 取 P0 + P1 actions
            - info → 取所有
        """
        action_plans = playbook.get("action_plans") or []
        if not action_plans:
            return []

        priority_filter = {
            "critical": ["P0"],
            "warning": ["P0", "P1"],
            "info": ["P0", "P1", "P2"],
        }.get(severity, ["P0", "P1", "P2"])

        suggestions: list[str] = []
        for plan in action_plans:
            if not isinstance(plan, dict):
                continue
            if plan.get("priority") not in priority_filter:
                continue
            plan_name = plan.get("name", "")
            actions = plan.get("actions") or []
            for action in actions[:3]:  # 每个 plan 最多取 3 条
                suggestions.append(f"[{plan_name}] {action}")
            if len(suggestions) >= 6:
                break
        return suggestions[:6]

    def _extract_rx_actions(self, playbook: dict) -> list[RxAction]:
        """从 playbook 提取结构化 RxAction 列表 (P3.7 新增)

        读取 playbook YAML 顶层的 rx_actions 列表. 每个条目必须包含全部 8 个字段.
        YAML 中缺失必填字段的条目会被跳过并记录 warning.
        """
        raw_rx = playbook.get("rx_actions") or []
        if not raw_rx:
            return []

        required_fields = ("id", "title", "description", "owner", "timeframe",
                           "priority", "effort", "expected_impact")
        actions: list[RxAction] = []
        for item in raw_rx:
            if not isinstance(item, dict):
                logger.warning(f"rx_actions 条目不是 dict, 跳过: {item!r}")
                continue
            missing = [f for f in required_fields if not item.get(f)]
            if missing:
                logger.warning(
                    f"rx_actions 条目缺失字段 {missing}, 跳过: {item.get('id', '?')!r}"
                )
                continue
            actions.append(RxAction(
                id=str(item["id"]),
                title=str(item["title"]),
                description=str(item["description"]),
                owner=str(item["owner"]),
                timeframe=str(item["timeframe"]),
                priority=str(item["priority"]),
                effort=str(item["effort"]),
                expected_impact=str(item["expected_impact"]),
            ))
        return actions

    def _extract_sub_sector_notes(self, playbook: dict) -> list[str]:
        """提取当前 sub_sector 的专属提示"""
        if not self.sub_sector:
            return []
        notes_dict = playbook.get("sub_sector_notes") or {}
        notes = notes_dict.get(self.sub_sector)
        if isinstance(notes, list):
            return notes[:5]
        return []

    def _render_description(
        self,
        metric_def: dict,
        name_zh: str,
        actual: float,
        benchmark_median: Optional[float],
        status: Status,
        severity: Severity,
        fallback_description: str,
    ) -> str:
        """生成给客户看的诊断描述文案"""
        # 优先使用 registry 里的 description
        if fallback_description:
            return fallback_description

        # 否则模板生成
        if benchmark_median is not None:
            return (
                f"{name_zh} 当前为 {self._format_value(actual)}, "
                f"行业基准中位数 {self._format_value(benchmark_median)}, "
                f"状态: {status}"
            )
        return f"{name_zh} 当前为 {self._format_value(actual)}, 状态: {status}"

    @staticmethod
    def _format_value(v: float) -> str:
        """格式化数值显示

        - 0-1 之间的数当作百分比 → 显示 X.X%
        - 否则保留 2 位小数
        """
        if 0 <= v <= 1:
            return f"{v * 100:.1f}%"
        return f"{v:.2f}"
