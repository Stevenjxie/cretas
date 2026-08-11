"""Restaurant AI regression eval — the accumulated probe battery, runnable.

Every phrasing class fixed in the R6→R20 sweep rounds (Sheet 7/22 菜品链,
存在性/排名/比较/能力/域外/单日/同比/店×菜/口语盈亏 …) lives here as an
executable expectation.  Run it after ANY routing/resolver/extractor change:

    python -m smartbi.scripts.restaurant_ai_eval --base https://admin.cretaceousfuture.com

Checks are STRUCTURAL (markers that must / must-not appear), not exact
numbers — demo data rolls daily so numbers drift by design.  A case fails
loudly with the actual answer so triage is one read, not a re-probe.

Chains run in one shared session (multi-turn inheritance / entity switch).
Exit code 0 = all pass; 1 = any fail — safe for CI / cron wiring.
"""
from __future__ import annotations

import argparse
import calendar as _calendar
import datetime as _dt
import json
import os
import random
import re
import string
import sys
import time
import urllib.request
from typing import Any, Dict, List, Optional

# ── 租户夹具：会随租户变的东西，只在这里定义一次 ────────────────────────
#
# 🔴 这个块存在的理由（2026-08-09 复盘）：8-06 餐饮租户收敛把 DEMO_REST 停用
#    （9 个账号 is_active=f、`cretas.demo.rest.*` 默认值清空），这道 52 断言的
#    电池从此**登录就崩**，每天往 alerts 写一行失败，4 天零回归证据；
#    再往前，7-25 起就没再全绿过。
#
#    根因不是「谁忘了改」——是租户身份散在 **70 多处字符串字面量**里，
#    换租户要改 70 处，于是没人改。判据：**会随租户变的东西只能有一处定义**，
#    否则「跟着变」这件事的成本高到不会发生。
#
# ⛔ 换租户时只改这一块，然后跑 `_preflight_fixture` —— 它会在跑断言之前
#    先向真实租户核对这些实体存在，把「夹具过期」报成一句人话，
#    而不是让 52 条断言各挂各的、看起来像 AI 退化。
FACTORY_ID = "MOCK_REST"

#: 主菜品：菜品链、独立问、话题跳转都用它。要求本租户里**确实有销量**。
_DISH_MAIN = "米饭"
#: 第二菜品：用于「中途换实体」链，必须与 _DISH_MAIN 不同。
_DISH_ALT = "娃娃菜"
#: _DISH_ALT 的错别字形态（语音转写/形近字），必须**不存在**于菜单。
_DISH_ALT_TYPO = "蛙蛙菜"
#: 招牌菜：名字要长、要能被前缀部分匹配（见 _DISH_SIG_PARTIAL）。
_DISH_SIG = "水煮牛肉"
#: _DISH_SIG 的真前缀，且在本租户菜单内**唯一**——这条用例测的是
#: 「用户只打了半个菜名，系统能不能认出来」。前缀不唯一就变成了歧义测试，
#: 前缀等于全名就什么都没测。
_DISH_SIG_PARTIAL = "水煮"
#: 必须不存在的菜名，用于「如实说没找到，不许拿榜单顶包」。
_DISH_MISSING = "不存在的菜ABC"
#: 操作模式（下架预览）用的菜，与上面几个分开，避免链间串味。
_DISH_OPERATE = "红糖糍粑"

#: 具名门店 A/B/C：分别用于「菜品链里点名门店」「门店维度提问」「澄清乱序链」。
_STORE_A = "模拟·静安嘉里中心店"
_STORE_B = "模拟·打浦桥日月光店"
_STORE_C = "模拟·长宁龙之梦店"
#: 歧义片段：必须**同时匹配 ≥2 家**门店，这条用例测的就是「问得不够具体时反问」。
#: ⛔ 换租户时最容易悄悄失效的就是它 —— 上一个租户有两家「日月光店」，
#:    本租户只有一家，照搬过来这条用例会变成「非歧义」而依然显示通过。
_STORE_AMBIG = "社区店"
_STORE_AMBIG_1 = "模拟·宝山大场社区店"
#: ⚠️ 反问只列前 3 家候选，这两个必须都在被列出的那几家里，
#:    否则断言会因为「候选被截断」而红，那不是行为错。
_STORE_AMBIG_2 = "模拟·普陀真如社区店"
#: 本租户门店名的共同前缀，用于「答案里点到了某家店」这类不指定是哪家的断言。
_STORE_NAME_PREFIX = "模拟·"
#: 别的租户的门店名——跨租户泄漏的阴性对照，不随本租户变。
_OTHER_TENANT_STORES = ["兄弟土菜馆", "有滋有味总部"]


def _assert_fixture_self_consistent() -> None:
    """夹具内部自洽 —— 这些错在跑之前就能发现，不必等打网络。"""
    assert _DISH_SIG_PARTIAL != _DISH_SIG and _DISH_SIG.startswith(_DISH_SIG_PARTIAL), (
        f"_DISH_SIG_PARTIAL({_DISH_SIG_PARTIAL}) 必须是 _DISH_SIG({_DISH_SIG}) 的**真前缀**"
        " —— 等于全名等于没测部分匹配"
    )
    assert _DISH_ALT_TYPO != _DISH_ALT, "错别字形态不能与正确菜名相同"
    assert len({_DISH_MAIN, _DISH_ALT, _DISH_SIG, _DISH_OPERATE}) == 4, (
        "四个菜品夹具必须互不相同，否则链间会串味")
    assert _STORE_AMBIG in _STORE_AMBIG_1 and _STORE_AMBIG in _STORE_AMBIG_2, (
        f"_STORE_AMBIG({_STORE_AMBIG}) 必须同时是两家门店名的子串，"
        "否则「问得不够具体要反问」这条用例测的就不是歧义了")
    assert _STORE_AMBIG_1 != _STORE_AMBIG_2, "歧义的两家门店不能是同一家"
    assert len({_STORE_A, _STORE_B, _STORE_C}) == 3, "三家具名门店必须互不相同"


_assert_fixture_self_consistent()

# ── Case schema ──────────────────────────────────────────────────────────
# {q, contains: [...], excludes: [...], chain: str|None}
# chain: cases sharing the same chain id run in ONE session, in file order.
_FORBIDDEN_EVERYWHERE = [
    "暂未配置执行器",           # dead-end (R13e/R14c 修)
    "没有获得可展示的结果",     # generic wrapper swallow (R14b 修)
    "Step1",                    # reasoning leak (R13c 修)
    "实体识别",                 # reasoning leak
    "工厂制造分析不适用",       # G4 factory frame (R20 修)
    "生产统计报告",             # G4 upload frame (R15 修)
]

# ── 日期敏感用例的演化史（三版，值得留着）────────────────────────────────
# v1: 把「暂无数据」写死成期望 → 只有周一能过, 其余六天必挂
#     (2026-07-28 周二实测: 本周 30 家店 ¥285,776, 系统正确给分析却被判失败)。
# v2: 按 `today.weekday()==0` 切换两套断言 —— 比 v1 好, 但**仍然是代理指标**:
#     它猜「周一 = 本周为空」, 前提是「demo 数据覆盖到昨天」。
# v3 (2026-08-10): 那个前提也过期了 —— 实测 08-10 周一当天就有数据(722 单
#     ¥26.5万, 还在累积), 系统又一次正确却被判失败。
#     改成断言**不变式**(见 _THIS_WEEK_EMPTY_PERIOD_CASE), 不再依赖任何环境推断。
# 判据: **一条断言连着两次因为「环境推断过期」而误报, 就该换成不变式**, 而不是
#       再修一次推断 —— 修推断只是把下次误报推迟到下一次环境变化。
_TODAY = _dt.date.today()


# ── 相对时间断言必须动态算，不能写死 ──────────────────────────────────
# 问句问的是「上个月」「上上个月」「去年12月」这类**相对**时间，答案里的具体
# 年月自然随今天滚动。断言若写死成 "2026-06" / "2026-05-01 至 2026-05-31"，
# 就只有写它的那个月能过 —— 下个月一到全体误报，跨年更是全挂。
# （2026-07-28 审查实测：当时有 2 条 8/1 必挂、2 条跨年必挂。）
# 显式带年份的问句（「2026年3月」「2025年全年」）不在此列，那种写死是对的。
def _ym(offset_months: int) -> str:
    """相对本月偏移 N 个月的 'YYYY-MM'。_ym(-1) = 上个月。"""
    total = _TODAY.year * 12 + (_TODAY.month - 1) + offset_months
    return f"{total // 12}-{total % 12 + 1:02d}"


def _month_span(offset_months: int) -> str:
    """相对本月偏移 N 个月的整月区间 'YYYY-MM-01 至 YYYY-MM-DD'。"""
    ym = _ym(offset_months)
    year, month = int(ym[:4]), int(ym[5:])
    last_day = _calendar.monthrange(year, month)[1]
    return f"{ym}-01 至 {ym}-{last_day:02d}"


# ⚠️ 2026-08-10 重写: 原来这条按 `today.weekday() == 0` 分成两套断言, 周一那套
#    期望系统说「没有可用的经营数据」, 前提写着「demo 数据覆盖到昨天, 所以周一时
#    本周为空」。**那个前提已经过期** —— 实测 08-10 周一当天就有数据(722 单
#    ¥26.5万, 还在累积), 系统正确给出分析却被判失败。
#
#    根子上, `weekday()` 是**代理指标**: 真正要判的是「本周有没有数据」, 而电池是
#    黑盒 HTTP 客户端, 查不到库。用星期几去猜, 就要维护一份「今天该是什么样」的
#    推断 —— 而推断会过期, 过期时表现为「系统对了、闸红了」。
#
#    改成断言**不变式**: 不管本周有没有数据, 两种回答各自必须自洽 ——
#      · 说了「没有可用的经营数据」→ 就必须给相邻周期(最近7天/上周), 不能撒手
#      · 给了分析 → 就不许同时谎称没有数据
#    两个分支都真的在检查行为, 且**不依赖任何环境推断**。
#    判据: **能断言不变式就别断言分支。**
_THIS_WEEK_EMPTY_PERIOD_CASE: Dict[str, Any] = {
    "q": "这周全部门店营收怎么提高，给我今天能做的动作",
    "excludes": [
        "请先把缺少的数据补齐",
        "当前能查到的数据还不够",
        "本次结果没有可靠覆盖",
    ],
    "invariant": {
        # 说了没数据 → 必须已经给出相邻周期, 否则就是把用户撂在原地。
        # 第三位 require_any 是 2026-08-10 补的: 原先只写了前两位(forbid 为空表),
        # 那条断言一次都不可能红 —— 见 _run_case 里 invariant 循环的注释。
        "空周期必须给相邻周期": (
            ["没有可用的经营数据"], [], ["最近7天", "上周", "上个月", "最近30天"],
        ),
        # 给了分析 → 不许同时说没数据(自相矛盾)
        "有分析就不许谎称没数据": (["优化建议"], ["没有可用的经营数据"]),
    },
    # 无论走哪条路, 都得给出可切换的时间窗按钮 —— 这条与本周有没有数据无关。
    "followup_contains": ["最近7天"],
}


CASES: List[Dict[str, Any]] = [
    # ── 菜品链：缺时间 → 缺门店 → 结果 → 指标/对象切换 ──
    {"q": f"{_DISH_MAIN}的销量是多少", "chain": "dish",
     "contains": ["哪个时间范围"],
     "followup_contains": ["本月", "上个月", "最近7天", "最近30天"],
     "followup_excludes": _OTHER_TENANT_STORES},
    {"q": "本月", "chain": "dish",
     # ⚠️ 2026-08-11 改断言: 原来写死字面量「哪一组门店」—— 那是**模型自撰的措辞**。
     #    #2493 之后延续轮恒定走门店按钮, 实际返回是「你想查看哪家门店的米饭销量？」,
     #    **行为正确而断言变红**。挂在措辞上的断言换个说法就误报, 而它守的从来不是
     #    措辞: 守的是「这一轮在**问门店**, 而不是直接答」。
     #    改成结构判据: 是个问句(？) + 问的是门店 + 给了门店按钮。
     "contains": ["？", "门店"],
     "followup_contains": ["全部门店", _STORE_NAME_PREFIX],
     "followup_excludes": _OTHER_TENANT_STORES},
    {"q": "全部门店", "chain": "dish",
     "contains": [f"「{_DISH_MAIN}」", "销量"]},
    {"q": "那成本呢", "chain": "dish", "contains": [f"「{_DISH_MAIN}」", "成本"]},
    {"q": f"那{_DISH_SIG}呢", "chain": "dish",
     "contains": [f"「{_DISH_SIG}」"], "excludes": [f"「{_DISH_MAIN}」"]},
    {"q": "那毛利呢", "chain": "dish",
     "contains": [f"「{_DISH_SIG}」"], "excludes": [f"「{_DISH_MAIN}」"]},
    {"q": "是否合理", "chain": "dish", "contains": [f"「{_DISH_SIG}」"]},
    {"q": "怎么优化", "chain": "dish", "contains": [f"「{_DISH_SIG}」"]},
    {"q": "换成上个月呢", "chain": "dish",
     "contains": [f"「{_DISH_SIG}」", _ym(-1)]},
    # ── 真实前端按钮链：具名门店必须来自当前菜品/时间的实际销售范围 ──
    {"q": f"{_DISH_MAIN}的销量是多少", "chain": "dish_named_store",
     "contains": ["哪个时间范围"],
     "followup_contains": ["本月", "上个月", "最近7天", "最近30天"]},
    {"q": "本月", "chain": "dish_named_store",
     # 见上一条同样的理由: 断言从「模型的那句话」改成「问句 + 问的是门店」。
     "contains": ["？", "门店"],
     # ⛔ 只断言「按钮里出现了本租户的门店」, 不断言是哪一家:
     #    本租户 10 家门店营收差距 2% 以内, 按钮取其中 3 家, 取到谁会随数据滚动。
     "followup_contains": [_STORE_NAME_PREFIX],
     "followup_excludes": _OTHER_TENANT_STORES},
    {"q": f"{_STORE_A}", "chain": "dish_named_store",
     "contains": [f"{_STORE_A}", f"「{_DISH_MAIN}」", "销量"],
     "excludes": ["没有找到", "毛利或毛利率"]},
    # ── 菜品独立问 ──
    {"q": f"本月全部门店{_DISH_MAIN}赚钱吗", "contains": ["结论", f"「{_DISH_MAIN}」", "毛利率"]},
    {"q": f"这周全部门店{_DISH_MAIN}卖了多少", "contains": [f"「{_DISH_MAIN}」", "销量"]},
    {"q": f"本月全部门店{_DISH_MAIN}的营收和销量分别是多少",
     "contains": [f"「{_DISH_MAIN}」", "营收", "销量"]},
    {"q": f"本月全部门店{_DISH_MAIN}和{_DISH_SIG}哪个赚钱",
     "contains": ["毛利"], "excludes": ["没有找到"]},
    {"q": f"本月全部门店{_DISH_MISSING}的销量",
     "contains": ["没有找到"], "excludes": ["排行"]},
    # ── 排名 / 存在性 ──
    {"q": "本月全部门店哪个菜卖得好", "contains": ["菜品销量排行", "销量"],
     "excludes": ["门店营收", "经营销售概览", "最强门店"]},
    {"q": "本月全部门店哪个菜卖得最好", "contains": ["销量排行", "已剔除"],
     # ⚠️ 2026-08-10: 原来写的是 ". 打包盒" —— 前面那个点是**编号列表的排版**
     #    (`3. 打包盒`)。排行改成 markdown 表格后, 这个字符串永远不会出现,
     #    于是三条排除**全部恒真**: 打包盒真漏进榜单也照样绿。
     #    判据: **断言不要挂在排版上。** 挂了之后, 换个排版就是静默失效 ——
     #          不报错、不变红, 只是从此什么都不测。
     #    改成裸名字, 与列表/表格/任何排版都无关, 且严格更强。
     #    (口径说明里写的是「米饭、包装、餐具、纸巾等」, 不含这三个词, 不会误伤)
     "excludes": ["打包盒", "需要餐具", "无需餐具"]},
    {"q": "本月全部门店哪道菜卖得最差", "contains": ["卖得最差"]},
    {"q": "本月全部门店有没有毛利率是负的菜",
     "contains": ["毛利", "成本覆盖率"]},
    {"q": "本月全部门店有没有店在亏损",
     "contains": ["门店", "成本覆盖率"]},
    {"q": "上个月全部门店哪家店亏钱了",
     "contains": ["门店"], "excludes": ["没有找到名为"]},
    {"q": "本月全部门店毛利率最高的菜是哪个", "contains": ["毛利"]},
    # ── 店×菜 ──
    {"q": f"本月全部门店哪家店的{_DISH_MAIN}卖得最好",
     "contains": [f"「{_DISH_MAIN}」", "门店销量排行"]},
    {"q": f"本月全部门店哪家店的{_DISH_SIG_PARTIAL}卖得最好",
     "contains": [f"「{_DISH_SIG_PARTIAL}」", "门店销量排行"]},
    {"q": f"本月{_STORE_B}的{_DISH_MAIN}卖得怎么样",
     "contains": [f"{_STORE_B}", f"「{_DISH_MAIN}」", "销量"]},
    {"q": f"本月{_STORE_B}的毛利率",
     "contains": [f"{_STORE_B}", "毛利率"]},
    # ── 营收 / 时间窗 ──
    {"q": "这个月全部门店生意怎么样", "contains": ["本月", "总营收"]},
    {"q": "昨天全部门店卖了多少钱", "contains": ["昨天"]},
    {"q": "今天全部门店营业额多少", "contains": ["今天"]},
    {"q": "全部门店今年比去年增长多少", "contains": ["今年", "去年"]},
    {"q": "全部门店上周和上上周营收对比", "contains": ["上周", "上上周"]},
    {"q": "本月全部门店订单量如何", "contains": ["单", "平均每单"],
     "excludes": ["请检查是否上传"]},
    {"q": "哪个门店营收最好", "chain": "store_rank",
     "contains": ["哪个时间范围"],
     "followup_contains": ["本月", "上个月", "最近7天", "最近30天"]},
    {"q": "本月", "chain": "store_rank",
     "contains": ["结论", "营收最高"]},
    {"q": "最近30天各门店的营收排名", "contains": ["门店"]},
    {"q": "本月全部门店晚上生意怎么样", "contains": ["晚市"]},
    {"q": "全部门店3月份的营收多少", "contains": [f"{_TODAY.year}年3月"],
     "excludes": ["没有找到名为"]},
    # 🔴 2026-08-09 修: 原断言写 `excludes: ["全部历史"]`, 而系统给出的**正确**回答是
    #    「2025年12月…没有可用的数据。**没有用全部历史**或其他日期替代」——
    #    排除项匹配到了**否定句里的那四个字**, 正确行为被判红。
    #    判据: 排除项是子串匹配, 写之前先问「这几个字会不会出现在正确答案的否定句里」。
    #    改成正向断言那句免责声明: 它只在系统**确实没有**回落时才出现,
    #    而回落时答案会直接给数字、不会说这句话 —— 两种情形可区分。
    {"q": "全部门店去年12月的营收",
     "contains": [f"{_TODAY.year - 1}年12月", "没有用全部历史"]},
    {"q": "全部门店2026年3月生意怎么样", "contains": ["2026年3月"]},
    {"q": "全部门店最近三个月营收趋势", "contains": ["营收趋势"],
     "excludes": ["全部历史"]},
    {"q": "全部门店上上个月营收多少",
     "contains": [_month_span(-2)]},
    {"q": "全部门店2025年全年营收多少", "contains": ["2025年"],
     "excludes": ["没有找到名为"]},
    # ⛔ 歧义反问：候选必须是**两家都含 _STORE_AMBIG 的门店**。
    #    换租户时最容易在这里悄悄失效 —— 若片段只匹配到一家，系统会直接作答，
    #    而这条用例仍可能因为「指的是哪家」恰好没出现而被判失败，
    #    看起来像 AI 退化，实际是夹具过期。`_assert_fixture_self_consistent`
    #    与 `_preflight_fixture` 一起把这种情况提前报成人话。
    # ⚠️ 必须把时间槽先填上: 时间和门店同时缺失时，系统会先反问时间，
    #    这条用例就测不到门店歧义了（2026-08-09 实测：「社区店的营收」被
    #    时间反问抢先，补上「本月」后才出现「匹配到多家门店」）。
    {"q": f"本月{_STORE_AMBIG}的营收",
     # ⚠️ 候选门店列在**正文**里, 不在按钮里 —— 断言必须落在正文。
     #    写成 followup_contains 会红, 但红的原因是「我查错了地方」, 不是行为错。
     "contains": ["匹配到多家门店", f"{_STORE_AMBIG_1}", f"{_STORE_AMBIG_2}"],
     "followup_excludes": _OTHER_TENANT_STORES},
    {"q": f"本月全部门店{_DISH_MAIN}和{_DISH_ALT}和{_DISH_SIG}的销量",
     "contains": [f"{_DISH_MAIN}", f"{_DISH_ALT}", f"{_DISH_SIG}"],
     "excludes": ["请指定其中一道"]},
    {"q": "本月全部门店哪些菜没人点", "contains": ["卖得最差"]},
    {"q": "本月全部门店外卖占了几成", "contains": ["外卖", "堂食", "单量占"]},
    {"q": "全部门店哪些食材快没了", "contains": ["补货"]},
    # ── 预测排班：三个可执行未来范围 + 历史人效边界 ──
    {"q": "明天怎么排班",
     "contains": ["明天", "预测排班 FactBook", "大模型解读", "历史人效仅作为趋势证据"],
     "excludes": ["历史实际人效低于目标就补人"]},
    {"q": "下周需要多少兼职",
     "contains": ["下周", "预测排班 FactBook", "大模型解读", "历史人效仅作为趋势证据"],
     "excludes": ["历史实际人效低于目标就补人"]},
    {"q": "下个月各店人效安排",
     "contains": ["下个月", "预测排班 FactBook", "大模型解读", "历史人效仅作为趋势证据"],
     "excludes": ["历史实际人效低于目标就补人"]},
    {"q": "各岗位这个月的人效怎么样",
     "contains": ["不能把它偷换成明天", "明天", "下周", "下个月", "历史人效"],
     "excludes": ["预测排班 FactBook"]},
    {"q": "本月全部门店翻台率怎么样", "contains": ["翻台率"],
     "excludes": ["操作已完成"]},
    {"q": "本月全部门店客单价最高的店是哪家", "contains": ["平均每单"],
     "excludes": ["没有找到名为"]},
    {"q": f"本月全部门店{_DISH_MAIN}的销量、毛利率和成本分别是多少",
     "contains": [f"「{_DISH_MAIN}」"]},
    {"q": f"这个月全部门店生意怎么样，另外{_DISH_MAIN}卖得好不好",
     "contains": ["本月", f"「{_DISH_MAIN}」"]},
    {"q": f"全部门店{_DISH_OPERATE}本月销量为什么低",
     "contains": ["判断", "“销量低”的前提"],
     "excludes": ["如果你问的是销量为什么上涨或下降"]},
    {"q": f"全部门店{_DISH_OPERATE}本月销量为什么高",
     "contains": ["判断", "“销量高”的前提"],
     "excludes": ["如果你问的是销量为什么上涨或下降"]},
    # ⛔ 断言「点到了某一家店」而不是「点到了具体哪一家」：本租户 10 家门店
    #    营收差距在 2% 以内，「昨天最强的是谁」每天都会换 —— 写死某一家等于
    #    掷硬币，红了也说明不了任何问题。这条用例真正要守的是
    #    「两问一答里第二问没被吞掉」。
    {"q": "先告诉我昨天全部门店的营收，再告诉我哪家店业绩最好",
     "contains": ["昨天", _STORE_NAME_PREFIX]},
    # ── 能力 / 域外 / 方法论 ──
    {"q": "你们能做什么", "contains": ["门店经营数据"]},
    {"q": "今天天气怎么样", "contains": ["不会编造"],
     "excludes": ["库存、生产、质检"]},
    {"q": "毛利率低有什么行业参考做法", "contains": ["行业参考做法"]},
    # 断言不能只写问句本身(原样回显也能过) — 要求真的给出主题清单与用法指引。
    {"q": "行业参考做法", "contains": ["行业参考做法", "具体主题"]},
    # ── 损耗 / 库存 (Java 工具面) ──
    {"q": "最近损耗怎么样", "contains": ["损耗"]},
    # ── 周初当前周期暂无数据：不泛化补数，主动给可执行的相邻周期 ──
    # ⚠️ 日期敏感，断言按今天是不是周一切换 —— 见 _THIS_WEEK_EMPTY_PERIOD_CASE。
    _THIS_WEEK_EMPTY_PERIOD_CASE,
    {"q": "最近7天全部门店营收怎么提高，给我今天能做的动作",
     "contains": ["优化建议", "营收"],
     "excludes": [
         "本次结果没有可靠覆盖",
         "我没太看懂",
         "请先把缺少的数据补齐",
     ]},
    # ── 操作模式：自然说法进入确认；数据筛选批量操作先列候选再逐项确认 ──
    {"q": f"下架{_DISH_OPERATE}", "mode": "OPERATE", "preview_only": True,
     "contains": ["确认后将下架菜品"],
     "result_contains": [f"{_DISH_OPERATE}", "已下架"],
     "excludes": ["还没有把握直接回答", "请切换到【操作】页"]},
    {"q": "把最近7天全部门店销量最低的5道菜下架", "mode": "OPERATE",
     "contains": ["先查看候选菜品", "具体菜名", "只有确认后才会执行"],
     "followup_contains": ["先查看这5道菜"],
     "excludes": ["未找到菜品「最近7天全部门店销量最低的5道」"]},

    # ── 链B 实体切换：换菜品，时间/门店范围继承；再换指标；再换时间 ──
    # 补于 2026-07-28：原电池只有 dish / dish_named_store / store_rank 三条链，
    # 「中途换实体」「换完实体再换时间」这两种最常见的老板追问从没被守住。
    {"q": f"本月全部门店{_DISH_MAIN}卖得怎么样", "chain": "dish_switch",
     "contains": [f"「{_DISH_MAIN}」", "销量"]},
    {"q": f"那{_DISH_ALT}呢", "chain": "dish_switch",
     "contains": [f"「{_DISH_ALT}」", "销量"], "excludes": [f"「{_DISH_MAIN}」"]},
    {"q": "那毛利呢", "chain": "dish_switch",
     "contains": [f"「{_DISH_ALT}」", "毛利"], "excludes": [f"「{_DISH_MAIN}」"]},
    {"q": "换成上个月呢", "chain": "dish_switch",
     "contains": [f"「{_DISH_ALT}」", _month_span(-1)], "excludes": [f"「{_DISH_MAIN}」"]},

    # ── 链F 澄清鲁棒性：反问时间时用户先答了门店，补上时间后要两者都记住 ──
    {"q": "哪个菜卖得好", "chain": "clarify_reorder",
     "contains": ["哪个时间范围"]},
    {"q": f"{_STORE_C}", "chain": "clarify_reorder",
     "contains": ["哪个时间范围"]},
    {"q": "本月", "chain": "clarify_reorder",
     "contains": [f"{_STORE_C}", "销量排行"]},

    # ── 链E 话题跳转：换了话题就**不该**继承上一轮的菜品 ──
    {"q": f"本月全部门店{_DISH_MAIN}销量", "chain": "topic_jump",
     "contains": [f"「{_DISH_MAIN}」", "销量"]},
    {"q": "门店名单给我看看", "chain": "topic_jump",
     "contains": ["家门店"], "excludes": [f"「{_DISH_MAIN}」", "没有找到"]},
    {"q": "今天天气怎么样", "chain": "topic_jump",
     "contains": ["不会编造"], "excludes": [f"「{_DISH_MAIN}」"]},

    # ── 错别字 / 输入噪声：真人打字与语音转写的常见形态 ──
    # 补于 2026-07-28：电池此前一条错别字用例都没有。以下形态均已实测确认
    # 当前行为正确，加进来是当回归护栏用，不是提新要求。
    {"q": "本月全部门店莹收多少",            # 形近字 营→莹
     "contains": ["总营收"], "excludes": ["我没太看懂"]},
    {"q": "本月全部门店赢收多少",            # 同音字 营→赢
     "contains": ["总营收"], "excludes": ["我没太看懂"]},
    {"q": "本月全部门店营收多少呢？？？",     # 标点噪声
     "contains": ["总营收"]},
    {"q": "本月全部门店哪个菜卖的好",        # 语音转写 得→的：要答得出，不能喊看不懂
     "contains": ["本月"], "excludes": ["我没太看懂", "没有找到"]},
    {"q": "这月挣了多少",                    # 口语“挣”指净利润；数据缺口必须如实说明
     "contains": ["净利润", "缺少费用"]},
    {"q": f"本月全部门店{_DISH_ALT_TYPO}的销量",        # 菜名错字：必须明说没找到，不许拿榜单顶包
     "contains": ["没有找到"], "excludes": ["排行"]},
]


def _rand_sid(prefix: str) -> str:
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"eval-{prefix}-{tail}"


def _post_json(url: str, payload: Dict[str, Any],
               headers: Optional[Dict[str, str]] = None,
               timeout: int = 240) -> Dict[str, Any]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _looks_transient(flat: str) -> bool:
    """瞬态故障(蓝绿切换 / LLM 熔断 / 网络抖动)的可识别形态。

    这类失败跟"行为错了"必须区分开: 它不该被记成回归, 更不该在部署门禁上
    造成假红。判据取自 planner 不可用时的 fail-closed 文案与传输异常。
    """
    return any(m in flat for m in (
        "<TRANSPORT ERROR",
        "暂时无法把这次选择与上一轮问题合并",
        "我现在暂时无法完整理解这句话",
        "请稍后重试",
    ))


def invariant_problems(flat: str, invariant: Dict[str, Any]) -> List[str]:
    """判一条答案是否违反自洽不变式。纯函数, 可单测 —— 见 `_run_case` 的调用点。

    每项写成 `(need, forbid)` 或 `(need, forbid, require_any)`:
      · need       —— 全部出现才触发这条规则(不触发就跳过, 不算通过也不算失败)
      · forbid     —— 触发后**不许**出现的词, 出现即违反
      · require_any—— 触发后**至少要出现一个**的词, 一个都没有即违反

    ⚠️ 第三位是 2026-08-10 补的。在那之前本结构只有前两位, 于是「说了没数据就
       必须给相邻周期」这条只能写成 `(需求, [])` —— forbid 为空表, 内层循环
       一次都不执行, **那条断言永远不会红**, 而它旁边的注释声称它在检查相邻周期。
       判据: **写完一条断言先问「它靠什么变红」** —— 答不上来就是没有断言。
    """
    problems: List[str] = []
    for name, spec in invariant.items():
        need, forbid = spec[0], spec[1]
        require_any = spec[2] if len(spec) > 2 else ()
        if not all(t in flat for t in need):
            continue
        for t in forbid:
            if t in flat:
                problems.append(f"[{name}] 既然说了「{need[0]}」就不该出现「{t}」")
        if require_any and not any(t in flat for t in require_any):
            problems.append(
                f"[{name}] 说了「{need[0]}」却没有给出 {list(require_any)} 中的任何一个"
            )
    return problems


_TABLE_SEP_RE = re.compile(r"^\s*\|(\s*:?-{3,}:?\s*\|)+\s*$")


def _table_cells(line: str) -> int:
    """一行里未转义的 `|` 个数 —— 菜名里带 `|` 必须已被转义, 否则列数会被冲乱。"""
    return len(re.findall(r"(?<!\\)\|", line))


def markdown_table_problems(message: str) -> List[str]:
    """答案里若出现表格, 它必须是**结构合法**的表格。

    🔴 2026-08-11: 08-10 起上线的 8 张表, 到用户手里全都少了表头前的空行 ——
       markdown-it 需要空行才把表格当成一个块, 少了它整张表被并进上一段渲染成
       普通文字。四个 PR、两轮 85/85 电池、CI 全绿, 没有一条断言看得见。

    **为什么既有断言全都看不见**:
      · 子串检查 `"销量" in answer` —— 字还在, 只是排版没了
      · 源码检查 `"_markdown_table(" in <该函数源码>` —— 调用还在, 只是下游改了结果
    两种在结构上就不可能覆盖「排版塌了」这个属性。

    ⛔ **必须喂未压平的原文**。电池里到处在用的
       `flat = " ".join(message.split())` 把所有空白压平 ——
       基于 flat 的检查**不可能**发现排版问题, 那等于给自己发一张永远绿的通行证。

    判据: 改渲染就必须看渲染; 而看过一次不够, 要有一道每轮都跑的闸。
    """
    problems: List[str] = []
    lines = message.split("\n")
    for i, line in enumerate(lines):
        if not _TABLE_SEP_RE.match(line):
            continue
        if i == 0:
            problems.append("表格分隔行前面没有表头")
            continue
        header = lines[i - 1]
        if _table_cells(header) != _table_cells(line):
            problems.append(
                f"表格列数对不上: 表头 {_table_cells(header)} 竖线 / "
                f"分隔行 {_table_cells(line)} 竖线")
        # 🔴 这一条就是 8 张表全中的那个: 表头**之前**必须是空行(或整段开头),
        #    否则 markdown-it 把表格并进上一段。
        if i >= 2 and lines[i - 2].strip() != "":
            problems.append(
                f"表格前缺空行(会被并进上一段渲染成一坨): "
                f"上一行是 {lines[i - 2].strip()[:30]!r}")
        for row in lines[i + 1:]:
            if not row.strip().startswith("|"):
                break
            if _table_cells(row) != _table_cells(line):
                problems.append(
                    f"表格数据行列数对不上: {row.strip()[:40]!r}")
                break
    return problems


def _run_case(base: str, auth: Dict[str, str], sid: str,
              case: Dict[str, Any]) -> Dict[str, Any]:
    """跑一条用例, 返回 {problems, flat, followups, elapsed}。不做重试。"""
    started = time.time()
    flat_result = ""
    flat_followups = ""
    try:
        payload: Dict[str, Any] = {"userInput": case["q"], "sessionId": sid}
        if case.get("mode"):
            payload["mode"] = case["mode"]
        if case.get("preview_only"):
            payload["previewOnly"] = True
        resp = _post_json(
            f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute",
            payload, headers=auth,
        )
        response_data = resp.get("data") or {}
        message = str(response_data.get("message") or "")
        result_data = response_data.get("resultData") or {}
        flat_result = json.dumps(response_data, ensure_ascii=False, sort_keys=True)
        followups = result_data.get("suggestedFollowups") or []
        flat_followups = " ".join(
            f"{item.get('label') or ''} {item.get('question') or ''}"
            for item in followups if isinstance(item, dict)
        )
    except Exception as exc:  # noqa: BLE001 — eval must report, not crash
        message = f"<TRANSPORT ERROR: {exc}>"

    flat = " ".join(message.split())
    problems: List[str] = []
    for marker in case.get("contains", []):
        if marker not in flat:
            problems.append(f"缺少「{marker}」")
    for marker in case.get("excludes", []) + _FORBIDDEN_EVERYWHERE:
        if marker in flat:
            problems.append(f"不应出现「{marker}」")
    for marker in case.get("result_contains", []):
        if marker not in flat_result:
            problems.append(f"结构化结果缺少「{marker}」")
    for marker in case.get("followup_contains", []):
        if marker not in flat_followups:
            problems.append(f"按钮缺少「{marker}」")
    for marker in case.get("followup_excludes", []):
        if marker in flat_followups:
            problems.append(f"按钮不应出现「{marker}」")
    # ── invariant: 断言**行为自洽**, 而不是猜今天该走哪个分支 ──────────────
    # (判定逻辑抽成 `invariant_problems` —— 内联在这个要发 HTTP 的函数里时,
    #  它没有任何办法被单测覆盖, 而"永远不会红的断言"正是它自己犯过的错。)
    # 有些用例的正确答案取决于环境(本周有没有数据 / 某租户有没有某个实体)。把环境
    # 条件写进断言, 就要维护一份「今天该是什么样」的推断, 而那份推断会过期 ——
    # 2026-08-10 实测: 本用例按 `today.weekday()==0` 分支, 前提写着「demo 数据覆盖到
    # 昨天, 所以周一时本周为空」, 但当天数据**其实已经在落**(08-10 周一 722 单
    # ¥26.5万), 系统正确给出分析却被判失败。
    # 判据: **能断言不变式就别断言分支** —— 分支要靠一份会过期的环境推断,
    #       不变式只靠答案自己。
    #
    # ⚠️ 2026-08-10 二次修正: 上面那条改写完成的当天, 本结构只支持
    #    (need → forbid) 一个方向, 也就是**只能表达「说了 A 就不许说 B」**。
    #    于是「说了没数据就必须给相邻周期」这条被写成 `(["没有可用的经营数据"], [])`
    #    —— forbid 是空表, 内层循环一次都不执行, 那条不变式**永远不会红**,
    #    而它旁边的注释信誓旦旦地说它在检查相邻周期。注释声称的检查不存在,
    #    比不写更糟: 下一个人会把它当成已经守住的东西。
    #    补上第三位 require_any(至少命中一个)。⛔ 用 any 不用 all —— 相邻周期
    #    有好几种合法说法(最近7天/上周/上个月), 要求全都出现等于要求答案啰嗦。
    problems += invariant_problems(flat, case.get("invariant", {}))
    # ⛔ 喂 `message` 不是 `flat` —— flat 已经把空白压平, 拿它查排版等于自发通行证。
    #    这条对**每一题**都跑, 不用逐题登记: 表格是哪一题给的不重要, 给了就必须合法。
    problems += markdown_table_problems(message)
    return {
        "problems": problems, "flat": flat,
        "followups": flat_followups, "elapsed": time.time() - started,
    }


def _preflight_fixture(base: str, auth: Dict[str, str]) -> List[str]:
    """跑断言之前，先向**真实租户**核对夹具里的实体确实存在。

    🔴 为什么值得单独跑这一步：夹具过期和 AI 退化会产生**一模一样的红**——
       52 条断言各挂各的，读起来像「模型突然不认识菜名了」。2026-08-06 换租户
       之后正是这种情形，只不过更早一步崩在登录上，连红都没红出来。
       这一步把「夹具指向的东西在这个租户里不存在」单独摘出来，报成一句人话。

    ⚠️ 只做**存在性**核对，不做数值核对：数值每天滚动，那是断言层的事。
    返回问题列表；空列表 = 夹具与租户对得上。
    """
    problems: List[str] = []
    sid = _rand_sid("preflight")

    stores = _run_case(base, auth, sid, {"q": "门店名单给我看看"})["flat"]
    for label, name in (("_STORE_A", _STORE_A), ("_STORE_B", _STORE_B),
                        ("_STORE_C", _STORE_C),
                        ("_STORE_AMBIG_1", _STORE_AMBIG_1),
                        ("_STORE_AMBIG_2", _STORE_AMBIG_2)):
        if name not in stores:
            problems.append(f"{label}=「{name}」在 {FACTORY_ID} 的门店名单里不存在")
    if stores.count(_STORE_AMBIG) < 2:
        problems.append(
            f"_STORE_AMBIG=「{_STORE_AMBIG}」在门店名单里只出现 "
            f"{stores.count(_STORE_AMBIG)} 次 —— 歧义反问用例需要它匹配至少两家")

    for label, dish, want_found in (
            ("_DISH_MAIN", _DISH_MAIN, True),
            ("_DISH_ALT", _DISH_ALT, True),
            ("_DISH_SIG", _DISH_SIG, True),
            ("_DISH_OPERATE", _DISH_OPERATE, True),
            ("_DISH_ALT_TYPO", _DISH_ALT_TYPO, False),
            ("_DISH_MISSING", _DISH_MISSING, False)):
        flat = _run_case(base, auth, _rand_sid("preflight"),
                         {"q": f"本月全部门店{dish}的销量"})["flat"]
        # ⛔ 判「这道菜在不在」要看**答出来了没有**，不能看有没有出现「没有找到」。
        #    2026-08-09 实测: 不存在的菜有两种拒答措辞 ——「没有找到名为…的菜品」
        #    和「查询维度超出计划 resolver 的能力范围」。第一版只认前者，
        #    于是把后者读成「这道菜存在」，飞行前核对自己报了个假红。
        #    正确判据是正向的: 答案里以「」引出这道菜**并且**给了销量。
        answered = (f"「{dish}」" in flat
                    and ("销量" in flat or "营收" in flat)
                    and "没有找到" not in flat)
        found = answered
        if found != want_found:
            problems.append(
                f"{label}=「{dish}」应当{'存在' if want_found else '**不存在**'}"
                f"于 {FACTORY_ID} 的菜单，实测{'找得到' if found else '找不到'}")
    return problems


# ── 本轮取数条件 (provenance) ──────────────────────────────────────────
#
# 🔴 2026-08-11: 电池分数**不是代码版本的函数**, 是
#        代码 × 今天哪个模型还活着 × 计划缓存冷热
#    三者的函数。不记下来, 下一个人拿到两个分数就没法判断差异来自哪一项。
#
#    当天实测: 交接文档与我自己都报过「两轮读数完全一致」—— 它是**构造出来的**。
#    第二轮 85 题里 59 题直接吃了第一轮刚写进 `_SEMANTIC_PLAN_CACHE`(进程内,
#    TTL 6h) 的计划, 结构上不可能与第一轮不同。
#    判据: **两个读数之间有因果链(前一轮把计划写进了后一轮读的缓存),
#          就不构成重复验证。**
#
#    同一层缓存还会把**非确定性伪装成稳定**: 电池 [27] 连过 12 轮再连挂 2 轮,
#    转折点压在一次部署重启上, 看着像那次部署的回归; 落库记录显示同代码同模型下
#    21:16 通过、21:25 失败 —— planner 在摇摆, 缓存只是把某一次冻住了 6 小时,
#    而部署重启 = 重掷骰子。
#
# ⛔ 不从 HTTP 响应判冷热: 实测同一句连打两次, 响应里
#    matchMethod / cacheHitType / fromCache / source / queryPlanHash
#    **逐字相同**, 没有任何字段能区分。权威信号只在服务日志里。
_PROD_LOG_PATH = os.environ.get(
    "RESTAURANT_EVAL_PROD_LOG", "/www/wwwroot/cretas/python-prod.log")

_PLAN_CACHE_HIT_MARK = "zero-token plan-cache hit"
_LLM_SERVED_RE = re.compile(r"slot=\S+ OK via (\S+)")


def summarize_provenance(log_slice: str) -> Dict[str, Any]:
    """数出本轮**在什么条件下取的数**: 几次吃了旧计划, 哪些模型在服务。

    纯函数, 入参是本轮跨越的那段日志文本 —— 这样它能被单测覆盖。
    「要发 HTTP 才能跑的判定逻辑没法被单测覆盖」是本文件早先栽过的坑
    (见 `invariant_problems` 抽出来的那段注释)。
    """
    models: Dict[str, int] = {}
    for model in _LLM_SERVED_RE.findall(log_slice):
        models[model] = models.get(model, 0) + 1
    return {
        "cache_hits": log_slice.count(_PLAN_CACHE_HIT_MARK),
        "fresh_parses": sum(models.values()),
        "models": models,
    }


def render_provenance(info: Dict[str, Any]) -> List[str]:
    """渲染成人话。

    ⛔ 读不到日志时必须**明说不可知**, 不能省掉这一段 —— 缺失的段落会被读成
       「没问题」。「沉默即通过」是本仓反复在拆的东西。
    ⛔ 不设「缓存命中率超过 X% 才告警」这种阈值: 阈值是猜的, 而事实是精确的 ——
       直接报「有几次吃的是旧计划」, 让读的人自己判断可比性。
    """
    head = ["", "── 本轮取数条件 ──"]
    if info.get("unavailable"):
        return head + [
            f"⚠️ 取数条件**不可知**({info['unavailable']})。",
            "   不要拿本轮分数与别轮直接比: 吃缓存的轮次是重放, 不是独立样本。",
        ]
    hits, fresh = info["cache_hits"], info["fresh_parses"]
    lines = head + [f"计划缓存: 命中 {hits} / 真解析 {fresh}"]
    if hits == 0:
        lines.append("  ✅ 全冷 —— 可与其它**全冷**轮次比较。")
    else:
        lines.append(
            f"  ⚠️ 其中 {hits} 次吃的是先前轮次写进缓存的计划 —— 这部分"
            f"**不是独立样本**, 与上一轮一致属于结构性必然, 不构成复现。")
    if info["models"]:
        lines.append("服务模型: " + "、".join(
            f"{m} ×{n}" for m, n in
            sorted(info["models"].items(), key=lambda kv: (-kv[1], kv[0]))))
    else:
        lines.append("服务模型: (本轮没有真解析 —— 全部命中缓存)")
    return lines


def _log_cursor() -> Optional[int]:
    """记下本轮开始时服务日志的长度, 之后只读这之后追加的部分。"""
    try:
        return os.path.getsize(_PROD_LOG_PATH)
    except OSError:
        return None


def _provenance_since(start: Optional[int]) -> Dict[str, Any]:
    if start is None:
        return {"unavailable": f"读不到 {_PROD_LOG_PATH}"}
    try:
        if os.path.getsize(_PROD_LOG_PATH) < start:
            # 轮转过就没法只读增量了 —— 说不可知, 别拿残缺的一段冒充完整。
            return {"unavailable": "日志在本轮期间轮转过"}
        with open(_PROD_LOG_PATH, "rb") as handle:
            handle.seek(start)
            raw = handle.read()
    except OSError as exc:  # noqa: BLE001 — 取数条件读不到不该让电池崩
        return {"unavailable": f"读日志失败: {exc}"}
    return summarize_provenance(raw.decode("utf-8", "replace"))


def run_eval(base: str, only: Optional[str] = None) -> int:
    # 本轮开始时的日志长度 —— 收尾时只读这之后追加的那一段, 用来说清
    # 「这一轮是在什么条件下取的数」(见 `summarize_provenance` 上面那段)。
    # 放在登录之前: 预检夹具也会发查询、也会焐热缓存, 它同样属于本轮条件。
    log_cursor = _log_cursor()

    # ── 登录：用租户自己的账号，不用免密 demo-login ──────────────────────
    #
    # 🔴 2026-08-06~08-09 这道电池连挂 4 天，就是因为它打的是
    #    `/auth/demo-login?tenant=rest`，而 8-05 租户收敛已经把
    #    `cretas.demo.rest.*` 清空、DEMO_REST 的 9 个账号全部停用。
    #
    # ⛔ 不把 demo-login 重新指向 MOCK_REST —— application.properties 里写着
    #    两条互相咬合的约束：demo 租户会被 `DemoReadOnlyInterceptor` 上只读锁，
    #    而「⛔ MOCK_REST 绝不能进 cretas.demo.factory-ids，进去它就失去写能力，
    #    而演示需要『有操作设置的』」。于是只改指向就等于开一个**免密可写的
    #    超管入口**，加进名单又会废掉演示的写能力。两边都不能选。
    #    8-05 拍板的正解本来就写在那段注释里：**演示一律用 MOCK_REST 的账号登录**。
    #
    # ⚠️ 凭证只从环境变量取，绝不落进仓库（本项目硬规则）。缺了就明确报错停下，
    #    不回落到任何免密路径 —— 那正是上一次静默失效的形状。
    import os

    username = os.environ.get("RESTAURANT_EVAL_USERNAME", "").strip()
    password = os.environ.get("RESTAURANT_EVAL_PASSWORD", "")
    if not username or not password:
        print("FATAL: 缺少评测凭证环境变量 RESTAURANT_EVAL_USERNAME / "
              "RESTAURANT_EVAL_PASSWORD。")
        print(f"  它们应指向 {FACTORY_ID} 的一个活跃账号；服务器上由 "
              "scripts/cron/restaurant-ai-eval.sh 从 .env.prod 注入。")
        return 2
    login = _post_json(f"{base}/api/mobile/auth/unified-login", {
        "username": username,
        "password": password,
        "deviceInfo": {"deviceId": _rand_sid("eval-device"),
                       "deviceModel": "regression-eval",
                       "platform": "android", "osVersion": "1"},
    })
    data = login.get("data") or {}
    token = data.get("token") or (data.get("tokens") or {}).get("token")
    if not token:
        print(f"FATAL: 登录失败 (user={username}) -> {login}")
        return 2
    if data.get("factoryId") and data["factoryId"] != FACTORY_ID:
        # ⛔ 登录成功但登进了别的租户 = 后面 52 条断言全部在错的数据上跑。
        #    宁可停下: 这种「跑完了但量错了对象」比崩掉更难发现。
        print(f"FATAL: 账号 {username} 属于租户 {data['factoryId']}, "
              f"而电池要测的是 {FACTORY_ID} —— 断言会跑在错的数据上。")
        return 2
    auth = {"Authorization": f"Bearer {token}"}

    fixture_problems = _preflight_fixture(base, auth)
    if fixture_problems:
        print(f"FATAL: 租户夹具与 {FACTORY_ID} 对不上 —— 先修夹具再看断言，"
              "否则 52 条断言会各挂各的、看起来像模型退化:")
        for problem in fixture_problems:
            print(f"  · {problem}")
        return 3

    # ── 预分组成"执行单元" ────────────────────────────────────────────
    # 独立用例各成一个单元; 同一 chain 的全部步骤合成一个单元, 因为链是有状态的:
    # 单独重跑其中一步没有意义(会话里缺前置轮次), 要重试只能整条链换新会话重来。
    units: List[Any] = []
    chain_steps: Dict[str, List[Any]] = {}
    for idx, case in enumerate(CASES, 1):
        chain = case.get("chain")
        if not chain:
            units.append((None, [(idx, case)]))
        elif chain in chain_steps:
            chain_steps[chain].append((idx, case))
        else:
            chain_steps[chain] = [(idx, case)]
            units.append((chain, chain_steps[chain]))

    # ⛔ --only 命中链式用例时直接拒绝。
    # 链是有状态的: 只跑其中一步 = 会话里缺前置轮次, 得到的通过/失败都不可信。
    # (本守卫补于 2026-07-28 审查: 之前 --only 会静默打断链, 结果误导人。)
    hit_chains = sorted({
        case["chain"]
        for _chain, steps in units
        for _idx, case in steps
        if case.get("chain") and only and only in case["q"]
    })
    if hit_chains:
        print(f"FATAL: --only '{only}' 命中链式用例 {hit_chains}; "
              f"链必须整条跑, 单跑一步会话里缺前置轮次, 结果不可信。")
        return 2

    passed, failed = 0, 0
    failures: List[str] = []
    latencies: List[float] = []

    def _report(idx: int, case: Dict[str, Any], outcome: Dict[str, Any]) -> None:
        nonlocal passed, failed
        latencies.append(outcome["elapsed"])
        q, problems = case["q"], outcome["problems"]
        if problems:
            failed += 1
            failures.append(
                f"[{idx:02d}] {q}\n"
                f"     {'; '.join(problems)}\n"
                f"     实际: {outcome['flat'][:160]}\n"
                f"     按钮: {outcome['followups'][:160]}"
            )
            print(f"✗ [{idx:02d}] {outcome['elapsed']:5.1f}s {q} — {'; '.join(problems)}")
        else:
            passed += 1
            print(f"✓ [{idx:02d}] {outcome['elapsed']:5.1f}s {q}")

    for chain, steps in units:
        if only and not any(only in c["q"] for _i, c in steps):
            continue
        if chain is None:
            idx, case = steps[0]
            # 蓝绿切换/熔断窗会产生瞬态失败 — 失败自动重试一次再定论。
            outcome = _run_case(base, auth, _rand_sid("solo"), case)
            if outcome["problems"]:
                time.sleep(30)
                outcome = _run_case(base, auth, _rand_sid("solo"), case)
            _report(idx, case, outcome)
            continue

        # 链: 整条跑; 若失败且形态是瞬态, 换新会话整条重来一次。
        for attempt in (1, 2):
            sid = _rand_sid(chain)
            outcomes = [_run_case(base, auth, sid, case) for _i, case in steps]
            broken = [o for o in outcomes if o["problems"]]
            if not broken or attempt == 2 or not any(
                _looks_transient(o["flat"]) for o in broken
            ):
                break
            print(f"  ↻ 链 {chain} 命中瞬态故障, 换新会话整条重跑…")
            time.sleep(30)
        for (idx, case), outcome in zip(steps, outcomes):
            _report(idx, case, outcome)

    if latencies:
        ordered = sorted(latencies)
        p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]
        print(f"\n耗时: 平均 {sum(latencies)/len(latencies):.1f}s | 中位 "
              f"{ordered[len(ordered)//2]:.1f}s | p95 {p95:.1f}s | 最慢 {ordered[-1]:.1f}s")
    print(f"== {passed} passed, {failed} failed / {passed + failed} run ==")
    # ⛔ 无论成败都打 —— 取数条件是**读这个分数的前提**, 不是失败时才需要的附注。
    print("\n".join(render_provenance(_provenance_since(log_cursor))))
    if failures:
        print("\n".join(["", "── 失败明细 ──", *failures]))
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com",
                        help="Java 后端 base URL")
    parser.add_argument("--only", default="",
                        help="only run cases whose query contains this substring")
    args = parser.parse_args()
    sys.exit(run_eval(args.base, args.only or None))


if __name__ == "__main__":
    main()
