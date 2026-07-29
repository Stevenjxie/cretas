"""批量执行「计划」—— 报告的全部取数逻辑就是这一个 for 循环。

统一原则 R1: 报告执行的是**和聊天框一模一样**的 sealed QuerySpec。所以这里:

* 没有 SQL，没有 resolver 调用，没有 metric 计算 —— 一行都没有；
* 唯一的取数动作是 ``await answer_fn(query, pool, factory_id, role)``，
  ``answer_fn`` 默认就是 ``restaurant_intent_service.tiered_answer``；
* 因此 Answer Contract 11 项校验、fail-closed 澄清、计划缓存与晋升表的零
  token 出口、capture 飞轮采集，报告一样吃得到，不需要复制任何一份。

⛔ fail-closed 语义 (禁止降级处理):
执行链对一节返回 ``None`` / ``clarification`` / ``contract_pass=False``，
都算这一节**没拿到可信数据**。此时整份报告不生成，
:class:`~.errors.ReportGenerationError` 把每一节的失败原因原样带出去。
没有「跳过这一节继续排版」、没有「填 0」、没有「用上月数顶上」这些分支 ——
翻遍本文件也找不到写默认值的地方。
"""
from __future__ import annotations

import calendar
import logging
import re
from datetime import date, datetime
from typing import Any, Awaitable, Callable, Dict, List, Optional, Sequence, Tuple

from .errors import ReportGenerationError, SectionFailure
from .freshness import resolve_freshness
from .model import DataFreshness, KpiBlock, MonthlyReport, ReportSection
from .tabular import charts_to_tables
from .template import DEFAULT_MONTHLY_TEMPLATE, ReportTemplate, SectionTemplate

logger = logging.getLogger(__name__)

AnswerFn = Callable[..., Awaitable[Optional[Dict[str, Any]]]]

_PERIOD_RE = re.compile(r"^(\d{4})-(\d{1,2})$")


def _default_answer_fn() -> AnswerFn:
    # 延迟导入: 报告模块被 import 时不该把整条餐饮意图链拖起来 (也让测试可以
    # 完全不碰 DB / LLM 地注入自己的 answer_fn)。
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    return tiered_answer


def parse_period(period: Optional[str], as_of_date: str) -> Tuple[str, str, str]:
    """``'2026-06'`` → ``('2026-06', '2026年6月', '2026-06-01~2026-06-30')``。

    ``period`` 省略时取**数据截至日期所在的月份**，而不是"今天所在的月份" ——
    7 月 1 号跑报告，数据只到 6/28，默认给的应该是 6 月报告；给一份空的 7 月
    报告才是最典型的降级。
    """
    if period:
        m = _PERIOD_RE.match(period.strip())
        if not m:
            raise ReportGenerationError(
                f"报告周期格式非法：{period!r}，应为 YYYY-MM（如 2026-06）。",
                code="REPORT_BAD_PERIOD",
            )
        year, month = int(m.group(1)), int(m.group(2))
        if not 1 <= month <= 12:
            raise ReportGenerationError(
                f"报告周期月份非法：{period!r}。", code="REPORT_BAD_PERIOD",
            )
    else:
        anchor = date.fromisoformat(as_of_date)
        year, month = anchor.year, anchor.month
    last_day = calendar.monthrange(year, month)[1]
    normalized = f"{year:04d}-{month:02d}"
    label = f"{year}年{month}月"
    span = f"{normalized}-01 ~ {normalized}-{last_day:02d}"
    return normalized, label, span


def _kpis_from(result: Dict[str, Any]) -> Tuple[KpiBlock, ...]:
    """KPI 卡片原样透传。

    只读 ``title`` / ``value``，**不重算**、不做单位换算、缺字段就跳过这张卡
    而不是填 ``-``: 报告里出现一张写着 "客单价 -" 的卡，读者会当成"客单价是
    零"，比没有这张卡更糟。
    """
    raw = result.get("kpis")
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)):
        return ()
    out: List[KpiBlock] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        title = item.get("title")
        value = item.get("value")
        if not title or value is None or value == "":
            continue
        out.append(KpiBlock(title=str(title), value=str(value)))
    return tuple(out)


def _classify_failure(
    section: SectionTemplate, query: str, result: Optional[Dict[str, Any]],
) -> Optional[SectionFailure]:
    """把执行链的返回值判成「成功」或「哪种失败」。

    注意这里对 ``clarification`` 也判失败: 报告是无人值守跑的，执行链反问
    「你想看哪个时间范围？」时没有人能回答。此时正确做法是把这句反问原样报给
    调用方（模板问句需要补齐口径），而不是替用户瞎选一个时间窗。
    """
    if result is None:
        return SectionFailure(
            section_key=section.key, heading=section.heading, query=query,
            reason_code="NOT_DELEGATED",
            detail="餐饮计划执行链没有接管这个问题（非餐饮租户或计划不可用），"
                   "本节没有任何可信数据。",
        )
    kind = result.get("kind")
    if kind == "clarification":
        return SectionFailure(
            section_key=section.key, heading=section.heading, query=query,
            reason_code="NEEDS_CLARIFICATION",
            detail="执行链要求先澄清口径，报告无人值守无法回答：" + str(
                result.get("answer_text") or "（未给出澄清文本）"
            ),
        )
    if kind != "answer":
        return SectionFailure(
            section_key=section.key, heading=section.heading, query=query,
            reason_code="UNEXPECTED_KIND",
            detail=f"执行链返回了未知结果类型 {kind!r}。",
        )
    if not result.get("contract_pass"):
        return SectionFailure(
            section_key=section.key, heading=section.heading, query=query,
            reason_code="CONTRACT_FAILED",
            detail="Answer Contract 校验未通过，答案不可信。",
        )
    if not str(result.get("answer_text") or "").strip():
        return SectionFailure(
            section_key=section.key, heading=section.heading, query=query,
            reason_code="EMPTY_ANSWER",
            detail="执行链返回了空结论。",
        )
    return None


def _section_from(
    section: SectionTemplate, query: str, result: Dict[str, Any],
) -> ReportSection:
    resolvers = result.get("executed_resolvers")
    return ReportSection(
        key=section.key,
        heading=section.heading,
        query=query,
        answer_text=str(result["answer_text"]).strip(),
        kpis=_kpis_from(result),
        tables=charts_to_tables(result.get("charts")),
        plan_hash=result.get("query_plan_hash"),
        executed_resolvers=tuple(
            str(r) for r in resolvers
        ) if isinstance(resolvers, Sequence) and not isinstance(
            resolvers, (str, bytes)
        ) else (),
        code=result.get("code"),
    )


async def build_monthly_report(
    pool,
    factory_id: str,
    role: Optional[str] = None,
    *,
    template: Optional[ReportTemplate] = None,
    period: Optional[str] = None,
    answer_fn: Optional[AnswerFn] = None,
    freshness: Optional[DataFreshness] = None,
) -> MonthlyReport:
    """批量执行模板里的计划，返回一份**每节都拿到真实数据**的报告。

    Raises
    ------
    ReportDataUnavailableError
        查不到数据截至时间（连"这份报告的数是到哪天的"都说不出）。
    ReportGenerationError
        任意一节没拿到可信数据；``.failures`` 列出每一节的原因。
    """
    tpl = template or DEFAULT_MONTHLY_TEMPLATE
    fresh = freshness or await resolve_freshness(pool, factory_id)
    normalized_period, period_label, period_span = parse_period(
        period, fresh.as_of_date,
    )
    run_answer = answer_fn or _default_answer_fn()

    sections: List[ReportSection] = []
    failures: List[SectionFailure] = []
    for section in tpl.sections:
        query = section.render_query(period_label)
        try:
            result = await run_answer(query, pool, factory_id, role)
        except Exception as exc:  # 执行链自身炸了
            logger.warning(
                "[monthly-report] section %s raised: %s", section.key, exc,
            )
            failures.append(SectionFailure(
                section_key=section.key, heading=section.heading, query=query,
                reason_code="EXECUTION_ERROR",
                detail=f"执行链抛出异常：{exc}",
            ))
            continue
        failure = _classify_failure(section, query, result)
        if failure is not None:
            failures.append(failure)
            continue
        sections.append(_section_from(section, query, result))  # type: ignore[arg-type]

    if failures:
        raise ReportGenerationError(
            f"「{tpl.title}·{period_label}」共 {len(tpl.sections)} 节，"
            f"其中 {len(failures)} 节没有拿到可信数据，本次不生成报告文件"
            f"（宁可不出，也不出一份带假数的报告）：\n"
            + "\n".join(f"- {f.describe()}" for f in failures),
            code="REPORT_SECTION_FAILED",
            failures=failures,
        )

    return MonthlyReport(
        factory_id=factory_id,
        template_code=tpl.code,
        title=f"{tpl.title}（{period_label}）",
        period_label=period_label,
        freshness=fresh,
        sections=tuple(sections),
        meta={
            "period": normalized_period,
            "period_span": period_span,
            "section_count": len(sections),
            "generated_at": fresh.generated_at,
        },
    )


def report_generated_at() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")
