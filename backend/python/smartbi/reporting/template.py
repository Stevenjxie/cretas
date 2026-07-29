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
# 顺序 = 老板读报告的顺序: 先总量, 再趋势, 再拆门店/渠道, 最后是毛利。
#
# ⚠️ 每条问句都必须**显式写出门店范围** (2026-07-29 生产实测):
# 报告是无人值守跑的, 而意图层对没写范围的问句会走澄清闸
# (`NEEDS_CLARIFICATION`) —— 没人在旁边回答"你想看哪几家门店", 于是整份报告
# fail-closed 不生成。上线首版 6 节里有 5 节漏了范围词, 在 30 家门店的真实
# 租户上**一份报告都出不来**; stub 测试发现不了, 因为 stub 绕过了澄清闸。
# 改问句时务必先在真实多门店租户上跑一遍再合。
DEFAULT_MONTHLY_TEMPLATE = ReportTemplate(
    code="RESTAURANT_MONTHLY_DEFAULT",
    title="餐饮月度经营报告",
    sections=(
        SectionTemplate(
            key="overview",
            heading="一、经营总览",
            query="{period}全部门店整体经营概览，营收、订单量和客单价分别是多少",
        ),
        SectionTemplate(
            key="trend",
            heading="二、营收趋势与环比",
            query="{period}全部门店营收的月度趋势和环比变化",
        ),
        SectionTemplate(
            key="store_revenue",
            heading="三、各门店营收对比",
            query="{period}各门店营收对比",
        ),
        SectionTemplate(
            key="channel_mix",
            heading="四、堂食与外卖结构",
            query="{period}全部门店堂食和外卖的占比",
        ),
        # 「全部门店毛利率」而不是「全部门店(各门店)毛利率对比」: 后者被 Answer
        # Contract 判为口径覆盖不足直接否决, 前者才落到按门店排名的毛利 resolver
        # (2026-07-29 对 6 月/7 月各跑 3 次, 6/6 稳定通过)。
        SectionTemplate(
            key="store_margin",
            heading="五、各门店毛利率",
            query="{period}全部门店毛利率",
        ),
        # ⛔ 「六、损耗排行」已摘除 (2026-07-29)。
        #
        # 不是问句写法问题, 改不了: 损耗 resolver **完全无视请求的时间窗** ——
        # 问「2026年6月...损耗排行」, 三种写法返回的都是"近 30 天损耗总览"、
        # 数字一字不差。放进月度报告 = 标题写着 6 月、内容是近 30 天, 是一份
        # 看起来完全正常但口径错误的报告。
        #
        # 这类错误 fail-closed **挡不住**: 它挡的是"没拿到数据", 而这里
        # resolver 确实返回了数据, 只是答的是另一个时间窗; 报告层又刻意不解析
        # 日期 (日期口径的唯一权威在意图层), 于是会原样排版进去。
        #
        # 复原条件: 损耗 resolver 支持显式月份窗口后, 把这一节加回来并在真实
        # 租户上验证「问 6 月答的就是 6 月」。
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
