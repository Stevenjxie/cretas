"""喂给 LLM 的门店名单被截断时，必须**说清是我们截的**，⛔ 不能让它说成「你没提供」。

## 缺陷（📏 MOCK_REST prod 2026-08-18，2 轮同 md5=93734559）

老板问「我要不要关掉最差的那家店」，产品答了 948 字，其中：

```
「你给的摘要里只有营业额排名前5的门店，没有后5名的数据，所以没法判断哪家垫底」
「**需要你提供后5家店的营业额、订单数、就餐人数数据**，才能算出哪家店真的垫底」
```

而那 10 家店的数据**全在库里** —— 同一天「哪家店卖得最好」列出了最高与最低，
损耗答案里有完整的 10 行门店表。

▎LLM 说的「我只看到前 5 名」是**忠实的**。
▎错在我们只给了它 5 家，然后它把「摘要被截断」翻译成了「用户没提供数据」。
▎⇒ 老板照着去「提供数据」会一无所获 —— 反目标里最重的那一条。

⚠️ 而且它是 `kind=answer`，**任何「答上率」都把它数成成功**。

## 📏 实测（阳性对照真能红：n50=10 > n5=5）

```
最近30天有交易门店                        = 10
finance_summary(top_n_stores=5)  top_stores = 5 条
finance_summary(top_n_stores=50) top_stores = 10 条
store_count 字段                          = 10   ← 与 top_stores 同一个 payload, 不受 LIMIT 影响
```

⇒ 「共 10 家」这个事实**就在 LLM 手上**，我们却没让那段文本说清楚。

## 🔴 截断有**两处**，改一处等于没改（硬约束 8）

```
① synthesis_engine.py:1827 / 2012   top_n_stores=5        ← 查库时截
② factbook.py:_render_finance       stores[:5]            ← **渲染给 LLM 时又截一次**
```

②才是真正喂给 LLM 的那一层。而且它渲染的那段文本里：

```
- 营业日 29 天，门店 10 家          ← 说了共 10 家
- Top 门店（按营业额）：            ← 只列 5 条, 没说这是前几名
  1. …  2. …  3. …  4. …  5. …
```

**LLM 的推断完全正确** —— 是我们给的信息自相矛盾。

⚠️ 数 `top_n_stores` **数不到** `stores[:5]`，因为它们名字不同。
⇒ 数「这个结构涉及的所有地方」不能只数一个名字，要**从 LLM 收到的那段文本反推**。
"""
from __future__ import annotations

from typing import Any, Dict, List

from smartbi.agent.factbook import FactBook


def _fin(store_count: int, rendered_available: int) -> Dict[str, Any]:
    """构造 finance payload。

    ⚠️ 形状取自真实 `finance_summary` 返回（📏 键名实测过）：
    `store_count` 是 `COUNT(DISTINCT store_id)`，**不受 top_n LIMIT 影响**；
    `top_stores` 是被 LIMIT 截过的名单。两者可以不一致 —— 那正是本缺陷的形状。
    """
    return {
        "start_date": "2026-07-20", "end_date": "2026-08-18",
        "total_revenue": 20762058.81, "bill_count": 57792,
        "customer_count": 0, "avg_bill_value": 359.25,
        "store_count": store_count, "day_count": 29,
        "top_stores": [
            {"store_id": f"S{i}", "store_name": f"模拟·门店{i}",
             "revenue": 2100000.0 - i * 1000, "bill_count": 5780 - i}
            for i in range(1, rendered_available + 1)
        ],
    }


def _finance_lines(fin: Dict[str, Any]) -> List[str]:
    lines: List[str] = []
    FactBook(finance=fin)._render_finance(lines)
    return lines


def _text(fin: Dict[str, Any]) -> str:
    return "\n".join(_finance_lines(fin))


# ── 承重 ────────────────────────────────────────────────────────────────

def test_ten_stores_are_all_rendered():
    """📏 prod 那个租户的形状：10 家店，⛔ 不该只给 LLM 5 家。"""
    text = _text(_fin(store_count=10, rendered_available=10))
    for i in range(1, 11):
        assert f"模拟·门店{i}" in text, (
            f"第 {i} 家没进 LLM 的摘要 —— 它会据此说「你没提供后几名的数据」\n{text}"
        )


def test_truncation_says_we_truncated_it_not_that_the_user_owes_us_data():
    """超过上限时**明说是我们截的，数据在系统里**。

    ⛔ 这一句是整条修复的核心：LLM 可以照抄它，而照抄的是**真话**。
       ⛔ 不指望 LLM 自己从「门店 30 家」+「列了 20 条」推出正确措辞 ——
       📏 实测它推出来的是「需要你提供后 5 家店的数据」。
    """
    text = _text(_fin(store_count=30, rendered_available=20))
    assert "共 30 家" in text, text
    assert "在系统里" in text, (
        "没有明说「其余门店的数据在系统里」—— LLM 会把截断说成用户没提供\n" + text
    )


def test_truncated_roster_is_capped_at_the_single_declared_constant():
    from smartbi.agent.factbook import LLM_STORE_ROSTER_CAP

    text = _text(_fin(store_count=30, rendered_available=30))
    rendered = sum(1 for i in range(1, 31) if f"模拟·门店{i}：" in text)
    assert rendered == LLM_STORE_ROSTER_CAP, (
        f"渲染了 {rendered} 家, 上限常量是 {LLM_STORE_ROSTER_CAP}"
    )


# ── 阴性对照：⛔ 不许变成一条无条件的噪音 ────────────────────────────────

def test_no_truncation_note_when_nothing_was_truncated():
    """没截断时**不许**出现那句话 —— 否则它就是一条会误导的提示。"""
    text = _text(_fin(store_count=10, rendered_available=10))
    assert "在系统里" not in text, (
        "没截断却说了「其余门店的数据在系统里」—— 一条无中生有的提示\n" + text
    )
    assert "共 10 家" not in text or "Top 门店" in text


def test_store_count_line_is_unchanged():
    """阴性对照：原有那行「营业日 N 天，门店 M 家」逐字不变。"""
    text = _text(_fin(store_count=10, rendered_available=10))
    assert "- 营业日 29 天，门店 10 家" in text, text


def test_empty_roster_renders_nothing_extra():
    """阴性对照：一家店都没有时，⛔ 不许凭空冒出截断说明。"""
    text = _text(_fin(store_count=0, rendered_available=0))
    assert "Top 门店" not in text
    assert "在系统里" not in text


# ── 同源：截断上限只此一处 ───────────────────────────────────────────────

def test_the_cap_has_exactly_one_home():
    """⛔ 查库那一处和渲染那一处必须读**同一个**常量。

    📏 改之前它们是两个各写死的 5：`top_n_stores=5`（synthesis_engine）
    与 `stores[:5]`（factbook）。名字不同 ⇒ 数其中一个数不到另一个 ⇒
    只改一处会「实测没变化」而让人怀疑方向错了。
    """
    import ast
    import inspect

    from smartbi.agent import synthesis_engine as se
    from smartbi.agent.factbook import LLM_STORE_ROSTER_CAP

    assert isinstance(LLM_STORE_ROSTER_CAP, int) and LLM_STORE_ROSTER_CAP >= 20, (
        "上限要明写且足够覆盖常见连锁规模"
    )

    src = inspect.getsource(se)
    tree = ast.parse(src)
    literal_caps = [
        node.value
        for call in ast.walk(tree)
        if isinstance(call, ast.Call)
        for kw in call.keywords
        if kw.arg == "top_n_stores"
        for node in [kw.value]
        if isinstance(node, ast.Constant)
    ]
    assert literal_caps == [], (
        f"synthesis_engine 里还有写死的 top_n_stores={literal_caps} —— "
        f"它与 factbook 的渲染上限会漂, 而漂的表现是「改了一处没效果」"
    )
