"""给 MOCK_REST 造成本卡缺口 —— **种在 139 的模拟平台上**，这次是真的到顶了。

⚠️⚠️ **这个脚本跑在 139.196.165.140 上，不是 47。** 它改的是模拟平台自己的
      种子代码与 SQLite 库，与本仓其它脚本不共用运行环境。放在本仓只为
      **版本化 + 记账**（正向改了什么、原值是多少，全部写死在下面）。

    scp .../seed_mock_platform_gap.py root@139.196.165.140:/tmp/
    ssh root@139.196.165.140 'cd /www/wwwroot/mock-platform/code && \
        ../venv311/bin/python /tmp/seed_mock_platform_gap.py apply \
        --confirm YES-MOCK-PLATFORM'

## 为什么种在这一层（三次之后终于到顶）

| 版本 | 种在哪 | 被谁抹掉 | 寿命 |
|---|---|---|---|
| v1 | `agg_restaurant_product_cost` | ETL Stage 3d 每轮重算 | 一轮 ETL |
| v2 | `fact_restaurant_recipe_line` | **平台同步** `menu_writer` | 到下一次菜单同步（重启后立刻） |
| **v3** | **139 的 `seed.py` + `data.db`** | —— | **同步会如实把缺口搬过来** |

🔑 `menu_writer` 是个**同步**，同步的职责是让库匹配源。
   **源里有缺口，库里就永远有缺口，跑多少轮都在。**
   这是唯一一个种子「按设计持久」而不是「跟写者赛跑」的位置。

## 139 这一层「谁写这三张表」（照规矩先列全）

    mock_platform/world/seed.py:165  INSERT ingredient  ON CONFLICT DO UPDATE
    mock_platform/world/seed.py:185  INSERT recipe      ON CONFLICT DO UPDATE
    mock_platform/world/seed.py:206  INSERT dish        ON CONFLICT DO UPDATE

全仓只有这三处（已 grep `INSERT/UPDATE/DELETE (recipe|dish|ingredient)`，排除 tests）。
`seed_world` 由 `cli.py` 的 `_generate_forever()` 在**循环之外**调用 ——
即**每次服务启动一次**，不是每分钟。

⚠️ 三条都是 **UPSERT，没有 DELETE** ⇒ 光改 `seed.py` 不够，
   已经在库里的那几行不会自己消失。**必须同时删一次**（与 47 侧 agg 行同一形状）。

## ⛔ 为什么不是「从 `_RECIPES` 里删掉那四道菜」

`seed_world` 对 **`_DISHES` 里的每一道**都调
`_full_cost_cents(name, ...) → food_cost_cents → _RECIPES[dish_name]` ——
删条目会 **KeyError，模拟平台启动即崩**。
⇒ 改成一个**跳过集合**：`_RECIPES` 原样保留（`dish.cost_cents` 不受影响），
  只在写 `recipe` 表时跳过。缺口的形态正是「recipe 表里没有这几道菜」。

📌 也**不删 `dish`**：删了那几道菜连 `dim_restaurant_cost_product` 映射都没有，
   而 POS 那边照样在卖 —— 那是另一种缺陷，不是「缺成本卡」。

## 记账

| 改什么 | 原值 | 新值 |
|---|---|---|
| `seed.py` `_RECIPES["米饭"]` | `[("大米", 130), ("食盐", 1)]` | `[("大米", 13000), ("食盐", 100)]` |
| `seed.py` 新增跳过集合 | （无） | `{"罗氏虾","娃娃菜","凉拌木耳","酸梅汤"}` |
| `data.db` `recipe` 表 | 那四道菜共 25 行 | 删除 |
| `data.db` `recipe` 米饭 2 行 | `大米 130 / 食盐 1` | `13000 / 100` |
"""
from __future__ import annotations

import argparse
import pathlib
import sqlite3
import sys

SEED_PY = pathlib.Path("/www/wwwroot/mock-platform/code/mock_platform/world/seed.py")
DB_PATH = pathlib.Path("/www/wwwroot/mock-platform/data.db")

#: 故意不给配方的菜 —— 模拟「店里在卖但没录成本卡」。
#: 🔑 挑高营收的，照青花椒的真实形状（它营收前 18 里只有 4 道有卡）。
GAP_DISHES = ("罗氏虾", "娃娃菜", "凉拌木耳", "酸梅汤")

#: 单位录错的那张卡。⚠️ 改**份量**不改单价 —— 「一份米饭用 13 公斤米」
#: 正是 kg 当成 g 填的真实长相；改单价会连累用同一种原料的其它菜。
BAD_DISH = "米饭"
BAD_FACTOR = 100
BAD_ORIGINAL = (("大米", 130), ("食盐", 1))

_MARK = "_DISHES_WITHOUT_RECIPE"

_PATCH_BLOCK = '''
#: 🔴 演示用缺口（seed_mock_platform_gap.py 写入，可反向）。
#: 这几道菜**故意不写 recipe 行** —— 模拟「店里在卖但没录成本卡」，
#: 让 47 侧的「先补这 N 道」「覆盖率 65%」这些能力在演示租户上显形。
#: ⛔ 不从 `_RECIPES` 里删条目：`seed_world` 对每道菜都算 `_full_cost_cents`，
#:    删了会 KeyError 让服务启动即崩。
_DISHES_WITHOUT_RECIPE = {"罗氏虾", "娃娃菜", "凉拌木耳", "酸梅汤"}
'''

_OLD_LOOP = ("    for dish_name, lines in _RECIPES.items():\n"
             "        did = dish_ids.get(dish_name)\n")
_NEW_LOOP = ("    for dish_name, lines in _RECIPES.items():\n"
             "        if dish_name in _DISHES_WITHOUT_RECIPE:\n"
             "            continue          # 演示缺口, 见该常量注释\n"
             "        did = dish_ids.get(dish_name)\n")

_OLD_RICE = '    "米饭":       [("大米", 130), ("食盐", 1)],'
_NEW_RICE = '    "米饭":       [("大米", 13000), ("食盐", 100)],   # 演示: 单位录错(kg 当 g)'


def _read() -> bytes:
    return SEED_PY.read_bytes()          # ⛔ 二进制 IO, 不让行尾被改写


def _apply_source() -> None:
    b = _read()
    if _MARK.encode() in b:
        print("  seed.py 已打过补丁, 跳过源码改动（幂等）")
        return
    anchor = b"def seed_supply_chain(conn: sqlite3.Connection) -> None:"
    assert b.count(anchor) == 1, "找不到 seed_supply_chain 定义"
    b = b.replace(anchor, _PATCH_BLOCK.encode() + b"\n\n" + anchor)
    assert b.count(_OLD_LOOP.encode()) == 1, "配方写入循环的形状变了, 停"
    b = b.replace(_OLD_LOOP.encode(), _NEW_LOOP.encode())
    assert b.count(_OLD_RICE.encode()) == 1, "米饭那行的形状变了, 停"
    b = b.replace(_OLD_RICE.encode(), _NEW_RICE.encode())
    SEED_PY.write_bytes(b)
    print(f"  seed.py 已改: 跳过集合 + {BAD_DISH} 份量 ×{BAD_FACTOR}")


def _revert_source() -> None:
    b = _read()
    if _MARK.encode() not in b:
        print("  seed.py 没有补丁, 跳过（幂等）")
        return
    b = b.replace(_PATCH_BLOCK.encode() + b"\n\n", b"")
    b = b.replace(_NEW_LOOP.encode(), _OLD_LOOP.encode())
    b = b.replace(_NEW_RICE.encode(), _OLD_RICE.encode())
    assert _MARK.encode() not in b, "跳过集合没删干净"
    SEED_PY.write_bytes(b)
    print("  seed.py 已还原")


def _db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def _apply_db(conn) -> None:
    # ⛔ 这一步不能省: seed 三条全是 UPSERT-无删除, 已在库里的行不会自己消失。
    ids = {r["name"]: r["id"] for r in conn.execute("SELECT id, name FROM dish")}
    for dish in GAP_DISHES:
        did = ids.get(dish)
        if did is None:
            raise SystemExit(f"⛔ dish 表里没有 {dish!r} —— 名单对不上, 停")
        cur = conn.execute("DELETE FROM recipe WHERE dish_id = ?", (did,))
        print(f"  缺口 {dish}: 删 recipe {cur.rowcount} 行")
    ing = {r["name"]: r["id"] for r in conn.execute("SELECT id, name FROM ingredient")}
    for name, qty in BAD_ORIGINAL:
        conn.execute("UPDATE recipe SET qty_milli = ? WHERE dish_id = ? AND ingredient_id = ?",
                     (qty * BAD_FACTOR, ids[BAD_DISH], ing[name]))
        print(f"  坏卡 {BAD_DISH}/{name}: qty_milli {qty} -> {qty * BAD_FACTOR}")
    conn.commit()


def _revert_db(conn) -> None:
    ids = {r["name"]: r["id"] for r in conn.execute("SELECT id, name FROM dish")}
    ing = {r["name"]: r["id"] for r in conn.execute("SELECT id, name FROM ingredient")}
    for name, qty in BAD_ORIGINAL:
        conn.execute("UPDATE recipe SET qty_milli = ? WHERE dish_id = ? AND ingredient_id = ?",
                     (qty, ids[BAD_DISH], ing[name]))
        print(f"  恢复 {BAD_DISH}/{name}: qty_milli -> {qty}")
    conn.commit()
    print("  ⚠️ 那 4 道菜的 recipe 行由**下次服务启动时的 seed_world** 补回"
          "（去掉跳过集合之后它就会重新 UPSERT）。")


def _status(conn) -> int:
    print(f"seed.py 补丁: {'✅ 在' if _MARK.encode() in _read() else '❌ 不在'}")
    rows = conn.execute(
        "SELECT d.name, COUNT(r.dish_id) AS n, "
        "       COALESCE(SUM(r.qty_milli), 0) AS q "
        "  FROM dish d LEFT JOIN recipe r ON r.dish_id = d.id "
        " GROUP BY d.id ORDER BY d.name").fetchall()
    for r in rows:
        mark = ("  <- 计划缺口" if r["name"] in GAP_DISHES
                else "  <- 计划坏卡" if r["name"] == BAD_DISH else "")
        print(f"  {r['name']:<10} 配方行={r['n']:<3} 用量合计={r['q']}{mark}")
    return 0


def _smoke(conn) -> int:
    """种子还在不在 —— **连这一层的前提一起查**。

    🔴 前提写全了才有用: 上一版只查了「运营库没有 recipes」, 而真正的写者
       (平台同步)从来没进过名单。这次把这一层的三个前提都查:
       ① 139 服务在跑  ② 那几道菜**还在 dish 列表里**  ③ recipe 里没有它们
    """
    import subprocess
    bad = 0

    rc = subprocess.run(["systemctl", "is-active", "--quiet",
                         "cretas-mock-platform.service"]).returncode
    ok = rc == 0
    print(f"① 前提: cretas-mock-platform 服务在跑 {'✅' if ok else '🔴 没跑'}")
    bad += 0 if ok else 1

    names = {r["name"] for r in conn.execute("SELECT name FROM dish")}
    missing = [d for d in GAP_DISHES if d not in names]
    ok = not missing
    print(f"② 前提: 那 4 道菜仍在 dish 列表里 "
          f"{'✅' if ok else '🔴 掉了: ' + ', '.join(missing)}")
    bad += 0 if ok else 1

    still = [r["name"] for r in conn.execute(
        "SELECT d.name FROM dish d JOIN recipe r ON r.dish_id = d.id "
        " WHERE d.name IN (%s) GROUP BY d.id"
        % ",".join("?" * len(GAP_DISHES)), GAP_DISHES)]
    ok = not still
    print(f"③ 缺口: recipe 表里没有它们 "
          f"{'✅' if ok else '🔴 又有了: ' + ', '.join(still)}")
    bad += 0 if ok else 1

    q = conn.execute(
        "SELECT COALESCE(SUM(r.qty_milli),0) AS q FROM dish d "
        "  JOIN recipe r ON r.dish_id = d.id WHERE d.name = ?",
        (BAD_DISH,)).fetchone()["q"]
    want = sum(x * BAD_FACTOR for _n, x in BAD_ORIGINAL)
    ok = q == want
    print(f"④ 坏卡: {BAD_DISH} 用量合计 = {q}（应为 {want}）{'✅' if ok else '🔴'}")
    bad += 0 if ok else 1

    print(f"\n=== 不满足的项: {bad} ===")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("mode", choices=("apply", "revert", "status", "smoke"))
    ap.add_argument("--confirm", default="")
    args = ap.parse_args()
    if args.mode in ("apply", "revert") and args.confirm != "YES-MOCK-PLATFORM":
        print("拒绝: 需要 --confirm YES-MOCK-PLATFORM")
        print("⛔ 本脚本只动 139 上的模拟平台。青花椒 / DEMO_REST 一行不写。")
        return 2
    if not SEED_PY.exists():
        print(f"⛔ 找不到 {SEED_PY} —— 这个脚本要在 139 上跑")
        return 2
    conn = _db()
    try:
        if args.mode == "apply":
            _apply_source()
            _apply_db(conn)
            print("\n⚠️ 下一步: 重启 cretas-mock-platform, 再去 47 扳一次 "
                  "`python -m smartbi.scripts.seed_mock_rest_menu --apply "
                  "--confirm MOCK_REST`")
            return 0
        if args.mode == "revert":
            _revert_source()
            _revert_db(conn)
            return 0
        if args.mode == "smoke":
            return _smoke(conn)
        return _status(conn)
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
