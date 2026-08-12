"""数字出处子集闸 —— 答案正文里的每个数，都得在执行产出里找得到出处。

## 它防的是什么

「答非所问」和「数字是编的」是两种不同的病。契约闸管前者。这个管后者:
正文里出现一个 ¥22,405,943，而执行产出里没有任何数能推出它 —— 那就是模型
自己写出来的数。店长看不出区别。

## 🔴 四处必须**显式**设计，否则它就是个恒真式

上一轮实测过一个「自洽闸」是恒真式(左右来源相同，一次都红不了)，
以及一个源码闸绿是因为它在断言里**自己模拟**了被测的清洗。这四条是那两次的解药:

### ① 归一规则写在闸自己身上，不在调用方

`¥22,405,943.00` / `22405943` / `2240.5943万` 是同一个数。归一必须是闸的一部分 ——
放在调用方就变成「调用方先把两边洗成一样，再断言它们一样」。

### ② 派生数：**允许由源数字算出**(显式决定，不是默认)

毛利率 = 毛利 ÷ 营收。它**不在**源数据里，是正文里算出来的。
两个选项:
  (a) 只认源数字 → 会把**正确**的答案判红(毛利率永远找不到出处)
  (b) 允许一层派生 → 闸变松，但仍然抓得住凭空捏造的数

选 (b)，且**只做一层、只做四则**(`a/b`、`a/b*100`、`a-b`、`a+b`、`a*b`)。
理由: 捏造的数几乎不会恰好等于任意两个真数的四则结果；而两层派生的闭包会
大到什么数都"找得到出处"——那才是真的恒真式。
⛔ 这个决定写在这里，不写在文档里 —— 文档不会在闸变松时报警。

### ③ 必须跑在产品实际入口

闸的输入必须是**产品真实返回的** `answer_text` 与 `kpis/rows`。
⛔ 断言里不许自己先调渲染函数或清洗函数再比 —— 那样测的是「我调的那两个函数
   一致」，不是「产品端出去的正文有出处」。

### ④ 上线前先量命中率分布

近 0% 或近 100% 都是仪器问题，不是产品结论:
  - 近 100%(全都有出处) → 多半是归一/派生太松，闸放行一切
  - 近 0%(全都没出处)   → 多半是我没把执行产出喂对，量的是自己的接线

所以本模块**先作为仪器交付**(`audit_answer` 返回逐个数字的判定)，
把分布量出来、逐条读过之后，才谈要不要变成放行门禁。
"""
from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, Iterable, List, Sequence, Tuple

#: 🔴 日期/时刻**先剔掉再找数字**。第一版没有这一步, 实测在真实答案上
#:    `2026-07-14` 被切成 `2026` / `-07` / `-14` 三个"数字", 全判无出处 ——
#:    命中率中位数被压到 5.6%。那不是产品在编数, 是我的正则在编数字。
#:    (这正是判据 ④ 「近 0% 先怀疑仪器」抓出来的第一件事。)
_DATETIME_RE = re.compile(
    r"\d{4}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?"   # 2026-07-14 / 2026年7月14日
    r"|\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?"                    # 07-14 / 7月14日
    r"|\d{1,2}:\d{2}(?::\d{2})?"                            # 12:30[:00]
)

#: 正文里的数字。允许千分位、小数、负号、百分号；单位(元/万/%)在归一时剥掉。
_NUMBER_RE = re.compile(r"-?\d[\d,]*(?:\.\d+)?")

#: 归一后保留的小数位。比这更细的差别在正文里本来也不会呈现。
_QUANT = Decimal("0.0001")

#: ⚠️ 这些数**不进闸** —— 它们是排版/口径本身，不是「从数据里查出来的数」。
#: 例如「最近30天」的 30、「前 5」的 5、「共 2 个月」的 2。
#: ⛔ 这个豁免集合是闸最容易被撑大的地方: 每加一个，闸就松一点。
#:    加之前先问「这个数是不是真的与数据无关」。
_STRUCTURAL = {
    Decimal(0), Decimal(1), Decimal(2), Decimal(3), Decimal(5), Decimal(7),
    Decimal(10), Decimal(30), Decimal(60), Decimal(90), Decimal(100), Decimal(365),
}


def _norm(raw: str) -> Decimal | None:
    """① 归一写在闸里：剥千分位，量化到固定小数位。"""
    try:
        return Decimal(raw.replace(",", "")).quantize(_QUANT)
    except (InvalidOperation, ValueError):
        return None


def extract_numbers(text: str) -> List[Tuple[str, Decimal]]:
    """正文里出现的数字，保留原文串以便报告时贴原文。

    ⚠️ 先把日期/时刻抹成空格 —— 它们是**时间范围的表述**，不是查出来的数。
    """
    text = _DATETIME_RE.sub(" ", text or "")
    out = []
    for m in _NUMBER_RE.finditer(text):
        v = _norm(m.group())
        if v is not None:
            out.append((m.group(), v))
    return out


def _walk(obj: Any) -> Iterable[Decimal]:
    """执行产出里所有的数 —— kpis / rows / 嵌套 dict 一律走到底。"""
    if isinstance(obj, bool):
        return
    if isinstance(obj, (int, float, Decimal)):
        try:
            yield Decimal(str(obj)).quantize(_QUANT)
        except InvalidOperation:
            pass
        return
    if isinstance(obj, str):
        for _, v in extract_numbers(obj):
            yield v
        return
    if isinstance(obj, dict):
        for v in obj.values():
            yield from _walk(v)
        return
    if isinstance(obj, (list, tuple)):
        for v in obj:
            yield from _walk(v)


def source_numbers(execution_output: Any) -> set:
    return set(_walk(execution_output))


def derived_closure(base: set) -> set:
    """② 一层四则派生。⛔ 只做一层 —— 两层的闭包会让任何数都'找得到出处'。"""
    out = set(base)
    vals = [v for v in base if v != 0]
    for a in vals:
        for b in vals:
            for cand in (a / b, a / b * 100, a - b, a + b, a * b):
                try:
                    out.add(Decimal(cand).quantize(_QUANT))
                except (InvalidOperation, ValueError):
                    pass
    return out


def audit_answer(
    answer_text: str,
    execution_output: Any,
    *,
    allow_derived: bool = True,
) -> Dict[str, Any]:
    """③ 输入必须是**产品真实返回的**正文与执行产出。返回逐个数字的判定。

    ⚠️ 这是**仪器**，不是放行门禁。先量分布(见模块 docstring ④)。
    """
    base = source_numbers(execution_output)
    allowed = derived_closure(base) if allow_derived else set(base)

    found = extract_numbers(answer_text)
    verdicts = []
    for raw, val in found:
        if val in _STRUCTURAL:
            kind = "structural"
        elif val in base:
            kind = "source"
        elif val in allowed:
            kind = "derived"
        else:
            kind = "unsourced"
        verdicts.append({"raw": raw, "value": str(val), "kind": kind})

    counted = [v for v in verdicts if v["kind"] != "structural"]
    unsourced = [v for v in counted if v["kind"] == "unsourced"]
    return {
        "total": len(found),
        "counted": len(counted),
        "structural": len(found) - len(counted),
        "source": sum(1 for v in counted if v["kind"] == "source"),
        "derived": sum(1 for v in counted if v["kind"] == "derived"),
        "unsourced": len(unsourced),
        "hit_rate": (len(counted) - len(unsourced)) / len(counted) if counted else None,
        "verdicts": verdicts,
        "source_pool": len(base),
    }


def distribution(audits: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """④ 命中率分布 —— 近 0% 或近 100% 一律当仪器问题报出来。"""
    rates = [a["hit_rate"] for a in audits if a["hit_rate"] is not None]
    if not rates:
        return {"n": 0, "warning": "没有任何一条答案里有可判定的数字 —— 仪器没接上"}
    rates_sorted = sorted(rates)
    mid = len(rates_sorted) // 2
    median = (rates_sorted[mid] if len(rates_sorted) % 2
              else (rates_sorted[mid - 1] + rates_sorted[mid]) / 2)
    perfect = sum(1 for r in rates if r >= 1.0)
    zero = sum(1 for r in rates if r <= 0.0)
    out = {
        "n": len(rates), "min": min(rates), "median": median, "max": max(rates),
        "mean": sum(rates) / len(rates),
        "all_sourced_answers": perfect, "nothing_sourced_answers": zero,
    }
    if perfect == len(rates):
        out["warning"] = ("命中率 100% —— 先怀疑归一/派生太松(闸放行一切), "
                          "不要当成「产品很干净」的结论")
    elif zero == len(rates):
        out["warning"] = ("命中率 0% —— 先怀疑执行产出没喂对, "
                          "量的多半是我自己的接线")
    return out
