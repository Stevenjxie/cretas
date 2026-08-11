"""餐饮语义链的两个预算必须对得上 —— 跨语言的那一对。

## 为什么需要这道闸

2026-08-12 实测事故: Python 给语义链批了 45s, 而**出资方**是 Java —— RN 只调
Java, Java 转发 Python, Java 的客户端超时才是 Python 被允许的最长时间。当时
Java 是 30s, 于是:

    [Java] Tiered intent answer failed ... in 30002ms: timeout
    [Java] [Branch:TieredFirst] restaurant semantic planner unavailable

用户看到的「餐饮语义规划暂时不可用」是 **Java 的秒表到点**写出来的, 不是 Python
失败。Python 可能第 40 秒才算完, 算完也没人收。

最刺眼的是: Python 那个 45.0 常量**正上方的注释逐字写着** "Keep the whole
cascade inside Java's independent 30 s deadline"。**意图写对了, 约束不存在** ——
注释管不住常量, 声明不构成机制。同一个文件里单跳预算做对了(`from
common.llm_router import _SLOT_HOP_BUDGET_SECONDS`, 单一来源), 总预算恰恰犯了
它自己警告过的错, 只不过漂的对象在另一个语言里。

## 判据

两侧都从**源码**里读常量, 不是各自 import 一份可能已经漂了的副本 ——
Java 那侧本来就 import 不进来, 所以只能读源码; 为了让两侧同源, Python 这侧
也读源码而不是 import。这样任何一边被人改了数字, 这里立刻红。
"""
import re
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[3]
_PY_SRC = (_REPO / "backend/python/smartbi/gold/restaurant/restaurant_intent.py")
_JAVA_SRC = (_REPO / "backend/java/cretas-api/src/main/java/com/cretas/aims"
             / "client/GoldFinanceClient.java")

# Java 必须比 Python 多出的余量: 网络往返 + 请求/响应序列化 + Java 侧编排。
_MIN_MARGIN_SECONDS = 2.0


def _python_total_budget_seconds() -> float:
    src = _PY_SRC.read_text(encoding="utf-8")
    m = re.search(r"^_SEMANTIC_TOTAL_TIMEOUT_SECONDS\s*=\s*([0-9.]+)",
                  src, re.M)
    assert m, "没在 restaurant_intent.py 里找到 _SEMANTIC_TOTAL_TIMEOUT_SECONDS"
    return float(m.group(1))


def _java_min_timeout_seconds() -> float:
    src = _JAVA_SRC.read_text(encoding="utf-8")
    m = re.search(r"MIN_TIERED_ANSWER_TIMEOUT_MS\s*=\s*([0-9_]+)L", src)
    assert m, "没在 GoldFinanceClient.java 里找到 MIN_TIERED_ANSWER_TIMEOUT_MS"
    return int(m.group(1).replace("_", "")) / 1000.0


@pytest.mark.skipif(not _JAVA_SRC.exists(), reason="Java 源码不在本次检出范围内")
def test_java_deadline_covers_the_python_semantic_budget():
    """出资方的预算必须盖得住花钱的那一侧, 否则 Python 算完了也没人收。"""
    py = _python_total_budget_seconds()
    java = _java_min_timeout_seconds()
    assert java >= py + _MIN_MARGIN_SECONDS, (
        f"Java 的 MIN_TIERED_ANSWER_TIMEOUT_MS={java}s 盖不住 Python 的 "
        f"_SEMANTIC_TOTAL_TIMEOUT_SECONDS={py}s (至少要多 {_MIN_MARGIN_SECONDS}s "
        f"给网络和序列化)。两个数是一对: 改一个必须同时改另一个 —— "
        f"2026-08-12 就是这一对打架(45 vs 30)让用户拿到「规划器暂时不可用」。"
    )


def test_python_total_budget_fits_at_least_two_hops():
    """总预算必须放得下「一个候选失败后还能再试一个」。

    这是 `_SEMANTIC_TOTAL_TIMEOUT_SECONDS` 原注释里的口径("总预算 = 单跳 × ~2"),
    在这里变成一条会红的断言 —— 否则降级路径上第一个候选一挂, 链就没机会了。
    """
    from common.llm_router import _SLOT_HOP_BUDGET_SECONDS

    py = _python_total_budget_seconds()
    assert py >= _SLOT_HOP_BUDGET_SECONDS * 2, (
        f"总预算 {py}s 放不下两跳 (单跳 {_SLOT_HOP_BUDGET_SECONDS}s) —— "
        f"第一个候选超时就没有第二次机会了"
    )
