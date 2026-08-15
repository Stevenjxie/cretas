"""B-1 第一步 · 对 `_detect_output_preference` 做**真源码变异**, 证明那道闸能红。

⛔ 不在探针里「重写一遍那两个错版本」—— 那样量的是我重写的东西, 不是产品代码
   (形态 D: 两份必漂)。这里直接改源文件、跑闸、再还原。

## 两个变异各自守什么

| 变异 | 改成 | 应该红的用例 |
|---|---|---|
| M1 | 还原成**修改前**的纯加法版本 | 2 / 3 / 4 —— 缺陷本身 |
| M2 | 「否定命中就 `return (TEXT,)`」 | **只有 7** —— 一个在前六条上全绿的错实现 |

M2 存在的理由: 它比正确实现简单, 前六条全过。**没有第 7 条用例, 我会写出它
并且验收通过。** ⇒ 这次变异量的是「第 7 条用例值不值得存在」。

## 硬约束 5

文本模式 + `newline=''` —— ⛔ 不用 `bytes` 字面量(中文注释进不去), 也不做行尾转换。

## 硬约束 1 / 形态 C‴

先断言「变异真的到达了源码」(替换发生了 + 语法仍合法), 再看闸红不红。
替换没发生的话, 闸不红什么都不说明。
"""
import pathlib
import subprocess
import sys

SRC = pathlib.Path(__file__).resolve().parents[2] / "smartbi" / "gold" / "restaurant" / "restaurant_intent.py"
GATE = [sys.executable, "-m", "smartbi.scripts.b1_output_preference_gate"]
CWD = pathlib.Path(__file__).resolve().parents[2]

# ── M1: 还原成修改前的纯加法 ────────────────────────────────────────────────
M1_OLD = """    said = False                      # 用户是否**显式表达过**输出形态
    suppressed = set()"""
M1_NEW = """    return _MUTANT_LEGACY(lowered)     # MUTATION M1
    said = False                      # 用户是否**显式表达过**输出形态
    suppressed = set()"""
M1_HELPER = '''

def _MUTANT_LEGACY(lowered):
    """MUTATION M1 —— 2026-08-16 修改**之前**的实现, 逐字还原。"""
    forms = [
        form
        for form, tokens in _OUTPUT_FORM_TOKENS
        if any(token in lowered for token in tokens)
    ]
    if not forms:
        return ()
    return (OUTPUT_FORM_TEXT, *forms)
'''

# ── M2: 否定命中就整个清空 ──────────────────────────────────────────────────
M2_OLD = """    forms = []
    for form, tokens in _OUTPUT_FORM_TOKENS:
        if any(token in lowered for token in tokens):"""
M2_NEW = """    if suppressed:                     # MUTATION M2 —— 否定即清空
        return (OUTPUT_FORM_TEXT,)
    forms = []
    for form, tokens in _OUTPUT_FORM_TOKENS:
        if any(token in lowered for token in tokens):"""

#: 🔴 期望第一版写的是 M1={2,3,4} / M2={7}, **两个都错**, 而实测把它们纠正了:
#:   · M1 还红在**第 8 条**('别用表格，画个图' -> 纯加法给出 text+table+chart,
#:     把用户明确否掉的表格也带上了)。那条我原以为只跟 M2 有关。
#:   · M2 红的是**第 8 条不是第 7 条**。第 7 条('别用图，用表格')在 M2 下是**绿的** ——
#:     因为「别用图」压根没被识别成否定(chart 的 token 里没有裸「图」), suppressed 为空,
#:     M2 的提前返回根本不触发。⇒ 第 7 条的注释「『否定即清空』会在这条红」**是假的**,
#:     已改。真正杀死 M2 的是第 8 条。
MUTATIONS = [
    ("M1 还原成修改前的纯加法", [(M1_OLD, M1_NEW)], M1_HELPER, {2, 3, 4, 8}),
    ("M2 否定即清空(前七条全绿的错实现)", [(M2_OLD, M2_NEW)], "", {8}),
]

N_CASES = 8


def _read():
    # ⛔ 不用 `Path.read_text(newline=…)` —— 那个参数要 Python 3.13+, 本机 3.11 直接 TypeError。
    #    硬约束 5 的原样写法就是 open(), 我图省事换成 pathlib 才踩的。
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(body):
    with open(SRC, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def run_gate():
    p = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True, encoding="utf-8")
    # 🔴 只数**前 N 行**用例行。闸在末尾还会打一行「🔴 不符合:」小标题, 它同样以 🔴
    #    开头 —— 第一版把它数成了「第 9 条用例红了」, 于是两个变异都被判成
    #    「没按预期红」, 而它们其实红得完全正确。
    #    ⇒ 又一次「闸把自己的输出也数了进去」(本仓已记过两次)。
    marked = [l for l in p.stdout.splitlines() if l[:1] in ("✅", "🔴")][:N_CASES]
    reds = {i for i, line in enumerate(marked, start=1) if line.startswith("🔴")}
    return p.returncode, reds, p.stdout


def main() -> int:
    original = _read()
    baseline_rc, baseline_reds, _ = run_gate()
    print(f"[基线] rc={baseline_rc} 红的用例={sorted(baseline_reds) or '无'}")
    if baseline_rc != 0:
        print("⛔ 基线就不是绿的 ⇒ 变异对照无意义。")
        return 2

    failures = []
    try:
        for name, pairs, helper, expect_red in MUTATIONS:
            body = original
            for old, new in pairs:
                if body.count(old) != 1:
                    print(f"⛔ {name}: 锚点出现 {body.count(old)} 次(应为 1) ⇒ 变异没到达源码")
                    failures.append(name)
                    break
                body = body.replace(old, new, 1)
            else:
                body += helper
                _write(body)
                # 形态 C‴: 先证明变异到达且语法合法, 再看闸红不红
                syn = subprocess.run(
                    [sys.executable, "-c", f"import ast,pathlib;ast.parse(pathlib.Path(r'{SRC}').read_text(encoding='utf-8'))"],
                    capture_output=True, text=True,
                )
                if syn.returncode != 0:
                    print(f"⛔ {name}: 变异后语法错 ⇒ 闸红的是 SyntaxError, 不是断言")
                    print(syn.stderr[-400:])
                    failures.append(name)
                    continue
                rc, reds, out = run_gate()
                ok = (rc == 1 and reds == expect_red)
                print(f"\n{'✅' if ok else '🔴'} {name}")
                print(f"   rc={rc} 红的用例={sorted(reds) or '无'} 期望恰好={sorted(expect_red)}")
                if not ok:
                    failures.append(name)
                    print("   ── 闸的原文 ──")
                    print("\n".join("   " + l for l in out.splitlines()))
    finally:
        _write(original)

    post_rc, post_reds, _ = run_gate()
    print(f"\n[还原后] rc={post_rc} 红的用例={sorted(post_reds) or '无'}")
    if post_rc != 0:
        print("⛔ 还原之后闸不绿 ⇒ 源文件没恢复干净, 停下人工检查。")
        return 2

    print("=" * 78)
    if failures:
        print(f"🔴 {len(failures)} 个变异没按预期红: {failures}")
        return 1
    print("✅ 两个变异都恰好红在该红的用例上 —— 这道闸在守东西")
    return 0


if __name__ == "__main__":
    sys.exit(main())
