"""T3 prompt 的**可缓存前缀**不许掉下来。

## 为什么要有这道闸

DeepSeek 前缀缓存命中 vs 未命中实测差 **50 倍**，而 deepseek 现在真的在链上、
2026-09-13 之后要扛七个槽 ⇒ 这是**成本项**不是优化项。

实测（2026-08-16）当前形态**已经达标**：变的东西（用户那句话）排在
9659 字符的第 9645 位，共同前缀 **99.9%**。

▎所以这道闸守的不是「去优化它」，是**「别把已经对的事做坏」**。

⛔ 最容易做坏的方式：往 prompt **前部**插入随请求变化的内容
   （租户名 / 今天日期 / 门店列表上移）—— 那会一次性打碎全部前缀。

## 阳性对照（硬约束 9）

「共同前缀很长」是个正向读数，但它可能因为**两个 prompt 根本一样**而虚高。
⇒ 必须同时断言两个 prompt **确实不同**，否则这条断言在
「prompt 构造器坏掉、恒返回同一串」时也会绿。
"""
import pytest

import smartbi.gold.restaurant.restaurant_intent as ri

#: 低于它就说明有变化的内容被挪到了前面。
#: ⚠️ 定在 95% 而不是实测的 99.9% —— 留出正常演进的余量，
#:    ⛔ 但不留到「插一段租户名进去也照样绿」的程度。
MIN_PREFIX_RATIO = 0.95

#: ⚠️ 真实形状是 `{q, a_summary}`（`ChatSessionService` 存的），
#:    ⛔ 不是 `{question, answer}` —— 后者会让 history 只插进表头、内容为空，
#:    而两份不同 history 产出**完全相同**的 prompt（我实测踩过，
#:    一度以为抓到了「history 没进 prompt」的大 bug）。
_H1 = [{"q": "昨天营收多少", "a_summary": "昨天营收 1.2 万"}]
_H2 = [{"q": "上周毛利多少", "a_summary": "上周毛利 2.3 万"}]


def _common_prefix(a: str, b: str) -> int:
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def _build(query="今天生意怎么样", history=None, stores=()):
    return ri._build_t3_prompt(query, None, history, stores, None)


#: ⚠️ 参数化**只传 label**, ⛔ 不传 prompt 字符串 ——
#:    pytest 会拿参数当测试 ID, 而那是两段 9KB 的 prompt(实测输出 129KB, 不可读)。
_CASES = {
    "不同问句": lambda: (_build(), _build("上个月毛利多少")),
    "不同history": lambda: (_build(history=_H1), _build(history=_H2)),
    "无史vs有史": lambda: (_build(), _build(history=_H1)),
    "不同门店列表": lambda: (_build(), _build(stores=("A店", "B店"))),
}


@pytest.mark.parametrize("label", list(_CASES))
def test_prefix_stays_cacheable(label):
    a, b = _CASES[label]()
    # 阳性对照先看：两个 prompt **确实不同**。
    # ⛔ 少了这一条，构造器坏成「恒返回同一串」时这道闸照样全绿。
    assert a != b, f"[{label}] 两个 prompt 完全一样 ⇒ 下面的比率无意义"

    n = _common_prefix(a, b)
    ratio = n / max(len(a), len(b))
    assert ratio >= MIN_PREFIX_RATIO, (
        f"[{label}] 可缓存前缀掉到 {ratio:.1%}（{n}/{max(len(a), len(b))}）。\n"
        f"⇒ 多半是有随请求变化的内容被挪到了 prompt 前部。\n"
        f"   分岔处: {a[n:n + 60]!r}\n"
        f"   对照:   {b[n:n + 60]!r}\n"
        f"⚠️ DeepSeek 前缀缓存命中/未命中差 50 倍，而 9-13 之后 deepseek 扛七个槽。"
    )


def test_the_variable_part_is_the_users_question_and_it_is_last():
    """变的那一段必须是**用户那句话**，且在最后。

    ⚠️ 这条比上面的比率更具体：比率高也可能是「变的是别的东西、只是它也很短」。
    """
    a, b = _build("今天生意怎么样"), _build("上个月毛利多少")
    n = _common_prefix(a, b)
    assert "用户问题" in a[max(0, n - 40):n], (
        f"分岔点不在「用户问题:」之后 —— 变的不是问句本身:\n  {a[max(0, n - 60):n]!r}")
    assert "今天生意怎么样" in a[n:n + 40]
    assert "上个月毛利多少" in b[n:n + 40]


def test_prompt_is_identical_on_two_different_days(monkeypatch):
    """换一天再构造一次，prompt 必须**逐字相同**。

    🔴 这条是补上来的 —— 上面那条「共同前缀 ≥95%」**抓不住日期**：
    同一次运行里两个 prompt 含的是**同一个今天**，比率照样 99.9%。
    实测变异 Z1（往 prompt 前部插 `今天是 {date.today()}`）在只有比率断言时
    **一条都不红**，而它会让前缀**每天**失效一次。

    ⚠️ 「跨请求稳定」和「跨天稳定」是两个性质，比率只量得到前一个。

    ⛔ 也不能改成「前缀里不许出现日期串」—— 实测正常 prompt 里就有两个
       **写死的示例日期**（`2026-06-03` / `2026-06-18`），那是常量，不打碎缓存。
       按那个写法会误伤。

    🔴 冻钟要打在**真 `datetime` 模块**上，⛔ 不是只打 `ri` 的模块级名字。
       第一版只打了 `ri.datetime` / `ri.date`，而变异 Z1 用的是**函数内**
       `import datetime as _dt` —— 那绕过模块级名字，**闸一条都不红**。
       我当时把它当成「已知盲区」写进了 docstring；实测发现它**关得掉**：
       `import datetime` 拿到的是**同一个模块对象**，改它的属性两种写法都吃得到。
       ⚠️ 「登记盲区」不该成为不去关它的理由 —— 先问一句「这个真的关不掉吗」。
    """
    import datetime as _real

    class _FrozenDate(_real.date):
        _fake = _real.date(2026, 1, 2)

        @classmethod
        def today(cls):
            return cls._fake

    class _FrozenDateTime(_real.datetime):
        @classmethod
        def now(cls, tz=None):
            return _real.datetime.combine(_FrozenDate._fake, _real.time(12, 0), tz)

        @classmethod
        def today(cls):
            return _real.datetime.combine(_FrozenDate._fake, _real.time(12, 0))

    # 打在**模块对象**上 ⇒ 函数内 `import datetime as _dt` 也吃得到
    monkeypatch.setattr(_real, "date", _FrozenDate)
    monkeypatch.setattr(_real, "datetime", _FrozenDateTime)
    # 模块自己 `from datetime import datetime` 早已绑定，另外再打一次
    monkeypatch.setattr(ri, "datetime", _FrozenDateTime, raising=False)
    monkeypatch.setattr(ri, "date", _FrozenDate, raising=False)

    # 阳性对照: 冻结确实生效, **且函数内 import 那条路也生效**
    # ⛔ 少了第二句, 上面那次「关掉盲区」就没被验证过
    assert ri.datetime.now().date() == _real.date(2026, 1, 2), "时钟没冻住"
    import datetime as _via_local_import
    assert _via_local_import.date.today() == _real.date(2026, 1, 2), (
        "函数内 import 的那条路没被冻住 —— 盲区还在")
    day_a = _build()

    _FrozenDate._fake = _real.date(2026, 7, 30)
    assert ri.datetime.now().date() == _real.date(2026, 7, 30), "第二天没推动"
    day_b = _build()

    assert day_a == day_b, (
        "换一天之后 prompt 变了 ⇒ 前缀缓存**每天**失效一次。"
        f"  分岔处: {day_a[_common_prefix(day_a, day_b):][:80]!r}"
    )


def test_system_message_carries_no_per_request_content():
    """system 消息必须是**常量** —— 它排在最前面，一变就全废。

    ⛔ 尤其不许放「今天是 X」这种每天变的东西。
    """
    import datetime
    import inspect

    src = inspect.getsource(ri)
    today = datetime.date.today()
    # 只看 system 消息那一段的字面量
    idx = src.find('"role": "system"')
    assert idx > 0, "找不到 system 消息 —— 闸的锚点失效了(阳性对照)"
    chunk = src[idx:idx + 400]
    for bad in (str(today.year) + "-", "今天是", "date.today()", "datetime.now()"):
        assert bad not in chunk, f"system 消息附近出现随请求/日期变化的内容: {bad!r}"
