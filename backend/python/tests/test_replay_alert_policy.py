"""回放跑批的**告警分级** —— (a) 不喊，(b)(c) 必须喊。

## 被守的行为

`rc=2` 是「这次没量到东西」（硬约束 4），而它有**三个成因，处置完全不同**：

    (a) eligible_stored=0   存量按设计全部失效（旧格式，等人逐条盖章）
                            → **不是故障**，⛔ 不告警
    (b) eligible_stored>0 却 0 条回放  → 仪器坏了（A 遍撬棍失效）→ 告警
    (c) positive_control=0 / 表里 0 行 → 格式门坏 / plan_version 对不上 → 告警

## 为什么 (a) 必须闭嘴（owner 2026-08-15 裁定 ①）

2026-08-13 起 prod 每天落的都是 (a)，而告警一律喊
「阳性对照未通过, 本次读数作废」—— 而 `positive_control` 明明是 **1**。

▎**一个天天误报的告警最终会被忽略，而它拖下水的是所有告警的可信度。**
（形态 E：闸的完备性与闸的存活是矛盾的。宁可窄而可信。）

## ⛔ 这里守的是「(a) 闭嘴」，不是「什么都不喊」

所以每条阴性断言都配一条**阳性**的：把 (a) 的那一个条件改掉，它必须重新开口。
只断言「不喊」的话，`return ""` 这种实现也能全绿。
"""
from __future__ import annotations

import pytest

#: ⛔ 从**无副作用**的那个模块 import。
#: `replay_equivalence_probe` 在模块级跑 `bootstrap_probe`(设租户 ContextVar /
#: 改 sys.path), 只要 import 它, 同进程后面的用例就会被污染 ——
#: 实测 7 条不相干的测试因此变红(单独跑 9 passed, 一起跑 7 failed)。
from smartbi.scripts.replay_alert_policy import alert_for


def _alert(rc, *, pc=1, elig=0, total=40):
    return alert_for(rc, positive_control=pc, eligible_stored=elig,
                     stored_total=total)


# ── (a) 这一类，且**只有**这一类，闭嘴 ────────────────────────────────────
def test_a_no_eligible_stock_is_silent():
    """🔴 存量按设计全失效 —— 落台账，⛔ 不告警。"""
    assert _alert(2, pc=1, elig=0, total=40) == "", (
        "(a) 类还在告警 —— 它每天都会发生, 会把所有告警的可信度一起拖下水")


def test_a_is_silent_only_because_of_eligible_zero():
    """🔴 阳性对照：把 (a) 的那个条件改掉，它必须**重新开口**。

    ⛔ 没有这一条，`alert_for` 直接 `return ""` 也能让上面那条绿。
    """
    assert _alert(2, pc=1, elig=1, total=40) != "", (
        "有合格存量却 0 回放 —— 这是仪器坏了, 必须喊")


# ── (b) 仪器坏了：有合格存量却一条没回放 ─────────────────────────────────
@pytest.mark.parametrize("elig", [1, 7, 40])
def test_b_instrument_dead_alerts(elig):
    line = _alert(2, pc=1, elig=elig, total=40)
    assert "INSTRUMENT DEAD" in line, line
    assert str(elig) in line, f"没说清有几条合格存量: {line!r}"


# ── (c) 格式门坏 / 表里 0 行 ─────────────────────────────────────────────
def test_c_positive_control_failed_alerts():
    line = _alert(2, pc=0, elig=0, total=40)
    assert "INSTRUMENT DEAD" in line, line
    assert "格式门" in line, line


def test_c_zero_rows_alerts():
    """⚠️ 0 行是 plan_version 对不上，**不是**「没有存量计划」。"""
    line = _alert(2, pc=1, elig=0, total=0)
    assert "INSTRUMENT DEAD" in line, line
    assert "0 行" in line, line


# ── 漂移与全绿 ───────────────────────────────────────────────────────────
def test_drift_alerts():
    assert "DRIFT" in _alert(1, pc=1, elig=12, total=40)


def test_all_equivalent_is_silent():
    assert _alert(0, pc=1, elig=40, total=40) == ""


# ── 🔴 三个成因**互不相同**：⛔ 防「三种都喊同一句」回潮 ──────────────────
def test_the_three_rc2_causes_say_different_things():
    a = _alert(2, pc=1, elig=0, total=40)     # 按设计失效
    b = _alert(2, pc=1, elig=5, total=40)     # 撬棍失效
    c = _alert(2, pc=0, elig=0, total=40)     # 格式门坏
    d = _alert(2, pc=1, elig=0, total=0)      # 0 行
    assert len({a, b, c, d}) == 4, (
        f"四个成因没有各说各的: {(a, b, c, d)!r} —— "
        "压成一句正是被误报咬了三天的那个形状")


# ── 载体：cron 必须**清场**再跑，且只负责搬运 ────────────────────────────
def test_cron_clears_the_alert_file_and_does_not_decide():
    """⛔ 两件事：① 清上一次的告警文件 ② 判定不许写回 shell。

    ① 不清 → cron 读到**昨天的**告警，当成今天的（与产出文件同一条纪律）。
    ② 判定写在 shell 里就没法单测，而它正是被误报咬了三天的那一段。
    """
    from pathlib import Path
    # tests → python → backend → <repo root>。⚠️ parents[2] 是 `backend`,
    #   本仓记过一次同样的差一位。
    cron = (Path(__file__).resolve().parents[3] / "scripts" / "cron"
            / "replay-equivalence-daily.sh")
    assert cron.exists(), f"⛔ 路径写错了就不是「跳过」而是「没测」: {cron}"
    src = cron.read_text(encoding="utf-8")
    assert "rm -f /tmp/replay_equivalence.json /tmp/replay_equivalence.alert" in src, (
        "cron 没有清上一次的告警文件 —— 昨天的故障会被当成今天的")
    assert "PROBE_ALERT_OUT=" in src, "cron 没把告警文件路径传给探针"

    # 判定不许回到 shell：条件行里不许再出现这几个判定量。
    decision_terms = ("eligible_stored", "positive_control", "stored_total")
    offenders = [ln.strip() for ln in src.splitlines()
                 if ln.strip().startswith(("if ", "elif "))
                 and any(t in ln for t in decision_terms)]
    assert not offenders, (
        f"告警判定又写回 shell 了, 那段没法单测: {offenders}")

    # 🔴 「没喊」有两种：**决定不喊** / **没能做决定**。后者必须喊。
    #    实测(2026-08-15): 探针一个 NameError 让它在第 190 行就崩了,
    #    告警文件压根没被创建 —— 而「文件不存在」和「(a) 类决定不喊」
    #    在 `[ -s ... ]` 里长得一模一样, 表象都是「alerts 没有新增」。
    assert "! -f /tmp/replay_equivalence.alert" in src, (
        "cron 没有区分「决定不喊」和「没能做决定」—— "
        "探针崩掉时会静默, 而那正好长得像修好了")


# ── 🔴 载体：判定模块必须**无 import 期副作用** ──────────────────────────
def test_policy_module_has_no_import_side_effects():
    """⛔ 判定模块不许 import 任何会在模块级动全局状态的东西。

    实测代价(2026-08-15): 这些断言原来住在 `replay_equivalence_probe` 里,
    而那个模块在模块级跑 `bootstrap_probe`(设租户 ContextVar / 改 sys.path)。
    于是本文件只是 import 了它, 同进程后面的
    `test_tenant_ctx_plumbing` / `test_capability_calculator_unit`
    **7 条当场变红** —— 单独跑 9 passed, 一起跑 7 failed。

    ▎判定逻辑要能被单独 import。跟 IO/自举/全局状态放在一起,
    ▎它就只能连着那一整套一起加载, 而那一整套会改别人的世界。
    """
    import ast
    from pathlib import Path

    mod = (Path(__file__).resolve().parents[1] / "smartbi" / "scripts"
           / "replay_alert_policy.py")
    assert mod.exists(), f"⛔ 路径写错了就不是「跳过」而是「没测」: {mod}"
    tree = ast.parse(mod.read_text(encoding="utf-8"))

    imported = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported += [a.name for a in node.names]
        elif isinstance(node, ast.ImportFrom):
            imported.append(node.module or "")
    banned = [m for m in imported
              if m and m != "__future__" and m.startswith("smartbi")]
    assert not banned, (
        f"判定模块 import 了 smartbi 的东西: {banned} —— "
        "那会把自举/全局状态一起拉进来, 正是它被拆出来的原因")

    # 模块级只许有 import / 常量 / 函数定义, ⛔ 不许有裸调用。
    calls = [n for n in tree.body if isinstance(n, ast.Expr)
             and isinstance(n.value, ast.Call)]
    assert not calls, f"判定模块在模块级有调用: {[ast.dump(c)[:60] for c in calls]}"
