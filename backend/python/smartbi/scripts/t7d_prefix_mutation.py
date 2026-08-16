"""T7-d · 对 T3 prompt 做真源码变异，证明「可缓存前缀」那道闸能红。

两个变异都是**最容易发生的做坏方式**：往 prompt **前部**插入随请求变化的内容。

| 变异 | 插什么 | 后果 |
|---|---|---|
| Z1 | 今天日期 | 前缀**每天**失效一次 |
| Z2 | 用户问句 | 前缀**每次**都失效 —— 缓存等于没有 |

⚠️ 本文件用 Write 落盘、⛔ 不经 shell heredoc ——
   前两次尝试的 `\\n` 被 heredoc 吃成真换行，写出去的是语法错的源码，
   于是「变异红了」红的是 SyntaxError 不是断言（硬约束 6，本轮第三次）。
"""
import pathlib
import subprocess
import sys

SRC = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant" / "restaurant_intent.py"
CWD = pathlib.Path(__file__).resolve().parents[2]
GATE = [sys.executable, "-m", "pytest",
        "tests/test_t3_prompt_prefix_is_cacheable.py", "-q", "--timeout=60"]

ANCHOR = '\n    return (\n        "你是餐饮老板问答系统的意图解析器。'

Z1 = (
    '\n    import datetime as _dt'
    '\n    return ('
    '\n        f"今天是 {_dt.date.today()}。\\n"'
    '\n        "你是餐饮老板问答系统的意图解析器。'
)
Z2 = (
    '\n    return ('
    '\n        f"当前问题: {query}\\n"'
    '\n        "你是餐饮老板问答系统的意图解析器。'
)

MUTATIONS = [
    ("Z1 前部插今天日期(前缀每天失效)", Z1),
    ("Z2 前部插用户问句(前缀每次都失效)", Z2),
]


def _read():
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(body):
    with open(SRC, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def main() -> int:
    original = _read()
    if original.count(ANCHOR) != 1:
        print(f"⛔ 锚点 {original.count(ANCHOR)} 次(应为 1) —— 变异到不了源码")
        return 2

    base = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True)
    print(f"[基线] rc={base.returncode}")
    if base.returncode != 0:
        print("⛔ 基线不绿 ⇒ 变异对照无意义")
        return 2

    failures = []
    try:
        for name, repl in MUTATIONS:
            _write(original.replace(ANCHOR, repl, 1))
            # 形态 C‴: 先证明变异到达且语法合法, 再看闸红不红
            syn = subprocess.run(
                [sys.executable, "-c",
                 f"import ast,pathlib;ast.parse(pathlib.Path(r'{SRC}').read_text(encoding='utf-8'))"],
                capture_output=True, text=True)
            if syn.returncode != 0:
                print(f"⛔ {name}: 变异后语法错 ⇒ 红的是 SyntaxError 不是断言")
                print("   " + syn.stderr.strip().splitlines()[-1])
                failures.append(name)
                continue
            r = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True)
            reds = [l.split("::")[1].split()[0][:44]
                    for l in r.stdout.splitlines() if l.startswith("FAILED")]
            ok = r.returncode != 0
            print(f"{'✅' if ok else '🔴'} {name}: {'红' if ok else '**没红**'}  {reds[:4]}")
            if not ok:
                failures.append(name)
    finally:
        _write(original)

    post = subprocess.run(GATE, cwd=CWD, capture_output=True, text=True)
    print(f"[还原后] rc={post.returncode}")
    if post.returncode != 0:
        print("⛔ 还原之后不绿 ⇒ 源文件没恢复干净")
        return 2
    print("=" * 70)
    if failures:
        print(f"🔴 {len(failures)} 个变异没按预期红: {failures}")
        return 1
    print(f"✅ {len(MUTATIONS)} 个变异都红了 —— 这道闸拦得住「往前部插东西」")
    return 0


if __name__ == "__main__":
    sys.exit(main())
