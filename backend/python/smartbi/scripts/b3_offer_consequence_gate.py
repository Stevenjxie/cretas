"""B-3 闸 · T2 开价必须说清「不做的后果」和「做的好处」。

## 他哪句话要求了这个

「45.1 到 53.9 **不是特别直观**…**你要告诉他如果不做的后果是什么, 以及做的
好处是什么**, 不是说一个数字再去给大家」(owner 口述转录, ⛔ 非逐字稿)。

⇒ 排序「**能不能让他做出决定 > 数字准不准**」。这条改的不是数, 是「只给数」。

## 四条断言

1. **后果在, 且用金额说** —— 「54.9%」是比例, 「¥407,832 算不出毛利」才是他店里的钱
2. **好处在, 且用金额说** —— 补完能多算进多少营收
3. **底层那两个数保留** —— 它是后果表述的输入, ⛔ 不许因为「不直观」就删掉
4. **单行** —— `text` 在正文里被当 `> ` 引用块塞进去, 多行会让第二行掉出引用块

## 阳性对照(硬约束 9)

断言 4 是阴性的(「没有换行」)。⇒ 配一条证明这个检查**能**发现换行:
往 text 里插一个 `\\n` 断言它被抓到。抓不到说明检查器坏了。
"""
import sys

from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps

GAPS = [
    {"name": "罗氏虾", "revenue": 188800.0},
    {"name": "鲈鱼", "revenue": 149688.0},
    {"name": "水煮牛肉", "revenue": 117912.0},
]
COVERAGE = 0.451
DENOM = 1000000.0


def main() -> int:
    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    offers = offers_for_cost_gaps(GAPS, COVERAGE, DENOM)
    if not offers:
        print("⛔ 一条开价都没出 ⇒ 下面全部无意义。")
        return 2
    o = offers[0]
    text = str(o["text"])
    print("── 开价原文 ──")
    print(f"   {text}")
    print("── 原文结束 ──\n")

    uncovered = (1 - COVERAGE) * DENOM
    gained = sum(g["revenue"] for g in GAPS)

    check(
        "1 后果在, 且用**金额**说",
        f"¥{uncovered:,.0f}" in text and "算不出毛利" in text,
        f"应含 ¥{uncovered:,.0f}",
    )
    check(
        "2 好处在, 且用**金额**说",
        f"¥{gained:,.0f}" in text and "就能算进来" in text,
        f"应含 ¥{gained:,.0f}",
    )
    check(
        "3 底层那两个百分数保留（⛔ 不许因为「不直观」就删掉）",
        f"{COVERAGE * 100:.1f}%" in text and "提到约" in text and "先补这" in text,
    )
    check("4 单行（`text` 会被当 `> ` 引用块塞进正文）", "\n" not in text)

    print("\n" + "=" * 74)
    # 阳性对照: 换行检查器能抓到换行吗
    control_ok = "\n" in (text + "\n占位")
    print(f"[阳性对照] 换行检查确实能发现换行: {control_ok}")
    if not control_ok:
        print("⛔ 检查器坏了 ⇒ 断言 4 是恒真式。")
        return 2
    # 阳性对照 2: 覆盖率已满时不该开价(否则「后果」是句空话)
    none_offer = offers_for_cost_gaps(GAPS, 1.0, DENOM)
    print(f"[阳性对照] 覆盖率 100% 时不开价: {none_offer == []}")
    if none_offer != []:
        print("⛔ 满覆盖还在喊「有营收算不出毛利」—— 那句后果在任何数据上都成立, 不区分好坏。")
        return 2

    print(f"[主断言]   {4 - len(bad)}/4 条通过")
    if bad:
        print(f"\n🔴 不通过: {bad}")
        return 1
    print("=" * 74)
    print("✅ 开价说清了不做的后果和做的好处")
    return 0


if __name__ == "__main__":
    sys.exit(main())
