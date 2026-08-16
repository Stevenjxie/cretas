"""B-5 · 对 `attribution.py` 做真源码变异，证明归因闸能红。

| 变异 | 破坏的行为 | 应该红 |
|---|---|---|
| S1 | 交叉项摊进客流（不再单列） | 1 —— 恒等式 |
| S2 | 去掉交叉项噪声闸（**总是**挑一个主因） | 3 |
| S3 | 缺输入时把 None 兜底成 0 | 4/5 |
| S4 | 主因判反（客流/客单价对调） | 2 |

🔴 **S2 是最重要的一条**：「总是给个主因」在第 1 档（恒等式）上**完全绿** ——
   三项之和照样等于 ΔR。它只在「两者都动」那一档才露出来。
   ⇒ 没有第 3 档，我会写出 S2 并且验收通过，然后**在大促那天告诉老板
     「主要是客流」**，而真相是他自己打的折。

⚠️ S3 值得单说：`or 0` 是最省事的写法，而它把「没取到」翻译成「没营业」——
   两者对归因是**相反**的意思。
"""
import pathlib
import subprocess
import sys

SRC = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant" / "attribution.py"
CWD = pathlib.Path(__file__).resolve().parents[2]
GATE = [sys.executable, "-m", "smartbi.scripts.b5_attribution_gate"]

S1_OLD = "    traffic = dq * p0          # 客流贡献"
S1_NEW = "    traffic = dq * p0 + dq * dp   # MUTATION S1 —— 交叉项摊进客流"

S2_OLD = "    if abs(total) > 0.01 and abs(cross) / abs(total) <= _CROSS_TERM_NOISE:"
S2_NEW = "    if abs(total) > 0.01:   # MUTATION S2 —— 总是挑一个主因"

S3_OLD = """    missing = [name for name, v in (
        ("今天营收", revenue_now), ("今天单量", orders_now),
        ("基线营收", revenue_base), ("基线单量", orders_base),
    ) if v is None]"""
S3_NEW = """    revenue_now = revenue_now or 0.0   # MUTATION S3 —— None 兜底成 0
    orders_now = orders_now or 0.0
    revenue_base = revenue_base or 0.0
    orders_base = orders_base or 0.0
    missing = []"""

S4_OLD = '        driver = "traffic" if abs(traffic) >= abs(ticket) else "ticket"'
S4_NEW = '        driver = "ticket" if abs(traffic) >= abs(ticket) else "traffic"   # MUTATION S4'

#: (名字, 替换对, 应该红的档, **期望的 rc**)
#: 🔴 rc 要逐个写清楚, ⛔ 不能一律期望 1 —— 硬约束 4: rc=2 是「这次没量到东西」,
#:    与 rc=1「量到了且指向缺陷」是**两件事**, 混在一起就等于把三态压回两态。
#: ⚠️ S4 期望 rc=2 是**对的**: 把主因判反, 恰好也打坏了闸自己的**阳性对照**
#:    (「纯客流场景下『主要是客流』出得来」), 于是闸正确地宣告「本轮读数作废」。
#:    ⇒ 那不是闸失灵, 那正是阳性对照该有的反应。
MUTATIONS = [
    ("S1 交叉项摊进客流", [(S1_OLD, S1_NEW)], "1", 1),
    ("S2 总是挑一个主因(第 1 档看不出来)", [(S2_OLD, S2_NEW)], "3", 1),
    ("S3 None 兜底成 0", [(S3_OLD, S3_NEW)], "4", 1),
    ("S4 主因判反(同时打坏阳性对照 ⇒ 期望 rc=2)", [(S4_OLD, S4_NEW)], "2", 2),
]


def _read():
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(body):
    with open(SRC, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def run_gate():
    """返回 (rc, 红在哪几档)。档号 = 断言名开头那个数字。

    ⛔ 只认「🔴 <数字>」这种用例行，⛔ 不数闸末尾的「🔴 不通过:」汇总行
    —— 那行会被算进最后一档（本仓同形已踩过三次，其中一次是我自己的计数器）。
    """
    p = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True, encoding="utf-8")
    reds = set()
    for line in p.stdout.splitlines():
        if line.startswith("🔴 ") and len(line) > 2 and line[2].isdigit():
            reds.add(line[2])
    return p.returncode, reds, p.stdout


def main() -> int:
    original = _read()
    rc0, reds0, _ = run_gate()
    print(f"[基线] rc={rc0} 红的档={sorted(reds0) or '无'}")
    if rc0 != 0:
        print("⛔ 基线不绿 ⇒ 变异对照无意义。")
        return 2

    failures = []
    try:
        for name, pairs, expect, expect_rc in MUTATIONS:
            body, broke = original, False
            for old, new in pairs:
                if body.count(old) != 1:
                    print(f"⛔ {name}: 锚点 {body.count(old)} 次(应为 1) ⇒ 变异没到达源码")
                    failures.append(name)
                    broke = True
                    break
                body = body.replace(old, new, 1)
            if broke:
                continue
            _write(body)
            syn = subprocess.run(
                [sys.executable, "-c",
                 f"import ast,pathlib;ast.parse(pathlib.Path(r'{SRC}').read_text(encoding='utf-8'))"],
                capture_output=True, text=True)
            if syn.returncode != 0:
                print(f"⛔ {name}: 变异后语法错 ⇒ 红的是 SyntaxError 不是断言")
                failures.append(name)
                continue
            rc, reds, out = run_gate()
            ok = (rc == expect_rc and expect in reds)
            print(f"{'✅' if ok else '🔴'} {name}: rc={rc} 红的档={sorted(reds) or '无'} 期望 rc={expect_rc} 且含第 {expect} 档")
            if not ok:
                failures.append(name)
                print("\n".join("   " + l for l in out.splitlines() if l.startswith("🔴")))
    finally:
        _write(original)

    rc9, _, _ = run_gate()
    print(f"[还原后] rc={rc9}")
    if rc9 != 0:
        print("⛔ 还原之后闸不绿 ⇒ 源文件没恢复干净。")
        return 2
    print("=" * 74)
    if failures:
        print(f"🔴 {len(failures)} 个变异没按预期红: {failures}")
        return 1
    print(f"✅ {len(MUTATIONS)} 个变异都红在该红的那一档上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
