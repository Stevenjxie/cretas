"""建议要跟着数据走 —— 一句无条件的模板话就是「假建议」。

## 缺陷（2026-08-17 MOCK_REST 冷启动实测）

四个不同问句拿到的建议**一字不差**：

```
这个月生意怎么样 / 昨天营业额多少 / 最近30天卖了多少钱 / 哪家店卖得最好
  → 「建议：先把低于中位的门店拉出来，看是客流少、平均每单低，还是折扣过重；…」
```

而同一时刻的数据是：**10 家店营收最高 ¥2,242,922 / 最低 ¥2,184,720，相差 2.6%**
（CV 0.9%）。产品还是说「表现最强的是 X」「低于中位的有 4 家，**先看** Y」。

▎老板照着去查，**会一无所获**。
▎反目标里最重的一条：**一条误发的提示，烧掉的是「这东西说的话能信」。**

## ⛔ 修法不是压掉建议

2.6% × ¥2.2M ≈ ¥5.8 万，是真钱 —— 该不该追是**他的**判断。
产品的毛病是**替他做了那个判断却没给依据**：说了「谁最强 / 先看谁」，
从没说「差多少」。

⇒ 两件事：① 把差距量出来接在结论后面；② 让建议随差距变。

## 阈值是明写出来的选择

`_STORE_SPREAD_WORTH_CHASING_PCT = 5.0` —— **不是推导出来的**。
它被印在正文里（「低于我们认为值得单独去查的 5%」），
⛔ 藏起来的阈值没人能质疑；印出来老板可以直接反驳。
"""
import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    _STORE_SPREAD_WORTH_CHASING_PCT,
    _advice_line,
)


def _stores(*revenues):
    return [{"store_name": f"店{i}", "revenue": r, "bill_count": 100}
            for i, r in enumerate(revenues, 1)]


class TestAdviceTracksTheSpread:
    def test_flat_spread_says_do_not_split_by_store(self):
        """prod 实测那一刻的形状：10 家店相差 2.6%。"""
        text = _advice_line(_stores(2242922.94, 2184719.97), True)
        assert "按门店拆多半找不到东西" in text, text
        assert "2.6%" in text, f"没把实际差距说出来：{text}"

    def test_flat_spread_points_somewhere_else(self):
        """⛔ 不能只说「别查门店」就没了 —— 要给他下一个能看的方向。"""
        text = _advice_line(_stores(100.0, 98.0), True)
        assert "菜品毛利" in text or "时段" in text, text

    def test_wide_spread_keeps_the_store_hunt(self):
        """阳性对照：差距大时**必须**照旧建议按门店查。

        ⛔ 少了这条，上面两条可能只是因为这个函数永远说「别查门店」。
        """
        text = _advice_line(_stores(100.0, 50.0), True)
        assert "值得单独看" in text, text
        assert "低于中位的门店拉出来" in text, text
        assert "50.0%" in text, f"没把实际差距说出来：{text}"

    def test_the_threshold_is_printed_so_it_can_be_argued_with(self):
        """阈值必须出现在给老板的话里 —— 藏起来的阈值没法被质疑。"""
        text = _advice_line(_stores(100.0, 98.0), True)
        assert f"{_STORE_SPREAD_WORTH_CHASING_PCT:.0f}%" in text, text


class TestItDegradesHonestly:
    @pytest.mark.parametrize("stores,can_see_money", [
        ([], True),                      # 一家店都没有
        (_stores(100.0), True),          # 只有一家, 无从比较
        (_stores(100.0, 50.0), False),   # 没有金额权限
        (_stores(0.0, 0.0), True),       # 营收全零
    ])
    def test_falls_back_to_the_generic_advice(self, stores, can_see_money):
        """算不出差距时退回原来的建议 —— ⛔ 不许编一个差距出来。"""
        text = _advice_line(stores, can_see_money)
        assert "低于中位的门店拉出来" in text
        assert "相差" not in text, f"算不出差距却说了一个：{text}"


class TestTheAdviceIsNotAConstantAnyMore:
    def test_two_different_data_states_give_different_advice(self):
        """🔴 承重：这正是缺陷本身 —— 不同数据必须给出不同的话。

        ⚠️ 这条断言是这次的**变异对照**固化下来的：把 `_advice_line` 换回
           常量，它立刻红；而只看「有没有建议」的断言不会红。
        """
        flat = _advice_line(_stores(100.0, 99.0), True)
        wide = _advice_line(_stores(100.0, 40.0), True)
        assert flat != wide, "两种差距下建议一模一样 —— 它还是模板话"
