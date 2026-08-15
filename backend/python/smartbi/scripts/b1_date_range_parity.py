"""B-1 前置闸 · `date_range` 与 `days` 必须**逐字相同**。

⛔ 这条是防「参数悄悄变成口径」的闸, ⛔ 不是性能测试。

    compute_dish_margins(date_range=(今天-29, 今天))
      必须与
    compute_dish_margins(days=30)
      **逐字相同**

不相同 ⇒ 加 `date_range` 确实改了语义 ⇒ 那时才是口径问题, 停下报 organizer。

## 为什么要真跑, ⛔ 不能只比 SQL 字符串

两条分支的 SELECT / JOIN / GROUP BY 是同一个模板, 只差 WHERE 的时间谓词 ——
比字符串只能证明「模板一样」, 证明不了「**同一个窗口下出数一样**」。
`CURRENT_DATE - 29` 与显式 `(今天-29, 今天)` 的边界是否真的重合(含不含端点、
时区、`t.date` 的类型)只有跑出来才知道。

## 阳性对照(硬约束 9)

主读数是阴性的(「两侧没有差异」)。⇒ 必须先证明这个比较**能**发现差异:
故意把一侧的窗口挪一天, 断言它**报出不同** —— 报不出来说明比较器本身坏了。
"""
import asyncio
import datetime
import json
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
ctx = bootstrap_probe(FACTORY)


def _norm(d):
    """⛔ 逐字比较, 不做任何容差 —— 「差不多」正是这条闸要拦的东西。"""
    return json.dumps(d, ensure_ascii=False, sort_keys=True, default=str)


async def main() -> int:
    pool = await ctx.pool()
    from smartbi.gold.restaurant.dish_margin import compute_dish_margins

    today = datetime.date.today()
    # 🔴 与 `days=30` 等价的窗口是 **today-30 .. today**, ⛔ 不是 today-29。
    #    原谓词是 `t.date >= CURRENT_DATE - 30`(**无上界**), 即 31 天含今天。
    #    第一版我写 today-29 ⇒ 闸红了 —— 而红的是**我的夹具**, 不是被测对象。
    #    ⇒ 先把夹具摆正, 再看它是不是真的改了语义。
    same = (today - datetime.timedelta(days=30), today)
    off_by_one = (today - datetime.timedelta(days=29), today)

    by_days = await compute_dish_margins(pool, FACTORY, days=30)
    by_range = await compute_dish_margins(pool, FACTORY, date_range=same)
    by_shift = await compute_dish_margins(pool, FACTORY, date_range=off_by_one)

    a, b, c = _norm(by_days), _norm(by_range), _norm(by_shift)
    print(f"days=30            dishes={len(by_days.get('dishes', []))} 字节={len(a)}")
    print(f"date_range 同窗     dishes={len(by_range.get('dishes', []))} 字节={len(b)}")
    print(f"date_range 挪一天   dishes={len(by_shift.get('dishes', []))} 字节={len(c)}")

    print("\n" + "=" * 70)
    # 阳性对照先跑 —— 比较器活着才轮到主读数
    control_ok = (b != c)
    print(f"[阳性对照] 挪一天能被这个比较发现: {control_ok}")
    if not control_ok:
        print("⛔ 比较器发现不了一天的差异 ⇒ 主读数无意义, 本轮作废。")
        return 2

    parity = (a == b)
    print(f"[主断言]   date_range(同窗) 与 days=30 逐字相同: {parity}")
    if not parity:
        print("\n🔴 不相同 —— `date_range` 改变了语义, 那是**口径问题**。")
        print("   ⇒ 停下报 organizer, ⛔ 不要自行调整让它们相等。")
        for k in sorted(set(by_days) | set(by_range)):
            x, y = _norm(by_days.get(k)), _norm(by_range.get(k))
            if x != y:
                print(f"   差异键 {k}:\n     days   : {x[:220]}\n     range  : {y[:220]}")
        return 1
    print("=" * 70)
    print("✅ 时间窗只是参数, 口径未变")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
