"""面向店长的句子里不许出现内部概念词。

## 为什么

owner 2026-08-11:「最重要的是不能在前端返回输出里面显示置信度这些技术内容。」

prod 实测(用户在多轮里打了个不完整的门店名「龙之梦」):

    我识别到的**问题对象**与准备执行的**分析范围**不一致。
    请明确要看菜品、门店还是全店汇总，我不会用相邻指标替代。

店长读不懂「问题对象」「分析范围」是什么。⚠️ 注意漏出去的**不是置信度数字** ——
是**内部概念名**, 比数字更隐蔽, 因为它读起来像中文。

`sanitize_customer_ai_text` 有一张术语替换表, 它覆盖了「语义规划」(→「问题理解」)
却没覆盖这几个。**机制是对的, 词表漏了。**

## 这道闸做什么

扫**整个模块**的面向用户字符串(排除 docstring 与日志), 断言经 sanitize 之后
不含内部概念词。这样往词表里加一个词, 会立刻找出全模块所有还没改的地方 ——
而不是逐条撞见逐条改。

## ⛔ 这张词表是手写的, 它的弱点写在这里

本仓判据:「判据里出现手写清单就问, 这张表错了会怎样」。
答: 漏一个词 = 那个词照样能漏到前端, 这道闸对它沉默。

**能推导的那一半已经推导**: resolver 编码(`RESTAURANT_OPS_*`)直接从
`_INTENT_DESCRIPTIONS` 的键现算 —— 枚举值漏进句子这一类不依赖手写。
剩下的中文概念词无处可推导, 只能手写; 加词的成本是一行, 而它一加就全模块生效。
"""
from __future__ import annotations

import ast
import inspect
from typing import List, Tuple

import pytest

from smartbi.gold.customer_text import INTERNAL_VOCAB, sanitize_customer_ai_text

#: ⛔ 手写(见模块 docstring 的自陈)。只收**店长读不懂且没法据此行动**的词。
#: 「毛利」「门店」这类业务词不在此列 —— 它们正是店长每天在说的话。
#:
#: ⚠️ 2026-08-12: 词表本身已搬到 `smartbi.gold.customer_text`，因为运行时判据
#:    (答案里有没有黑话) 也要读同一份，而 production 代码不能 import 测试模块。
#:    这里保留别名，是为了让本文件里那些讲「这张表错了会怎样」的注释仍然指得着它。
#:    ⛔ 不要在这里再写一份字面量 —— 两份会漂。
_INTERNAL_VOCAB = INTERNAL_VOCAB

_MODULES = ("smartbi.gold.restaurant.restaurant_intent",)


def _customer_facing_strings(module_name: str) -> List[Tuple[int, str]]:
    """模块里**面向用户**的中文句子。

    排除四类不是给用户看的字符串:
      · docstring —— 写给维护者的
      · 传给 `logger.*` 的 —— 写给运维的
      · 函数名含 `prompt` 的函数体内的 —— **写给模型的**
      · 不含中文标点/人称的 —— 多半是键名、SQL、格式串

    ⚠️ 第三条是实测加的: 第一版把 T3 系统提示词(「你是餐饮老板问答系统的意图
       解析器…」)报成了违规。**误报会让人把闸关掉, 比没有闸更糟。**
    """
    import importlib

    module = importlib.import_module(module_name)
    src = inspect.getsource(module)
    tree = ast.parse(src)

    docstrings = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef,
                             ast.AsyncFunctionDef)):
            body = getattr(node, "body", None)
            if (body and isinstance(body[0], ast.Expr)
                    and isinstance(body[0].value, ast.Constant)
                    and isinstance(body[0].value.value, str)):
                docstrings.add(id(body[0].value))

    prompt_strings = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and                 "prompt" in node.name.lower():
            for sub in ast.walk(node):
                if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
                    prompt_strings.add(id(sub))

    log_args = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            func = node.func
            name = getattr(func, "attr", None) or getattr(func, "id", None)
            if name in ("debug", "info", "warning", "error", "exception"):
                for arg in ast.walk(node):
                    if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                        log_args.add(id(arg))

    found: List[Tuple[int, str]] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
            continue
        if (id(node) in docstrings or id(node) in log_args
                or id(node) in prompt_strings):
            continue
        text = node.value
        if not any("一" <= ch <= "鿿" for ch in text):
            continue
        # 面向用户的句子特征: 有中文标点或人称。键名/SQL/格式串没有。
        if not any(tok in text for tok in ("。", "？", "，", "你", "请", "我")):
            continue
        found.append((node.lineno, text))
    return found


@pytest.mark.parametrize("module_name", _MODULES)
def test_no_internal_vocabulary_reaches_the_customer(module_name):
    """🔴 承重: 面向用户的句子, 经 sanitize 之后不许含内部概念词。"""
    offenders = []
    for lineno, text in _customer_facing_strings(module_name):
        cleaned = sanitize_customer_ai_text(text)
        hits = [w for w in _INTERNAL_VOCAB if w in cleaned]
        if hits:
            offenders.append(f"{module_name}:{lineno} {hits} -> {text[:50]!r}")
    assert not offenders, (
        "这些面向店长的句子里有内部概念词(店长读不懂, 也没法据此行动):\n  "
        + "\n  ".join(offenders))


@pytest.mark.parametrize("module_name", _MODULES)
def test_no_resolver_code_reaches_the_customer(module_name):
    """⛔ 这一半是**推导**的, 不是手写: resolver 编码直接从登记表的键现算。

    枚举值漏进句子(如「本次走的是 RESTAURANT_OPS_GROSS_MARGIN」)属于这一类。
    """
    from smartbi.gold.restaurant.restaurant_intent import _INTENT_DESCRIPTIONS

    codes = tuple(_INTENT_DESCRIPTIONS)
    assert codes, "阴性对照: 登记表是空的, 这条断言等于空转"

    offenders = []
    for lineno, text in _customer_facing_strings(module_name):
        cleaned = sanitize_customer_ai_text(text)
        hits = [c for c in codes if c in cleaned]
        if hits:
            offenders.append(f"{module_name}:{lineno} {hits}")
    assert not offenders, "resolver 编码漏进了面向用户的句子:\n  " + "\n  ".join(offenders)


def test_the_collector_actually_finds_something():
    """⛔ 阴性对照: 收集器返回空集时, 上面两条断言恒真。

    这正是本仓反复在拆的「闸没跑」—— 一个 AST 过滤器写错(比如把全部字符串都当成
    docstring 排掉)会让这道闸从此什么都不测, 而且**它会一直是绿的**。
    """
    found = _customer_facing_strings(_MODULES[0])
    assert len(found) > 20, f"只收到 {len(found)} 条面向用户的句子 —— 过滤器可能写错了"


def test_the_vocabulary_gate_can_actually_go_red():
    """⛔ 阴性对照: 拿一句真含内部概念词的话喂 sanitize, 它必须留在里面。

    如果 sanitize 恰好把这些词都替换掉了, 上面那条承重断言就永远绿 ——
    那时该改的是词表, 不是闸。
    """
    bad = "我识别到的问题对象与准备执行的分析范围不一致。"
    cleaned = sanitize_customer_ai_text(bad)
    assert any(w in cleaned for w in _INTERNAL_VOCAB), (
        "内部概念词被 sanitize 吃掉了 —— 那么承重断言测不到任何东西")
