"""报告模板 = 一串自然语言问句 + 排版顺序。

这是「报告不是新引擎」最直白的证据: 模板里**没有 SQL、没有 metric 名、没有
resolver code**，只有客户自己会问的中文问句。报告跑起来跟一个人坐在聊天框里
按顺序问这 N 个问题、把答案抄进 Word 完全等价 —— 因此:

* 每一节都吃到同一套 Answer Contract 11 项校验；
* 每一节都能命中计划缓存 / 晋升表 (零 token 出口), 报告越跑越便宜；
* 客户改口径 = 改这里的问句，不需要动取数代码。

``{period}`` 占位符在执行前被替换成人类写法的月份 (如 ``2026年6月``)，交给
``parse_restaurant_query`` 自己解析时间窗 —— 报告层**不解析日期**，日期口径
的唯一权威仍是意图层 (核心红线: LLM 永不算数字和日期，报告层同理)。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple

PERIOD_PLACEHOLDER = "{period}"


@dataclass(frozen=True)
class SectionTemplate:
    """一节 = 一个问句。

    ``key`` 稳定不变 (用于失败定位和前端锚点)，``heading`` 是报告上的标题，
    ``query`` 是真正送进执行链的问句。
    """

    key: str
    heading: str
    query: str

    def render_query(self, period_label: str) -> str:
        return self.query.replace(PERIOD_PLACEHOLDER, period_label)


@dataclass(frozen=True)
class ReportTemplate:
    code: str
    title: str
    sections: Tuple[SectionTemplate, ...]

    def section_keys(self) -> Tuple[str, ...]:
        return tuple(s.key for s in self.sections)


# 客户口径的默认月度报告 (7/27 会议 B 40:30 / 46:16「月度报告自动生成+文件下载」)。
# 顺序 = 老板读报告的顺序: 先总量, 再趋势, 再拆门店/菜品/渠道, 最后是成本损耗。
DEFAULT_MONTHLY_TEMPLATE = ReportTemplate(
    code="RESTAURANT_MONTHLY_DEFAULT",
    title="餐饮月度经营报告",
    sections=(
        SectionTemplate(
            key="overview",
            heading="一、经营总览",
            query="{period}整体经营概览，营收、订单量和客单价分别是多少",
        ),
        SectionTemplate(
            key="trend",
            heading="二、营收趋势与环比",
            query="{period}营收的月度趋势和环比变化",
        ),
        SectionTemplate(
            key="store_revenue",
            heading="三、各门店营收对比",
            query="{period}各门店营收对比",
        ),
        SectionTemplate(
            key="channel_mix",
            heading="四、堂食与外卖结构",
            query="{period}堂食和外卖的占比",
        ),
        SectionTemplate(
            key="store_margin",
            heading="五、各门店毛利率",
            query="{period}各门店毛利率对比",
        ),
        SectionTemplate(
            key="wastage",
            heading="六、损耗排行",
            query="{period}损耗最多的食材排行",
        ),
    ),
)

_TEMPLATES: Dict[str, ReportTemplate] = {
    DEFAULT_MONTHLY_TEMPLATE.code: DEFAULT_MONTHLY_TEMPLATE,
}


def get_template(code: Optional[str] = None) -> ReportTemplate:
    """按 code 取模板；未知 code 直接 ``KeyError``。

    不做「未知就回落默认模板」—— 那是降级: 用户点的是 A 报告拿到 B 报告，
    而且文件里不会有任何提示。
    """
    if not code:
        return DEFAULT_MONTHLY_TEMPLATE
    return _TEMPLATES[code]


def list_templates() -> Tuple[ReportTemplate, ...]:
    return tuple(_TEMPLATES.values())
