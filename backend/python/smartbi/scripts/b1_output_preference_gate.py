"""B-1 第一步闸 · `_detect_output_preference` 的否定处理。

⛔ 这条闸**先于接线**跑。接线会把「说『别用表格』反而锁死表格」这个潜伏缺陷
   变成用户可见的行为 —— 先修再接, 顺序不能反。

## 八条用例的分工

前六条是缺陷本身(三条红、三条本来就绿, 绿的那三条是**回归对照**:
证明这次改动没把原来对的弄坏)。

后两条专门堵一个**更简单但是错的**实现:「否定命中就返回 (TEXT,)」。
那个实现在前六条上全绿, 只在第 7 条上红 —— 用户明明要了表格却被清空。
⇒ 没有第 7 条, 我大概率会写出那个更简单的版本并且验收通过。

## 阳性对照(硬约束 9)

主读数里有阴性断言(「别用表格」之后 table **不出现**)。
⇒ 配一条: 同一个 table 形态在别的问句上**出得来**(第 5 条)。
   出不来的话「不出现」就是恒真式, 什么都没守住。
"""
import sys

from smartbi.gold.restaurant.restaurant_intent import (
    DEFAULT_OUTPUT_PREFERENCE,
    _detect_output_preference,
)

TEXT, TABLE, CHART = "text", "table", "chart"

#: (问句, 期望, 这条在守什么)
CASES = [
    ("今天各菜品的经营情况", (),              "没提 ⇒ 落租户默认"),
    ("用文字说就行",         (TEXT,),         "🔴 修前 () ⇒ 落默认照样出表格"),
    ("别用表格",             (TEXT,),         "🔴🔴 修前 (text,table) ⇒ 说别用反而锁死"),
    ("不要表格，文字就行",    (TEXT,),         "🔴🔴 修前 (text,table) ⇒ 同上"),
    ("列个表看看",           (TEXT, TABLE),   "回归 + table 的阳性对照"),
    ("画个图",               (TEXT, CHART),   "回归"),
    ("别用图，用表格",        (TEXT, TABLE),   "回归: 未被识别的否定 ⛔ 不许误伤已明确要的表格"),
    ("别用表格，画个图",      (TEXT, CHART),   "🕳 堵洞: 逐形态相减 —— M1 和 M2 都死在这条"),
]

#: 🔴 第 7 条最初标的是「『否定即清空』会在这条红」—— **假的**, 变异实测它在那个错
#:   实现下是绿的。成因: chart 的 token 里没有裸「图」, 所以「别用图」根本没被识别成
#:   否定, `suppressed` 为空, 「否定即清空」的提前返回不触发。
#:   ⇒ 真正杀死那个错实现的是**第 8 条**。第 7 条降级为回归用例。
#:
#: 🧊 由此暴露的**已知缺口, 本轮不修**(登记而非豁免):
#:   「别用图 / 别画图」这类**动词型**否定识别不到 —— 否定短语由前缀 + 肯定 token
#:   派生, 而 chart 的 token 是「画图 / 图表 / 柱状图…」, 前缀表里没有能与之组成
#:   「别画图」的项。
#:   ⛔ 不加裸「别」/「不」前缀: 「按**类别表格**展示」会被读成否定(误伤), 这正是
#:      形态 E —— 宁可闸窄而可信。
#:   ⚠️ 而且加了会**变坏**: 现在「别画图」-> () -> 落默认 (TEXT, TABLE);
#:      一旦识别成否定, 按裁定的第 4 条会变成 (TEXT,), 反而把用户没否过的表格也丢了。
#:   ⇒ 用户可见的伤害为零(chart 本来就不在默认里), 留给 organizer 裁。


def main() -> int:
    print(f"DEFAULT_OUTPUT_PREFERENCE = {DEFAULT_OUTPUT_PREFERENCE}\n")
    bad = []
    for query, expect, guards in CASES:
        got = _detect_output_preference(query)
        ok = got == expect
        mark = "✅" if ok else "🔴"
        print(f"{mark} {query!r:24} -> {str(got):22} 期望 {str(expect):22} | {guards}")
        if not ok:
            bad.append((query, expect, got))

    print("\n" + "=" * 78)
    # 阳性对照: table 这个形态在别的问句上出得来 ⇒ 上面「别用表格后 table 不出现」不是恒真式
    control = _detect_output_preference("列个表看看")
    control_ok = TABLE in control
    print(f"[阳性对照] table 形态在别处出得来: {control_ok}  ({control})")
    if not control_ok:
        print("⛔ table 一次都出不来 ⇒ 那几条阴性断言是恒真式, 本轮读数作废。")
        return 2

    print(f"[主断言]   {len(CASES) - len(bad)}/{len(CASES)} 条符合期望")
    if bad:
        print("\n🔴 不符合:")
        for query, expect, got in bad:
            print(f"   {query!r}: 期望 {expect}, 实得 {got}")
        return 1
    print("=" * 78)
    print("✅ 否定处理正确 —— 可以进第二步接线")
    return 0


if __name__ == "__main__":
    sys.exit(main())
