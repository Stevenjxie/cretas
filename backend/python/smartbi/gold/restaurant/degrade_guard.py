"""降级留着，静默去掉 —— 编程错误不许被写成「本次没有这块内容」。

## 🔴 为什么(owner 2026-08-14 裁定)

`_build_follow_up_actions` 里一句 `except Exception` + `logger.warning`
把一个 `AttributeError`(`'str' object has no attribute 'get'`)写成了
「追问按钮生成失败, 本次不带按钮」——
**T1/T2 按钮从上线起一次都没出现过, 而没有人知道。**

⚠️ owner 明确**不裁**「收窄成不降级」: 按钮出不来不该让整个答案挂掉,
   降级本身是对的。**问题不是降级, 是静默。**

▎**降级不是问题, 静默才是。** 把「我坏了」翻译成「这里没有内容」的 except,
▎和 `COALESCE(x, 0)`、`.get(k, 0)` 是同一族 —— 都在用一个合法的值
▎顶替一个本该被看见的故障。

## 处置

| 失败类型 | 怎么办 |
|---|---|
| **预期的**(数据缺、外部不可达、契约不成立) | 窄捕获 + 降级 + WARNING，照旧 |
| **编程错误**(`AttributeError`/`TypeError`/`KeyError`/`IndexError`/`NameError`) | 单独捕获 + **ERROR 级** + **计数器 +1** + 仍然降级(用户不受影响) |

⇒ 用户侧行为**一字不变**; 但计数器让「这块内容一直是空的」变成可判定的。

正例见 `restaurant_ops_router.resolve_gross_margin` 的
`except CostKeySourceUnavailable` —— 窄捕获**且不降级**, 明确回「算不了」。
那是「这个失败必须让用户知道」的一类。本模块管的是另一类。
"""
from __future__ import annotations

import logging
from typing import Callable, Dict, Tuple, Type, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")

#: 这些是**编程错误**, 不是「数据没有」。它们出现意味着代码写错了。
#: ⚠️ 不含 `ValueError` —— 它常被用来表达「输入不合法」这种预期失败。
#: ⚠️ 不含 `Exception` 本身 —— 那就退回裸捕获了。
PROGRAMMING_ERRORS: Tuple[Type[BaseException], ...] = (
    AttributeError, TypeError, KeyError, IndexError, NameError,
)

#: 每种降级点各记一个数。⚠️ 进程内计数, 重启归零 —— 它的用途是
#: **让断言和排查抓得住**, 不是长期指标(那要走 Prometheus)。
_COUNTERS: Dict[str, int] = {}


def counter(name: str) -> int:
    """这个降级点到目前为止吞掉了几个**编程错误**。"""
    return _COUNTERS.get(name, 0)


def counters() -> Dict[str, int]:
    return dict(_COUNTERS)


def reset_counters() -> None:
    """⚠️ 只给测试用。"""
    _COUNTERS.clear()


def degrade_on_error(
    name: str,
    fallback: T,
    fn: Callable[[], T],
    *,
    expected: Tuple[Type[BaseException], ...] = (),
    what: str = "",
) -> T:
    """跑 `fn()`; 失败就返回 `fallback`, 但**按失败的种类分别留痕**。

    :param name: 降级点的名字, 也是计数器的键。
    :param expected: 这里**预期会发生**的失败(数据缺、外部不可达…)。
        它们打 WARNING, ⛔ 不计数 —— 计数器是给「代码写错了」用的,
        混进预期失败它就永远不为 0, 那条断言当场失效。
    :param what: 人话, 进日志。

    ⚠️ 顺序: 先 `expected` 再 `PROGRAMMING_ERRORS` 再兜底 ——
       `expected` 里若有人写了 `Exception`, 那是调用方自己的问题, 这里不替他挡。
    """
    label = what or name
    try:
        return fn()
    except expected:                                   # noqa: B902 —— 调用方声明的
        logger.warning("[%s] %s 失败(预期内), 本次降级", name, label,
                       exc_info=True)
        return fallback
    except PROGRAMMING_ERRORS:
        # 🔴 ERROR 级 + 计数: 这是**代码写错了**, 不是「数据没有」。
        #    降级仍然发生(用户不受影响), 但它不再是静默的。
        _COUNTERS[name] = _COUNTERS.get(name, 0) + 1
        logger.error(
            "[%s] 🔴 %s 抛了**编程错误** —— 这不是缺数据, 是代码有 bug。"
            " 本次降级(用户不受影响), 累计 %d 次。",
            name, label, _COUNTERS[name], exc_info=True)
        return fallback
    except Exception:                                  # noqa: BLE001
        # 剩下的既不是声明的预期失败也不是编程错误 —— 仍然降级, 但打 ERROR,
        # 因为「没预料到」本身就值得看见。
        _COUNTERS[name] = _COUNTERS.get(name, 0) + 1
        logger.error("[%s] 🔴 %s 抛了未预料的异常, 本次降级, 累计 %d 次。",
                     name, label, _COUNTERS[name], exc_info=True)
        return fallback


def assert_no_silent_programming_errors(*names: str) -> None:
    """正常路径跑完之后, 这些降级点**一个编程错误都不该吞掉**。

    ⛔ 这条断言是这一整轮的产物: 单测自己喂 dict 时 `build_actions` 一直全绿,
       而生产上它每次都 `AttributeError`。计数器让那件事**可判定**。
    """
    hit = {n: counter(n) for n in names if counter(n)}
    assert not hit, (
        f"降级点吞掉了编程错误: {hit}\n"
        f"⇒ 用户那边看到的是「本次没有这块内容」, 而真相是代码抛了异常。"
        f"看 ERROR 级日志里的 traceback。")
