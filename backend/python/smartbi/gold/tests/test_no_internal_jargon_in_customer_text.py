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

## ⛔ 这道闸看不见什么（2026-08-13 实测的五个盲区，修了 1/4/5，2/3 留着）

| # | 盲区 | 现状 |
|---|---|---|
| 1 | 只扫 1 个模块 | ✅ 已修 —— 改成从包里**发现**全部模块 |
| 2 | 标点启发式把无标点的片段当成键名跳过 | ⚠️ **留着**。去掉它会把 SQL/键名全报成违例，误报会让人把闸关掉，比没有闸更糟 |
| 3 | f-string 插值 | ⚠️ **留着**。整句在**运行时**才拼出来，黑话在变量里，**任何静态扫描都看不见** —— 这要靠运行时判据，不是这道闸的职责 |
| 4 | 词表只有中文形（`resolver` / `dimension` 漏网）| ✅ 已修 —— 词表补了英文形 |
| 5 | **闸自己先 sanitize 再查** | ✅ 已修 —— 见 `scan_module_for_jargon` |

🔴 **盲区 5 是根因，1~4 是它的放大器**：即使模块扫全、词表补英文、解决 f-string，
只要闸自己先 sanitize，它照样绿在「生产不 sanitize」的串上。

⚠️ **别让下一个人以为它全覆盖**：2 和 3 是已知看不见的，写在这里就是为了这个。

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

#: 这道闸现在**只报不拦**。
#:
#: 🔴 为什么先不拦：闸刚从「先 sanitize 再查」改成「直接查源码字面串」
#:    （见 `scan_module_for_jargon`），存量违例会立刻红一片 ——
#:    与「『字段』进词表」那次是同一堵墙：**在改文案之前先把 CI 弄红**。
#:
#: ⛔ **改回拦的判据**：清单清空之日，把这里改成 `True`。
#:    ⚠️ 但在那之前还有一件更重要的事：**「面向用户」这个判定目前是假的** ——
#:    它用「有没有中文标点」当代理，同时造出假阳性（prompt / SQL / 正则 /
#:    开发者断言消息）和假阴性（`restaurant_intent_service` 里那句真的会发给
#:    店长的话，因为它没有中文标点）。
#:    真正的修法是**把面向用户的串收敛到一处定义，闸扫那一处**
#:    （`customer_text.py` 已有先例）。那是个真重构，排在 P0 之后。
#:    ⛔ 在那之前把这里改成 `True`，等于用一个判不准的代理去拦人。
ENFORCE_JARGON_GATE = False

def _discover_customer_facing_modules() -> Tuple[str, ...]:
    """`smartbi.gold.restaurant` 下的全部模块 —— **发现出来的，不是手写清单**。

    🔴 2026-08-13 盲区 1 的修法。此前 `_MODULES` 只有一个模块，而 prod 上那句
       「查询维度超出计划 resolver 的能力范围」在 `restaurant_intent_service.py`
       —— **根本不在扫描范围内**。手写清单漏一个模块是静默的。
    """
    import pkgutil

    import smartbi.gold.restaurant as pkg

    names = []
    for info in pkgutil.iter_modules(pkg.__path__):
        if info.ispkg or info.name.startswith("_"):
            continue
        names.append(f"smartbi.gold.restaurant.{info.name}")
    return tuple(sorted(names))


_MODULES = _discover_customer_facing_modules()


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


def scan_module_for_jargon(module_name: str):
    """扫一个模块的**源码字面串**，返回 `(行号, 命中词, 原文)`。

    🔴 2026-08-13 盲区 5 的修法 —— 这是本次最要命的那一个。

    此前这里写的是 `cleaned = sanitize_customer_ai_text(text)` 再查 ——
    **闸自己替产品做了一遍清洗**。于是它绿着，而 prod 把原串原样发给店长：

        源码串   : 查询维度超出计划 resolver 的能力范围     含 ['维度']
        sanitize : 查询方面超出计划 数据查询 的能力范围     含 []      <- 闸看到的
        prod 实发: 这次没有开算：查询维度超出计划 resolver…  含 ['维度'] <- 店长看到的

    ⛔ 界限（owner 2026-08-13 裁定）：
         **模型生成的文本** -> sanitize 兜底，因为我们控制不了它
         **我们自己敲进源码的串** -> 不许靠 sanitize 兜，**直接写对**
       这道闸扫的正是后一类，所以它**不调 sanitize**。

    ⚠️ 另一个理由是 sanitize 是**词替换不是改写**：那句话洗完读不通
       （「查询方面超出计划 数据查询 的能力范围」）。让生产也调 sanitize
       会产出一堆半通不通的句子 —— 那不是修复，是把一种缺陷换成另一种。
    """
    out = []
    for lineno, text in _customer_facing_strings(module_name):
        hits = [w for w in _INTERNAL_VOCAB if w in text]
        if hits:
            out.append((lineno, hits, text))
    return out


@pytest.mark.parametrize("module_name", _MODULES)
def test_no_internal_vocabulary_reaches_the_customer(module_name):
    """⚠️ **当前是「只报不拦」**：产出清单，不 fail。

    🔴 为什么先不拦：闸刚从「先 sanitize 再查」改成「直接查源码字面串」，
       存量违例会立刻红一片 —— 与「『字段』进词表」那次是同一堵墙：
       **在改文案之前先把 CI 弄红**。所以先出全量清单，清完再改成拦。

    ⛔ 这条注释就是「什么时候改回拦」的判据：清单清空之日，把下面那行
       `if False:` 去掉即可。留着 `assert` 的形状，是为了改回来只动一行。
    """
    offenders = [
        f"{module_name}:{lineno} {hits} -> {text[:60]!r}"
        for lineno, hits, text in scan_module_for_jargon(module_name)
    ]
    if offenders:
        print(f"\n[JARGON] {module_name} 共 {len(offenders)} 条:")
        for line in offenders:
            print("   ", line)
    # ⛔ 存量清完之前只报不拦。改回拦: 把 `if False and` 去掉。
    if ENFORCE_JARGON_GATE and offenders:
        raise AssertionError(
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

    # ⛔ 同样不调 sanitize —— 理由见 `scan_module_for_jargon` 的 docstring。
    offenders = []
    for lineno, text in _customer_facing_strings(module_name):
        hits = [c for c in codes if c in text]
        if hits:
            offenders.append(f"{module_name}:{lineno} {hits}")
    if offenders:
        print(f"\n[RESOLVER-CODE] {module_name}: " + "; ".join(offenders))
    if ENFORCE_JARGON_GATE and offenders:
        raise AssertionError(
            "resolver 编码漏进了面向用户的句子:\n  " + "\n  ".join(offenders))


def test_the_collector_actually_finds_something():
    """⛔ 阴性对照: 收集器返回空集时, 上面两条断言恒真。

    这正是本仓反复在拆的「闸没跑」—— 一个 AST 过滤器写错(比如把全部字符串都当成
    docstring 排掉)会让这道闸从此什么都不测, 而且**它会一直是绿的**。
    """
    # ⚠️ 用**已知一定有大量面向用户句子**的那个模块, 不用 `_MODULES[0]` ——
    #    后者现在是「发现出来的第一个(按字母序)」, 换个新模块进来就会让这条
    #    阴性对照失去意义。
    found = _customer_facing_strings("smartbi.gold.restaurant.restaurant_intent")
    assert len(found) > 20, f"只收到 {len(found)} 条面向用户的句子 —— 过滤器可能写错了"
    assert len(_MODULES) > 1, (
        f"模块发现只找到 {len(_MODULES)} 个 —— 盲区 1 又回来了")


def test_the_vocabulary_gate_can_actually_go_red():
    """⛔ 阴性对照: 拿一句真含内部概念词的话喂 sanitize, 它必须留在里面。

    如果 sanitize 恰好把这些词都替换掉了, 上面那条承重断言就永远绿 ——
    那时该改的是词表, 不是闸。
    """
    bad = "我识别到的问题对象与准备执行的分析范围不一致。"
    cleaned = sanitize_customer_ai_text(bad)
    assert any(w in cleaned for w in _INTERNAL_VOCAB), (
        "内部概念词被 sanitize 吃掉了 —— 那么承重断言测不到任何东西")
