"""澄清按钮改成「合成完整问句」的效果 —— 正例 / 反例对照。

⛔ 不描述，跑出来看。每一行都过**真的** `_resolve_sales_date_range`
（确定性层，与生产同一个函数），而不是我口述它会怎样。

## 判据（每条合成句都要过）

1. **自足**：合成句单独拿去解析，解得出窗口（不是「全部历史」）
2. **不重复**：不出现两个时间词 / 两个门店段
3. **可复用**：它是一句人能看懂、下次能直接问的话（飞轮语料的价值就在这）

拼出来过不了 1，就**退回现在的行为**（发光秃秃的词），⛔ 不发一个更坏的串。
"""
import sys

from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_date_range

TIME_BUTTONS = ("本月", "上个月", "最近7天", "最近30天")


def compose(window: str, seed: str) -> str:
    """按钮合成：前置。

    ⚠️ 前置之所以安全，是因为**触发时间澄清的硬条件**就是
    「LLM 没认出时间词 且 确定性层解不出窗口」——
    走到这一步的 seed 按定义没有时间段可替换。
    """
    return f"{window}{seed}"


def self_sufficient(sentence: str) -> bool:
    """自足 = 单独拿去解析解得出窗口。"""
    return _resolve_sales_date_range(sentence)[1] != "全部历史"


def show(title, seeds, buttons, expect_ok=True):
    print(f"\n{'=' * 76}\n{title}\n{'=' * 76}")
    for seed in seeds:
        seed_ok = self_sufficient(seed)
        print(f"\n原问句: {seed!r}   自足={seed_ok}"
              f"{'   ⚠️ 它不会触发时间澄清' if seed_ok else ''}")
        for w in buttons:
            now = w                      # 现在发的
            after = compose(w, seed)     # 改后发的
            ok = self_sufficient(after)
            label = _resolve_sales_date_range(after)[1]
            mark = "✅" if ok == expect_ok else "🔴"
            print(f"  点「{w}」")
            print(f"      现在  → {now!r:14} 自足={self_sufficient(now)}  "
                  f"窗口={_resolve_sales_date_range(now)[1]!r}")
            print(f"      改后  → {after!r:28} 自足={ok}  窗口={label!r}  {mark}")


def main() -> int:
    # ── 正例：真实会触发时间澄清的问句 ──────────────────────────────
    show("正例 · 缺时间的问句（生产上真的会反问「哪个时间范围」）",
         ["米饭的销量是多少", "哪个菜卖得好", "哪个门店营收最好", "有没有店在亏损"],
         TIME_BUTTONS)

    # ── 正例 2：两轮澄清（先时间后门店）会不会拼坏 ──────────────────
    print(f"\n{'=' * 76}\n正例2 · 连续两轮澄清：时间 → 门店\n{'=' * 76}")
    seed = "米饭的销量是多少"
    after_time = compose("本月", seed)
    after_both = f"全部门店{after_time}"
    for s in (seed, after_time, after_both):
        print(f"  {s!r:34} 自足={self_sufficient(s)}  窗口={_resolve_sales_date_range(s)[1]!r}")
    print("  ⇒ 门店段前置到时间段之前, 与 `_time_window_switch_followups` 的"
          "`{store}{time}{body}` 顺序一致")

    # ── 反例 1：seed 自己已经带时间 ────────────────────────────────
    print(f"\n{'=' * 76}\n反例1 · seed 自己已带时间词（⚠️ 这类**到不了**时间澄清）\n{'=' * 76}")
    for seed in ["最近损耗怎么样", "上个月哪个菜卖得好", "本月营业额是多少"]:
        print(f"  {seed!r:22} 自足={self_sufficient(seed)}  "
              f"窗口={_resolve_sales_date_range(seed)[1]!r}")
        bad = compose("本月", seed)
        print(f"      若硬拼「本月」→ {bad!r}  窗口={_resolve_sales_date_range(bad)[1]!r}")
    print("  ⇒ 这些句子确定性层就解得出窗口 ⇒ **不满足时间澄清的触发条件** ⇒"
          " 生产上按钮不会出现, 硬拼是我构造的场景")

    # ── 反例 2：合成之后仍然不自足 ⇒ 自足性检查必须拦下 ──────────────
    print(f"\n{'=' * 76}\n反例2 · 合成后仍不自足 ⇒ 退回现在的行为\n{'=' * 76}")
    weird = ""
    for w in ("本月",):
        after = compose(w, weird)
        print(f"  seed={weird!r} 点「{w}」→ {after!r}  自足={self_sufficient(after)}")
    print("  ⇒ seed 为空时合成句退化成光秃秃的词 —— 自足性检查放行(它确实解得出窗口),")
    print("     但它**不是一句完整的话** ⇒ 需要额外一条: seed 非空才合成。")

    print(f"\n{'=' * 76}")
    print("飞轮语料的差别（这才是长期价值那一格）:")
    print("  现在会记下:  '本月' / '上个月' / '最近7天' / '最近30天' / '全部门店'")
    print("             ⇒ 5 个高频、无意义、且含义随上一轮变化的串")
    print("  改后会记下:  '本月米饭的销量是多少' / '最近30天哪个菜卖得好' …")
    print("             ⇒ 每条都是完整、自足、下次能直接问的句子")
    return 0


if __name__ == "__main__":
    sys.exit(main())
