"""F821 未定义名字 —— **窄而可信**的一道闸（形态 E）。

## 为什么单独挑 F821

`ci.yml` 那步全量 flake8 目前有 **299 条**违规（189 条 E501 之类），
`python-gate.yml` 因此**刻意不跑 flake8**：捆进来这道闸从第一天起就挡所有人。

而 F821 不一样：它是「这个名字在运行到那一行时不存在」——
**不是风格问题，是一个等着抛 `NameError` 的缺陷**。全仓只有 1 条。

▎宁可窄而可信，不要宽而被关掉。

## 它当天就抓到了两条

**① 我自己刚部署上生产的那个**（2026-08-15）：改撬棍时把常量从
`_REAL_FP_FN` 改名成 `_REAL_SEM_FP_FN` / `_REAL_RULES_FP_FN`，
**漏了第 190 行那一处**。探针在 prod 上第 190 行就崩了。

🔴 而它当时的**表象是「修好了」**：告警文件压根没被创建，于是
「文件不存在」和「(a) 类决定不喊」在 cron 的 `[ -s ... ]` 里长得一模一样，
读数是「alerts 没有新增」—— 正是我们想要的那个结果。
（靠台账行数没涨才发现。⇒「没喊」有两种：**决定不喊 / 没能做决定**。）

**② 存量的那一条**：`smartbi/api/chat.py:1566` 引用 `effective_user_q`，
而那个名字只在**另一个函数**里（2462 行起，流式那条路）赋值。
那一行是 planner-outage fail-closed 的**留痕**，它自己的注释写着
「⛔ 静默的 fail-closed 让它永远查不出根因」——
**而这条留痕本身会抛 NameError 而不是打日志。**

⇒ 已在下面**显式登记**，⛔ 不是静默排除：登记是留痕，不是豁免。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

_PY_ROOT = Path(__file__).resolve().parents[1]

#: 🔴 已知未修的 F821，**逐条登记 + 写理由**（硬约束 8：故意不改的要留痕）。
#:    ⛔ 只许缩短，不许加长 —— 加长意味着又放进来一个 NameError。
#:
#: ✅ 空的（2026-08-15）。原来那一条 `smartbi/api/chat.py:1566` 已修:
#:    它引用 `effective_user_q`，而那名字只在流式那条路(:2462 起)的**另一个
#:    函数**里赋值 —— 一条**为了防止静默 fail-closed 而写的留痕，自己在静默
#:    地失败**。owner 裁定打**进来的那个 raw query**(`query`, :1361 无条件赋值):
#:    改写后的变量在失败点未定义，恰恰是因为**改写还没发生**；
#:    fail-closed 留痕要记的是「进来的是什么」。
#:    ⛔ 登记是留痕不是豁免 —— 修好就该摘掉，所以摘了。
KNOWN: dict[str, str] = {}


def _run_flake8():
    proc = subprocess.run(
        [sys.executable, "-m", "flake8", "--select=F821", "."],
        cwd=_PY_ROOT, capture_output=True, text=True, timeout=600)
    return proc


def test_no_new_undefined_names():
    """⛔ F821 只许减少。新增一条 = 放进来一个等着抛 NameError 的缺陷。"""
    proc = _run_flake8()
    if proc.returncode != 0 and not proc.stdout.strip():
        pytest.skip(f"flake8 跑不起来, 本条读数作废: {proc.stderr[:200]}")

    found = {}
    for line in proc.stdout.splitlines():
        # 形如  .\smartbi\api\chat.py:1566:33: F821 undefined name 'x'
        if "F821" not in line:
            continue
        head = line.split(": F821")[0]
        path, lineno = head.rsplit(":", 2)[0], head.rsplit(":", 2)[1]
        key = f"{path.lstrip('.').lstrip('/').lstrip(chr(92)).replace(chr(92), '/')}:{lineno}"
        found[key] = line.strip()

    new = {k: v for k, v in found.items() if k not in KNOWN}
    assert not new, (
        "新增了未定义名字(F821) —— 每一条都是一个等着抛 NameError 的缺陷:\n  "
        + "\n  ".join(new.values()))


def test_the_gate_can_actually_fire():
    """🔴 阳性对照 —— **「0 条违规」和「闸没在扫」长得一模一样**。

    ⚠️ 第一版的阳性对照是「登记表非空 ⇒ 全仓至少扫得到 1 条」。
       那条在 `KNOWN` 被清空的那天**会误红**, 而且它证明的是
       「仓里有 bug」而不是「闸能开火」—— 两件事。
    ⇒ 改成给它一个**故意写坏**的临时文件, 断言它认得出来。
       这条与仓里有没有存量违规无关, 所以不会随修复而失效。
    """
    import tempfile
    import textwrap

    with tempfile.TemporaryDirectory() as d:
        bad = Path(d) / "deliberately_broken.py"
        bad.write_text(
            textwrap.dedent("""
                def f():
                    return _this_name_is_never_defined
            """), encoding="utf-8")
        proc = subprocess.run(
            [sys.executable, "-m", "flake8", "--select=F821", str(bad)],
            capture_output=True, text=True, timeout=120)

    assert "F821" in proc.stdout, (
        "闸对一个**故意写坏**的文件都没反应 —— 它没在守任何东西。"
        f"\nstdout={proc.stdout!r}\nstderr={proc.stderr[:300]!r}")


def test_known_list_is_not_stale():
    """⛔ 登记表里的条目修好了要**摘掉**，否则它会遮住同一位置的新问题。"""
    proc = _run_flake8()
    if proc.returncode != 0 and not proc.stdout.strip():
        pytest.skip("flake8 跑不起来, 本条读数作废")
    found_keys = set()
    for line in proc.stdout.splitlines():
        if "F821" not in line:
            continue
        head = line.split(": F821")[0]
        path, lineno = head.rsplit(":", 2)[0], head.rsplit(":", 2)[1]
        found_keys.add(
            f"{path.lstrip('.').lstrip('/').lstrip(chr(92)).replace(chr(92), '/')}:{lineno}")
    stale = set(KNOWN) - found_keys
    assert not stale, (
        f"登记表里这些已经不再违规了, 摘掉它们: {sorted(stale)}")
