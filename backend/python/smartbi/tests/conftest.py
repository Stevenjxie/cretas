"""`smartbi/tests/` 的本地 conftest —— 不收集「没有任何用例的脚本」。

## 为什么需要这个

这个目录里 75 个 `test_*.py` 是历史遗留的**独立脚本**, 不是 pytest 用例。
2026-08-10 逐文件量出来的构成:

     1 个  真 pytest 用例(无顶层副作用)
    63 个  有用例, 但**也有顶层副作用**
    11 个  纯脚本 —— 一个 `def test_` / `class Test` 都没有

pytest 收集一个模块的方式是 **import 它**, 而 import 会执行全部顶层代码。那 11 个
纯脚本的顶层会打印、读文件、调 API, 其中 `test_vl_simple.py` 在图片不存在时直接
`exit(1)` → `INTERNALERROR> SystemExit: 1`, **整轮收集当场死掉**, 后面所有文件一个
都不会跑。

判据: **模块导入期不要改全局 IO、不要 exit** —— 一个导入期副作用挡住了 75 个
      文件几个月(python-gate.yml 因此把整个目录排除在门禁之外)。

## 为什么按「有没有用例」自动判, 而不是列一张文件清单

列清单就是又一份手写表: 新增一个脚本没人会想起来更新它, 于是它再次搞崩整轮收集,
而现象("整个 CI 忽然什么都没跑")离原因非常远。这里的判据是**可以从文件本身读出来
的**, 就不该靠人记。

⛔ 这不是把问题藏起来 —— 它们本来就不是测试(零个用例, 收集了也是零个用例)。真正
   的修法是给它们补 `if __name__ == "__main__":` 守卫或搬出 tests 目录, 那是独立
   一件事; 在那之前, 至少不能让它们把别人的测试一起带走。
"""
from __future__ import annotations

import re
from pathlib import Path

_HAS_CASE = re.compile(r"^\s*(async\s+)?def\s+test_|^\s*class\s+Test", re.M)
# 无参数的 test_ 函数 —— 只有这种才可能是真 pytest 用例。
_ZERO_ARG_CASE = re.compile(r"^\s*(async\s+)?def\s+test_\w+\(\s*\)", re.M)
# 带参数的 test_ 函数 —— pytest 会把参数当 fixture 找。
_ARG_CASE = re.compile(r"^\s*(async\s+)?def\s+test_\w+\(\s*[^)\s]", re.M)
_IS_SCRIPT = re.compile(r'^if\s+__name__\s*==\s*["\']__main__["\']', re.M)


def _is_harness_not_tests(src: str) -> bool:
    """「一个真用例都没有的脚本」的第二种形态: 函数名叫 test_ 但**全部带参数**。

    2026-08-10 实测: smartbi/tests 下有 6 个文件(test_excel_edge_cases /
    test_structure_detector / test_multi_sheet_analysis / test_multirow_header /
    test_header_llm_selector / test_header_advanced)共 **27 个** `test_*` 函数,
    **全部带参数、零个无参数**, 且每个文件都有 `main()` + `__main__` 块 ——
    它们是 `main()` 里手工调用的脚手架(如
    `async def test_standard_table_detection(detector) -> StructureTestResult`),
    带参数、有返回值。pytest 把参数当 fixture 找, 于是 27 个
    `fixture 'parser'/'detector'/'df_raw' not found`。

    ⚠️ 本文件第一版的判据只有「有没有 def test_」, 把这 6 个当成了真测试 ——
       判据漏了一种形态。判据: **「是不是测试」不能只看函数名前缀, 还要看它是不是
       pytest 调得动**(无参数, 或参数是真 fixture)。

    ⛔ 仍然是自动判别, 不是文件清单: 清单会随新增脚本过期, 而这三个特征
       (全部 test_ 带参数 / 零个无参数 / 有 __main__ 入口)可以从文件本身读出来。
    """
    return (
        bool(_ARG_CASE.search(src))
        and not _ZERO_ARG_CASE.search(src)
        and bool(_IS_SCRIPT.search(src))
    )


collect_ignore = []

for _p in sorted(Path(__file__).parent.glob("test_*.py")):
    try:
        _src = _p.read_text(encoding="utf-8", errors="replace")
    except OSError:                       # 读不了就交给 pytest 自己报
        continue
    if not _HAS_CASE.search(_src) or _is_harness_not_tests(_src):
        collect_ignore.append(_p.name)
