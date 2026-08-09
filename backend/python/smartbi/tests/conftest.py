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

collect_ignore = []

for _p in sorted(Path(__file__).parent.glob("test_*.py")):
    try:
        _src = _p.read_text(encoding="utf-8", errors="replace")
    except OSError:                       # 读不了就交给 pytest 自己报
        continue
    if not _HAS_CASE.search(_src):
        collect_ignore.append(_p.name)
