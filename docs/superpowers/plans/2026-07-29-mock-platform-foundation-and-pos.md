# 餐饮外部平台模拟器：底座 + POS 线 实施计划（计划 A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 139 上跑起一个与本系统物理隔离的餐饮平台模拟器，按客如云风格暴露 POS 订单 API，并在 47 侧打通增量拉取 + 回调，让 `MOCK_REST` 租户的看板分钟级持续变化。

**Architecture:** 模拟端内部只有一份「世界模型」（SQLite），生成器按客流曲线持续写入订单，平台 router 只读这份事实并按各平台风格包装暴露。47 侧新增 connector 框架（游标/幂等/退避/失败隔离）+ 平台 adapter，写进现有 Silver 表，随 `cretas-python` 常驻循环运行。

**Tech Stack:** Python 3.11 / FastAPI / SQLite（模拟端）；asyncpg / PostgreSQL（我们侧）；systemd + nginx（139 部署）

**Spec:** `docs/superpowers/specs/2026-07-29-restaurant-mock-platform-api-design.md`

## Global Constraints

- Python 运行时必须是 **python3.11**（`deploy-smartbi-python.sh` 硬校验 `CRETAS_PYTHON_RUNTIME_BIN`，非 3.11 直接 exit）。
- **隔离铁律**：`mock-platform/` 下不得出现 `smartbi` / `psycopg` / `asyncpg` / `smartbi_prod_db` / `cretas_prod_db`。验收命令零命中。
- **禁降级**：拿不到数据一律明确报错，绝不返回假数据、不写 0、不静默跳过。
- 我们侧 API 统一响应格式 `{ success, data, message }`；模拟端**不遵守**这个格式，用各平台自己的包装。
- smartbi schema 变更必须写 `backend/python/smartbi/database/migrations/V<YYYYMMDD>_<NN>__*.sql`，由 `deploy-smartbi-python.sh` Step 3.5 自动 apply。**禁手动 psql DDL。**
- 本计划占用 migration 版本号 **`V20261101_01`**（现有最大为 `V20261031_02`）。
- prod 只从 `main` 部署；任何部署前 `git checkout main && git pull`。
- 租户固定为 **`MOCK_REST`**，绝不写 `DEMO_REST`。
- 模拟端只部署到 **139**（`139.196.165.140`）；connector 只部署到 **47**（`47.100.235.168`）。

## File Structure

**模拟端**（仓库根级新目录 `mock-platform/`，与 `backend/` 平级，物理隔离）：

| 文件 | 职责 |
|---|---|
| `mock-platform/mock_platform/config.py` | 环境变量读取：SQLite 路径、平台密钥、门店数、每店日单量 |
| `mock-platform/mock_platform/db.py` | SQLite 连接管理 + schema 初始化 |
| `mock-platform/mock_platform/world/schema.sql` | 世界模型建表 DDL |
| `mock-platform/mock_platform/world/seed.py` | 10 门店 + 菜品种子 |
| `mock-platform/mock_platform/world/curve.py` | 客流曲线：分钟级订单配额 |
| `mock-platform/mock_platform/world/generator.py` | 订单生成（常驻循环 + 回填） |
| `mock-platform/mock_platform/world/derive.py` | 订单 → 各平台切面的派生 |
| `mock-platform/mock_platform/api/_auth.py` | 三家鉴权算法（本计划只用客如云） |
| `mock-platform/mock_platform/api/_paging.py` | 游标分页 |
| `mock-platform/mock_platform/api/keruyun.py` | 客如云风格 router |
| `mock-platform/mock_platform/api/app.py` | FastAPI app 装配 |
| `mock-platform/mock_platform/callback.py` | 回调推送客户端 |
| `mock-platform/mock_platform/cli.py` | `serve` / `backfill` 入口 |
| `mock-platform/tests/` | 模拟端测试 |

**我们侧**（`backend/python/`）：

| 文件 | 职责 |
|---|---|
| `smartbi/ingestion/platforms/models.py` | 归一化订单结构（各 adapter 的公共出参） |
| `smartbi/ingestion/platforms/cursor_store.py` | 游标读写 |
| `smartbi/ingestion/platforms/framework.py` | 拉取框架：游标推进/幂等/退避/失败隔离 |
| `smartbi/ingestion/platforms/keruyun.py` | 客如云 adapter：签名 + 字段映射 |
| `smartbi/ingestion/platforms/writer.py` | 归一化订单 → Silver 表 |
| `smartbi/api/platform_callback.py` | 回调端点（三层校验） |
| `smartbi/database/migrations/V20261101_01__platform_sync_and_mock_tenant.sql` | 游标表 + `MOCK_REST` 门店维度 |

---

### Task 1: 模拟端骨架 + 隔离验收

**Files:**
- Create: `mock-platform/mock_platform/__init__.py`
- Create: `mock-platform/mock_platform/config.py`
- Create: `mock-platform/requirements.txt`
- Create: `mock-platform/README.md`
- Test: `mock-platform/tests/test_isolation.py`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `mock_platform.config.Settings` — 带属性 `db_path: str`、`keruyun_app_key: str`、`keruyun_app_secret: str`、`callback_url: str`、`callback_secret: str`、`store_count: int`、`orders_per_store_per_day: int`；模块级函数 `get_settings() -> Settings`

- [ ] **Step 1: 写隔离验收测试（先失败）**

```python
# mock-platform/tests/test_isolation.py
"""隔离铁律：模拟端必须是一个外部系统，不是本系统的模块。

一旦这条挂掉，模拟器就退化成「我们自己写给自己看的假数据」，失去全部验证价值。
"""
import pathlib
import re

FORBIDDEN = re.compile(r"\b(smartbi|psycopg|asyncpg|smartbi_prod_db|cretas_prod_db)\b")
PKG_ROOT = pathlib.Path(__file__).resolve().parent.parent / "mock_platform"


def test_模拟端不得引用本系统任何东西():
    offenders = []
    for path in PKG_ROOT.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        for lineno, line in enumerate(text.splitlines(), 1):
            if FORBIDDEN.search(line):
                offenders.append(f"{path.name}:{lineno}: {line.strip()}")
    assert offenders == [], "模拟端泄漏了本系统依赖:\n" + "\n".join(offenders)


def test_模拟端不得声明数据库驱动依赖():
    req = (PKG_ROOT.parent / "requirements.txt").read_text(encoding="utf-8")
    for banned in ("psycopg", "asyncpg", "sqlalchemy"):
        assert banned not in req.lower(), f"requirements.txt 不该有 {banned}"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd mock-platform && python -m pytest tests/test_isolation.py -v`
Expected: FAIL，`mock_platform` 目录不存在（`rglob` 得到空集时第一个测试会通过，但第二个测试读 `requirements.txt` 会 `FileNotFoundError`）

- [ ] **Step 3: 建包与配置**

```python
# mock-platform/mock_platform/__init__.py
"""餐饮外部平台模拟器。与 Cretas 系统物理隔离，唯一出口是 HTTP。"""
```

```python
# mock-platform/mock_platform/config.py
"""配置全部来自环境变量。没有默认密钥——缺了就报错，不静默用弱值。"""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache


class ConfigError(RuntimeError):
    """必需配置缺失。启动期直接失败，不带着空密钥跑。"""


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ConfigError(f"缺少必需环境变量 {name}")
    return value


@dataclass(frozen=True)
class Settings:
    db_path: str
    keruyun_app_key: str
    keruyun_app_secret: str
    callback_url: str
    callback_secret: str
    store_count: int
    orders_per_store_per_day: int


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings(
        db_path=os.getenv("MOCK_DB_PATH", "/www/wwwroot/mock-platform/data.db"),
        keruyun_app_key=_required("MOCK_KERUYUN_APP_KEY"),
        keruyun_app_secret=_required("MOCK_KERUYUN_APP_SECRET"),
        callback_url=os.getenv("MOCK_CALLBACK_URL", ""),
        callback_secret=_required("MOCK_CALLBACK_SECRET"),
        store_count=int(os.getenv("MOCK_STORE_COUNT", "10")),
        orders_per_store_per_day=int(os.getenv("MOCK_ORDERS_PER_STORE", "200")),
    )
```

```text
# mock-platform/requirements.txt
fastapi>=0.110
uvicorn[standard]>=0.27
httpx>=0.27
pytest>=8.0
pytest-asyncio>=0.23
```

```markdown
<!-- mock-platform/README.md -->
# 餐饮外部平台模拟器

模拟二维火/客如云/美团/抖音风格的餐饮开放平台，部署在 139，与 Cretas 系统物理隔离。

- 存储：SQLite（不碰 PostgreSQL）
- 唯一出口：HTTP
- 设计：`docs/superpowers/specs/2026-07-29-restaurant-mock-platform-api-design.md`

## 本地跑

    export MOCK_KERUYUN_APP_KEY=... MOCK_KERUYUN_APP_SECRET=... MOCK_CALLBACK_SECRET=...
    python -m mock_platform.cli serve
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd mock-platform && python -m pytest tests/test_isolation.py -v`
Expected: 2 passed

- [ ] **Step 5: 补配置的单测并跑通**

```python
# mock-platform/tests/test_config.py
import pytest

from mock_platform.config import ConfigError, get_settings


def test_缺必需环境变量直接报错不静默降级(monkeypatch):
    get_settings.cache_clear()
    monkeypatch.delenv("MOCK_KERUYUN_APP_KEY", raising=False)
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", "s")
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "c")
    with pytest.raises(ConfigError, match="MOCK_KERUYUN_APP_KEY"):
        get_settings()
    get_settings.cache_clear()
```

Run: `cd mock-platform && python -m pytest tests/ -v`
Expected: 3 passed

- [ ] **Step 6: Commit**

```bash
git add mock-platform/
git commit -m "feat(mock-platform): 模拟端骨架 + 隔离验收测试" -- mock-platform/
```

---

### Task 2: 世界模型 schema + 10 门店种子

**Files:**
- Create: `mock-platform/mock_platform/world/__init__.py`
- Create: `mock-platform/mock_platform/world/schema.sql`
- Create: `mock-platform/mock_platform/db.py`
- Create: `mock-platform/mock_platform/world/seed.py`
- Test: `mock-platform/tests/test_seed.py`

**Interfaces:**
- Consumes: `mock_platform.config.get_settings`
- Produces:
  - `mock_platform.db.connect(db_path: str) -> sqlite3.Connection`（已建表、`row_factory=sqlite3.Row`）
  - `mock_platform.world.seed.seed_world(conn, store_count: int) -> None`（幂等）
  - 表 `store(id, code, name, format, traffic_factor)`、`dish(id, name, category, price_cents, cost_cents, groupon_eligible)`

- [ ] **Step 1: 写失败测试**

```python
# mock-platform/tests/test_seed.py
from mock_platform.db import connect
from mock_platform.world.seed import seed_world


def test_种子建出10家店且业态齐全(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    rows = conn.execute("SELECT format, COUNT(*) c FROM store GROUP BY format").fetchall()
    formats = {r["format"]: r["c"] for r in rows}
    assert sum(formats.values()) == 10
    assert set(formats) == {"flagship", "community", "mall"}


def test_种子幂等重复跑不翻倍(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    seed_world(conn, store_count=10)
    assert conn.execute("SELECT COUNT(*) c FROM store").fetchone()["c"] == 10


def test_菜品有成本且成本低于售价(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    bad = conn.execute(
        "SELECT name FROM dish WHERE cost_cents <= 0 OR cost_cents >= price_cents"
    ).fetchall()
    assert bad == [], f"成本非法的菜品: {[r['name'] for r in bad]}"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd mock-platform && python -m pytest tests/test_seed.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'mock_platform.db'`

- [ ] **Step 3: 写 schema 与连接**

```sql
-- mock-platform/mock_platform/world/schema.sql
CREATE TABLE IF NOT EXISTS store (
    id             INTEGER PRIMARY KEY,
    code           TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    format         TEXT NOT NULL,          -- flagship / community / mall
    traffic_factor REAL NOT NULL           -- 客流基准系数，1.0 = 平均
);

CREATE TABLE IF NOT EXISTS dish (
    id               INTEGER PRIMARY KEY,
    name             TEXT NOT NULL UNIQUE,
    category         TEXT NOT NULL,
    price_cents      INTEGER NOT NULL,
    cost_cents       INTEGER NOT NULL,
    groupon_eligible INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS "order" (
    id             INTEGER PRIMARY KEY,
    order_no       TEXT NOT NULL UNIQUE,
    store_id       INTEGER NOT NULL REFERENCES store(id),
    channel        TEXT NOT NULL,          -- dine_in / takeaway / groupon
    placed_at      TEXT NOT NULL,          -- ISO8601 UTC
    biz_date       TEXT NOT NULL,          -- YYYY-MM-DD 营业日
    gross_cents    INTEGER NOT NULL,
    discount_cents INTEGER NOT NULL DEFAULT 0,
    net_cents      INTEGER NOT NULL,
    guest_count    INTEGER NOT NULL DEFAULT 1,
    seq            INTEGER NOT NULL        -- 全局单调游标
);

CREATE TABLE IF NOT EXISTS order_item (
    id          INTEGER PRIMARY KEY,
    order_id    INTEGER NOT NULL REFERENCES "order"(id),
    dish_id     INTEGER NOT NULL REFERENCES dish(id),
    qty         INTEGER NOT NULL,
    price_cents INTEGER NOT NULL,
    amount_cents INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS payment (
    id           INTEGER PRIMARY KEY,
    order_id     INTEGER NOT NULL REFERENCES "order"(id),
    method       TEXT NOT NULL,           -- cash / wechat / alipay / platform
    amount_cents INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_order_seq ON "order"(seq);
CREATE INDEX IF NOT EXISTS idx_order_store_date ON "order"(store_id, biz_date);
```

```python
# mock-platform/mock_platform/db.py
"""SQLite 连接。模拟端的全部持久化只有这一个文件，不连任何外部数据库。"""
from __future__ import annotations

import pathlib
import sqlite3

_SCHEMA = pathlib.Path(__file__).parent / "world" / "schema.sql"


def connect(db_path: str) -> sqlite3.Connection:
    pathlib.Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(_SCHEMA.read_text(encoding="utf-8"))
    return conn
```

- [ ] **Step 4: 写种子**

```python
# mock-platform/mock_platform/world/__init__.py
```

```python
# mock-platform/mock_platform/world/seed.py
"""10 家门店 + 菜品种子。幂等：重复调用不翻倍。"""
from __future__ import annotations

import sqlite3

# (code, name, format, traffic_factor)
_STORES = [
    ("MK01", "模拟·打浦桥日月光店", "mall", 1.60),
    ("MK02", "模拟·徐汇美罗城店", "mall", 1.35),
    ("MK03", "模拟·静安嘉里中心店", "flagship", 1.50),
    ("MK04", "模拟·陆家嘴正大店", "flagship", 1.40),
    ("MK05", "模拟·长宁龙之梦店", "mall", 1.10),
    ("MK06", "模拟·杨浦五角场店", "mall", 1.00),
    ("MK07", "模拟·普陀真如社区店", "community", 0.72),
    ("MK08", "模拟·闵行莘庄社区店", "community", 0.68),
    ("MK09", "模拟·宝山大场社区店", "community", 0.60),
    ("MK10", "模拟·浦东金桥社区店", "community", 0.65),
]

# (name, category, price_cents, cost_cents, groupon_eligible)
_DISHES = [
    ("藤椒鸡", "热菜", 5800, 2100, 1),
    ("水煮牛肉", "热菜", 6800, 2900, 1),
    ("干锅花菜", "热菜", 3800, 1200, 1),
    ("鲈鱼", "水产", 8800, 4200, 1),
    ("罗氏虾", "水产", 12800, 6800, 0),
    ("娃娃菜", "素菜", 2200, 600, 0),
    ("米饭", "主食", 300, 80, 0),
    ("酸梅汤", "饮品", 1200, 300, 0),
    ("红糖糍粑", "甜品", 2600, 700, 1),
    ("凉拌木耳", "凉菜", 1800, 500, 0),
]


def seed_world(conn: sqlite3.Connection, store_count: int) -> None:
    if store_count > len(_STORES):
        raise ValueError(f"最多支持 {len(_STORES)} 家门店，请求了 {store_count}")
    for code, name, fmt, factor in _STORES[:store_count]:
        conn.execute(
            "INSERT INTO store(code, name, format, traffic_factor) VALUES (?,?,?,?) "
            "ON CONFLICT(code) DO UPDATE SET name=excluded.name, "
            "format=excluded.format, traffic_factor=excluded.traffic_factor",
            (code, name, fmt, factor),
        )
    for name, cat, price, cost, groupon in _DISHES:
        conn.execute(
            "INSERT INTO dish(name, category, price_cents, cost_cents, groupon_eligible) "
            "VALUES (?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET "
            "category=excluded.category, price_cents=excluded.price_cents, "
            "cost_cents=excluded.cost_cents, groupon_eligible=excluded.groupon_eligible",
            (name, cat, price, cost, groupon),
        )
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd mock-platform && python -m pytest tests/ -v`
Expected: 6 passed

- [ ] **Step 6: Commit**

```bash
git add mock-platform/
git commit -m "feat(mock-platform): 世界模型 schema + 10 门店种子" -- mock-platform/
```

---

### Task 3: 客流曲线 + 订单生成器（含回填）

**Files:**
- Create: `mock-platform/mock_platform/world/curve.py`
- Create: `mock-platform/mock_platform/world/generator.py`
- Test: `mock-platform/tests/test_curve.py`
- Test: `mock-platform/tests/test_generator.py`

**Interfaces:**
- Consumes: `mock_platform.db.connect`、`mock_platform.world.seed.seed_world`
- Produces:
  - `mock_platform.world.curve.minute_weight(store_format: str, minute_of_day: int) -> float`
  - `mock_platform.world.curve.daily_minute_quota(store_format: str, daily_orders: int) -> list[int]`（长度 1440，求和 == `daily_orders`）
  - `mock_platform.world.generator.generate_orders(conn, *, store_id: int, biz_date: str, minute_of_day: int, count: int, rng: random.Random) -> int`（返回新建订单数）
  - `mock_platform.world.generator.backfill(conn, *, days: int, orders_per_store: int, today: datetime.date, rng: random.Random) -> int`

- [ ] **Step 1: 写曲线的失败测试**

```python
# mock-platform/tests/test_curve.py
from mock_platform.world.curve import daily_minute_quota, minute_weight


def test_午市晚市是双峰且凌晨无客流():
    assert minute_weight("mall", 3 * 60) == 0.0            # 凌晨 3 点
    lunch = minute_weight("mall", 12 * 60 + 30)
    dinner = minute_weight("mall", 19 * 60)
    afternoon = minute_weight("mall", 15 * 60 + 30)
    assert lunch > afternoon > 0
    assert dinner > afternoon


def test_商场店晚市比社区店更陡():
    mall = minute_weight("mall", 19 * 60) / minute_weight("mall", 12 * 60 + 30)
    community = minute_weight("community", 19 * 60) / minute_weight("community", 12 * 60 + 30)
    assert mall > community


def test_分钟配额求和精确等于当日单量():
    quota = daily_minute_quota("flagship", 200)
    assert len(quota) == 1440
    assert sum(quota) == 200
    assert all(q >= 0 for q in quota)
    assert quota[3 * 60] == 0
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd mock-platform && python -m pytest tests/test_curve.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'mock_platform.world.curve'`

- [ ] **Step 3: 实现曲线**

```python
# mock-platform/mock_platform/world/curve.py
"""客流曲线：把当日单量摊到 1440 分钟。

午市 11:00-14:00、晚市 17:00-21:00 双峰。三种业态曲线形状不同——
商场店晚市更陡（下班人流），社区店午市更平缓（居民就近）。
"""
from __future__ import annotations

import math

_LUNCH_PEAK = 12 * 60 + 30
_DINNER_PEAK = 19 * 60
_LUNCH_WINDOW = (11 * 60, 14 * 60)
_DINNER_WINDOW = (17 * 60, 21 * 60)

# format -> (午市权重, 晚市权重, 峰宽分钟)
_SHAPE = {
    "mall": (1.0, 1.8, 55),
    "flagship": (1.0, 1.5, 65),
    "community": (1.0, 1.15, 75),
}


def _bell(minute: int, peak: int, width: int) -> float:
    return math.exp(-((minute - peak) ** 2) / (2.0 * width * width))


def minute_weight(store_format: str, minute_of_day: int) -> float:
    """该分钟的相对客流权重。营业时段外恒为 0（不产生订单）。"""
    if store_format not in _SHAPE:
        raise ValueError(f"未知业态: {store_format}")
    lunch_w, dinner_w, width = _SHAPE[store_format]
    in_lunch = _LUNCH_WINDOW[0] <= minute_of_day < _LUNCH_WINDOW[1]
    in_dinner = _DINNER_WINDOW[0] <= minute_of_day < _DINNER_WINDOW[1]
    if not (in_lunch or in_dinner):
        return 0.0
    total = 0.0
    if in_lunch:
        total += lunch_w * _bell(minute_of_day, _LUNCH_PEAK, width)
    if in_dinner:
        total += dinner_w * _bell(minute_of_day, _DINNER_PEAK, width)
    return total


def daily_minute_quota(store_format: str, daily_orders: int) -> list[int]:
    """按曲线把 daily_orders 摊到 1440 分钟。求和精确等于 daily_orders。

    用最大余数法分配，避免逐分钟四舍五入导致总数漂移。
    """
    weights = [minute_weight(store_format, m) for m in range(1440)]
    total_w = sum(weights)
    if total_w <= 0:
        raise ValueError(f"业态 {store_format} 的曲线权重全为 0")
    exact = [w / total_w * daily_orders for w in weights]
    quota = [int(x) for x in exact]
    remainder = daily_orders - sum(quota)
    # 余数按小数部分从大到小补齐
    order = sorted(range(1440), key=lambda i: exact[i] - quota[i], reverse=True)
    for i in order[:remainder]:
        quota[i] += 1
    return quota
```

- [ ] **Step 4: 跑曲线测试确认通过**

Run: `cd mock-platform && python -m pytest tests/test_curve.py -v`
Expected: 3 passed

- [ ] **Step 5: 写生成器的失败测试**

```python
# mock-platform/tests/test_generator.py
import datetime
import random

from mock_platform.db import connect
from mock_platform.world.generator import backfill, generate_orders
from mock_platform.world.seed import seed_world


def _conn(tmp_path):
    conn = connect(str(tmp_path / "g.db"))
    seed_world(conn, store_count=10)
    return conn


def test_生成的订单金额自洽(tmp_path):
    conn = _conn(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=5, rng=random.Random(42))
    rows = conn.execute(
        'SELECT o.id, o.gross_cents, o.discount_cents, o.net_cents, '
        '(SELECT COALESCE(SUM(amount_cents),0) FROM order_item WHERE order_id=o.id) items '
        'FROM "order" o').fetchall()
    assert len(rows) == 5
    for r in rows:
        assert r["gross_cents"] == r["items"], "订单毛额必须等于明细合计"
        assert r["net_cents"] == r["gross_cents"] - r["discount_cents"]
        assert r["net_cents"] > 0


def test_支付金额等于订单实收(tmp_path):
    conn = _conn(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=19 * 60, count=8, rng=random.Random(7))
    bad = conn.execute(
        'SELECT o.order_no FROM "order" o WHERE o.net_cents <> '
        '(SELECT COALESCE(SUM(amount_cents),0) FROM payment WHERE order_id=o.id)'
    ).fetchall()
    assert bad == [], f"支付与实收不符: {[r['order_no'] for r in bad]}"


def test_seq严格单调递增(tmp_path):
    conn = _conn(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=3, rng=random.Random(1))
    generate_orders(conn, store_id=2, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=3, rng=random.Random(2))
    seqs = [r["seq"] for r in conn.execute('SELECT seq FROM "order" ORDER BY id')]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)


def test_回填按每店日单量产出(tmp_path):
    conn = _conn(tmp_path)
    created = backfill(conn, days=2, orders_per_store=200,
                       today=datetime.date(2026, 7, 29), rng=random.Random(3))
    assert created == 10 * 200 * 2
    dates = {r["biz_date"] for r in conn.execute('SELECT DISTINCT biz_date FROM "order"')}
    assert dates == {"2026-07-27", "2026-07-28"}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `cd mock-platform && python -m pytest tests/test_generator.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'mock_platform.world.generator'`

- [ ] **Step 7: 实现生成器**

```python
# mock-platform/mock_platform/world/generator.py
"""订单生成。生成器是世界模型唯一的写入方，平台 router 只读。

金额一律用「分」为单位的整数，避免浮点累加误差导致三边对账假性不平。
"""
from __future__ import annotations

import datetime
import random
import sqlite3

from .curve import daily_minute_quota

_CHANNELS = ("dine_in", "takeaway", "groupon")
_CHANNEL_WEIGHTS = (0.62, 0.28, 0.10)
_PAY_BY_CHANNEL = {
    "dine_in": ("wechat", "alipay", "cash"),
    "takeaway": ("platform",),
    "groupon": ("platform", "wechat"),
}


def _next_seq(conn: sqlite3.Connection) -> int:
    row = conn.execute('SELECT COALESCE(MAX(seq), 0) + 1 AS s FROM "order"').fetchone()
    return int(row["s"])


def generate_orders(conn, *, store_id: int, biz_date: str,
                    minute_of_day: int, count: int, rng: random.Random) -> int:
    """在指定门店/营业日/分钟生成 count 笔订单。返回实际新建数。"""
    if count <= 0:
        return 0
    dishes = conn.execute("SELECT id, price_cents, groupon_eligible FROM dish").fetchall()
    if not dishes:
        raise RuntimeError("菜品表为空，先跑 seed_world")
    seq = _next_seq(conn)
    placed_base = datetime.datetime.fromisoformat(biz_date).replace(
        hour=minute_of_day // 60, minute=minute_of_day % 60
    )
    created = 0
    for i in range(count):
        channel = rng.choices(_CHANNELS, weights=_CHANNEL_WEIGHTS, k=1)[0]
        guest_count = rng.randint(1, 6) if channel == "dine_in" else 1
        pool = [d for d in dishes if not (channel == "groupon" and not d["groupon_eligible"])]
        line_count = rng.randint(2, 6)
        lines = []
        gross = 0
        for _ in range(line_count):
            dish = rng.choice(pool)
            qty = rng.randint(1, 3)
            amount = dish["price_cents"] * qty
            gross += amount
            lines.append((dish["id"], qty, dish["price_cents"], amount))
        discount = 0
        if channel == "groupon":
            discount = int(gross * rng.uniform(0.15, 0.30))
        elif channel == "takeaway":
            discount = int(gross * rng.uniform(0.0, 0.12))
        net = gross - discount
        order_no = f"MK{placed_base:%Y%m%d}{store_id:02d}{seq:08d}"
        placed_at = (placed_base + datetime.timedelta(seconds=rng.randint(0, 59))).isoformat()
        cur = conn.execute(
            'INSERT INTO "order"(order_no, store_id, channel, placed_at, biz_date, '
            "gross_cents, discount_cents, net_cents, guest_count, seq) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)",
            (order_no, store_id, channel, placed_at, biz_date,
             gross, discount, net, guest_count, seq),
        )
        order_id = cur.lastrowid
        conn.executemany(
            "INSERT INTO order_item(order_id, dish_id, qty, price_cents, amount_cents) "
            "VALUES (?,?,?,?,?)",
            [(order_id, d, q, p, a) for d, q, p, a in lines],
        )
        method = rng.choice(_PAY_BY_CHANNEL[channel])
        conn.execute(
            "INSERT INTO payment(order_id, method, amount_cents) VALUES (?,?,?)",
            (order_id, method, net),
        )
        seq += 1
        created += 1
    return created


def backfill(conn, *, days: int, orders_per_store: int,
             today: datetime.date, rng: random.Random) -> int:
    """一次性造过去 days 天的历史订单（不含今天）。返回新建总数。

    看板一开始就要有趋势和环比可看，否则得等一个月。
    """
    stores = conn.execute("SELECT id, format FROM store ORDER BY id").fetchall()
    total = 0
    for day_offset in range(days, 0, -1):
        biz_date = (today - datetime.timedelta(days=day_offset)).isoformat()
        for store in stores:
            quota = daily_minute_quota(store["format"], orders_per_store)
            for minute, count in enumerate(quota):
                if count:
                    total += generate_orders(
                        conn, store_id=store["id"], biz_date=biz_date,
                        minute_of_day=minute, count=count, rng=rng,
                    )
    return total
```

- [ ] **Step 8: 跑测试确认通过**

Run: `cd mock-platform && python -m pytest tests/ -v`
Expected: 13 passed

- [ ] **Step 9: Commit**

```bash
git add mock-platform/
git commit -m "feat(mock-platform): 客流曲线 + 订单生成器 + 历史回填" -- mock-platform/
```

---

### Task 4: 客如云风格 API（鉴权 + 游标分页 + 限流）

**Files:**
- Create: `mock-platform/mock_platform/api/__init__.py`
- Create: `mock-platform/mock_platform/api/_auth.py`
- Create: `mock-platform/mock_platform/api/_paging.py`
- Create: `mock-platform/mock_platform/api/keruyun.py`
- Create: `mock-platform/mock_platform/api/app.py`
- Test: `mock-platform/tests/test_keruyun_api.py`

**Interfaces:**
- Consumes: `mock_platform.config.get_settings`、`mock_platform.db.connect`
- Produces:
  - `mock_platform.api._auth.keruyun_sign(params: dict[str, str], app_secret: str) -> str`
  - `mock_platform.api._paging.page_orders(conn, *, since_seq: int, limit: int) -> tuple[list[dict], int, bool]` 返回 `(orders, next_cursor, has_more)`
  - `mock_platform.api.app.create_app() -> fastapi.FastAPI`
  - HTTP `GET /keruyun/open/order/list?appKey&timestamp&sign&cursor&limit`

- [ ] **Step 1: 写失败测试**

```python
# mock-platform/tests/test_keruyun_api.py
import random

import pytest
from fastapi.testclient import TestClient

from mock_platform.api._auth import keruyun_sign
from mock_platform.api.app import create_app
from mock_platform.db import connect
from mock_platform.world.generator import generate_orders
from mock_platform.world.seed import seed_world

APP_KEY = "mock-key"
APP_SECRET = "mock-secret"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    db = str(tmp_path / "api.db")
    monkeypatch.setenv("MOCK_DB_PATH", db)
    monkeypatch.setenv("MOCK_KERUYUN_APP_KEY", APP_KEY)
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", APP_SECRET)
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "cb")
    from mock_platform.config import get_settings
    get_settings.cache_clear()
    conn = connect(db)
    seed_world(conn, store_count=10)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=25, rng=random.Random(11))
    conn.close()
    yield TestClient(create_app())
    get_settings.cache_clear()


def _signed(params: dict) -> dict:
    p = dict(params)
    p["appKey"] = APP_KEY
    p["timestamp"] = "1785300000"
    p["sign"] = keruyun_sign(p, APP_SECRET)
    return p


def test_签名错误被拒(client):
    p = _signed({"cursor": "0", "limit": "10"})
    p["sign"] = "deadbeef"
    r = client.get("/keruyun/open/order/list", params=p)
    assert r.status_code == 200          # 平台风格：HTTP 200 + 业务错误码
    assert r.json()["code"] == "AUTH_SIGN_INVALID"


def test_缺签名被拒(client):
    r = client.get("/keruyun/open/order/list", params={"cursor": "0", "limit": "10"})
    assert r.json()["code"] == "AUTH_SIGN_INVALID"


def test_游标分页不重不漏(client):
    seen, cursor, pages = [], "0", 0
    while True:
        r = client.get("/keruyun/open/order/list",
                       params=_signed({"cursor": cursor, "limit": "10"}))
        body = r.json()
        assert body["code"] == "0"
        seen.extend(o["orderNo"] for o in body["data"]["list"])
        pages += 1
        if not body["data"]["hasMore"]:
            break
        cursor = str(body["data"]["nextCursor"])
        assert pages < 10, "分页没有收敛"
    assert len(seen) == 25
    assert len(set(seen)) == 25


def test_limit超上限被拒(client):
    r = client.get("/keruyun/open/order/list",
                   params=_signed({"cursor": "0", "limit": "5000"}))
    assert r.json()["code"] == "PARAM_LIMIT_TOO_LARGE"


def test_订单结构含明细与支付(client):
    body = client.get("/keruyun/open/order/list",
                      params=_signed({"cursor": "0", "limit": "1"})).json()
    order = body["data"]["list"][0]
    assert set(order) >= {"orderNo", "shopCode", "channel", "placedAt", "bizDate",
                          "grossAmount", "discountAmount", "netAmount", "items", "payments"}
    assert order["items"] and order["payments"]
    assert sum(i["amount"] for i in order["items"]) == order["grossAmount"]
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd mock-platform && python -m pytest tests/test_keruyun_api.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'mock_platform.api'`

- [ ] **Step 3: 实现鉴权与分页**

```python
# mock-platform/mock_platform/api/__init__.py
```

```python
# mock-platform/mock_platform/api/_auth.py
"""平台鉴权算法。

客如云走 token + sign 两段式。这里实现 sign 部分：与美团/抖音刻意不同，
目的是让 connector 侧被迫处理异构鉴权——这正是接真实平台时的实际情况。
"""
from __future__ import annotations

import hashlib
import hmac


def keruyun_sign(params: dict[str, str], app_secret: str) -> str:
    """参数按名字典序拼成 key=value&，用 app_secret 做 HMAC-SHA256，取小写 hex。

    参与签名的参数排除 sign 本身与空值。
    """
    items = sorted(
        (k, str(v)) for k, v in params.items()
        if k != "sign" and v is not None and str(v) != ""
    )
    payload = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(
        app_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256
    ).hexdigest().lower()
```

```python
# mock-platform/mock_platform/api/_paging.py
"""基于全局单调 seq 的游标分页。seq 单调保证不重不漏。"""
from __future__ import annotations

MAX_LIMIT = 200


def page_orders(conn, *, since_seq: int, limit: int):
    rows = conn.execute(
        'SELECT o.*, s.code AS shop_code FROM "order" o '
        "JOIN store s ON s.id = o.store_id "
        "WHERE o.seq > ? ORDER BY o.seq LIMIT ?",
        (since_seq, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    orders = []
    for r in rows:
        items = conn.execute(
            "SELECT d.name, oi.qty, oi.price_cents, oi.amount_cents "
            "FROM order_item oi JOIN dish d ON d.id = oi.dish_id WHERE oi.order_id = ?",
            (r["id"],),
        ).fetchall()
        payments = conn.execute(
            "SELECT method, amount_cents FROM payment WHERE order_id = ?", (r["id"],)
        ).fetchall()
        orders.append({
            "orderNo": r["order_no"],
            "shopCode": r["shop_code"],
            "channel": r["channel"],
            "placedAt": r["placed_at"],
            "bizDate": r["biz_date"],
            "grossAmount": r["gross_cents"],
            "discountAmount": r["discount_cents"],
            "netAmount": r["net_cents"],
            "guestCount": r["guest_count"],
            "items": [
                {"dishName": i["name"], "qty": i["qty"],
                 "price": i["price_cents"], "amount": i["amount_cents"]}
                for i in items
            ],
            "payments": [
                {"method": p["method"], "amount": p["amount_cents"]} for p in payments
            ],
        })
    next_cursor = rows[-1]["seq"] if rows else since_seq
    return orders, int(next_cursor), has_more
```

- [ ] **Step 4: 实现 router 与 app**

```python
# mock-platform/mock_platform/api/keruyun.py
"""客如云风格订单接口。

平台风格：HTTP 恒 200，成败看业务 code。这一点刻意保留——真实平台大多如此，
connector 若只看 HTTP 状态码就会把失败当成功，正是要压测的地方。
"""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ..config import get_settings
from ..db import connect
from ._auth import keruyun_sign
from ._paging import MAX_LIMIT, page_orders

router = APIRouter(prefix="/keruyun/open", tags=["keruyun"])


def _fail(code: str, message: str) -> JSONResponse:
    return JSONResponse({"code": code, "message": message, "data": None})


@router.get("/order/list")
async def order_list(request: Request):
    settings = get_settings()
    params = dict(request.query_params)
    if params.get("appKey") != settings.keruyun_app_key:
        return _fail("AUTH_APPKEY_INVALID", "appKey 无效")
    expected = keruyun_sign(params, settings.keruyun_app_secret)
    if params.get("sign", "") != expected:
        return _fail("AUTH_SIGN_INVALID", "签名校验失败")
    try:
        cursor = int(params.get("cursor", "0"))
        limit = int(params.get("limit", "50"))
    except ValueError:
        return _fail("PARAM_INVALID", "cursor / limit 必须是整数")
    if limit > MAX_LIMIT:
        return _fail("PARAM_LIMIT_TOO_LARGE", f"limit 上限为 {MAX_LIMIT}")
    if limit <= 0:
        return _fail("PARAM_INVALID", "limit 必须为正")
    conn = connect(settings.db_path)
    try:
        orders, next_cursor, has_more = page_orders(conn, since_seq=cursor, limit=limit)
    finally:
        conn.close()
    return JSONResponse({
        "code": "0",
        "message": "success",
        "data": {"list": orders, "nextCursor": next_cursor, "hasMore": has_more},
    })
```

```python
# mock-platform/mock_platform/api/app.py
from __future__ import annotations

from fastapi import FastAPI

from .keruyun import router as keruyun_router


def create_app() -> FastAPI:
    app = FastAPI(title="Cretas 餐饮平台模拟器", version="1.0.0")
    app.include_router(keruyun_router)

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    return app
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd mock-platform && python -m pytest tests/ -v`
Expected: 18 passed

- [ ] **Step 6: 跑隔离验收，确认新代码没引入禁用依赖**

Run: `cd mock-platform && python -m pytest tests/test_isolation.py -v`
Expected: 2 passed

- [ ] **Step 7: Commit**

```bash
git add mock-platform/
git commit -m "feat(mock-platform): 客如云风格订单 API — 签名/游标分页/限流" -- mock-platform/
```

---

### Task 5: CLI + 常驻生成循环 + 回调推送

**Files:**
- Create: `mock-platform/mock_platform/callback.py`
- Create: `mock-platform/mock_platform/cli.py`
- Test: `mock-platform/tests/test_callback.py`

**Interfaces:**
- Consumes: `mock_platform.world.generator.generate_orders` / `backfill`、`mock_platform.config.get_settings`
- Produces:
  - `mock_platform.callback.build_signature(body: bytes, timestamp: str, nonce: str, secret: str) -> str`
  - `mock_platform.callback.notify(client, url: str, secret: str, *, max_seq: int) -> bool`
  - CLI：`python -m mock_platform.cli serve` / `python -m mock_platform.cli backfill --days N`

- [ ] **Step 1: 写回调签名的失败测试**

```python
# mock-platform/tests/test_callback.py
from mock_platform.callback import build_signature


def test_签名对相同输入稳定():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    assert a == b and len(a) == 64


def test_body变了签名就变():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":6}', "1785300000", "n1", "sec")
    assert a != b


def test_nonce变了签名就变_防重放():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":5}', "1785300000", "n2", "sec")
    assert a != b


def test_密钥变了签名就变():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "other")
    assert a != b
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd mock-platform && python -m pytest tests/test_callback.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'mock_platform.callback'`

- [ ] **Step 3: 实现回调客户端**

```python
# mock-platform/mock_platform/callback.py
"""回调推送：只发「有新数据」的信号，不发数据本身。

真实平台的回调带数据，这里刻意牺牲一点仿真度：回调丢一次就永久少一笔，
改成触发器后回调丢失由 connector 的定时拉取兜底，两条路指向同一个幂等写入。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import secrets
import time

logger = logging.getLogger(__name__)


def build_signature(body: bytes, timestamp: str, nonce: str, secret: str) -> str:
    """HMAC-SHA256(secret, timestamp + nonce + body)，小写 hex。"""
    payload = timestamp.encode("ascii") + nonce.encode("ascii") + body
    return hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest().lower()


async def notify(client, url: str, secret: str, *, max_seq: int) -> bool:
    """推一次「新数据到 max_seq」。失败只记日志——拉取会兜底，不阻塞生成。"""
    if not url:
        return False
    body = json.dumps({"platform": "keruyun", "maxSeq": max_seq},
                      separators=(",", ":")).encode("utf-8")
    timestamp = str(int(time.time()))
    nonce = secrets.token_hex(8)
    headers = {
        "Content-Type": "application/json",
        "X-Mock-Timestamp": timestamp,
        "X-Mock-Nonce": nonce,
        "X-Mock-Signature": build_signature(body, timestamp, nonce, secret),
    }
    try:
        resp = await client.post(url, content=body, headers=headers, timeout=5.0)
        if resp.status_code != 200:
            logger.warning("[callback] 非 200: %s %s", resp.status_code, resp.text[:200])
            return False
        return True
    except Exception as exc:  # noqa: BLE001 — 回调失败不该拖垮生成器
        logger.warning("[callback] 推送失败: %s", exc)
        return False
```

- [ ] **Step 4: 实现 CLI**

```python
# mock-platform/mock_platform/cli.py
"""模拟端入口。

serve    — 起 HTTP 服务 + 常驻生成循环（按分钟推进，边生成边回调通知）
backfill — 一次性造历史订单
"""
from __future__ import annotations

import argparse
import asyncio
import datetime
import logging
import random

import httpx
import uvicorn

from .api.app import create_app
from .callback import notify
from .config import get_settings
from .db import connect
from .world.curve import daily_minute_quota
from .world.generator import backfill, generate_orders
from .world.seed import seed_world

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("mock-platform")


async def _generate_forever() -> None:
    """每分钟推进一次：给每家店按曲线配额补上这一分钟该出的单。"""
    settings = get_settings()
    rng = random.Random()
    conn = connect(settings.db_path)
    seed_world(conn, settings.store_count)
    stores = conn.execute("SELECT id, format FROM store ORDER BY id").fetchall()
    quotas = {
        s["id"]: daily_minute_quota(s["format"], settings.orders_per_store_per_day)
        for s in stores
    }
    async with httpx.AsyncClient() as client:
        while True:
            now = datetime.datetime.now()
            minute = now.hour * 60 + now.minute
            biz_date = now.date().isoformat()
            created = 0
            for store in stores:
                count = quotas[store["id"]][minute]
                if count:
                    created += generate_orders(
                        conn, store_id=store["id"], biz_date=biz_date,
                        minute_of_day=minute, count=count, rng=rng,
                    )
            if created:
                row = conn.execute('SELECT MAX(seq) s FROM "order"').fetchone()
                logger.info("[gen] 第 %s 分钟生成 %d 单, maxSeq=%s", minute, created, row["s"])
                await notify(client, settings.callback_url,
                             settings.callback_secret, max_seq=int(row["s"]))
            await asyncio.sleep(60)


def _cmd_serve(args: argparse.Namespace) -> None:
    app = create_app()

    @app.on_event("startup")
    async def _arm_generator():
        asyncio.create_task(_generate_forever())

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


def _cmd_backfill(args: argparse.Namespace) -> None:
    settings = get_settings()
    conn = connect(settings.db_path)
    seed_world(conn, settings.store_count)
    total = backfill(conn, days=args.days,
                     orders_per_store=settings.orders_per_store_per_day,
                     today=datetime.date.today(), rng=random.Random())
    logger.info("[backfill] 造出 %d 单，覆盖过去 %d 天", total, args.days)


def main() -> None:
    parser = argparse.ArgumentParser(prog="mock_platform")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_serve = sub.add_parser("serve")
    p_serve.add_argument("--host", default="0.0.0.0")
    p_serve.add_argument("--port", type=int, default=9200)
    p_serve.set_defaults(func=_cmd_serve)
    p_back = sub.add_parser("backfill")
    p_back.add_argument("--days", type=int, required=True)
    p_back.set_defaults(func=_cmd_backfill)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: 跑全部测试**

Run: `cd mock-platform && python -m pytest tests/ -v`
Expected: 22 passed

- [ ] **Step 6: Commit**

```bash
git add mock-platform/
git commit -m "feat(mock-platform): CLI serve/backfill + 常驻生成循环 + 回调推送" -- mock-platform/
```

---

### Task 6: migration — 游标表 + MOCK_REST 门店维度

**Files:**
- Create: `backend/python/smartbi/database/migrations/V20261101_01__platform_sync_and_mock_tenant.sql`
- Test: `backend/python/tests/test_platform_sync_migration.py`

**Interfaces:**
- Consumes: 无
- Produces:
  - 表 `platform_sync_cursor(factory_id, platform, cursor_value, updated_at)`，主键 `(factory_id, platform)`
  - 表 `platform_store_map(factory_id, platform, platform_store_code, store_id)`，主键 `(factory_id, platform, platform_store_code)`
  - `dim_store` 中 10 行 `MOCK_REST` 门店（按 `name` 唯一，**没有 store_code 列**）
  - `fact_pos_transaction` 上的**部分唯一索引** `WHERE source_type = 'mock_keruyun'`

> ⚠️ **2026-07-29 计划修正（实测推翻了原假设，原文已作废）**
>
> 原计划这一节假设 `dim_store` 有 `store_code` 列、`fact_pos_transaction` 有 `transaction_no` 列。**两个都不存在。** 实测结果：
>
> 1. `dim_store` 实际列 = `store_id / factory_id / name / brand / city / province / region`，唯一约束 `(factory_id, name)`，**无 store_code**。它被 23 处外键引用，不该为了我们加列 → 改为新建 `platform_store_map` 映射表。这也更正确：不同平台对同一门店有不同 code（美团≠抖音≠POS），映射本来就该按平台建。
> 2. `fact_pos_transaction` 实际是 `source_type` + `source_bill_no`（均 NOT NULL），且**除主键外无任何唯一约束**。
> 3. 🔴 该表 `(factory_id, source_type, source_bill_no)` 上**已有 151,978 组重复**（全表 1,382,267 行）。**直接加唯一索引会失败，按 Step 3.5 的设计会 ABORT 整个 Python 部署。** → 只加**部分唯一索引**，谓词限定 `source_type='mock_keruyun'`，初始覆盖 0 行，不可能与历史冲突。
>
> 🔴 **查表方法论**：这些老表的 `tenant_isolation` policy **没有** `__internal__` 逃生门（与本计划新建的表相反）。用 `set_config('app.factory_id','__internal__')` 查会得到**假 0 行**，据此会得出「表是空的」「零重复」两个错误结论。查老表必须用真租户 id，或用 `sudo -u postgres psql` 绕 RLS。

- [ ] **Step 1: 复核真实表结构（数据已在本计划修正中给出，此步是确认没被别的 session 改过）**

Run:

```bash
ssh root@47.100.235.168 "PW=\$(grep '^SMARTBI_DB_PASSWORD=' /www/wwwroot/cretas/.env.prod | cut -d= -f2- | tr -d '\"'); export PGPASSWORD=\"\$PW\"; psql -h localhost -U smartbi_user -d smartbi_prod_db -c '\\d dim_store'; sudo -u postgres psql -d smartbi_prod_db -tAc \"SELECT count(*) FROM (SELECT factory_id, source_type, source_bill_no FROM fact_pos_transaction GROUP BY 1,2,3 HAVING count(*)>1) t;\""
```

Expected：`dim_store` 无 `store_code` 列；重复组数 > 0（写这份计划时是 151978）。**只要重复组数非 0，就绝不能加全表唯一索引。** 若发现结构与上面的修正不符，停下来说明，不要硬写。

- [ ] **Step 2: 写 migration**

```sql
-- backend/python/smartbi/database/migrations/V20261101_01__platform_sync_and_mock_tenant.sql
-- 餐饮外部平台模拟器: connector 游标表 + MOCK_REST 租户门店维度
-- spec: docs/superpowers/specs/2026-07-29-restaurant-mock-platform-api-design.md

BEGIN;

CREATE TABLE IF NOT EXISTS platform_sync_cursor (
    factory_id   TEXT        NOT NULL,
    platform     TEXT        NOT NULL,
    cursor_value TEXT        NOT NULL DEFAULT '0',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, platform)
);

ALTER TABLE platform_sync_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_sync_cursor FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS platform_sync_cursor_select ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_select ON platform_sync_cursor
    FOR SELECT USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_sync_cursor_insert ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_insert ON platform_sync_cursor
    FOR INSERT WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_sync_cursor_update ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_update ON platform_sync_cursor
    FOR UPDATE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    ) WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_sync_cursor_delete ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_delete ON platform_sync_cursor
    FOR DELETE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_sync_cursor TO smartbi_user;

COMMENT ON TABLE platform_sync_cursor IS
    '外部平台增量拉取游标. 每租户每平台一行, connector 拉完一页后推进.';

-- ── 平台门店映射 ──────────────────────────────────────────────────────
-- dim_store 没有 store_code 列(实际列: store_id/factory_id/name/brand/city/
-- province/region, 唯一约束 (factory_id, name)), 且被 23 处外键引用, 不该为
-- 我们加列。不同平台对同一门店有不同 code(美团≠抖音≠POS), 按平台建映射更对。
CREATE TABLE IF NOT EXISTS platform_store_map (
    factory_id          TEXT   NOT NULL,
    platform            TEXT   NOT NULL,
    platform_store_code TEXT   NOT NULL,
    store_id            BIGINT NOT NULL REFERENCES dim_store(store_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, platform, platform_store_code)
);

ALTER TABLE platform_store_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_store_map FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS platform_store_map_select ON platform_store_map;
CREATE POLICY platform_store_map_select ON platform_store_map
    FOR SELECT USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_store_map_insert ON platform_store_map;
CREATE POLICY platform_store_map_insert ON platform_store_map
    FOR INSERT WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_store_map_update ON platform_store_map;
CREATE POLICY platform_store_map_update ON platform_store_map
    FOR UPDATE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    ) WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_store_map_delete ON platform_store_map;
CREATE POLICY platform_store_map_delete ON platform_store_map
    FOR DELETE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_store_map TO smartbi_user;

-- ── 幂等索引 ──────────────────────────────────────────────────────────
-- 🔴 只能是**部分**唯一索引。全表 (factory_id, source_type, source_bill_no)
--    已有 151,978 组重复(全表 1,382,267 行), 加全表唯一索引会失败 →
--    按 deploy-smartbi-python.sh Step 3.5 的设计会 ABORT 整个 Python 部署。
--    谓词限定到我们自己的 source_type, 初始覆盖 0 行, 不可能与历史冲突。
CREATE UNIQUE INDEX IF NOT EXISTS uq_fact_pos_txn_mock_keruyun
    ON fact_pos_transaction (factory_id, source_bill_no)
    WHERE source_type = 'mock_keruyun';

-- ── MOCK_REST 的 10 家门店 + 平台 code 映射 ────────────────────────────
-- dim_store 按 (factory_id, name) 唯一; name 必须与模拟端
-- mock_platform/world/seed.py 的 _STORES 第二个字段逐字一致。
INSERT INTO dim_store (factory_id, name, brand, city, province)
VALUES
    ('MOCK_REST', '模拟·打浦桥日月光店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·徐汇美罗城店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·静安嘉里中心店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·陆家嘴正大店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·长宁龙之梦店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·杨浦五角场店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·普陀真如社区店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·闵行莘庄社区店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·宝山大场社区店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·浦东金桥社区店',   '模拟餐饮', '上海', '上海')
ON CONFLICT (factory_id, name) DO NOTHING;

-- MK01..MK10 → store_id。顺序与 seed.py 的 _STORES 一一对应。
INSERT INTO platform_store_map (factory_id, platform, platform_store_code, store_id)
SELECT 'MOCK_REST', 'keruyun', v.code, s.store_id
FROM (VALUES
    ('MK01', '模拟·打浦桥日月光店'), ('MK02', '模拟·徐汇美罗城店'),
    ('MK03', '模拟·静安嘉里中心店'), ('MK04', '模拟·陆家嘴正大店'),
    ('MK05', '模拟·长宁龙之梦店'),   ('MK06', '模拟·杨浦五角场店'),
    ('MK07', '模拟·普陀真如社区店'), ('MK08', '模拟·闵行莘庄社区店'),
    ('MK09', '模拟·宝山大场社区店'), ('MK10', '模拟·浦东金桥社区店')
) AS v(code, name)
JOIN dim_store s ON s.factory_id = 'MOCK_REST' AND s.name = v.name
ON CONFLICT (factory_id, platform, platform_store_code) DO NOTHING;

-- ── 支付渠道 ──────────────────────────────────────────────────────────
-- fact_pos_payment.channel_id 是 NOT NULL 外键(指向 dim_payment_channel),
-- 没有 method 文本列。writer 要按 (factory_id, name) 查 channel_id, 所以这里
-- 必须先把模拟端会产生的 4 种支付方式种进去。
-- 名字与模拟端 mock_platform/world/generator.py 的 _PAY_BY_CHANNEL 值对应:
--   cash→现金  wechat→微信  alipay→支付宝  platform→平台代收
INSERT INTO dim_payment_channel (factory_id, name, category)
VALUES
    ('MOCK_REST', '现金',     'cash'),
    ('MOCK_REST', '微信',     'wallet'),
    ('MOCK_REST', '支付宝',   'wallet'),
    ('MOCK_REST', '平台代收', 'platform')
ON CONFLICT (factory_id, name) DO NOTHING;

COMMIT;
```

- [ ] **Step 3: 写 migration 静态契约测试**

```python
# backend/python/tests/test_platform_sync_migration.py
"""migration 的静态契约: 单事务、RLS 四条 policy 齐全、门店 code 与模拟端对齐。"""
import pathlib
import re

MIG = (pathlib.Path(__file__).resolve().parents[1]
       / "smartbi" / "database" / "migrations"
       / "V20261101_01__platform_sync_and_mock_tenant.sql")


def test_migration存在且是单事务():
    sql = MIG.read_text(encoding="utf-8")
    assert sql.count("BEGIN;") == 1
    assert sql.count("COMMIT;") == 1


def test_RLS开启且强制():
    sql = MIG.read_text(encoding="utf-8")
    assert "ENABLE ROW LEVEL SECURITY" in sql
    assert "FORCE ROW LEVEL SECURITY" in sql


def test_四条policy齐全含DELETE():
    sql = MIG.read_text(encoding="utf-8").upper()
    for cmd in ("FOR SELECT", "FOR INSERT", "FOR UPDATE", "FOR DELETE"):
        assert cmd in sql, f"缺 {cmd} policy"


def test_门店code与模拟端一一对应():
    sql = MIG.read_text(encoding="utf-8")
    codes = set(re.findall(r"\bMK(\d{2})\b", sql))
    assert codes == {f"{i:02d}" for i in range(1, 11)}, "门店 code 必须是 MK01..MK10"


def test_唯一索引必须是部分索引():
    """🔴 防 prod 部署被 ABORT 的那一刀。

    fact_pos_transaction 上 (factory_id, source_type, source_bill_no) 已有
    151,978 组重复(全表 1,382,267 行)。加全表唯一索引会失败, 而 migration
    runner 失败 → deploy-smartbi-python.sh Step 3.5 ABORT 整个 Python 部署。
    只能加谓词限定到自己 source_type 的部分唯一索引。
    """
    sql = MIG.read_text(encoding="utf-8")
    m = re.search(
        r"CREATE UNIQUE INDEX[^;]*?ON\s+fact_pos_transaction[^;]*?;", sql, re.S | re.I
    )
    assert m, "缺少 fact_pos_transaction 上的幂等唯一索引"
    stmt = m.group(0)
    assert "WHERE" in stmt.upper(), (
        "必须是部分唯一索引(带 WHERE source_type=...)。全表唯一索引会因历史重复失败, "
        "进而 ABORT 整个 Python 部署。"
    )
    assert "source_type" in stmt, "WHERE 谓词必须限定 source_type"


def test_不得给dim_store加列():
    """dim_store 被 23 处外键引用, 不为我们这个功能改它的结构。

    门店映射走新建的 platform_store_map, 不走 dim_store 加列。
    """
    sql = MIG.read_text(encoding="utf-8").upper()
    assert "ALTER TABLE DIM_STORE" not in sql, "不许改 dim_store 结构"
    assert "PLATFORM_STORE_MAP" in sql, "门店映射必须建在 platform_store_map"
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest tests/test_platform_sync_migration.py -v`
Expected: 4 passed（`test_门店code与模拟端一一对应` 会强制你在 Step 2 里真的把 10 行门店写进去）

- [ ] **Step 5: Commit**

```bash
git add backend/python/smartbi/database/migrations/V20261101_01__platform_sync_and_mock_tenant.sql backend/python/tests/test_platform_sync_migration.py
git commit -m "feat(platform-sync): 游标表 + MOCK_REST 门店维度 migration" -- backend/python/smartbi/database/migrations/V20261101_01__platform_sync_and_mock_tenant.sql backend/python/tests/test_platform_sync_migration.py
```

---

### Task 7: connector 框架（归一化模型 + 游标 + 幂等 + 退避 + 失败隔离）

**Files:**
- Create: `backend/python/smartbi/ingestion/platforms/__init__.py`
- Create: `backend/python/smartbi/ingestion/platforms/models.py`
- Create: `backend/python/smartbi/ingestion/platforms/cursor_store.py`
- Create: `backend/python/smartbi/ingestion/platforms/framework.py`
- Test: `backend/python/tests/test_platform_framework.py`

**Interfaces:**
- Consumes: Task 6 的表 `platform_sync_cursor`
- Produces:
  - `models.NormalizedItem(dish_name: str, qty: int, price_cents: int, amount_cents: int)`
  - `models.NormalizedPayment(method: str, amount_cents: int)`
  - `models.NormalizedOrder(platform: str, platform_order_no: str, store_code: str, channel: str, placed_at: datetime, biz_date: date, gross_cents: int, discount_cents: int, net_cents: int, guest_count: int, items: list[NormalizedItem], payments: list[NormalizedPayment])`
  - `models.FetchPage(orders: list[NormalizedOrder], next_cursor: str, has_more: bool)`
  - `cursor_store.read_cursor(conn, factory_id: str, platform: str) -> str`
  - `cursor_store.write_cursor(conn, factory_id: str, platform: str, cursor: str) -> None`
  - `framework.PlatformAdapter`（Protocol）：属性 `platform: str`，方法 `async fetch_page(cursor: str, limit: int) -> FetchPage`
  - `framework.PlatformSyncError`
  - `framework.sync_platform(pool, adapter, *, factory_id: str, write_orders, max_pages: int = 20) -> int`

- [ ] **Step 1: 写失败测试**

```python
# backend/python/tests/test_platform_framework.py
"""connector 框架: 游标推进、幂等、失败隔离、禁降级。

不碰 DB / 不碰网络 —— adapter 与 writer 都用假实现注入。
"""
import datetime

import pytest

from smartbi.ingestion.platforms.framework import PlatformSyncError, sync_platform
from smartbi.ingestion.platforms.models import FetchPage, NormalizedOrder


def _order(no: str) -> NormalizedOrder:
    return NormalizedOrder(
        platform="keruyun", platform_order_no=no, store_code="MK01",
        channel="dine_in", placed_at=datetime.datetime(2026, 7, 29, 12, 0),
        biz_date=datetime.date(2026, 7, 29), gross_cents=1000,
        discount_cents=0, net_cents=1000, guest_count=2, items=[], payments=[],
    )


class _FakeAdapter:
    platform = "keruyun"

    def __init__(self, pages):
        self._pages = pages
        self.seen_cursors = []

    async def fetch_page(self, cursor, limit):
        self.seen_cursors.append(cursor)
        if not self._pages:
            return FetchPage(orders=[], next_cursor=cursor, has_more=False)
        return self._pages.pop(0)


class _FakeCursorStore(dict):
    pass


@pytest.mark.asyncio
async def test_多页拉取游标逐页推进(monkeypatch):
    written = []
    adapter = _FakeAdapter([
        FetchPage(orders=[_order("A1")], next_cursor="10", has_more=True),
        FetchPage(orders=[_order("A2")], next_cursor="20", has_more=False),
    ])
    store = {"cursor": "0"}

    async def _read(pool, factory_id, platform):
        return store["cursor"]

    async def _write(pool, factory_id, platform, cursor):
        store["cursor"] = cursor

    async def _write_orders(pool, factory_id, orders):
        written.extend(o.platform_order_no for o in orders)
        return len(orders)

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    n = await sync_platform(None, adapter, factory_id="MOCK_REST",
                            write_orders=_write_orders)
    assert n == 2
    assert written == ["A1", "A2"]
    assert adapter.seen_cursors == ["0", "10"]
    assert store["cursor"] == "20"


@pytest.mark.asyncio
async def test_写入失败不推进游标_下轮可重拉(monkeypatch):
    adapter = _FakeAdapter([
        FetchPage(orders=[_order("B1")], next_cursor="10", has_more=False),
    ])
    store = {"cursor": "0"}

    async def _read(pool, factory_id, platform):
        return store["cursor"]

    async def _write(pool, factory_id, platform, cursor):
        store["cursor"] = cursor

    async def _boom(pool, factory_id, orders):
        raise RuntimeError("silver 写入失败")

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    with pytest.raises(PlatformSyncError):
        await sync_platform(None, adapter, factory_id="MOCK_REST", write_orders=_boom)
    assert store["cursor"] == "0", "写失败必须保持游标不动，否则那批数据永久丢失"


@pytest.mark.asyncio
async def test_拉取失败明确抛错不静默当成无数据(monkeypatch):
    class _BrokenAdapter:
        platform = "keruyun"

        async def fetch_page(self, cursor, limit):
            raise ConnectionError("平台不可达")

    async def _read(pool, factory_id, platform):
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        raise AssertionError("不该推进游标")

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    async def _noop(pool, factory_id, orders):
        return 0

    with pytest.raises(PlatformSyncError, match="平台不可达"):
        await sync_platform(None, _BrokenAdapter(), factory_id="MOCK_REST",
                            write_orders=_noop)


@pytest.mark.asyncio
async def test_翻页有上限防打满(monkeypatch):
    adapter = _FakeAdapter([
        FetchPage(orders=[_order(f"C{i}")], next_cursor=str(i), has_more=True)
        for i in range(1, 100)
    ])

    async def _read(pool, factory_id, platform):
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        pass

    async def _write_orders(pool, factory_id, orders):
        return len(orders)

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    n = await sync_platform(None, adapter, factory_id="MOCK_REST",
                            write_orders=_write_orders, max_pages=5)
    assert n == 5, "单轮最多翻 max_pages 页，剩下的留给下一轮"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest tests/test_platform_framework.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'smartbi.ingestion.platforms'`

- [ ] **Step 3: 写归一化模型**

```python
# backend/python/smartbi/ingestion/platforms/__init__.py
"""外部餐饮平台增量接入。

各平台 adapter 把自己的报文归一化成 models.NormalizedOrder, 框架负责
游标推进 / 幂等 / 退避 / 失败隔离, writer 负责落 Silver。
"""
```

```python
# backend/python/smartbi/ingestion/platforms/models.py
"""平台归一化模型。

金额一律「分」为单位的整数: 各平台小数位约定不一, 浮点累加会让跨平台对账
出现假性不平。到写 Silver 那一步再换算成元。
"""
from __future__ import annotations

import datetime
from dataclasses import dataclass, field
from typing import List


@dataclass(frozen=True)
class NormalizedItem:
    dish_name: str
    qty: int
    price_cents: int
    amount_cents: int


@dataclass(frozen=True)
class NormalizedPayment:
    method: str
    amount_cents: int


@dataclass(frozen=True)
class NormalizedOrder:
    platform: str
    platform_order_no: str
    store_code: str
    channel: str
    placed_at: datetime.datetime
    biz_date: datetime.date
    gross_cents: int
    discount_cents: int
    net_cents: int
    guest_count: int
    items: List[NormalizedItem] = field(default_factory=list)
    payments: List[NormalizedPayment] = field(default_factory=list)


@dataclass(frozen=True)
class FetchPage:
    orders: List[NormalizedOrder]
    next_cursor: str
    has_more: bool
```

- [ ] **Step 4: 写游标存储**

```python
# backend/python/smartbi/ingestion/platforms/cursor_store.py
"""游标读写。RLS 表, 必须在同一连接上先设 app.factory_id。"""
from __future__ import annotations


async def read_cursor(pool, factory_id: str, platform: str) -> str:
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            row = await conn.fetchrow(
                "SELECT cursor_value FROM platform_sync_cursor "
                "WHERE factory_id = $1 AND platform = $2",
                factory_id, platform,
            )
    return row["cursor_value"] if row else "0"


async def write_cursor(pool, factory_id: str, platform: str, cursor: str) -> None:
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            await conn.execute(
                "INSERT INTO platform_sync_cursor(factory_id, platform, cursor_value) "
                "VALUES ($1, $2, $3) "
                "ON CONFLICT (factory_id, platform) DO UPDATE "
                "SET cursor_value = EXCLUDED.cursor_value, updated_at = NOW()",
                factory_id, platform, cursor,
            )
```

> ⚠️ `set_config(..., true)` 是**事务级**的，必须包在显式 `conn.transaction()` 里才生效。本仓踩过这个坑：asyncpg 上不开事务直接 `set_config(true)` 从不生效，RLS 靠连接池残留碰运气。

- [ ] **Step 5: 写框架**

```python
# backend/python/smartbi/ingestion/platforms/framework.py
"""拉取框架: 游标推进 / 幂等 / 失败隔离 / 禁降级。

禁降级在这里的含义: 拉不到或写不进, 都必须抛错并**保持游标不动**。
把失败当成「本轮无数据」再推进游标, 那批数据就永久丢了。
"""
from __future__ import annotations

import logging
from typing import Awaitable, Callable, List, Protocol

from .cursor_store import read_cursor, write_cursor
from .models import FetchPage, NormalizedOrder

logger = logging.getLogger(__name__)

DEFAULT_PAGE_SIZE = 200


class PlatformSyncError(RuntimeError):
    """本轮同步失败。调用方负责隔离: 一个平台失败不影响其他平台。"""


class PlatformAdapter(Protocol):
    platform: str

    async def fetch_page(self, cursor: str, limit: int) -> FetchPage: ...


WriteOrders = Callable[[object, str, List[NormalizedOrder]], Awaitable[int]]


async def sync_platform(pool, adapter: PlatformAdapter, *, factory_id: str,
                        write_orders: WriteOrders, max_pages: int = 20,
                        page_size: int = DEFAULT_PAGE_SIZE) -> int:
    """拉一轮增量。返回本轮写入的订单数。

    每页「先写入、后推进游标」: 写入是幂等的(平台单号唯一键), 崩在中间下轮重拉
    只会命中冲突不会重复计数; 反过来先推进游标就会漏数据。
    """
    cursor = await read_cursor(pool, factory_id, adapter.platform)
    total = 0
    for _ in range(max_pages):
        try:
            page = await adapter.fetch_page(cursor, page_size)
        except Exception as exc:  # noqa: BLE001 — 统一成 PlatformSyncError 供上层隔离
            raise PlatformSyncError(
                f"[{adapter.platform}] 拉取失败 cursor={cursor}: {exc}"
            ) from exc
        if page.orders:
            try:
                written = await write_orders(pool, factory_id, page.orders)
            except Exception as exc:  # noqa: BLE001
                raise PlatformSyncError(
                    f"[{adapter.platform}] 写入失败 cursor={cursor}: {exc}"
                ) from exc
            total += written
        cursor = page.next_cursor
        await write_cursor(pool, factory_id, adapter.platform, cursor)
        if not page.has_more:
            break
    else:
        logger.info("[%s] 本轮达到 max_pages=%d, 剩余留给下一轮", adapter.platform, max_pages)
    return total


async def sync_all(pool, adapters, *, factory_id: str, write_orders: WriteOrders) -> dict:
    """按平台逐个同步。**失败隔离**: 一个平台抛错不影响其余平台。

    返回 {platform: 写入数 或 错误字符串}。
    """
    results: dict = {}
    for adapter in adapters:
        try:
            results[adapter.platform] = await sync_platform(
                pool, adapter, factory_id=factory_id, write_orders=write_orders
            )
        except PlatformSyncError as exc:
            logger.error("[platform-sync] %s", exc)
            results[adapter.platform] = f"ERROR: {exc}"
    return results
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd backend/python && python -m pytest tests/test_platform_framework.py -v`
Expected: 4 passed

- [ ] **Step 7: Commit**

```bash
git add backend/python/smartbi/ingestion/platforms/ backend/python/tests/test_platform_framework.py
git commit -m "feat(platform-sync): connector 框架 — 游标/幂等/失败隔离/禁降级" -- backend/python/smartbi/ingestion/platforms/ backend/python/tests/test_platform_framework.py
```

---

### Task 8: 客如云 adapter + Silver writer

**Files:**
- Create: `backend/python/smartbi/ingestion/platforms/keruyun.py`
- Create: `backend/python/smartbi/ingestion/platforms/writer.py`
- Test: `backend/python/tests/test_keruyun_adapter.py`

**Interfaces:**
- Consumes: Task 7 的 `models` / `framework.PlatformAdapter`
- Produces:
  - `keruyun.sign(params: dict[str, str], app_secret: str) -> str`（必须与模拟端 `mock_platform.api._auth.keruyun_sign` 逐字节一致）
  - `keruyun.KeruyunAdapter(base_url: str, app_key: str, app_secret: str, client)`，属性 `platform = "keruyun"`
  - `keruyun.KeruyunBusinessError`
  - `writer.write_orders(pool, factory_id: str, orders: list[NormalizedOrder]) -> int`

- [ ] **Step 1: 先查 Silver 表真实结构（不能凭记忆写映射）**

Run:

```bash
ssh root@47.100.235.168 "PW=\$(grep '^SMARTBI_DB_PASSWORD=' /www/wwwroot/cretas/.env.prod | cut -d= -f2- | tr -d '\"'); export PGPASSWORD=\"\$PW\"; for t in fact_pos_transaction fact_pos_item fact_pos_payment dim_store; do echo \"=== \$t ===\"; psql -h localhost -U smartbi_user -d smartbi_prod_db -c \"\\d \$t\"; done"
```

> ⚠️ **2026-07-29 计划修正（实测结果，下面的 writer 代码已按此重写）**
>
> - `fact_pos_transaction` 实际列：`id / factory_id / upload_id / source_type / source_bill_no / store_id / staff_id / date / time / gross_amount / discount_amount / tax_amount / net_amount / actual_receive / customer_count / avg_per_capita / table_no / order_type / channel_origin / item_count / has_discount / meal_period`。**没有 `transaction_no`**；`source_type` 与 `source_bill_no` 均 NOT NULL。
> - `fact_pos_item` 靠 **`transaction_id`（bigint FK）** 关联，不是靠单号 + 日期 → writer 必须 `INSERT ... RETURNING id` 拿到主键再写明细。它另有 `product_id`（可空 FK）与 `source_item_raw`（菜名落这里）。
> - 幂等挂在 Task 6 建的**部分唯一索引** `uq_fact_pos_txn_mock_keruyun (factory_id, source_bill_no) WHERE source_type = 'mock_keruyun'` 上。`ON CONFLICT` 必须带同样的谓词才能命中部分索引。
> - 门店映射走 Task 6 建的 `platform_store_map`，**不是** `dim_store.store_code`（该列不存在）。
> - 金额列是 `NUMERIC(18,2)` 元，归一化模型是「分」→ 写入前除 100。

Expected: 打印四张表的完整列定义与主键/唯一约束，与上面的修正一致。若不一致（别的 session 改过表），停下来说明，不要硬写。

- [ ] **Step 2: 写 adapter 的失败测试**

```python
# backend/python/tests/test_keruyun_adapter.py
"""客如云 adapter: 签名互操作、业务错误码识别、字段映射。

签名互操作是最关键的一条 —— 两端算法必须逐字节一致, 否则线上表现为
「一直 401 但两边代码看起来都对」。这里直接 import 模拟端的实现对拍。
"""
import datetime
import json
import pathlib
import sys

import pytest

from smartbi.ingestion.platforms.keruyun import (
    KeruyunAdapter, KeruyunBusinessError, sign,
)

# 模拟端在仓库根的 mock-platform/ 下, 不是 backend/python 的包 —— 显式加路径。
_MOCK_ROOT = pathlib.Path(__file__).resolve().parents[3] / "mock-platform"
sys.path.insert(0, str(_MOCK_ROOT))


def test_签名与模拟端逐字节一致():
    from mock_platform.api._auth import keruyun_sign

    params = {"appKey": "k", "timestamp": "1785300000", "cursor": "0", "limit": "50"}
    assert sign(params, "sec") == keruyun_sign(params, "sec")


def test_签名排除sign本身与空值():
    base = {"appKey": "k", "timestamp": "1", "empty": ""}
    with_sign = dict(base, sign="whatever")
    assert sign(base, "s") == sign(with_sign, "s")


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload
        self.status_code = 200

    def json(self):
        return self._payload


class _FakeClient:
    def __init__(self, payload):
        self._payload = payload
        self.last_params = None

    async def get(self, url, params=None, timeout=None):
        self.last_params = params
        return _FakeResponse(self._payload)


@pytest.mark.asyncio
async def test_业务错误码被识别为失败_而不是当成空页():
    client = _FakeClient({"code": "AUTH_SIGN_INVALID", "message": "签名校验失败", "data": None})
    adapter = KeruyunAdapter("http://mock", "k", "s", client)
    with pytest.raises(KeruyunBusinessError, match="AUTH_SIGN_INVALID"):
        await adapter.fetch_page("0", 50)


@pytest.mark.asyncio
async def test_订单被正确归一化():
    payload = {
        "code": "0", "message": "success",
        "data": {
            "list": [{
                "orderNo": "MK2026072901000001", "shopCode": "MK01",
                "channel": "takeaway", "placedAt": "2026-07-29T12:05:00",
                "bizDate": "2026-07-29", "grossAmount": 12800,
                "discountAmount": 800, "netAmount": 12000, "guestCount": 1,
                "items": [{"dishName": "藤椒鸡", "qty": 2, "price": 5800, "amount": 11600}],
                "payments": [{"method": "platform", "amount": 12000}],
            }],
            "nextCursor": 42, "hasMore": False,
        },
    }
    adapter = KeruyunAdapter("http://mock", "k", "s", _FakeClient(payload))
    page = await adapter.fetch_page("0", 50)
    assert page.next_cursor == "42" and page.has_more is False
    order = page.orders[0]
    assert order.platform == "keruyun"
    assert order.platform_order_no == "MK2026072901000001"
    assert order.store_code == "MK01"
    assert order.biz_date == datetime.date(2026, 7, 29)
    assert order.placed_at == datetime.datetime(2026, 7, 29, 12, 5)
    assert (order.gross_cents, order.discount_cents, order.net_cents) == (12800, 800, 12000)
    assert order.items[0].dish_name == "藤椒鸡"
    assert order.payments[0].method == "platform"


@pytest.mark.asyncio
async def test_请求带上了签名参数():
    adapter = KeruyunAdapter("http://mock", "kk", "ss", _FakeClient(
        {"code": "0", "data": {"list": [], "nextCursor": 0, "hasMore": False}}))
    await adapter.fetch_page("7", 30)
    params = adapter._client.last_params
    assert params["appKey"] == "kk" and params["cursor"] == "7" and params["limit"] == "30"
    assert params["sign"] == sign({k: v for k, v in params.items() if k != "sign"}, "ss")
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd backend/python && python -m pytest tests/test_keruyun_adapter.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'smartbi.ingestion.platforms.keruyun'`

- [ ] **Step 4: 写 adapter**

```python
# backend/python/smartbi/ingestion/platforms/keruyun.py
"""客如云风格 adapter。

⚠️ 平台风格: HTTP 恒 200, 成败看业务 code。只看 status_code 会把失败当成功,
所以这里显式判 code != "0" 就抛错 —— 禁降级的具体落地。
"""
from __future__ import annotations

import datetime
import hashlib
import hmac
import time

from .models import FetchPage, NormalizedItem, NormalizedOrder, NormalizedPayment

PLATFORM = "keruyun"


class KeruyunBusinessError(RuntimeError):
    """平台返回了非 0 业务码。"""


def sign(params: dict, app_secret: str) -> str:
    """与模拟端 mock_platform.api._auth.keruyun_sign 必须逐字节一致。"""
    items = sorted(
        (k, str(v)) for k, v in params.items()
        if k != "sign" and v is not None and str(v) != ""
    )
    payload = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(
        app_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256
    ).hexdigest().lower()


class KeruyunAdapter:
    platform = PLATFORM

    def __init__(self, base_url: str, app_key: str, app_secret: str, client):
        self._base_url = base_url.rstrip("/")
        self._app_key = app_key
        self._app_secret = app_secret
        self._client = client

    async def fetch_page(self, cursor: str, limit: int) -> FetchPage:
        params = {
            "appKey": self._app_key,
            "timestamp": str(int(time.time())),
            "cursor": str(cursor),
            "limit": str(limit),
        }
        params["sign"] = sign(params, self._app_secret)
        resp = await self._client.get(
            f"{self._base_url}/keruyun/open/order/list", params=params, timeout=30.0
        )
        body = resp.json()
        code = str(body.get("code", ""))
        if code != "0":
            raise KeruyunBusinessError(
                f"{code}: {body.get('message')}"
            )
        data = body.get("data") or {}
        orders = [self._to_order(raw) for raw in data.get("list", [])]
        return FetchPage(
            orders=orders,
            next_cursor=str(data.get("nextCursor", cursor)),
            has_more=bool(data.get("hasMore", False)),
        )

    @staticmethod
    def _to_order(raw: dict) -> NormalizedOrder:
        return NormalizedOrder(
            platform=PLATFORM,
            platform_order_no=raw["orderNo"],
            store_code=raw["shopCode"],
            channel=raw["channel"],
            placed_at=datetime.datetime.fromisoformat(raw["placedAt"]),
            biz_date=datetime.date.fromisoformat(raw["bizDate"]),
            gross_cents=int(raw["grossAmount"]),
            discount_cents=int(raw["discountAmount"]),
            net_cents=int(raw["netAmount"]),
            guest_count=int(raw.get("guestCount", 1)),
            items=[
                NormalizedItem(
                    dish_name=i["dishName"], qty=int(i["qty"]),
                    price_cents=int(i["price"]), amount_cents=int(i["amount"]),
                )
                for i in raw.get("items", [])
            ],
            payments=[
                NormalizedPayment(method=p["method"], amount_cents=int(p["amount"]))
                for p in raw.get("payments", [])
            ],
        )
```

- [ ] **Step 5: 写 Silver writer**

列名已按实测校正（见 Step 1 的计划修正块），照下面写即可：

```python
# backend/python/smartbi/ingestion/platforms/writer.py
"""归一化订单 → Silver。

幂等: 用 fact_pos_transaction 上**现成的**唯一约束
      uq_fact_pos_txn (factory_id, source_type, store_id, source_bill_no)。
      不新建任何索引 —— 该表 1,382,267 行, 现成约束已够用。
      ⚠️ ON CONFLICT 的列清单必须与该约束**完全一致**(含 store_id), 否则
      Postgres 匹配不到约束会直接报错。
框架保证「先写入、后推进游标」, 崩在中间下轮重拉只会命中冲突, 不会重复计数。

门店映射走 platform_store_map(dim_store 没有 store_code 列)。
明细靠 transaction_id 外键关联 → 主表 INSERT 必须 RETURNING id。

金额: 归一化模型用「分」, Silver 是 NUMERIC(18,2) 元, 这里除 100。
"""
from __future__ import annotations

import logging
from decimal import Decimal
from typing import List

from .models import NormalizedOrder

logger = logging.getLogger(__name__)

# 模拟端的支付方式 → dim_payment_channel.name。
# 两边必须同时改: 这里加一项, V20261101_01 的 dim_payment_channel 种子也要加。
_CHANNEL_NAME = {
    "cash": "现金",
    "wechat": "微信",
    "alipay": "支付宝",
    "platform": "平台代收",
}


def _yuan(cents: int) -> Decimal:
    return (Decimal(cents) / Decimal(100)).quantize(Decimal("0.01"))


async def write_orders(pool, factory_id: str, orders: List[NormalizedOrder]) -> int:
    """写一批订单。返回实际新增数（已存在的不计）。"""
    if not orders:
        return 0
    written = 0
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            for order in orders:
                store_row = await conn.fetchrow(
                    "SELECT store_id FROM platform_store_map "
                    "WHERE factory_id = $1 AND platform = $2 AND platform_store_code = $3",
                    factory_id, order.platform, order.store_code,
                )
                if store_row is None:
                    # 禁降级: 门店映射不上就报错, 不建「未知门店」也不丢弃
                    raise RuntimeError(
                        f"门店映射失败: factory={factory_id} platform={order.platform} "
                        f"code={order.store_code} —— 检查 V20261101_01 的 "
                        f"platform_store_map 种子是否与模拟端 seed.py 的 _STORES 对齐"
                    )
                # RETURNING id: 明细表靠 transaction_id 外键关联, 必须拿到主键。
                # 命中部分唯一索引需要带上同样的 WHERE 谓词。
                txn_row = await conn.fetchrow(
                    "INSERT INTO fact_pos_transaction "
                    "(factory_id, store_id, source_type, source_bill_no, date, time, "
                    " gross_amount, discount_amount, net_amount, customer_count, "
                    " item_count, order_type) "
                    "VALUES ($1,$2,'mock_keruyun',$3,$4,$5,$6,$7,$8,$9,$10,$11) "
                    "ON CONFLICT (factory_id, source_type, store_id, source_bill_no) "
                    "DO NOTHING "
                    "RETURNING id",
                    factory_id, store_row["store_id"], order.platform_order_no,
                    order.biz_date, order.placed_at,
                    _yuan(order.gross_cents), _yuan(order.discount_cents),
                    _yuan(order.net_cents), order.guest_count, len(order.items),
                    order.channel,
                )
                if txn_row is None:
                    continue          # 已存在(冲突), 明细也不必重写
                txn_id = txn_row["id"]
                written += 1
                for item in order.items:
                    await conn.execute(
                        "INSERT INTO fact_pos_item "
                        "(transaction_id, factory_id, source_item_raw, "
                        " qty, unit_price, amount) VALUES ($1,$2,$3,$4,$5,$6)",
                        txn_id, factory_id, item.dish_name, item.qty,
                        _yuan(item.price_cents), _yuan(item.amount_cents),
                    )
                for pay in order.payments:
                    # fact_pos_payment 没有 method 文本列, 是 NOT NULL 的
                    # channel_id 外键 → 按 (factory_id, name) 查 dim_payment_channel。
                    channel_name = _CHANNEL_NAME.get(pay.method)
                    if channel_name is None:
                        raise RuntimeError(
                            f"未知支付方式 {pay.method!r} —— 需在 _CHANNEL_NAME 与 "
                            f"V20261101_01 的 dim_payment_channel 种子里同时补上"
                        )
                    ch_row = await conn.fetchrow(
                        "SELECT channel_id FROM dim_payment_channel "
                        "WHERE factory_id = $1 AND name = $2",
                        factory_id, channel_name,
                    )
                    if ch_row is None:
                        # 禁降级: 渠道查不到就报错, 不建「未知渠道」也不丢弃这笔支付
                        raise RuntimeError(
                            f"支付渠道映射失败: factory={factory_id} name={channel_name} "
                            f"—— 检查 V20261101_01 的 dim_payment_channel 种子"
                        )
                    await conn.execute(
                        "INSERT INTO fact_pos_payment "
                        "(transaction_id, factory_id, channel_id, amount) "
                        "VALUES ($1,$2,$3,$4)",
                        txn_id, factory_id, ch_row["channel_id"], _yuan(pay.amount_cents),
                    )
    logger.info("[platform-sync] 写入 %d/%d 笔订单 (factory=%s)",
                written, len(orders), factory_id)
    return written
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd backend/python && python -m pytest tests/test_keruyun_adapter.py tests/test_platform_framework.py -v`
Expected: 9 passed（adapter 5 + framework 4）

- [ ] **Step 7: Commit**

```bash
git add backend/python/smartbi/ingestion/platforms/keruyun.py backend/python/smartbi/ingestion/platforms/writer.py backend/python/tests/test_keruyun_adapter.py
git commit -m "feat(platform-sync): 客如云 adapter + Silver writer" -- backend/python/smartbi/ingestion/platforms/keruyun.py backend/python/smartbi/ingestion/platforms/writer.py backend/python/tests/test_keruyun_adapter.py
```

---

### Task 9: 回调端点（三层校验）+ 常驻拉取循环接线

**Files:**
- Create: `backend/python/smartbi/api/platform_callback.py`
- Modify: `backend/python/main.py`（import 区加 router，lifespan 里加 leader-gated 循环）
- Modify: `backend/python/auth_middleware.py`（把回调路径加进 `PUBLIC_PATHS`）
- Test: `backend/python/tests/test_platform_callback.py`

**Interfaces:**
- Consumes: Task 5 的 `mock_platform.callback.build_signature`（签名算法必须一致）、Task 7 的 `framework.sync_all`、Task 8 的 `KeruyunAdapter` / `write_orders`
- Produces:
  - `platform_callback.verify_signature(body: bytes, timestamp: str, nonce: str, signature: str, secret: str) -> bool`
  - `platform_callback.router`，挂 `POST /api/platform-callback/{platform}`
  - `platform_callback.CallbackRejected`

- [ ] **Step 1: 写失败测试**

```python
# backend/python/tests/test_platform_callback.py
"""回调端点三层校验: IP 白名单 / HMAC 验签 / 时间窗+nonce 防重放。

⚠️ 这个端点路径里没有 factoryId。本仓 2026-07-29 出过匿名访问事故 ——
登录校验挂在「URL 能否解析出 factoryId」上, 导致 /ai/* /upload/* 整类路径
对公网无鉴权。回调端点必须独立鉴权, 绝不重蹈。
"""
import time

import pytest

from smartbi.api.platform_callback import (
    CallbackRejected, check_replay, verify_signature,
)

SECRET = "test-secret"


def _sig(body: bytes, ts: str, nonce: str, secret: str = SECRET) -> str:
    import hashlib
    import hmac
    payload = ts.encode("ascii") + nonce.encode("ascii") + body
    return hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest().lower()


def test_验签算法与模拟端一致():
    import pathlib
    import sys
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / "mock-platform"))
    from mock_platform.callback import build_signature

    body, ts, nonce = b'{"maxSeq":9}', "1785300000", "abc"
    assert build_signature(body, ts, nonce, SECRET) == _sig(body, ts, nonce)


def test_正确签名通过():
    body, ts, nonce = b'{"maxSeq":1}', str(int(time.time())), "n1"
    assert verify_signature(body, ts, nonce, _sig(body, ts, nonce), SECRET) is True


def test_篡改body签名失败():
    ts, nonce = str(int(time.time())), "n1"
    good = _sig(b'{"maxSeq":1}', ts, nonce)
    assert verify_signature(b'{"maxSeq":999}', ts, nonce, good, SECRET) is False


def test_错误密钥签名失败():
    body, ts, nonce = b'{"maxSeq":1}', str(int(time.time())), "n1"
    bad = _sig(body, ts, nonce, "wrong-secret")
    assert verify_signature(body, ts, nonce, bad, SECRET) is False


def test_过期时间戳被拒():
    old = str(int(time.time()) - 600)          # 10 分钟前
    with pytest.raises(CallbackRejected, match="timestamp"):
        check_replay(old, "n-old", seen=set())


def test_未来时间戳也被拒():
    future = str(int(time.time()) + 600)
    with pytest.raises(CallbackRejected, match="timestamp"):
        check_replay(future, "n-future", seen=set())


def test_重放的nonce被拒():
    ts = str(int(time.time()))
    seen = set()
    check_replay(ts, "n-once", seen=seen)
    with pytest.raises(CallbackRejected, match="nonce"):
        check_replay(ts, "n-once", seen=seen)


def test_非法时间戳格式被拒():
    with pytest.raises(CallbackRejected, match="timestamp"):
        check_replay("not-a-number", "n", seen=set())
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest tests/test_platform_callback.py -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'smartbi.api.platform_callback'`

- [ ] **Step 3: 实现回调端点**

```python
# backend/python/smartbi/api/platform_callback.py
"""外部平台回调端点。

⚠️ 三层校验缺一不可, 且**必须独立鉴权** —— 本端点路径里没有 factoryId,
不能沿用「URL 能解析出 factoryId 才鉴权」那套 (2026-07-29 匿名访问事故根因)。

回调只是「有新数据」的触发器, 不携带业务数据: 回调丢一次由定时拉取兜底,
两条路指向同一个幂等写入。
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import os
import time
from typing import Optional, Set

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/platform-callback", tags=["Platform Callback"])

TIMESTAMP_WINDOW_SECONDS = 300
SUPPORTED_PLATFORMS = {"keruyun"}

# 进程内 nonce 去重。单 leader 拉取 + 短窗口, 内存集合足够;
# 多副本部署时换 Redis SETEX(nonce, 300)。
_SEEN_NONCES: Set[str] = set()


class CallbackRejected(RuntimeError):
    """回调被拒。消息里不回显密钥或签名细节。"""


def _allowed_ips() -> Set[str]:
    raw = os.getenv("PLATFORM_CALLBACK_ALLOWED_IPS", "")
    return {ip.strip() for ip in raw.split(",") if ip.strip()}


def _secret() -> str:
    value = os.getenv("PLATFORM_CALLBACK_SECRET", "").strip()
    if not value:
        # 禁降级: 没配密钥就拒绝服务, 绝不「没配就放行」
        raise CallbackRejected("PLATFORM_CALLBACK_SECRET 未配置")
    return value


def verify_signature(body: bytes, timestamp: str, nonce: str,
                     signature: str, secret: str) -> bool:
    payload = timestamp.encode("ascii") + nonce.encode("ascii") + body
    expected = hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest().lower()
    return hmac.compare_digest(expected, (signature or "").lower())


def check_replay(timestamp: str, nonce: str, seen: Optional[Set[str]] = None) -> None:
    """时间窗 + nonce 去重。不通过就抛 CallbackRejected。"""
    pool = _SEEN_NONCES if seen is None else seen
    try:
        ts = int(timestamp)
    except (TypeError, ValueError):
        raise CallbackRejected("timestamp 非法") from None
    if abs(int(time.time()) - ts) > TIMESTAMP_WINDOW_SECONDS:
        raise CallbackRejected("timestamp 超出允许窗口")
    if not nonce:
        raise CallbackRejected("nonce 缺失")
    if nonce in pool:
        raise CallbackRejected("nonce 已使用 (重放)")
    pool.add(nonce)


@router.post("/{platform}")
async def receive_callback(platform: str, request: Request):
    if platform not in SUPPORTED_PLATFORMS:
        return JSONResponse(status_code=404,
                            content={"success": False, "message": "未知平台", "data": None})
    client_ip = request.client.host if request.client else ""
    allowed = _allowed_ips()
    if allowed and client_ip not in allowed:
        logger.warning("[callback] 拒绝非白名单来源 %s", client_ip)
        return JSONResponse(status_code=403,
                            content={"success": False, "message": "来源不被允许", "data": None})
    body = await request.body()
    try:
        secret = _secret()
        check_replay(request.headers.get("X-Mock-Timestamp", ""),
                     request.headers.get("X-Mock-Nonce", ""))
    except CallbackRejected as exc:
        logger.warning("[callback] 拒绝: %s", exc)
        return JSONResponse(status_code=401,
                            content={"success": False, "message": str(exc), "data": None})
    if not verify_signature(body,
                            request.headers.get("X-Mock-Timestamp", ""),
                            request.headers.get("X-Mock-Nonce", ""),
                            request.headers.get("X-Mock-Signature", ""),
                            secret):
        logger.warning("[callback] 验签失败, 来源 %s", client_ip)
        return JSONResponse(status_code=401,
                            content={"success": False, "message": "验签失败", "data": None})
    # 只当触发器: 不解析业务数据, 交给拉取循环去拿。
    logger.info("[callback] %s 通知有新数据", platform)
    return {"success": True, "message": "ok", "data": {"platform": platform}}
```

- [ ] **Step 4: 把回调路径加进 PUBLIC_PATHS**

在 `backend/python/auth_middleware.py` 的 `PUBLIC_PATHS` 集合里加一条，并写清理由：

```python
    # 外部平台回调: 路径里没有 factoryId, 由端点自身做 IP 白名单 + HMAC 验签
    # + 时间窗/nonce 防重放三层校验 (smartbi/api/platform_callback.py)。
    # 走 PUBLIC_PATHS 是因为调用方是外部平台, 没有我们的 JWT —— 但它**不是**无鉴权。
    "/api/platform-callback/keruyun",
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend/python && python -m pytest tests/test_platform_callback.py -v`
Expected: 8 passed

- [ ] **Step 6: 接线常驻拉取循环**

在 `backend/python/main.py` 的 import 区（约 L83-92 那批 `from smartbi.api import ...` 附近）加：

```python
from smartbi.api import platform_callback  # noqa: E402  (外部平台回调, 2026-07-29)
```

在 `app.include_router(...)` 区加：

```python
app.include_router(platform_callback.router)
```

在 `lifespan()` 里、narrative pruner 那段之后，加 leader-gated 的拉取循环。**必须挂在 `_is_leader` 下**——多 worker 并发拉同一游标会重复写入并制造死锁风险（本仓已因此吃过 4× 并发 UPSERT 的亏）：

```python
    # ── 外部平台增量拉取 (2026-07-29) ────────────────────────────────
    # 只在 leader 上跑: 多 worker 并发拉同一游标会重复写入 + 死锁。
    _platform_sync_task = None
    if os.getenv("PLATFORM_SYNC_ENABLED", "").lower() in ("1", "true", "yes"):
        try:
            import asyncio as _asyncio_p
            import httpx as _httpx_p
            from smartbi.config import get_pg_pool as _get_pool_p
            from smartbi.ingestion.platforms.framework import sync_all
            from smartbi.ingestion.platforms.keruyun import KeruyunAdapter
            from smartbi.ingestion.platforms.writer import write_orders

            async def _sync_platforms_forever():
                await _asyncio_p.sleep(30)     # 让连接池先起来
                factory_id = os.getenv("PLATFORM_SYNC_FACTORY_ID", "MOCK_REST")
                base_url = os.getenv("PLATFORM_MOCK_BASE_URL", "")
                interval = int(os.getenv("PLATFORM_SYNC_INTERVAL_SECONDS", "60"))
                async with _httpx_p.AsyncClient() as client:
                    adapters = [KeruyunAdapter(
                        base_url,
                        os.getenv("PLATFORM_KERUYUN_APP_KEY", ""),
                        os.getenv("PLATFORM_KERUYUN_APP_SECRET", ""),
                        client,
                    )]
                    while True:
                        try:
                            pool = await _get_pool_p()
                            results = await sync_all(pool, adapters,
                                                     factory_id=factory_id,
                                                     write_orders=write_orders)
                            logger.info("[platform-sync] %s", results)
                        except Exception as ex:
                            logger.error(f"[platform-sync] 本轮失败: {ex}")
                        await _asyncio_p.sleep(interval)

            if _is_leader:
                _platform_sync_task = _asyncio_p.create_task(_sync_platforms_forever())
                logger.info("[leader] platform sync armed")
            else:
                logger.info("[follower] platform sync skipped (leader handles)")
        except Exception as e:
            logger.warning(f"[startup] platform sync init failed: {e}")
```

- [ ] **Step 7: 跑 import 冒烟，确认 main.py 没写坏**

Run: `cd backend/python && python -c "import main" 2>&1 | tail -5`
Expected: 无 traceback（可能有 warning，但不能有 ImportError / SyntaxError）

- [ ] **Step 8: 跑全量相关测试**

Run: `cd backend/python && python -m pytest tests/test_platform_callback.py tests/test_platform_framework.py tests/test_keruyun_adapter.py tests/test_demo_tenant_guard.py -v`
Expected: 全绿（回调 8 + 框架 4 + adapter 5 + demo guard 11 = 28 passed）

- [ ] **Step 9: Commit**

```bash
git add backend/python/smartbi/api/platform_callback.py backend/python/tests/test_platform_callback.py backend/python/main.py backend/python/auth_middleware.py
git commit -m "feat(platform-sync): 回调端点三层校验 + leader-gated 常驻拉取循环" -- backend/python/smartbi/api/platform_callback.py backend/python/tests/test_platform_callback.py backend/python/main.py backend/python/auth_middleware.py
```

---

### Task 10: 139 部署 + 端到端验收

**Files:**
- Create: `scripts/systemd/cretas-mock-platform.service`
- Create: `scripts/deploy/deploy-mock-platform.sh`
- Modify: `.claude/skills/server-operations/SKILL.md`（内容分布表加一行）

**Interfaces:**
- Consumes: 前 9 个任务的全部产物
- Produces: 139 上运行的 `cretas-mock-platform` 服务；47 上运行的拉取循环

- [ ] **Step 1: 写 systemd unit**

```ini
# scripts/systemd/cretas-mock-platform.service
[Unit]
Description=Cretas 餐饮外部平台模拟器 (139)
After=network.target

[Service]
Type=simple
WorkingDirectory=/www/wwwroot/mock-platform/code
EnvironmentFile=/www/wwwroot/mock-platform/.env
# ⚠️ 绑 127.0.0.1 不对外: 139 的阿里云安全组(账号 B)只放行 80/443/8086,
#    47→139 实测 9200/8085/8082 全 TIMEOUT。对外由 139 已有 nginx 从 80 反代 /mock/。
ExecStart=/www/wwwroot/mock-platform/venv311/bin/python -m mock_platform.cli serve --host 127.0.0.1 --port 9200
Restart=always
RestartSec=10
StandardOutput=append:/www/wwwroot/mock-platform/mock-platform.log
StandardError=append:/www/wwwroot/mock-platform/mock-platform.log

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: 写部署脚本**

```bash
#!/bin/bash
# scripts/deploy/deploy-mock-platform.sh
# 把模拟器部署到 139。⚠️ 只上 139，绝不上 47 —— 47 是我们的系统，
# 模拟器上 47 就破坏了「外部世界」的隔离前提。
set -eo pipefail

SERVER="root@139.196.165.140"
REMOTE_DIR="/www/wwwroot/mock-platform/code"
LOCAL_DIR="mock-platform"

echo "[1/5] 校验隔离铁律..."
if grep -rEn "smartbi|psycopg|asyncpg|smartbi_prod_db|cretas_prod_db" \
     "$LOCAL_DIR/mock_platform" --include="*.py"; then
    echo "错误: 模拟端泄漏了本系统依赖，拒绝部署"
    exit 1
fi

echo "[2/5] 本地跑测试..."
(cd "$LOCAL_DIR" && python -m pytest tests/ -q)

echo "[3/5] 同步代码到 139..."
ssh "$SERVER" "mkdir -p $REMOTE_DIR"
rsync -az --delete --timeout=60 \
    --exclude "__pycache__" --exclude ".pytest_cache" --exclude "*.db" \
    "$LOCAL_DIR/" "$SERVER:$REMOTE_DIR/"

echo "[4/5] 确保 python3.11 + 安装依赖 + 重启服务..."
# ⚠️ 139 出厂只有 python3.6 / python3.8, 没有 3.11(实测)。
#    alinux3-updates 源里有 python3.11-3.11.13-7.0.1.al8, 缺了就装。
ssh "$SERVER" "command -v python3.11 >/dev/null 2>&1 || yum install -y python3.11"
ssh "$SERVER" "cd $REMOTE_DIR && \
    (test -d /www/wwwroot/mock-platform/venv311 || python3.11 -m venv /www/wwwroot/mock-platform/venv311) && \
    /www/wwwroot/mock-platform/venv311/bin/pip install -q -r requirements.txt && \
    systemctl restart cretas-mock-platform"

echo "[5/7] 装 nginx 反代 /mock/ ..."
# ⚠️ 139 是**宝塔** nginx: 没有 /etc/nginx, 配置在 /www/server/nginx/,
#    vhost 在 /www/server/panel/vhost/nginx/*.conf 且由 nginx.conf 在 **http 层**
#    include —— 所以 location 不能单独扔一个文件进那个目录(那是 server 上下文)。
#    正确做法: location 体写进自己的 snippet 文件, 再往接管裸 IP:80 的
#    0.default.conf 里幂等插一行 include。⛔ 那是共享生产 vhost, 必须
#    锚在最后一个顶格 } 上 + nginx -t 失败回滚, 详见脚本注释与实现。
#    (以上每一条都实测过; 早前写 /etc/nginx/conf.d 的版本在 139 上会 cat 失败,
#     而后面的 `nginx -t && nginx -s reload` 照样 exit 0 => 全绿但零反代。)

echo "[6/6] 健康检查..."
# ⚠️ 判据必须是 generator=running, 不能只看 status=ok ——
#    生成器被 GC / 抛异常退出时 healthz 会回 {"status":"degraded","generator":"stopped"},
#    而 not_armed 那一档也是 status=ok。用 ok 做验收会在死掉的生成器上通过。
for i in $(seq 1 15); do
    if ssh "$SERVER" "curl -fsS -m 3 http://localhost:9200/healthz 2>/dev/null" \
         | grep -q '"generator":"running"'; then
        echo "✅ 模拟器健康 (生成器在跑)"
        # 再验一次公网路径, 否则 47 拉不到但本地健康检查照样绿
        ssh "$SERVER" "curl -fsS -m 5 http://139.196.165.140/mock/healthz" \
          | grep -q '"generator":"running"' \
          && { echo "✅ nginx /mock/ 反代通"; exit 0; }
        echo "❌ 本地 9200 健康但 nginx /mock/ 不通"
        exit 1
    fi
    sleep 2
done
echo "❌ 健康检查失败 (generator 未 running)"
ssh "$SERVER" "curl -s -m 3 http://localhost:9200/healthz; tail -30 /www/wwwroot/mock-platform/mock-platform.log"
exit 1
```

Run: `chmod +x scripts/deploy/deploy-mock-platform.sh`

- [ ] **Step 3: 在 139 上准备环境变量与目录**

```bash
ssh root@139.196.165.140 "mkdir -p /www/wwwroot/mock-platform && \
  printf '%s\n' \
    'MOCK_DB_PATH=/www/wwwroot/mock-platform/data.db' \
    \"MOCK_KERUYUN_APP_KEY=\$(openssl rand -hex 8)\" \
    \"MOCK_KERUYUN_APP_SECRET=\$(openssl rand -hex 24)\" \
    \"MOCK_CALLBACK_SECRET=\$(openssl rand -hex 24)\" \
    'MOCK_CALLBACK_URL=http://47.100.235.168:8083/api/platform-callback/keruyun' \
    'MOCK_STORE_COUNT=10' \
    'MOCK_ORDERS_PER_STORE=200' \
    > /www/wwwroot/mock-platform/.env && \
  chmod 600 /www/wwwroot/mock-platform/.env && \
  cat /www/wwwroot/mock-platform/.env"
```

把打印出来的 `MOCK_KERUYUN_APP_KEY` / `MOCK_KERUYUN_APP_SECRET` / `MOCK_CALLBACK_SECRET` 三个值记下来——下一步 47 侧要配同样的。**这三个值不进 git**，按本仓凭证规范记到 `.claude/skills/server-operations/db-credentials.md`（该文件已 gitignored）。

- [ ] **Step 4: 装 unit 并部署**

```bash
scp scripts/systemd/cretas-mock-platform.service root@139.196.165.140:/etc/systemd/system/
ssh root@139.196.165.140 "systemctl daemon-reload && systemctl enable cretas-mock-platform"
./scripts/deploy/deploy-mock-platform.sh
```

Expected: `✅ 模拟器健康`

- [ ] **Step 5: 回填 30 天历史**

```bash
ssh root@139.196.165.140 "cd /www/wwwroot/mock-platform/code && \
  set -a && . /www/wwwroot/mock-platform/.env && set +a && \
  /www/wwwroot/mock-platform/venv311/bin/python -m mock_platform.cli backfill --days 30"
```

Expected: 日志 `[backfill] 造出 60000 单，覆盖过去 30 天`（10 店 × 200 单 × 30 天）

- [ ] **Step 6: 配 47 侧环境变量并部署**

把 Step 3 的三个值写进 `/www/wwwroot/cretas/.env.prod`（追加，不覆盖已有内容）：

```
PLATFORM_SYNC_ENABLED=1
PLATFORM_SYNC_FACTORY_ID=MOCK_REST
PLATFORM_SYNC_INTERVAL_SECONDS=60
PLATFORM_MOCK_BASE_URL=http://139.196.165.140/mock
PLATFORM_KERUYUN_APP_KEY=<Step 3 的值>
PLATFORM_KERUYUN_APP_SECRET=<Step 3 的值>
PLATFORM_CALLBACK_SECRET=<Step 3 的 MOCK_CALLBACK_SECRET>
PLATFORM_CALLBACK_ALLOWED_IPS=139.196.165.140
```

然后按本仓规范从 `main` 部署（migration 由 Step 3.5 自动 apply）：

```bash
git checkout main && git pull origin main
LC_ALL=C ./scripts/deploy/deploy-smartbi-python.sh --env prod
```

- [ ] **Step 7: 核对 migration 与游标表**

```bash
ssh root@47.100.235.168 "PW=\$(grep '^SMARTBI_DB_PASSWORD=' /www/wwwroot/cretas/.env.prod | cut -d= -f2- | tr -d '\"'); export PGPASSWORD=\"\$PW\"; \
  psql -h localhost -U smartbi_user -d smartbi_prod_db -tAc \"SELECT filename FROM smartbi_migrations ORDER BY applied_at DESC LIMIT 2;\"; \
  psql -h localhost -U smartbi_user -d smartbi_prod_db -tAc \"SELECT set_config('app.factory_id','__internal__',false); SELECT count(*) FROM dim_store WHERE factory_id='MOCK_REST';\""
```

Expected: tracker 首条是 `V20261101_01__platform_sync_and_mock_tenant.sql`；`dim_store` 中 `MOCK_REST` 有 **10** 行

- [ ] **Step 8: 端到端验收 — 数据真的在动**

隔两分钟连查两次交易数，必须增长：

```bash
ssh root@47.100.235.168 "PW=\$(grep '^SMARTBI_DB_PASSWORD=' /www/wwwroot/cretas/.env.prod | cut -d= -f2- | tr -d '\"'); export PGPASSWORD=\"\$PW\"; \
  psql -h localhost -U smartbi_user -d smartbi_prod_db -tAc \"SELECT set_config('app.factory_id','MOCK_REST',false); SELECT count(*) FROM fact_pos_transaction WHERE factory_id='MOCK_REST';\"; \
  sleep 120; \
  psql -h localhost -U smartbi_user -d smartbi_prod_db -tAc \"SELECT set_config('app.factory_id','MOCK_REST',false); SELECT count(*) FROM fact_pos_transaction WHERE factory_id='MOCK_REST';\""
```

Expected: 第二个数 > 第一个数（**仅在营业时段成立**——11:00–14:00 或 17:00–21:00 之外曲线为 0，不会有新单，这不是 bug）

- [ ] **Step 9: 端到端验收 — 问答链路答得对**

对 `MOCK_REST` 租户问一句本月营收，确认数字与 Silver 对得上：

```bash
ssh root@47.100.235.168 "PORT=\$(ss -tln | grep -oE ':(10010|10020)' | head -1 | tr -d ':'); \
  curl -s -X POST \"http://localhost:\$PORT/api/mobile/MOCK_REST/ai-intents/execute\" \
    -H 'Content-Type: application/json' \
    -H \"Authorization: Bearer \$TOKEN\" \
    -d '{\"userInput\":\"本月全部门店营收多少\",\"sessionId\":\"mock-e2e\"}'"
```

Expected: 返回真实营收数字，且与上一步 `SELECT SUM(net_amount)` 一致。**若返回澄清问句，说明门店范围词缺失——但这里问句已含「全部门店」，若仍澄清则是 `dim_store` 种子没落对。**

- [ ] **Step 10: 更新 server-operations skill 的内容分布表**

在 `.claude/skills/server-operations/SKILL.md` 的「内容分布 — 禁止搞混」表里加一行：

```markdown
| **餐饮平台模拟器** | **139** (网关) | `/www/wwwroot/mock-platform/` | `139.196.165.140/mock/` (nginx 反代 → 127.0.0.1:9200) |
```

并在「本地目录 → 服务器路径映射」表加：

```markdown
| `mock-platform/` | **139** (网关) | `/www/wwwroot/mock-platform/code/` |
```

- [ ] **Step 11: Commit**

```bash
git add scripts/systemd/cretas-mock-platform.service scripts/deploy/deploy-mock-platform.sh .claude/skills/server-operations/SKILL.md
git commit -m "feat(mock-platform): 139 部署脚本 + systemd unit + 运维文档" -- scripts/systemd/cretas-mock-platform.service scripts/deploy/deploy-mock-platform.sh .claude/skills/server-operations/SKILL.md
```

---

## 完成判据

全部 10 个任务做完后，下面每一条都要能实际验证，不能只是"应该可以"：

1. `cd mock-platform && python -m pytest tests/ -v` 全绿，其中**隔离测试必须过**
2. `cd backend/python && python -m pytest tests/test_platform_*.py tests/test_keruyun_adapter.py -v` 全绿
3. 139 上 `systemctl is-active cretas-mock-platform` 为 `active`
4. **从 47 上**跑 `curl http://139.196.165.140/mock/healthz`，返回里含 `"generator":"running"`
   - ⚠️ 端口是 **80 的 `/mock/`**，不是 9200：9200 绑 127.0.0.1，且 47→139:9200 实测 TIMEOUT
   - ⚠️ 判据是 **`generator: running`**，不是 `status: ok`：没挂生成器时那一档也返回
     `{"status":"ok","generator":"not_armed"}`，用 `status:ok` 会在一个不产数据的服务上通过
   - ⚠️ 必须**从 47 发起**：从开发机 curl 通只证明"互联网上某处能访问"，
     证明不了拉取端能访问，而 47→139 恰是实测会出问题的方向
5. 营业时段内隔 2 分钟查两次 `fact_pos_transaction`，`MOCK_REST` 的行数在增长
6. 对 `MOCK_REST` 问「本月全部门店营收多少」，返回的数字与 `SELECT SUM(net_amount)` 对得上
7. 用错误签名调模拟端 API，返回 `AUTH_SIGN_INVALID`（不是 500，也不是放行）
8. 用错误签名打 47 的回调端点，返回 401（不是 200）

## 后续计划（不在本计划范围）

- **计划 B**：团购核销与外卖结算线（美团/抖音风格 API + `fact_channel_settlement` + 渠道真实利润）
- **计划 C**：点评内容线（大众点评/抖音评价 API + 现有 `restaurant_reviews` 落点）

两者都依赖本计划的世界模型与 connector 框架落地后，才能写出引用真实签名的任务卡。
