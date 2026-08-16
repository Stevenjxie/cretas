"""B-3 · 对 `fill_offers.py` 做真源码变异, 证明后果表述那道闸能红。

| 变异 | 破坏的行为 | 应该红 |
|---|---|---|
| P1 | 把后果整句删掉(退回「只给一个数」) | 1 |
| P2 | 后果改用百分比说, 不给金额 | 1 —— 「54.9%」不是他店里的钱 |
| P3 | 好处那段删掉 | 2 |
| P4 | 把两个百分数删掉(以为「不直观」就该删) | 3 |

P4 值得单独说: 客户说「不是特别直观」时, **最容易的误读是「那就别给数了」**。
底层那两个数是后果表述的输入, 三租户已验一位不差 —— 删了等于把已经对的东西
扔掉。这条变异就是钉住那个误读。
"""
import pathlib
import subprocess
import sys

SRC = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant" / "fill_offers.py"
CWD = pathlib.Path(__file__).resolve().parents[2]
GATE = [sys.executable, "-m", "smartbi.scripts.b3_offer_consequence_gate"]

P1_OLD = '        "text": (f"{consequence}"'
P1_NEW = '        "text": (f""   # MUTATION P1 —— 后果没了'

P2_OLD = '        f"现在有 ¥{uncovered_revenue:,.0f}（{(1 - coverage_ratio) * 100:.1f}%）的营收"'
P2_NEW = '        f"现在有 {(1 - coverage_ratio) * 100:.1f}% 的营收"   # MUTATION P2 —— 只给比例不给钱'

P3_OLD = '                 f"这 ¥{gained:,.0f} 的营收就能算进来——"'
P3_NEW = '                 f""   # MUTATION P3 —— 好处没了'

P4_OLD = '                 f"能算进毛利的营收会从 {coverage_ratio * 100:.1f}% "\n                 f"提到约 {after * 100:.1f}%"),'
P4_NEW = '                 f""),   # MUTATION P4 —— 「不直观」被误读成「别给数」'

MUTATIONS = [
    ("P1 后果整句删掉", [(P1_OLD, P1_NEW)], {"1"}),
    ("P2 后果改用百分比, 不给金额", [(P2_OLD, P2_NEW)], {"1"}),
    ("P3 好处那段删掉", [(P3_OLD, P3_NEW)], {"2"}),
    ("P4 把两个百分数删掉(「不直观」的误读)", [(P4_OLD, P4_NEW)], {"3"}),
]


def _read():
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(body):
    with open(SRC, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def run_gate():
    p = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True, encoding="utf-8")
    reds = set()
    for line in p.stdout.splitlines():
        if line.startswith("🔴 ") and len(line) > 2 and line[2].isdigit():
            reds.add(line[2:].split()[0])
    return p.returncode, reds, p.stdout


def main() -> int:
    original = _read()
    rc0, reds0, _ = run_gate()
    print(f"[基线] rc={rc0} 红={sorted(reds0) or '无'}")
    if rc0 != 0:
        print("⛔ 基线不绿 ⇒ 变异对照无意义。")
        return 2

    failures = []
    try:
        for name, pairs, expect in MUTATIONS:
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
            ok = (rc == 1 and reds == expect)
            print(f"{'✅' if ok else '🔴'} {name}: rc={rc} 红={sorted(reds) or '无'} 期望恰好={sorted(expect)}")
            if not ok:
                failures.append(name)
                print("\n".join("   " + l for l in out.splitlines() if l[:1] in ("✅", "🔴")))
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
    print(f"✅ {len(MUTATIONS)} 个变异都恰好红在该红的断言上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
