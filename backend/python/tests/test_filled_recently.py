"""层 1 的闸：「你补上去了，X% → Y%」以及**没补的时候它不出现**。

判据来自 `docs/decisions/2026-08-15-成本卡录入设计卡.md`：
  · 录入写运营库 `cretas_db.recipes`
  · 表单里**没有单价字段**（源码级）
  · 入口挂在 T2 缺卡清单上，⛔ 无新菜单
  · 承诺的那个数必须兑现
  · **阴性对照：没补的时候那句话不出现**
"""
from __future__ import annotations

import ast
import io
import pathlib
import tokenize

import pytest

from smartbi.gold.restaurant import filled_recently as fr


def _facts(*rows):
    """(菜名, 营收, 有没有卡) → `_dish_cost_facts` 的形状。"""
    return [{"name": n, "revenue": rev, "qty": 1.0,
             "unit_cost": (1.0 if carded else None)}
            for n, rev, carded in rows]


# ── 判据六：补了要说得出来 ────────────────────────────────────────────────
def test_says_what_was_filled_and_the_two_numbers():
    facts = _facts(("罗氏虾", 500.0, True), ("酸菜鱼", 300.0, True),
                   ("娃娃菜", 200.0, False))
    before = fr.coverage_without(facts, ["罗氏虾"])
    assert before == pytest.approx(300 / 1000)
    line = fr.render(filled=[{"name": "罗氏虾"}],
                     coverage_now=800 / 1000, coverage_before=before)
    assert "你补上去了" in line and "罗氏虾" in line
    assert "30.0%" in line and "80.0%" in line, line


# ── 🔴 判据七：阴性对照 ──────────────────────────────────────────────────
def test_says_nothing_when_nothing_was_filled():
    """🔴 没补的时候**不出现**。

    一句无条件冒出来的「你补上去了」是废话，而且是**会撒谎的废话** ——
    他没补，我们说他补了。
    """
    facts = _facts(("罗氏虾", 500.0, True), ("娃娃菜", 500.0, False))
    assert fr.render(filled=[], coverage_now=0.5,
                     coverage_before=fr.coverage_without(facts, [])) == ""
    # 阳性对照: 有补的时候**出得来** —— 否则上面那条在「永远不出现」时也绿
    assert fr.render(filled=[{"name": "罗氏虾"}], coverage_now=0.5,
                     coverage_before=0.0) != ""


def test_says_nothing_when_the_gain_is_invisible():
    """补了但增量 < 0.1pp 也不说 —— 「从 42.2% 提到 42.2%」读起来像坏了。

    ⚠️ 与 T2 开价用**同一个**门槛。
    """
    assert fr.render(filled=[{"name": "米饭"}],
                     coverage_now=0.4221, coverage_before=0.4220) == ""


def test_mutating_the_empty_guard_turns_the_negative_control_red(monkeypatch):
    """🔴 变异：让「没补」也说话，阴性对照必须红。

    ⚠️ 打的是 `render` 的**行为**（去掉那个空守卫），⛔ 不是改字符串 ——
       改字符串只证明断言在读那个串。
    """
    real = fr.render

    def _always(*, filled, coverage_now, coverage_before):
        return real(filled=filled or [{"name": "编的"}],
                    coverage_now=coverage_now, coverage_before=coverage_before)

    monkeypatch.setattr(fr, "render", _always)
    assert fr.render(filled=[], coverage_now=0.5, coverage_before=0.0) != "", \
        "变异没生效"
    with pytest.raises(AssertionError):
        assert fr.render(filled=[], coverage_now=0.5, coverage_before=0.0) == ""


# ── 判据二：承诺必须兑现 ─────────────────────────────────────────────────
def test_the_gain_matches_what_t2_promised():
    """🔴 T2 说「40.2% → 47.7%」，补完之后这里说的必须是**同一条线**。

    两边都从**同一份 facts** 推：T2 的 `coverage_after = 现覆盖 + 缺口营收/分母`，
    这里的 `coverage_now` = 补完之后的实际覆盖。⇒ 数值必须一致。
    ⛔ 「上升了」不算 —— 我们撤过两句兑现不了的承诺，第三次不能再有。
    """
    from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps

    # 补之前：罗氏虾没卡
    before_facts = _facts(("罗氏虾", 500.0, False), ("酸菜鱼", 300.0, True),
                          ("娃娃菜", 200.0, False))
    denom = sum(f["revenue"] for f in before_facts)
    cov_before = sum(f["revenue"] for f in before_facts
                     if f["unit_cost"] is not None) / denom
    gaps = [{"name": "罗氏虾", "revenue": 500.0},
            {"name": "娃娃菜", "revenue": 200.0}]
    offers = offers_for_cost_gaps(gaps, cov_before, denom, top_n=1)
    assert offers, "T2 没开价 —— 下面的比较会恒真"
    promised = offers[0]["coverage_after"]

    # 补之后：罗氏虾有卡了（同一批销量，⛔ 不换数据）
    after_facts = _facts(("罗氏虾", 500.0, True), ("酸菜鱼", 300.0, True),
                         ("娃娃菜", 200.0, False))
    cov_now = sum(f["revenue"] for f in after_facts
                  if f["unit_cost"] is not None) / denom
    assert cov_now == pytest.approx(promised, abs=1e-9), (
        f"T2 承诺 {promised:.4f}，实际补完是 {cov_now:.4f} —— "
        f"承诺兑现不了。⛔ 不许改那句承诺让它对上")

    # 而「你补上去了」说的两个数就是这两个
    line = fr.render(filled=[{"name": "罗氏虾"}], coverage_now=cov_now,
                     coverage_before=fr.coverage_without(after_facts, ["罗氏虾"]))
    assert f"{cov_before * 100:.1f}%" in line and f"{cov_now * 100:.1f}%" in line, line


# ── 判据一：写运营库 ─────────────────────────────────────────────────────
def test_recent_fills_are_read_from_the_operational_db_not_the_analytics_one():
    """🔴 「他补了什么」只能问**运营库** `recipes`。

    分析库那三层都会被 ETL / 平台同步抹掉（2026-08-14~15 连着三次实测），
    从那里读「他补了什么」会在几十分钟后静默变成「他没补」。

    ⚠️ 用 AST + 剥注释，⛔ 不数字符串 —— 注释里会提到那几张分析库表
       （说明为什么不读它们），不剥的话这条闸会把自己的说明也测进去。
    """
    src = pathlib.Path(fr.__file__).read_text(encoding="utf-8")
    toks = [t for t in tokenize.generate_tokens(io.StringIO(src).readline)
            if t.type != tokenize.COMMENT]
    tree = ast.parse(tokenize.untokenize(toks))
    for node in ast.walk(tree):
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) \
                and isinstance(node.value.value, str):
            node.value.value = ""
    code = ast.unparse(tree)
    assert "FROM recipes" in code, "没读运营库 recipes"
    for analytics in ("fact_restaurant_recipe_line",
                      "agg_restaurant_product_cost",
                      "dim_restaurant_cost_product"):
        assert analytics not in code, (
            f"读了分析库的 {analytics} —— 那一层会被 ETL/平台同步抹掉")


def test_recency_uses_created_at_not_updated_at():
    """⚠️ `updated_at` 会被任何一次改动刷新 —— 三个月前录的菜会被说成刚补的。"""
    assert "created_at" in fr._RECENT_SQL
    assert "updated_at" not in fr._RECENT_SQL


# ── 判据三/四：表单里没有单价字段 ────────────────────────────────────────
def test_the_entry_carries_no_price_and_points_at_the_existing_screen():
    """🔴 入口不带单价；指向**已经存在**的那个屏，⛔ 不新建。"""
    hint = fr.entry_hint("mp_dish_005", "罗氏虾")
    assert hint == {"screen": "RecipeEdit", "productTypeId": "mp_dish_005",
                    "dishName": "罗氏虾"}, hint
    for banned in ("price", "unitPrice", "单价", "cost"):
        assert banned not in str(hint), f"入口里带了 {banned!r}"


def test_the_recipe_form_has_no_price_field_at_all():
    """🔴 判据四：**源码级**确认单价字段不存在（不是「界面上看不见」）。

    ⚠️ 查的是 RN 那个表单屏本身，⛔ 不是查我们这侧的入口 ——
       入口不带单价不等于表单里没有单价输入框。
    """
    # ⚠️ `parents[3]` 才是仓根: tests → python → backend → <repo>。
    #    第一版写了 `parents[2]`, 于是 `form.exists()` 为假 → **skip** ——
    #    判据四当场变成「没验」而测试是绿的。⛔ 找不到就 fail, 不 skip。
    root = pathlib.Path(__file__).resolve().parents[3]
    form = (root / "frontend" / "CretasFoodTrace" / "src" / "screens"
            / "restaurant" / "recipes" / "RecipeEditScreen.tsx")
    assert form.exists(), (
        f"找不到表单屏 {form} —— ⛔ 不许 skip: skip 会让「单价字段不存在」"
        f"这条判据变成没验过, 而测试是绿的")
    text = form.read_text(encoding="utf-8", errors="ignore")
    for banned in ("price", "Price", "单价"):
        assert banned not in text, (
            f"表单里出现了 {banned!r} —— 单价必须**不存在**: "
            f"让他填单价会把「配方」和「采购价」混成一件事, "
            f"而后者本来就有来源、且随批次变")
    # 阳性对照: 用量那个字段**在** —— 否则上面那条在「文件是空的」时也绿
    assert "standardQuantity" in text, "表单里没有用量字段 —— 这条闸在测一个空文件"
