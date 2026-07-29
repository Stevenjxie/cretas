"""``output_preference`` → 报告导出的接线口 (spec §2.1 ↔ §3.2)。

意图层已经会把「生成报告 / 出个报告 / 导出报告 / 月度报告 / pdf」识别成
``OUTPUT_FORM_REPORT_FILE``，并通过 ``tiered_answer`` 的 ``output_preference``
字段一路白名单转发到 Java / 前端 (``gold_reads.py`` 的 tiered-answer 端点)。

这个模块只回答一个问题: **这一轮要不要出文件**。判断逻辑集中在这里, 是为了
不让 web-admin / mobile-rest-ai / RN 三处各自猜一套 —— 那正是 §2.1 引入
``output_preference`` 想消灭的分裂。

⚠️ 有意**不**在 ``tiered_answer`` 里自动触发导出: 报告要跑 N 个计划、动辄十几
秒，聊天那一轮不该被它拖住。正确接法是渲染层看到 ``report_file`` 就给一个
「生成月度报告文件」的按钮 / 让 cron 走 ``/monthly-report/export``。
"""
from __future__ import annotations

from typing import Any, Optional, Sequence

# 与 restaurant_intent.OUTPUT_FORM_REPORT_FILE 同值。这里不 import 那个模块，
# 免得报告包被 import 时把整条餐饮意图链拖起来 (runner 也是同样的延迟导入
# 策略)；两边不一致会被 test_report_file_form_matches_intent_layer 抓住。
OUTPUT_FORM_REPORT_FILE = "report_file"


def wants_report_file(output_preference: Optional[Sequence[Any]]) -> bool:
    """这一轮用户是不是在要一份**文件**。"""
    if not output_preference:
        return False
    return any(
        isinstance(form, str) and form.strip() == OUTPUT_FORM_REPORT_FILE
        for form in output_preference
    )
