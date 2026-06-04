# 餐饮 #61 Phase 1 — POS 菜品名称解析回填 (POS dish-name resolution backfill)

**日期**: 2026-06-04
**Issue**: Cretas restaurant feature #61, Phase 1 (取数入口集成 Phase 2 + 组织KPI Phase 3 DEFERRED — 需邓总确认二维火 API 可行性 + 组织结构)
**目标**: 解锁 #57 成本卡片准确性 —— 把 POS 导出的菜名解析/绑定到 cretas `product_types`，让财务 ETL Stage 3 (COGS) 能为更多 POS 菜品行写 COST 行。

---

## 1. 背景 & 关键发现 (在 origin/main 上实测，纠正 brief 的 stale-tree 假设)

Brief 假设 `dim_product_alias` 需在 **smartbi_db** 新建。origin/main 实际情况:

1. **`dim_product_alias` 已存在于 cretas_db** (不是 smartbi_db)。由 `smartbi/api/restaurant_ops_recipes.py` 内联 `CREATE TABLE IF NOT EXISTS` 建表，schema 极简:
   ```
   (id BIGSERIAL PK, factory_id VARCHAR(100), pos_name VARCHAR(500),
    product_type_id VARCHAR(100), created_at, UNIQUE(factory_id, pos_name))
   ```
   无 confidence / source / decided_by_agent / admin 字段 / RLS。
2. **财务 ETL `restaurant_finance_etl.py` Stage 3 (`_resolve_pos_to_product_types`) 从 `cretas_pool` 读 `dim_product_alias`** (try/except fallback，表不存在时静默跳过)。所以别名表**必须留在 cretas_db** —— ETL 在哪读，alias 就得写在哪，否则成本卡片解锁无效。
3. **smartbi migration runner (`apply-smartbi-migrations.sh`) 只管 smartbi_db / smartbi_prod_db** (`sudo -u postgres psql -d smartbi_db`)。cretas_db schema 由 Java/Hibernate + 内联 DDL 管理，**没有 Python 迁移路径**。
4. **id 类型不匹配**: `entity_resolution_history.b_entity_id` 是 BIGINT NOT NULL；`product_types.id` 是 **VARCHAR(100) UUID**。smartbi Orchestrator / DimResolver / TransitiveAgent 整套机制围绕整数 dim id (`dim_*` 表 + `<type>_id` 整数列) 设计，无法直接承载字符串 product UUID。

**结论**: gap 未完全闭合 (无 resolver pipeline / 无未解析队列 / 无模糊匹配自动接受 / 无审计 / 无回填)，但表已存在。**不 STOP，继续 Phase 1，按纠正后的架构实现，并明确记录偏离。**

---

## 2. 架构决策 (the one real fork)

| 关注点 | DB | 机制 |
|---|---|---|
| `dim_product_alias` (升级: +confidence, +source, +decided_by_agent, +admin_user, +admin_at, +updated_at) | **cretas_db** (ETL 读的地方) | resolver 运行时 idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (沿用既有内联 DDL 模式)。**不是** smartbi 迁移。 |
| `restaurant_pos_unresolved_queue` (未解析队列) | **smartbi_db** | smartbi 迁移 (runner 管理, RLS ENABLE+FORCE+policy + GRANT DML + sequence grant) |
| pos_dish 审计 | **smartbi_db** `entity_resolution_history` | smartbi 迁移 ALTER entity_type CHECK 加 `'pos_dish'` (保留 store/product/staff/dish)。BIGINT `b_entity_id` 用确定性正 BIGINT 代理 (md5 hash → bigint)，真实 string product_type_id 存 `b_name` + reasoning。pos_dish 行不参与跨类型 transitive 整数链 (它是独立 id 空间，正确)。 |
| **L4 transitive** | cretas (over `dim_product_alias`) | 对**别名表自身**的有界 transitive 查找: 若 pos_name N normalize 后等于某已确认别名的 normalized key，继承其 product_type_id (conf 0.80 × 0.95 discount)。复用 TransitiveAgent 的*概念*，不强套 BIGINT 机制。 |

**为何审计放 smartbi 而非 cretas**: `entity_resolution_history` 在 smartbi (PR #389 graduation 模式同源)；cretas 无 entity_resolution 基础设施。审计/队列是分析/admin 关注点，天然 smartbi 侧。

---

## 3. 迁移 (smartbi, MY PRE-SEGREGATED BLOCK V20260924 — 无碰撞，frontier=V20260920)

### V20260924_01__restaurant_pos_unresolved_queue.sql
```sql
CREATE TABLE IF NOT EXISTS restaurant_pos_unresolved_queue (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(100) NOT NULL,
    pos_name TEXT NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    revenue_at_risk NUMERIC(14,2) NOT NULL DEFAULT 0,
    best_candidate_id VARCHAR(100),
    best_candidate_name TEXT,
    best_confidence NUMERIC(3,2),
    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','confirmed','rejected','skipped')),
    admin_user VARCHAR(100),
    admin_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (factory_id, pos_name)
);
-- RLS ENABLE + FORCE + tenant_isolation policy on app.factory_id GUC
-- GRANT SELECT,INSERT,UPDATE,DELETE TO smartbi_user
-- GRANT USAGE,SELECT ON SEQUENCE restaurant_pos_unresolved_queue_id_seq TO smartbi_user
-- idx pending: (factory_id, status, revenue_at_risk DESC) WHERE status='pending'
```

### V20260924_02__entity_resolution_history_pos_dish_type.sql
DROP + re-ADD `entity_resolution_history_entity_type_check`，CHECK IN
`('store','product','staff','dish','pos_dish')` (保留当前全部 4 值 + 加 pos_dish)。
仅 history 表 (queue/labels 不需要 pos_dish — pos_dish 审计只走 history)。

> **注**: brief 说 3 个迁移 (V20260924_01 alias / _02 queue / _03 entity_type)。
> 因 alias 在 cretas (无迁移路径)，alias schema 升级走 resolver 运行时 ALTER，
> 故 smartbi 迁移只需 2 个: _01 queue, _02 entity_type。**这是有意偏离 brief，已记录。**

---

## 4. resolver — `backend/python/smartbi/gold/pos_name_resolver.py`

```python
async def ensure_alias_schema(cretas_pool, factory_id) -> None:
    # idempotent CREATE TABLE IF NOT EXISTS (沿用 recipes.py)
    # + ALTER TABLE ADD COLUMN IF NOT EXISTS confidence/source/decided_by_agent/
    #   admin_user/admin_at/updated_at
    # source CHECK: admin_confirmed/fuzzy_match/transitive/llm (用 NOT VALID 或 trigger? — 用列默认 + app 层校验，避免改既有行)

async def resolve_factory_pos_names(cretas_pool, smartbi_pool, factory_id) -> dict:
    # 1. 读 POS 菜名 (来源同 finance ETL: agg/fact POS distinct dish_name + revenue/occurrence)
    # 2. 读 dim_product_alias 已有 (L0)
    # 3. 读 product_types name→id (L1)
    # 4. 逐 unresolved name 跑 5 层:
    #    L0 alias exact → 已解析跳过
    #    L1 product_types exact (==) → conf 1.0, 不写 alias (ETL 主路径已命中 exact name)
    #    L2 _normalize_name 相等 → conf 0.95, ≥0.90 自动写 alias source=fuzzy_match
    #    L3 difflib SequenceMatcher ratio:
    #         ≥0.85 → auto-accept 写 alias source=fuzzy_match
    #         0.60–0.85 → unresolved_queue pending + best_candidate
    #         <0.60 → unresolved_queue pending 无 best_candidate
    #    L4 transitive-over-alias: normalize key 命中已确认别名 → 继承 conf 0.80*0.95=0.76 → 队列 (不自动写, 低于 0.85)
    #    其余 → unresolved_queue
    # 5. 队列按 revenue_at_risk DESC upsert (ON CONFLICT factory_id,pos_name DO UPDATE)
    # 返回 {resolvedAuto, queued, totalPosNames, ...} real counts
```

阈值 (brief defaults): auto-accept ≥0.85, queue 0.60–0.85, unresolved <0.60。
复用 `_normalize_name` / `_set_tenant` (verbatim from `restaurant_ops_etl.py`)。
revenue_at_risk = 该 POS 菜名累计 revenue (未解析 = 无 COGS 行 = 利润被高估的风险)。

---

## 5. admin API — `backend/python/smartbi/api/restaurant_name_resolution_admin.py`

router prefix `/api/smartbi/restaurant/name-resolution`，全部 `require_admin`:

| 端点 | 行为 |
|---|---|
| `GET /unresolved` | 列 pending (status='pending') sorted revenue_at_risk DESC |
| `POST /confirm` `{pos_name, product_type_id}` | 校验 product_type 属本租户 → 写 cretas `dim_product_alias` (conf=1.0, source=admin_confirmed, decided_by_agent='admin', admin_user, admin_at) + 写 smartbi `entity_resolution_history` (pos_dish, BIGINT 代理, audit) → 队列行 status=confirmed → **fail-soft** 后台 `run_full_finance_etl_with_retry` 增量重跑 + `_purge_indicator_cache_for_factory` |
| `POST /reject` `{pos_name}` | 队列行 status=rejected |
| `POST /skip` `{pos_name}` | 队列行 status=skipped |
| `POST /run-backfill` | 触发 `resolve_factory_pos_names`，返回 real counts |
| `GET /stats` | 已匹配 X / 总 Y / 覆盖率 Z% (product_types exact + alias 覆盖的 POS 菜名 vs 总 distinct POS 菜名) |

确认幂等: alias upsert ON CONFLICT，history upsert ON CONFLICT，重复 confirm 不重复创建。
后台重跑 fail-soft: `asyncio.create_task` 包 try/except，不 doom confirm response。
在 `main.py` 注册 router。

---

## 6. web-admin "菜品名称匹配" tab

KPI 卡 (已匹配 X/Y, 覆盖率 Z%) + 未解析队列表 (pos_name + best_candidate + confidence + 确认/拒绝/跳过 + bulk-confirm) + 防呆 Rule1 (确认后自动重跑 ETL ~30s 提示) + low-confidence warning badge。
**若 web-admin 工时偏大，可 defer UI 到 follow-up 并标注 —— API + resolver 是 P1 核心价值。**

---

## 7. 测试 (TDD)

`backend/python/smartbi/tests/test_pos_name_resolver.py`:
- L0 alias exact → 跳过 (已解析)
- L1 product_types exact → conf 1.0
- L2 normalized 相等 → conf 0.95 auto 写 alias
- L3 high-overlap (≥0.85) → auto-accept 写 alias
- L3 mid-overlap (0.60–0.85) → queue + best_candidate
- L3 no-match (<0.60) → queue 无 alias + revenue_at_risk
- L4 transitive-over-alias → 继承 → queue (0.76 < 0.85 不自动)
- queue 按 revenue_at_risk DESC
- admin confirm 写 alias + history (幂等)

mock cretas_pool / smartbi_pool (asyncpg 接口)。pytest real counts，flake8 clean。

---

## 8. HARD RULES 遵守

- 别名表确认已存在 (cretas)，不创建碰撞表。
- 新 smartbi 表 GRANT DML + sequence (recurring grant-gap) + RLS ENABLE+FORCE+policy。
- 迁移走 deploy-smartbi-python.sh runner，幂等 (CREATE IF NOT EXISTS, ON CONFLICT)。不手动 psql。
- 后台 ETL 重跑 fail-soft，不 doom。
- explicit-path commits + `git status --short`。无 emoji。Co-author trailer。
- commit + push `feat/restaurant-pos-name-resolution`。**不 merge，不 deploy。**

---

## 9. DEFERRED (Phase 2/3)

- 取数入口集成 (Phase 2) — 需邓总确认二维火 API 可行性
- 组织 KPI (Phase 3) — 需组织结构
- 供应商价格预警 — 不在 Phase 1
- 二维火 integration — 不在 Phase 1
