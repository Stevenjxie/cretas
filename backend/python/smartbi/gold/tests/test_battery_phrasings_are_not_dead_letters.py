"""电池当成「系统措辞」的那些串，产品里必须真的还有。

## 为什么要有这道闸（2026-08-12 实测，一次抓到两处）

排除项是**子串匹配**：产品文案一改，那个串就再也不会出现，排除项于是恒真 ——
**不报错、不变红、不消失，只是从此什么都不测**，而它读起来仍然像在守着什么。

当天全仓 grep（排除测试与电池）抓到两处已经死掉的：

  1. `"我没太看懂"` —— 5 处 `excludes` 在用它。产品源码里**一个字都没有**。
     系统现在说的是「我还没有完整理解这句话」/「我现在暂时无法完整理解这句话」。
  2. `"生产统计报告"` —— 在 `_FORBIDDEN_EVERYWHERE` 里，因此作用于**每一条用例**。
     产品源码里同样没有。它的关切已由「工厂制造分析不适用」承担。

同型的第三例电池自己记过：`". 打包盒"` 挂在编号列表排版上，排行改成表格后恒真。

## ⛔ 这道闸能做到什么、做不到什么（自陈）

判据是「这个串在产品源码里出现过」。它**不能**证明「餐饮问答这条路径还会发出它」
—— 串可能出现在一个不相干的模块里（实测：`实体识别` 命中的是 `food_kb`，
`Step1` 命中的是**负责把它删掉**的 `CustomerTextSanitizer`）。

要做到那一步得有调用图，而那不是一道测试的量级。
所以它守的是**「整串消失」**这一种腐烂 —— 恰好是上面两处的形态，
也是文案改动最常见的形态。⚠️ 别把它读成「这些排除项都还有效」。
"""
from __future__ import annotations

import pathlib
import subprocess

import pytest

from smartbi.scripts.restaurant_ai_eval import (
    _CANNOT_UNDERSTAND,
    _FORBIDDEN_EVERYWHERE,
)

#: 仓根：本文件在 `<repo>/backend/python/smartbi/gold/tests/` 之下 —— 数上去 5 层。
#: ⚠️ 第一版写的 `parents[4]`，那只到 `backend/`，于是 `backend/backend/python`
#:    不存在、每个串都查不到、**六个全红**。而红出来的话是「产品源码里已经没有
#:    这个串了」—— 一个路径 bug 伪装成六条腐烂的断言。
#:    所以下面要先断言根目录存在: 路径错了就该报成路径错。
_REPO_ROOT = pathlib.Path(__file__).resolve().parents[5]
_PRODUCT_ROOTS = ("backend/python", "backend/java")


def _appears_in_product_source(needle: str) -> str:
    """这个串在产品源码里出现过吗？返回命中的第一个文件，没有则返回 ""。

    ⛔ 排除测试与电池自身 —— 否则每个串都会命中「它自己」，这道闸就变成恒真的，
       而恒真正是它要拆的东西。
    """
    searched = 0
    for root in _PRODUCT_ROOTS:
        target = _REPO_ROOT / root
        if not target.exists():  # pragma: no cover - 单模块 checkout
            continue
        searched += 1
        result = subprocess.run(
            ["grep", "-rlF", needle, "--include=*.py", "--include=*.java", str(target)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        for path in (result.stdout or "").splitlines():
            # ⛔ 过滤只看**仓内相对路径**, 不看绝对路径。
            #    2026-08-12 实测: 拿绝对路径过滤时, worktree 目录名恰好叫
            #    `cretas-ai-eval`, 于是每一条命中都被「路径里含 eval」筛掉,
            #    六个串全判成「产品里没有」—— 一个过滤面太宽的 bug 伪装成
            #    六条腐烂的断言。判据: 过滤条件要作用在**仓内路径**上,
            #    否则它会把工作目录的名字也算进判定。
            try:
                rel = pathlib.Path(path).resolve().relative_to(_REPO_ROOT).as_posix()
            except ValueError:  # pragma: no cover - grep 不该越出仓外
                continue
            low = rel.lower()
            if "test" in low or "eval" in low:
                continue
            return rel
    assert searched, (
        f"一个产品源码根都没找到 (_REPO_ROOT={_REPO_ROOT}) —— 这是**路径 bug**, "
        f"不是「这些串都死了」。别照着下面那句红去改断言。")
    return ""


@pytest.mark.parametrize(
    "needle",
    list(_FORBIDDEN_EVERYWHERE) + [_CANNOT_UNDERSTAND],
    ids=lambda s: s[:16],
)
def test_battery_phrasings_are_not_dead_letters(needle):
    """🔴 承重: 电池引用的系统措辞必须在产品里还存在。

    变异实测(2026-08-12): 把 `_CANNOT_UNDERSTAND` 改回 `"我没太看懂"`
      → 红:「产品源码里已经没有『我没太看懂』了 —— 引用它的断言恒真」
      红在「这条断言不可能变红」这件事本身上。
    """
    where = _appears_in_product_source(needle)
    assert where, (
        f"产品源码里已经没有「{needle}」了 —— 引用它的断言恒真, 一次都不会红。\n"
        f"  要么改成产品现在真正的措辞, 要么删掉(别留着冒充守卫)。")


def test_the_gate_would_have_caught_the_two_that_rotted():
    """阴性对照: 这道闸对「已知死掉的两个串」确实会红。

    ⛔ 没有这一条, 上面那组全绿说明不了任何事 —— 可能是判据太松而不是真的都活着。
       (本仓判据: 闸绿最常见的原因是它没跑 / 它测的是自己的定义。)
    """
    for dead in ("我没太看懂", "生产统计报告"):
        assert not _appears_in_product_source(dead), (
            f"「{dead}」又出现在产品源码里了 —— 那本条阴性对照失去意义, "
            f"该把它加回 excludes 而不是留在这里当反例")
