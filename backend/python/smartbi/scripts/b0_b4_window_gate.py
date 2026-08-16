"""B-0 / B-4 闸 · 时间窗与菜名抽取。

## 他哪句话要求了这个

· B-4：「中午也会问一次…把**今天到中午的目前的所有信息**整理出来」
· B-0：实测「上半年营业额怎么样」被 `dish='上半年'` 拦成反问（不是客户原话，
  是执行侧实测的缺陷；修它是为了让上面那类问句走得到答案）

## 三档断言，缺一不可

1. **修好的** —— 该变的变了
2. **阴性对照** —— 真·累计（「到今天为止 / 截至今天 / 开业至今」）⛔ 不许被吞掉
   ⚠️ 这一档是本闸的重点：「含『今天』就当今天」会把真累计问句也改掉，
   而那个错法**在第 1 档上看不出来**（第 1 档全绿）。
3. **回归对照** —— 别的窗口没动

## 菜名抽取的阳性对照（硬约束 9）

「时间词不再被当菜名」是阴性读数。⇒ 必须同时证明抽取器**还活着**：
真菜名照样抽得出，且「总汇三明治」这种**含通用词的真菜名**不被误伤。
"""
import datetime
import sys

from smartbi.gold.restaurant.restaurant_ops_router import (
    _resolve_sales_date_range as resolve,
    extract_dish_candidate,
)

TODAY = datetime.date(2026, 8, 16)
D = datetime.date

FIXED = [
    ("上半年营业额怎么样", (D(2026, 1, 1), D(2026, 6, 30)), "上半年"),
    ("下半年生意如何", (D(2026, 7, 1), D(2026, 8, 16)), "下半年"),
    ("今年上半年营收", (D(2026, 1, 1), D(2026, 6, 30)), "上半年"),
    ("去年上半年", (D(2025, 1, 1), D(2025, 6, 30)), "去年上半年"),
    ("今天到现在的经营情况", (D(2026, 8, 16), D(2026, 8, 16)), "今天"),
    ("今天截至目前的情况", (D(2026, 8, 16), D(2026, 8, 16)), "今天"),
]
CUMULATIVE = ["到今天为止的营收", "截至今天的累计", "开业至今营收", "截至目前营收"]
REGRESSION = [
    ("今天营业额", (D(2026, 8, 16), D(2026, 8, 16)), "今天"),
    ("昨天营业额", (D(2026, 8, 15), D(2026, 8, 15)), "昨天"),
    ("上个季度营收", (D(2026, 4, 1), D(2026, 6, 30)), "上个季度"),
    ("上周营收", (D(2026, 8, 3), D(2026, 8, 9)), "上周"),
]
TIME_WORDS = ["上半年营业额怎么样", "下半年生意如何", "上季度营收", "本季度怎么样", "今年营业额"]
#: ⚠️ 名字**不能**叫 REAL_DISHES —— 那个名字在
#:    `smartbi/gold/tests/test_catalogue_over_blacklist.py` 已经用于另一个概念
#:    (纯菜名元组), `test_no_drifted_duplicate_constants` 会判它们「漂了」。
#:    ⛔ 不加豁免, 改我自己的名字: 这里是「问句 -> 期望菜名」的配对。
REAL_DISH_QUERIES = [("罗氏虾卖得怎么样", "罗氏虾"), ("总汇三明治的毛利率", "总汇三明治"),
               ("红糖糍粑销量", "红糖糍粑")]


def main() -> int:
    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    print("── 1 修好的 ──")
    for q, rng, label in FIXED:
        got = resolve(q, today=TODAY)
        check(f"  {q}", got == (rng, label), f"实得 {got}")

    print("── 2 阴性对照: 真·累计不许被吞 ──")
    for q in CUMULATIVE:
        got = resolve(q, today=TODAY)
        check(f"  {q}", got == ((D(2000, 1, 1), TODAY), "截至目前"), f"实得 {got}")

    print("── 3 回归对照: 别的窗口没动 ──")
    for q, rng, label in REGRESSION:
        got = resolve(q, today=TODAY)
        check(f"  {q}", got == (rng, label), f"实得 {got}")

    print("── 4 菜名抽取: 时间词不再被当菜名 ──")
    for q in TIME_WORDS:
        got = extract_dish_candidate(q)
        check(f"  {q}", got is None, f"实得 dish={got!r}")

    print("\n" + "=" * 74)
    # 阳性对照先看 —— 抽取器活着, 上面那四条阴性断言才有意义
    alive = [(q, extract_dish_candidate(q), want) for q, want in REAL_DISH_QUERIES]
    ok_alive = all(got == want for _, got, want in alive)
    print(f"[阳性对照] 真菜名照样抽得出（含「总汇三明治」这种带通用词的）: {ok_alive}")
    for q, got, want in alive:
        print(f"           {q!r} -> {got!r} (期望 {want!r})")
    if not ok_alive:
        print("⛔ 抽取器本身坏了 ⇒「时间词不再被当菜名」只是它什么都抽不出来, 读数作废。")
        return 2

    total = len(FIXED) + len(CUMULATIVE) + len(REGRESSION) + len(TIME_WORDS)
    print(f"[主断言]   {total - len(bad)}/{total} 条通过")
    if bad:
        print(f"\n🔴 不通过: {bad}")
        return 1
    print("=" * 74)
    print("✅ 时间窗与菜名抽取全部成立")
    return 0


if __name__ == "__main__":
    sys.exit(main())
