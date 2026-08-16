"""B-0 / B-4 · 真源码变异, 证明时间窗那道闸能红。

| 变异 | 破坏的行为 | 应该红 |
|---|---|---|
| Q1 | 「含『今天』就当今天」(去掉「今天在到之前」的判别) | **第 2 档** —— 真累计被吞 |
| Q2 | 上半年退回「落到全部历史」 | 第 1 档 |
| Q3 | 半年的右端点不夹到今天(下半年给到 12-31) | 第 1 档 |
| Q4 | 菜名排除表不含日历时间词 | 第 4 档 |

🔴 **Q1 是本轮最重要的一条**：那个错法在**第 1 档上全绿**（「今天到现在」照样
   变成今天），只有第 2 档的阴性对照能抓住它。⇒ 没有第 2 档，我会写出 Q1 并
   验收通过，然后把「到今天为止的营收」这类问句一起改坏。
"""
import pathlib
import subprocess
import sys

SRC = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant" / "restaurant_ops_router.py"
CWD = pathlib.Path(__file__).resolve().parents[2]
GATE = [sys.executable, "-m", "smartbi.scripts.b0_b4_window_gate"]

Q1_OLD = "    if _TODAY_SO_FAR_RE.search(text):"
Q1_NEW = '    if any(t in text for t in ("今天", "今日")):   # MUTATION Q1 —— 含今天就当今天'

Q2_OLD = '    if "上半年" in text or "下半年" in text:'
Q2_NEW = '    if False:   # MUTATION Q2 —— 上半年退回「落到全部历史」'

Q3_OLD = "            return (start, min(end, anchor)), label"
Q3_NEW = "            return (start, end), label   # MUTATION Q3 —— 右端点不夹到今天"

Q4_OLD = "} | _CALENDAR_PERIOD_TOKENS)"
Q4_NEW = "})   # MUTATION Q4 —— 菜名排除表不含日历时间词"

MUTATIONS = [
    ("Q1 含「今天」就当今天(第 1 档看不出来)", [(Q1_OLD, Q1_NEW)], "2"),
    ("Q2 上半年退回全部历史", [(Q2_OLD, Q2_NEW)], "1"),
    ("Q3 半年右端点不夹到今天", [(Q3_OLD, Q3_NEW)], "1"),
    ("Q4 菜名排除表不含时间词", [(Q4_OLD, Q4_NEW)], "4"),
]

#: 每一档在闸输出里的小标题, 用来判断红的是哪一档
SECTIONS = {"1": "1 修好的", "2": "2 阴性对照", "3": "3 回归对照", "4": "4 菜名抽取"}


def _read():
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(body):
    with open(SRC, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def run_gate():
    """返回 (rc, 红在哪几档)。⛔ 不数「红了几条」—— 要知道红的是**哪一档**。"""
    p = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True, encoding="utf-8")
    section, reds = None, set()
    for line in p.stdout.splitlines():
        # 🔴 分档区在 "====" 那条分隔线处结束。⛔ 不加这一条的话, 闸末尾的汇总行
        #    「🔴 不通过: [...]」会被算进**最后一档**, 于是每个变异都显示第 4 档也红。
        #    ——「闸把自己的输出也数了进去」, 本仓同形第三次, 这次是我自己的计数器。
        if line.startswith("="):
            section = None
        for key, title in SECTIONS.items():
            if title in line and line.startswith("──"):
                section = key
        if line.startswith("🔴") and section:
            reds.add(section)
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
            ok = (rc == 1 and reds == {expect})
            print(f"{'✅' if ok else '🔴'} {name}: rc={rc} 红的档={sorted(reds) or '无'} 期望恰好第 {expect} 档")
            if not ok:
                failures.append(name)
                print("\n".join("   " + l for l in out.splitlines() if l.startswith("🔴") or l.startswith("──")))
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
