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
    r"^[\s，。；：、]*(?:(?:来源|内部意图|意图|与|和|来自|数据表|利润表数据|"
    r"业务数据|经营数据|数据|结果)[\s，。；：、]*)*$"
)
NO_DISPLAYABLE_BUSINESS_RESULT = "没有获得可展示的业务结果，本次不生成结论。"

# ── 店长读不懂的词：两张表，各自写明「谁在强制它」 ────────────────────────
#
# 🔴 2026-08-12 搬到这里的理由：`INTERNAL_VOCAB` 原本只活在
#    `test_no_internal_jargon_in_customer_text.py` 里。那道闸扫的是**源码字符串**，
#    因此运行时判据（答案里有没有黑话）想复用同一份词表时**够不着它** ——
#    production 代码 import 测试模块是反的。两边各抄一份就会漂，而这正是本仓
#    反复拆过的形状（会随环境变的东西只能有一处定义）。
#    搬过来之后：源码闸和运行时判据读同一个元组，加一个词两边同时生效。
#
# ⛔ 两张表**不能合并**，因为强制它们的载体不同，合并会立刻让 CI 变红：

#: 内部概念名。**源码闸 + 运行时判据双方强制。**
#: 只收「店长读不懂且没法据此行动」的词；「毛利」「门店」这类业务词不在此列。
INTERNAL_VOCAB = (
    "问题对象", "分析范围", "执行范围", "语义规划", "查询计划", "计划版本",
    "维度", "槽位", "解析器", "置信度", "相似度", "向量", "意图代码",
)

#: 统计/计量黑话。**只由运行时判据强制，源码闸刻意不扫。**
#:
#: ⚠️ 这个「刻意」有实测依据，不是偷懒：2026-08-12 把这五个词并进
#:    `INTERNAL_VOCAB` 跑源码闸，当场红在
#:    `restaurant_intent:1316`「…才能计算弹性、置信区间和决定系数」——
#:    那是一句**真的会发给店长**的话。也就是说源码里现存违例，
#:    合并两张表 = 在改文案之前先把 CI 弄红。
#:    改那句文案属于 P-B（契约白话化）的范围，不属于评估升级。
#:    在它改完之前，这五个词由运行时判据盯着：答案里真出现了就报，
#:    而不是靠一句「记得改」的注释（注释不构成约束力，只有会红的断言构成）。
ANALYST_JARGON = (
    "置信区间", "因果效果", "回归估计", "显著性", "决定系数", "p值", "P值",
)

# The restaurant chat is read by owners and store managers, not engineers.
# Keep the replacements ordered from specific phrases to individual jargon so
# that the resulting sentences remain natural instead of becoming word-for-word
# translations of implementation details.
_CUSTOMER_PLAIN_LANGUAGE_REPLACEMENTS = (
    ("叙述模型预算已用完", "今天的智能分析次数已经用完"),
    ("叙述模型暂时不可用", "智能分析暂时有点忙"),
    ("叙述未通过数据因果门禁", "刚才生成的说明和系统数据对不上"),
    ("确定性多维分析", "按系统真实数据做的综合分析"),
    ("确定性计算发现", "从系统数据里看到的重点"),
    ("主因判定（确定性）", "从数据看，主要原因"),
    ("分维度数据与覆盖情况", "各方面的经营数据"),
    ("本轮已纳入维度", "这次已经查看的方面"),
    ("尚缺但可补充的维度", "还可以补充的方面"),
    ("还可补充的分析维度", "还可以补充的方面"),
    ("缺失维度清单", "还没有数据的方面"),
    ("缺失维度", "还没有数据的方面"),
    ("页面聚焦上下文", "当前页面信息"),
    ("结构化分析提示", "分析提示"),
    ("结构化上下文", "已经整理好的信息"),
    ("结构化数据", "系统里已经整理好的数据"),
    ("显式指定", "直接告诉我"),
    ("显式提供", "已经提供"),
    ("显式选择", "直接选择"),
    ("显式确认", "亲自确认"),
    ("语义规划", "问题理解"),
    ("语义解析", "问题理解"),
    ("实体槽位", "菜品、门店等条件"),
    ("时间槽位", "时间条件"),
    ("门店槽位", "门店条件"),
    ("Answer Contract", "回答检查"),
    ("QueryPlan", "查询安排"),
    ("可比基线", "能公平对比的历史数据"),
    ("同口径", "用同一种算法"),
    ("同一口径", "用同一种算法"),
    ("应收口径", "按订单应收金额计算"),
    ("聚合口径", "按全部数据合并计算"),
    ("数据口径与限制", "数据说明"),
    ("投诉类型为商家申诉口径", "这里的投诉只统计商家申诉记录"),
    ("相关≠因果", "一起变化不代表就是原因"),
    ("相关不等于因果", "一起变化不代表就是原因"),
    ("不宣称因果", "不能直接说它就是原因"),
    ("因果需标推测", "只能说可能有关，不能说就是原因"),
    ("因果归因", "判断原因"),
    ("因果增量", "由活动带来的新增效果"),
    ("相关信号", "一起变化的数据"),
    ("相邻指标替代", "拿别的数据顶替"),
    ("数据因果门禁", "数据核对"),
    ("Demo 租户", "演示账号"),
    ("Top 门店", "营业额靠前的门店"),
    ("POS/agg_daily", "收银和营业汇总"),
    ("POS+agg_daily_cost", "收银和成本汇总"),
    ("agg_product/POS明细+dim_product", "菜品销售明细"),
    ("restaurant_sku_forms完整BOM成本", "菜品配方与成本"),
    ("POS门店聚合", "各门店收银数据"),
    ("POS就餐人数", "收银记录的就餐人数"),
    ("收银就餐人数", "收银记录的就餐人数"),
    ("POS订单类型", "收银记录的订单类型"),
    ("POS餐段", "收银记录的用餐时段"),
    ("POS优惠金额与构成", "收银记录的优惠金额和类型"),
    ("非VIP", "非会员"),
    ("VIP", "会员"),
    ("评价ID DISTINCT", "评价编号去重"),
    ("按 评价编号去重", "按评价编号去重"),
    ("观测日", "有数据的天数"),
    ("全链基准", "全部门店平均值"),
    ("全链客单价", "全部门店每单平均消费"),
    ("客流效应", "订单量带来的差额"),
    ("客单价效应", "每单平均消费带来的差额"),
    ("客单价", "每单平均消费"),
    ("数据关系", "从数据看"),
    ("[小样本，结论需谨慎]", "（数据量较少，结论需谨慎）"),
    ("[口味/品质标签，非菜名]", "（口味或品质评价，不是菜名）"),
    ("resolver", "数据查询"),
    ("确定性", "按系统数据算出的"),
    ("显式", "明确"),
    ("结构化", "整理后的"),
    ("语义", "意思"),
    ("槽位", "条件"),
    ("多维", "多个方面"),
    ("维度", "方面"),
    ("可比基准", "能公平对比的历史数据"),
    ("基线", "用来对比的历史数据"),
    ("口径", "计算方法"),
    ("小样本", "数据量较少"),
    ("推测", "可能"),
)

_CUSTOMER_PLAIN_LANGUAGE_REGEX_REPLACEMENTS = (
    (re.compile(r"\bVIP\b", re.IGNORECASE), "会员"),
    (re.compile(r"\bBOM\b", re.IGNORECASE), "菜品配方"),
    (re.compile(r"\bROI\b", re.IGNORECASE), "活动是否划算"),
    (re.compile(r"\bSIMULATED\b", re.IGNORECASE), "演示数据"),
    (re.compile(r"\bPROXY\b", re.IGNORECASE), "参考数据"),
    (re.compile(r"\bMISSING\b", re.IGNORECASE), "暂无数据"),
    (re.compile(r"\bREAL\b", re.IGNORECASE), "真实数据"),
    (re.compile(r"\bDemo\b", re.IGNORECASE), "演示"),
)


def sanitize_customer_ai_text(value: Optional[str]) -> str:
    """Remove implementation details and rewrite jargon as store-manager language."""
    if not value:
        return ""
    cleaned_lines = []
    for raw_line in str(value).splitlines():
        # ⛔ 空行是 markdown 的**块分隔符**, 不是可有可无的留白。
        #
        # 🔴 2026-08-11 prod 实测(打真接口读渲染结果): 08-10 起陆续上线的 8 张
        #    markdown 表格, 到用户手里全都少了表头前的空行 ——
        #        '**本月…菜品销量排行（卖得最好前 5）：**\n| # | 菜品 | …'
        #    resolver 拼的明明是 `[标题, ""] + _markdown_table(...)`, 而响应里
        #    `answer_text` 的连续空行数是 **0**。根因就是这里: 原实现用
        #    `if line and ...` 把空行整行丢掉, 再用单个 `\n` 重拼。
        #    不报错 —— markdown-it 需要空行才把表格当成一个块, 少了它整张表被并进
        #    上一段渲染成一坨。
        #
        # 判据: **清洗文本的函数不能顺手改文本的结构。** 要删的是「实现细节」,
        #       空行不是实现细节。
        # ⚠️ 只保留**原本就是空行**的那些: 清洗之后才变空的(纯实现细节行)照旧丢掉,
        #    否则删掉一行技术噪音会在原地留下一个空行。
        if not raw_line.strip():
            cleaned_lines.append("")
            continue
        line = raw_line
        for old, new in _CUSTOMER_PLAIN_LANGUAGE_REPLACEMENTS:
            line = line.replace(old, new)
        for pattern, replacement in _CUSTOMER_PLAIN_LANGUAGE_REGEX_REPLACEMENTS:
            line = pattern.sub(replacement, line)
        line = _TOOL_EXPLANATION.sub("", line)
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
        line = re.sub(r"（\s*[，,]\s*", "（", line)
        line = re.sub(r"\s+([（(])", r"\1", line)
        line = re.sub(r"[ \t]+([，。；：])", r"\1", line).strip(" ，；：")
        if line and not _TECH_ONLY.fullmatch(line):
            cleaned_lines.append(line)
    cleaned = "\n".join(cleaned_lines).strip()
    return cleaned or NO_DISPLAYABLE_BUSINESS_RESULT


def has_displayable_business_result(value: Optional[str]) -> bool:
    """Return false when sanitization removed every business-facing fact."""
    text = (value or "").strip()
    return bool(text and text != NO_DISPLAYABLE_BUSINESS_RESULT)
