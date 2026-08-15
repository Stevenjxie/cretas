"""F821 未定义名字 —— **窄而可信**的一道闸（形态 E）。

## 为什么单独挑 F821

`ci.yml` 那步全量 flake8 目前有 **299 条**违规（189 条 E501 之类），
`python-gate.yml` 因此**刻意不跑 flake8**：捆进来这道闸从第一天起就挡所有人。

而 F821 不一样：它是「这个名字在运行到那一行时不存在」——
**不是风格问题，是一个等着抛 `NameError` 的缺陷**。全仓现在 **0 条**。

▎宁可窄而可信，不要宽而被关掉。

## 它当天就抓到了三条

**① 我自己刚部署上生产的那个**（2026-08-15）：改撬棍时把常量从
`_REAL_FP_FN` 改名，**漏了 `replay_equivalence_probe.py` 第 190 行那一处**。
探针在 prod 上第 190 行就崩了。

🔴 而它当时的**表象是「修好了」**：探针崩在写告警文件之前，文件压根没被创建，
于是「文件不存在」和「(a) 类决定不喊」在 cron 的 `[ -s ... ]` 里长得一模一样，
读数是「alerts 没有新增」—— 正是我们想要的那个结果。
（靠**台账行数也没涨**才发现。⇒「没喊」有两种：**决定不喊 / 没能做决定**。）

**② 存量的那一条**：`smartbi/api/chat.py:1566` 引用 `effective_user_q`，
而那名字只在流式那条路（`:2462` 起）的**另一个函数**里赋值。
那一行是 planner-outage fail-closed 的**留痕**，它自己的注释写着
「⛔ 静默的 fail-closed 让它永远查不出根因」——
**而这条留痕本身会抛 NameError 而不是打日志。** 已修（打进来的那个 raw query）。

**③ 闸自己没在跑**：第一次进 CI 时 `pytest fail` ——
CI 里根本没装 flake8。是下面那条**阳性对照**报的；
而「新增违规」那条如果写成 `pytest.skip`，就会在汇总行上长得像绿。
⇒ 已在 `python-gate.yml` 装上 flake8，且**本文件一个 `skip` 都没有**。
▎**闸不跑 = 没有闸。**
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

_PY_ROOT = Path(__file__).resolve().parents[1]

#: 🔴 已知未修的 F821，**逐条登记 + 写理由**（硬约束 8：故意不改的要留痕）。
#:    ⛔ 只许缩短，不许加长 —— 加长意味着又放进来一个 NameError。
#:
#: ✅ 空的（2026-08-15）。原来那一条 `smartbi/api/chat.py:1566` 已修：
#:    owner 裁定打**进来的那个 raw query**（`query`，`:1361` 无条件赋值）——
#:    改写后的变量在失败点未定义，恰恰是因为**改写还没发生**；
#:    fail-closed 留痕要记的是「进来的是什么」。
#:    ⛔ 登记是留痕不是豁免 —— 修好就该摘掉，所以摘了。
KNOWN: dict[str, str] = {}


def _run_flake8(target: str = "."):
    return subprocess.run(
        [sys.executable, "-m", "flake8", "--select=F821", target],
        cwd=_PY_ROOT, capture_output=True, text=True, timeout=600)


def _violations(stdout: str) -> dict[str, str]:
    """`.\\smartbi\\api\\chat.py:1566:33: F821 undefined name 'x'` → {路径:行号}"""
    out: dict[str, str] = {}
    for line in stdout.splitlines():
        if "F821" not in line:
            continue
        head = line.split(": F821")[0]
        parts = head.rsplit(":", 2)
        path, lineno = parts[0], parts[1]
        norm = path.lstrip(".").lstrip("/").lstrip(chr(92)).replace(chr(92), "/")
        out[f"{norm}:{lineno}"] = line.strip()
    return out


def test_no_new_undefined_names():
    """⛔ F821 只许减少。新增一条 = 放进来一个等着抛 NameError 的缺陷。"""
    proc = _run_flake8()
    # ⛔ **不 skip** —— skip 在汇总行上和 pass 长得几乎一样，于是「闸没跑」
    #    会被读成「闸绿了」。实测: CI 里没装 flake8, 这条如果 skip 就只剩
    #    阳性对照在报, 而那时它报的是「闸坏了」不是「代码有问题」。
    assert proc.stdout.strip() or proc.returncode == 0, (
        f"flake8 跑不起来, 这道闸没在守任何东西(⛔ 不是「没有违规」): "
        f"rc={proc.returncode} stderr={proc.stderr[:300]!r}")

    new = {k: v for k, v in _violations(proc.stdout).items() if k not in KNOWN}
    assert not new, (
        "新增了未定义名字(F821) —— 每一条都是一个等着抛 NameError 的缺陷:\n  "
        + "\n  ".join(new.values()))


def test_the_gate_can_actually_fire():
    """🔴 阳性对照 —— **「0 条违规」和「闸没在扫」长得一模一样**。

    ⚠️ 第一版的阳性对照是「登记表非空 ⇒ 全仓至少扫得到 1 条」。
       那条在 `KNOWN` 被清空的那天**会误红**，而且它证明的是
       「仓里有 bug」而不是「闸能开火」—— 两件事。
    ⇒ 改成给它一个**故意写坏**的临时文件，断言它认得出来。
       这条与仓里有没有存量违规无关，所以不会随修复而失效。
    """
    import tempfile
    import textwrap

    with tempfile.TemporaryDirectory() as d:
        bad = Path(d) / "deliberately_broken.py"
        bad.write_text(textwrap.dedent("""
            def f():
                return _this_name_is_never_defined
        """), encoding="utf-8")
        proc = _run_flake8(str(bad))

    assert "F821" in proc.stdout, (
        "闸对一个**故意写坏**的文件都没反应 —— 它没在守任何东西。"
        f"\nstdout={proc.stdout!r}\nstderr={proc.stderr[:300]!r}")


def test_known_list_is_not_stale():
    """⛔ 登记表里的条目修好了要**摘掉**，否则它会遮住同一位置的新问题。

    ⚠️ `KNOWN` 目前是空的 ⇒ **本条当前是恒真式**。留着是因为它在有人往
       登记表里加东西的那一天才开始守东西 —— 而那正是最需要它的时候。
       ⛔ 这一点写出来，别让下一个人把它读成「有一条断言在守」。
    """
    if not KNOWN:
        return                                  # 登记表为空，无可陈旧

    proc = _run_flake8()
    assert proc.stdout.strip() or proc.returncode == 0, (
        f"flake8 跑不起来, 本条没在守任何东西: rc={proc.returncode}")
    stale = set(KNOWN) - set(_violations(proc.stdout))
    assert not stale, f"登记表里这些已经不再违规了, 摘掉它们: {sorted(stale)}"
