"""B-1 第二步 · 对 `daily_table.py` 做**真源码变异**, 证明呈现闸能红。

四个变异各自打在**一条被裁定的行为**上, ⛔ 不是随手改一行实现(形态 C):

| 变异 | 破坏的行为 | 应该红 |
|---|---|---|
| N1 | 缺卡的行填 `¥0.00` 而不是「缺成本卡」 | 2a —— 「毛利是 0」与「算不出来」混成一个样 |
| N2 | 差额把**缺卡**的菜也算进去(一个很像对的写法) | 1 —— 恒等式 |
| N3 | 加回毛利率列 | 3a/3b —— 常数回声 0.68 |
| N4 | 披露之间用 `
` 而不是空行 | 6 —— 渲染后并成一坨 |

N4 是**修改前的真实写法**, 也是本仓「8 张表格上线后并成一坨」的成因。
⚠️ 它在纯文本读数里完全看不出来(字全在、顺序也对), 只有断言**渲染后的分段**
才抓得住 —— 那次四个 PR + 两轮 85/85 电池 + CI 全绿都没发现它。

N2 值得单独说: 「缺卡的菜也该算进差额」读起来完全合理, 而它是错的 ——
抬头毛利**只统计有卡的菜**, 缺卡的菜对差额贡献 0。这条变异证明恒等式断言
不是摆设, 它挡的正是这个最容易写错的方向。
"""
import pathlib
import subprocess
import sys

SRC = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant" / "daily_table.py"
CWD = pathlib.Path(__file__).resolve().parents[2]
GATE = [sys.executable, "-m", "smartbi.scripts.b1_daily_table_gate"]

N1_OLD = "            cost = profit = NO_COST_CARD\n        lines.append("
N1_NEW = "            cost = profit = _money(0.0)   # MUTATION N1\n        lines.append("

N2_OLD = "    identity_holds = abs(profit_gap - truncated_profit) < 0.01"
N2_NEW = (
    "    _no_card_profit = sum(float(d.get('grossProfit') or 0.0) for d in no_card)\n"
    "    identity_holds = abs(profit_gap - truncated_profit - _no_card_profit) < 0.01"
    "   # MUTATION N2"
)

N3_OLD = '        _row(["菜品", "营收", "成本", "毛利"]),'
N3_NEW = (
    '        _row(["菜品", "营收", "成本", "毛利", "毛利率"]),   # MUTATION N3'
)
N3_OLD2 = '        lines.append(_row([str(d.get("name") or "—"), _money(float(d.get("revenue") or 0.0)), cost, profit]))'
N3_NEW2 = (
    '        lines.append(_row([str(d.get("name") or "—"), _money(float(d.get("revenue") or 0.0)),\n'
    '                           cost, profit, f\'{float(d.get("marginRate") or 0):.2f}\']))   # MUTATION N3'
)

#: N4 —— 披露之间用 \n 而不是空行。这**正是修改前的写法**, 也是本仓那次
#: 「8 张表格上线后并成一坨」的成因: markdown 把连续非空行并成一个段落。
#: ⚠️ 它在纯文本读数里**完全看不出来** —— 字全在、顺序也对。
N4_OLD = 'return "\\n".join(lines) + "\\n\\n" + "\\n\\n".join(notes)'
N4_NEW = 'return "\\n".join(lines) + "\\n\\n" + "\\n".join(notes)   # MUTATION N4'

#: N5 —— 把覆盖率限定语删掉。这是「产品退化成普通 BI 报表」的那一步:
#: 数字一个不少、格式一样好看, 唯独不再说「这个数覆盖到哪」。
N5_OLD = "    cov = data.get(\"coverage\") or {}"
N5_NEW = "    cov = {}   # MUTATION N5 —— 限定语被删掉"

#: N6 —— 把「成本 > 营收」那条点名删掉(负数照样显示, 只是不说它是什么)。
#: 这是「数字准了但没用」的那一步: 老板看到 −¥115,674 只会以为系统坏了。
N6_OLD = "    if broken:"
N6_NEW = "    if False:   # MUTATION N6 —— 不再指着坏数据说话"

MUTATIONS = [
    ("N1 缺卡行填 ¥0.00", [(N1_OLD, N1_NEW)], {"2a"}),
    ("N6 不再点名成本>营收的菜", [(N6_OLD, N6_NEW)], {"8"}),
    ("N5 删掉覆盖率限定语(退化成普通 BI 报表)", [(N5_OLD, N5_NEW)], {"7"}),
    ("N4 披露之间用 \\n 不用空行(渲染后并成一坨)", [(N4_OLD, N4_NEW)], {"6"}),
    ("N2 差额把缺卡的菜也算进去", [(N2_OLD, N2_NEW)], {"1"}),
    ("N3 加回毛利率列", [(N3_OLD, N3_NEW), (N3_OLD2, N3_NEW2)], {"3a"}),
]


def _read():
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(body):
    with open(SRC, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def run_gate():
    p = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True, encoding="utf-8")
    # ⛔ 只认「🔴 <编号> …」这种用例行, ⛔ 不数闸自己的小标题
    #    (上一个变异脚本就是被「🔴 不符合:」那行骗过一次)
    reds = set()
    for line in p.stdout.splitlines():
        if line.startswith("🔴 ") and len(line) > 2:
            token = line[2:].split()[0]
            if token[0].isdigit():
                reds.add(token)
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
            body = original
            broke = False
            for old, new in pairs:
                if body.count(old) != 1:
                    print(f"⛔ {name}: 锚点出现 {body.count(old)} 次(应为 1) ⇒ 变异没到达源码")
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
                capture_output=True, text=True,
            )
            if syn.returncode != 0:
                print(f"⛔ {name}: 变异后语法错 ⇒ 红的是 SyntaxError 不是断言")
                print(syn.stderr[-300:])
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
        print("⛔ 还原之后闸不绿 ⇒ 源文件没恢复干净, 停下人工检查。")
        return 2
    print("=" * 78)
    if failures:
        print(f"🔴 {len(failures)} 个变异没按预期红: {failures}")
        return 1
    print(f"✅ {len(MUTATIONS)} 个变异都恰好红在该红的断言上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
