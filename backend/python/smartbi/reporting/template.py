"""报告模板 = 一串自然语言问句 + 排版顺序。

章节按**部门**组织(市场 → 财务 → 运营), 与四部门驾驶舱同一套划分; 老板读报告的
顺序仍然是先总量、再拆解、最后到后厨成本。

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
        # ── 市场 ────────────────────────────────────────────────────
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

        # ── 财务 ────────────────────────────────────────────────────
        # 「全部门店毛利率」而不是「全部门店(各门店)毛利率对比」: 后者被 Answer
        # Contract 判为口径覆盖不足直接否决, 前者才落到按门店排名的毛利 resolver
        # (2026-07-29 对 6 月/7 月各跑 3 次, 6/6 稳定通过)。
        SectionTemplate(
            key="store_margin",
            heading="五、各门店毛利率",
            query="{period}全部门店毛利率",
        ),
        SectionTemplate(
            key="recipe_cost",
            heading="六、菜品食材成本",
            query="{period}全部门店哪些菜的食材成本最高",
        ),

        # ── 运营 ────────────────────────────────────────────────────
        # ✅ 损耗一节 2026-07-29 曾被摘除, 2026-07-31 恢复。
        #
        # 当初摘除的原因: 损耗 resolver 无视请求的时间窗 —— 问「2026年6月…损耗排行」
        # 返回的是"近 30 天损耗总览", 数字一字不差。放进月度报告 = 标题写着 6 月、
        # 内容是近 30 天, 是一份看起来完全正常但口径错误的报告。
        #
        # PR #2076 让 resolve_wastage_top 真正按请求的窗口取数并在标题里带上具体
        # 日期; #2081 对领料与盘点做了同样的事。三节的问句都在真实租户上验过。
        SectionTemplate(
            key="wastage",
            heading="七、损耗排行",
            query="{period}全部门店损耗金额最高的食材是哪几个",
        ),
        SectionTemplate(
            key="requisition",
            heading="八、领料用量",
            query="{period}全部门店领料最多的是哪些食材",
        ),
        SectionTemplate(
            key="stocktaking",
            heading="九、盘点差异",
            query="{period}全部门店哪些食材经常盘亏",
        ),

        # ── 人事 ────────────────────────────────────────────────────
        # ⛔ 暂无章节。`fact_staffing_daypart` 全表 0 行(所有租户), 人效根本算不出来
        # —— 放一节进去只会得到「还没有配置各时段的人效数据」, 那不是报告内容。
        # 复原条件: 各时段在岗人数与目标人效配置到位后, 加一节并在真实租户上验证。
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
