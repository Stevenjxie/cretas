"""审计：本轮做的功能，**CI 自动跑的那部分**抓不抓得住退化？

## 为什么不能看「有没有写测试」

本轮 22 个验证件里 **16 个是 smartbi/scripts/ 下的手工探针 —— CI 从不跑**。
它们在我手上全绿，但一次退化不会有人知道。
▎本仓自己的教训：**闸不跑 = 没有闸**。

⇒ 这个审计**只跑 CI 会跑的那部分**（`tests/` 目录），逐个破坏产品行为，
看哪些**活了下来**。活下来的 = 自动化侧的真实缺口。

⛔ 不数「测试文件有几个」，⛔ 也不数断言条数 —— 那两个都和「能不能抓到」无关。
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
R = ROOT / "smartbi" / "gold" / "restaurant"

#: (功能, 文件, 锚点, 变异, 一句话说明这次破坏了什么)
MUTATIONS = [
    # ⚠️ 锚点选**常量本身** —— `cost = profit = NO_COST_CARD` 在文件里有两处
    #    (普通行 + 份数最多那行), 拿它当锚点会 count!=1 而使读数无效(第一版踩过)。
    ("B-1 缺卡行写「缺成本卡」", R / "daily_table.py",
     'NO_COST_CARD = "缺成本卡"',
     'NO_COST_CARD = "¥0.00"',
     "缺卡的菜显示 ¥0.00 —— 「毛利是 0」和「算不出来」混成一个样"),
    ("B-1 ⛔ 无毛利率列", R / "daily_table.py",
     '        _row(["菜品", "营收", "成本", "毛利"]),',
     '        _row(["菜品", "营收", "成本", "毛利", "毛利率"]),',
     "加回毛利率列 —— 对缺卡菜恒等于 0.68 的常数回声"),
    ("B-1 成本>营收点名", R / "daily_table.py",
     "    if broken:",
     "    if False:",
     "不再指着坏成本卡说话"),
    ("B-1 覆盖率限定语", R / "daily_table.py",
     '    cov = data.get("coverage") or {}',
     "    cov = {}",
     "删掉覆盖率限定语 —— 退化成普通 BI 报表"),
    ("B-3 开价后果表述", R / "fill_offers.py",
     '        "text": (f"{consequence}"',
     '        "text": (f""',
     "开价不再说「不做的后果」"),
    ("B-0 时间词不当菜名", R / "restaurant_ops_router.py",
     "        if candidate and not _is_pure_time_expression(candidate):",
     "        if candidate:",
     "「上半年」又被当成菜品名"),
    ("B-4 今天到现在", R / "restaurant_ops_router.py",
     "    if _TODAY_SO_FAR_RE.search(text):",
     "    if False:",
     "「今天到现在」又变回开业至今"),
    ("裁定③ 日历半年", R / "restaurant_ops_router.py",
     '    if "上半年" in text or "下半年" in text:',
     "    if False:",
     "上半年退回「全部历史」"),
]

def _ci_args():
    """⛔ **照抄 CI 那一步的 pytest 参数**, 不自拟目录。

    第一版直接跑 `tests/`, 基线就红了 —— 因为漏了 `ci-gate-excludes.txt`
    和 `-k "not e2e and not integration"`。那是我自己定的规矩:
    「全量要有出处, 把 CI 那一步的命令原样抄出来照跑」。
    ⚠️ 这里只取 `tests/` 这一段(本轮的功能都在它下面), 其余目录与本审计无关。
    """
    excludes = []
    for line in (ROOT / "ci-gate-excludes.txt").read_text(encoding="utf-8").splitlines():
        line = line.split("#")[0].strip()
        if line:
            excludes.append(f"--ignore={line}")
    return ["tests/", "--timeout=60", "--ignore=tests/test_data_accuracy.py",
            *excludes, "-k", "not e2e and not integration"]


CI_TESTS = _ci_args()


def _read(p):
    with open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(p, body):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(body)


def main() -> int:
    base = subprocess.run(
        [sys.executable, "-m", "pytest", *CI_TESTS, "-q", "--no-header"],
        cwd=ROOT, capture_output=True, text=True)
    if base.returncode != 0:
        print("⛔ 基线就不绿, 审计无意义")
        print(base.stdout[-800:])
        return 2
    print("[基线] tests/ 全绿\n")

    survived, caught = [], []
    for name, path, old, new, what in MUTATIONS:
        orig = _read(path)
        if orig.count(old) != 1:
            print(f"⛔ {name}: 锚点 {orig.count(old)} 次(应为 1) —— 审计本身失效")
            survived.append((name, "锚点失效"))
            continue
        _write(path, orig.replace(old, new, 1))
        try:
            r = subprocess.run(
                [sys.executable, "-m", "pytest", *CI_TESTS, "-q", "--no-header"],
                cwd=ROOT, capture_output=True, text=True)
            hit = r.returncode != 0
        finally:
            _write(path, orig)
        (caught if hit else survived).append((name, what))
        print(f"{'✅ 抓到' if hit else '🔴 活了下来'}  {name}")
        if not hit:
            print(f"          破坏的是: {what}")

    print("\n" + "=" * 74)
    print(f"CI 自动跑的部分: 抓到 {len(caught)} / {len(MUTATIONS)}，"
          f"**活下来 {len(survived)}**")
    if survived:
        print("\n🔴 自动化侧的缺口（这些行为退化了 CI 不会红）:")
        for name, what in survived:
            print(f"   · {name} —— {what}")
    return 1 if survived else 0


if __name__ == "__main__":
    sys.exit(main())
