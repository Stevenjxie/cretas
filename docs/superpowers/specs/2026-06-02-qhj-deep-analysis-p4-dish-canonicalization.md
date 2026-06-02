# P4 — 菜名归一 (跨店菜品 Canonical) 设计 Spec

**日期**: 2026-06-02
**作者**: 资深架构师 (workflow 产出)
**状态**: DRAFT — 待 review
**范围**: 青花椒 (qhj / RES_3101_009) 深度分析 P4 专项 — 跨店菜品 canonical 归一
**关联**: P1 (内容质量审计) / P2 (销售+评价 16 问答 gold 层) / P3 (出成率, 不相关)
**前置教训**: 仓库 `.claude/rules/fool-proof-design.md` (诚实标注 + relabel) / `python-java-port.md` (本模板用 `float()` 即可) / MEMORY #389/#390 (实体解析确认要写 history 不只 queue; 新表必 GRANT DML; entity_resolution 表 FORCE-RLS 脆弱) / `feedback_smartbi_table_grant_gap.md`

---

## 0. 一句话问题陈述

> 不同门店把**同一道菜**叫**不同名字** —— "招牌青花椒鱼(单人份)" vs "青花椒味鱼" vs "#招牌青花椒鱼(微麻微辣)(一吃)#" —— 系统当成 3 道不同菜，导致：
> 1. 销量 / 营收按"碎片菜名"分散，畅销菜排行失真 (一道菜被拆成 N 行，每行销量都偏低)
> 2. 评价 (按菜名) 跟销售 (按菜名) 对不上，无法回答"卖得最好的菜评价怎么样"
> 3. 统一菜单分析做不了 (集团 8 店没有一份共享菜单字典)

这是客户提的**最大最难**问题。本 spec 是独立专项。

---

## 1. 现状审计 (代码实锤)

### 1.1 已有的两套**互不连通**的归一系统

| 系统 | 文件 | 技术栈 | 作用域 | 表 | RLS | 状态 |
|---|---|---|---|---|---|---|
| **A. restaurant_dish_alias** (改进 1) | `shared/alias_normalizer.py` + `services/restaurant/menu_normalizer.py` | 同步 SQLAlchemy `Session` | **单工厂 `original_name` → `canonical_name`** (docstring 明示**不跨店**) | `restaurant_dish_alias` + `alias_review_queue` | ❌ 无 RLS (旧表, VARCHAR(64) factory_id) | 有规则层 + SequenceMatcher 相似度 + 人工审核 `confirm_merge` + `apply(df)` 替换 |
| **B. entity_resolution_***  (数据织网 Sub-Project B) | `canonical/entity_resolution/orchestrator.py` + `agents/*` | 异步 asyncpg `Pool` | 把**单个 raw_name** 解析到**已存在的** `dim_*` 行 | `entity_resolution_history` / `_admin_queue` / `_labels` | ✅ FORCE RLS | 5 agents (deterministic / contextual / embedding / transitive / llm_arbitrator) + admin queue UI + history 审计 |

**两套系统从不互相调用**。A 是给"分析时 df 列替换"用的；B 是给"silver writer 把 raw 行落到 dim 行"用的。

### 1.2 `dim_product` 没有 canonical / alias 层 (核心缺陷)

`2026_04_28_silver_dimensions.sql` line 74-85:

```sql
CREATE TABLE dim_product (
    product_id       BIGSERIAL PRIMARY KEY,
    factory_id       VARCHAR(50) NOT NULL,
    name             VARCHAR(500) NOT NULL,
    normalized_name  VARCHAR(500) NOT NULL,
    category         VARCHAR(100),
    ...
    CONSTRAINT uq_dim_product_factory_normname UNIQUE (factory_id, normalized_name)
);
```

- **唯一键 = `(factory_id, normalized_name)`** —— 只有 `normalized_name` **完全相同**的两个 raw name 才会合并成一行。
- `normalized_name` 由 `deterministic.py:normalize_for_dim()` 产生：仅做**繁→简 + 去标点 + 空白折叠 + ASCII 小写**。它**不去份量/口味/吃法后缀**，也**不跨语义合并**。
- 结果：`招牌青花椒鱼` 和 `青花椒味鱼` 各自一行 dim_product；`招牌青花椒鱼(单人份)` 去标点后 = `招牌青花椒鱼单人份` 又是另一行。**一道菜 N 个 product_id**。

### 1.3 entity_resolution 的 PRODUCT 路径做的是"raw → 已存在 dim 行"，**不是 dim 行之间合并**

- `deterministic.py` line 86-101: PRODUCT 走 `WHERE normalized_name = $2` 精确查 `dim_product`。
- `embedding.py` / `contextual.py`: 对 `dim_product` 全表 (LIMIT 1000) 算 cosine，返回最相似的**已存在 product_id**。
- `EntityType` enum (orchestrator.py line 14-19) 只有 `STORE / PRODUCT / STAFF` —— **没有 DISH/CANONICAL**。
- `entity_resolution_labels` / `_admin_queue` / `_history` 的 `CHECK (entity_type IN ('store','product','staff', ...))` —— product 已支持，但语义是"门店级 SKU 行"，**不是跨店 canonical dish**。

**结论**：entity_resolution 的 product 解析能把 raw review 名映射到一个 dim_product 行，但**永远不会把两个 dim_product 行判定为同一道菜**。跨店合并这一层**完全不存在**。

### 1.4 `unmatched_product_names` 是个死胡同 (JSONB 垃圾桶)

`review_writer.py` line 99-110: 评价行里的 `product_name` 若 `_resolve_product` 返回 None →

```python
unmatched_product_names.append(str(product_name))
admin_queue_count += 1
```

最终塞进 `dim_review_summary.unmatched_product_names` (JSONB)。**没有任何回填路径**：这些名字进了 JSONB 就再也出不来，`product_id` 永远 NULL。评价 ↔ 销售按菜对不上的根因之一。

### 1.5 P2 gold 层与 raw 聊天层都按"碎片菜名"聚合

- 经营驾驶舱畅销菜 / 慢销菜 (P2 Phase 1) 走 gold/物化模板，按 `dim_product` 或 raw POS `商品名称` 列聚合。
- `dish_name_normalizer.py` (本任务 Read 的) 只**单店去括号后缀** (`_VARIANT_SUFFIX_RE`，单条 trailing paren)，且**纯字符串、不跨店、不持久化** —— top-N 临时折叠，治标。
- `dish_classifier.py` 返回**品类** (主菜/饮品/...) **不是 canonical 菜名**。是分类不是归一。

---

## 2. 设计目标 & 非目标

### 目标
1. 建一个**跨店 canonical dish 实体层**：8 店所有菜名 → 一份集团级 canonical 菜品字典。
2. 用**已有的 entity_resolution 5-agent 框架** (规则精确名 + 向量语义 + LLM 仲裁) 做 dish 合并，不重造轮子。
3. **人工确认毕业**：客户审核合并提议 → 确认结果写 `entity_resolution_history` (per #389) + canonical 映射表，下次同名 0-agent 命中。
4. **回填** review ↔ sales 的 `canonical_dish_id`，消化 `unmatched_product_names`。
5. **解锁分析**：评价 × 销售按 canonical 菜关联 + 集团统一菜单分析。

### 非目标 (诚实划界)
- ❌ 不训练神经网络模型 (per MEMORY 垂直模型裁决：现在绝不训，攒蒸馏语料即可)。
- ❌ 不自动合并 (per #364 教训：**绝不静默自动毕业**；高置信也只 propose，人工拍板)。
- ❌ 不替换/废弃 `restaurant_dish_alias` (改进 1) —— 它服务"分析时 df 替换"的旧路径；P4 在其**之上**建 canonical 实体层，两者通过迁移桥接 (§5.3) 但不强行二合一 (避免高 blast radius 重写)。
- ❌ 不做菜品成本/BOM 归一 (那是 recipe 层，另立项)。
- ❌ 不碰 STORE/STAFF 解析 (本专项只 dish)。

---

## 3. 架构决策

### 决策 1 — canonical 层用**新 `entity_type='dish'`** 而非复用 `'product'`

**为什么不复用 product**：
- `product` 语义已固化为"门店级 SKU 行 (dim_product)"。entity_resolution 的 product 解析返回 `dim_product.product_id`。
- canonical dish 是**比 dim_product 更高一层**的聚合：1 个 canonical dish ← N 个 dim_product (跨店)。
- 若复用 `product`，`entity_resolution_history.b_entity_id` 会同时指代两种东西 (dim_product 行 / canonical dish)，语义污染，transitive agent 会串错。

**决策**：新增 `EntityType.DISH = "dish"`，新建 `dim_canonical_dish` 表，`b_entity_id` 指向 `canonical_dish_id`。CHECK 约束扩 `'dish'`。

### 决策 2 — canonical 层独立于 `dim_product`，用 `canonical_dish_id` 外键回连

```
raw POS / review 名
   │  (existing) deterministic.normalize_for_dim → normalized_name
   ▼
dim_product (门店级 SKU 行, 唯一键 = factory_id+normalized_name)   ← 现状不动
   │  (NEW P4) canonical_dish_id 外键
   ▼
dim_canonical_dish (跨店 canonical 菜, 1 ← N dim_product)          ← P4 新建
```

- `dim_product` schema **不改唯一键** (避免动现有 silver writer / agg 表外键，blast radius 大)。
- `dim_product` **新增一列** `canonical_dish_id BIGINT NULL` (外键 → `dim_canonical_dish`)。NULL = 尚未归一。
- 分析层 JOIN `dim_product.canonical_dish_id` 聚合到 canonical。

### 决策 3 — 复用 entity_resolution 5-agent，但 dish 的候选集是 `dim_canonical_dish` 不是 `dim_product`

agent resolve 的对象从"raw → dim_product 行"变成"dim_product 行 → canonical dish"：
- **deterministic**: dim_product.name 经**餐饮规则层** (`RestaurantMenuNormalizer.normalize_by_rules` 去 5 类后缀) 后，精确匹配已有 canonical dish 的 normalized canonical name。
- **embedding / contextual**: dim_product.name + category 向量比对 `dim_canonical_dish` 全集，cosine。
- **llm_arbitrator**: top-K canonical 候选 + dish context (category / 价格区间 / 哪些店有) → LLM 选一个或 null。
- **transitive**: 复用 `entity_resolution_history` (entity_type='dish') 做 a→b→c 闭环。

### 决策 4 — 分两阶段：P4a 离线建模+人工确认，P4b 接分析

- **P4a (本 spec 主体)**：离线跑 dish canonicalization，建 `dim_canonical_dish` + 人工审核 UI + 确认毕业写 history。**不动任何线上分析查询**。可灰度、可回滚、零 blast radius 到现有报表。
- **P4b (后续)**：分析层 (gold 模板 / P2 问答 / 经营驾驶舱) 切到 canonical 聚合。这一步动现有查询，blast radius 高，单独 PR + 多轮 E2E。

---

## 4. 数据模型 (Schema)

> 所有新表 FORCE RLS + tenant_isolation policy (镜像 `dim_product`)。**所有新表/序列必带 `GRANT SELECT,INSERT,UPDATE,DELETE` + `GRANT USAGE,SELECT ON SEQUENCE` 给 `smartbi_user`** (per #390 / `feedback_smartbi_table_grant_gap.md` —— 漏 GRANT = 静默 0 行写入)。

### 4.1 迁移 `V20260602_01__p4_canonical_dish.sql`

```sql
-- =============================================================================
-- dim_canonical_dish — 跨店 canonical 菜品字典
-- 1 canonical dish ← N dim_product (跨门店)
-- =============================================================================
CREATE TABLE IF NOT EXISTS dim_canonical_dish (
    canonical_dish_id  BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50) NOT NULL,
    canonical_name     VARCHAR(500) NOT NULL,   -- 客户确认的规范名 (展示用)
    normalized_key     VARCHAR(500) NOT NULL,   -- 规则层归一 key (去后缀+繁简+标点), 唯一去重用
    category           VARCHAR(100),            -- 继承自 dish_classifier 主品类
    member_count       INT NOT NULL DEFAULT 0,  -- 当前挂了几个 dim_product (审计/UI 用)
    status             VARCHAR(20) NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active', 'merged_away', 'retired')),
    merged_into_id     BIGINT REFERENCES dim_canonical_dish(canonical_dish_id),  -- 误建后软合并指向
    created_by         VARCHAR(50),             -- 'agent' / admin user / 'rule_seed'
    created_at         TIMESTAMP DEFAULT NOW(),
    updated_at         TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_canonical_dish_factory_normkey UNIQUE (factory_id, normalized_key)
);
ALTER TABLE dim_canonical_dish ENABLE ROW LEVEL SECURITY;
ALTER TABLE dim_canonical_dish FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON dim_canonical_dish;
CREATE POLICY tenant_isolation ON dim_canonical_dish FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
DROP TRIGGER IF EXISTS trg_canonical_dish_touch ON dim_canonical_dish;
CREATE TRIGGER trg_canonical_dish_touch BEFORE UPDATE ON dim_canonical_dish
    FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
CREATE INDEX IF NOT EXISTS idx_canonical_dish_factory_cat
    ON dim_canonical_dish (factory_id, category);

-- dim_product 加 canonical_dish_id 外键列 (NULL = 尚未归一)
ALTER TABLE dim_product
    ADD COLUMN IF NOT EXISTS canonical_dish_id BIGINT
        REFERENCES dim_canonical_dish(canonical_dish_id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_dim_product_canonical
    ON dim_product (factory_id, canonical_dish_id)
    WHERE canonical_dish_id IS NOT NULL;

-- ⛔ GRANT DML (per #390: 漏 GRANT = 静默 permission denied → fail-open 吞 → 0 行)
GRANT SELECT, INSERT, UPDATE, DELETE ON dim_canonical_dish TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE dim_canonical_dish_canonical_dish_id_seq TO smartbi_user;
-- dim_product 已有 GRANT (V20260428_03), 加列不需要重 GRANT, 但加列后确认:
-- SELECT has_column_privilege('smartbi_user','dim_product','canonical_dish_id','UPDATE');

-- Rollback:
--   ALTER TABLE dim_product DROP COLUMN IF EXISTS canonical_dish_id;
--   DROP TABLE IF EXISTS dim_canonical_dish;
```

### 4.2 迁移 `V20260602_02__p4_extend_entity_type_dish.sql`

```sql
-- 扩 entity_type CHECK 加 'dish' (3 张 entity_resolution 表 + 旧 product 不变)
ALTER TABLE entity_resolution_history
    DROP CONSTRAINT IF EXISTS entity_resolution_history_entity_type_check;
ALTER TABLE entity_resolution_history
    ADD CONSTRAINT entity_resolution_history_entity_type_check
        CHECK (entity_type IN ('store','product','staff','dish'));

ALTER TABLE entity_resolution_admin_queue
    DROP CONSTRAINT IF EXISTS entity_resolution_admin_queue_entity_type_check;
ALTER TABLE entity_resolution_admin_queue
    ADD CONSTRAINT entity_resolution_admin_queue_entity_type_check
        CHECK (entity_type IN (
            'store','product','staff','ingredient','dish',
            'shape_detection','sheet_merge','period_inference','field_conflict'
        ));

ALTER TABLE entity_resolution_labels
    DROP CONSTRAINT IF EXISTS entity_resolution_labels_entity_type_check;
ALTER TABLE entity_resolution_labels
    ADD CONSTRAINT entity_resolution_labels_entity_type_check
        CHECK (entity_type IN ('store','product','staff','dish'));
```

> ⚠️ **注意 `_history` 的 `entity_table` / `id_column` 推导**：orchestrator `_record_history` (line 148-149) 用 `f"dim_{input.entity_type.value}"` + `f"{value}_id"` 去 `dim_dish` 查 name。但我们的表叫 `dim_canonical_dish`，id 列 `canonical_dish_id`。**必须改 `_record_history` 对 dish 做特判** (§5.1 Task)，否则 history 写入查 `dim_dish` 表不存在报错 (fail 整个 resolve)。

### 4.3 P4a 不动的表 (现状外键依赖确认)

- `agg_product_period.product_id` → `dim_product.product_id`：不动 (P4b 才聚合到 canonical)。
- `dim_review_summary.product_id` / `fact_review_event.product_id` → `dim_product`：不动；P4 加回填 `canonical_dish_id` 走 dim_product JOIN，不直接改这两表外键。

---

## 5. 实施任务 (bite-sized, TDD)

> 执行用 `superpowers:subagent-driven-development` + `test-driven-development`。每个 task 先写测试。Python 模板用 `float()` 即可 (per `python-java-port.md` 本模板豁免 byte-parity)。

### Phase P4a — 离线 canonical 建模 + 人工确认毕业 (本 spec 交付)

#### Task 1 — schema 迁移 (V20260602_01 + _02)
- 写两个迁移文件 (§4.1 / §4.2)。
- **GRANT DML 必带** (per #390)。
- 部署走 `apply-smartbi-migrations.sh` (per `server-operations.md` HARD RULE，禁手动 psql)。
- **验收**：`smartbi_migrations` tracker 有两行；`has_table_privilege('smartbi_user','dim_canonical_dish','INSERT')` = true；`dim_product.canonical_dish_id` 列存在。

#### Task 2 — `EntityType.DISH` + dish 解析候选集
- `orchestrator.py`: enum 加 `DISH = "dish"`。
- **改 `_record_history`** (line 139-178)：dish 特判 —— `entity_table='dim_canonical_dish'`, `id_column='canonical_dish_id'`, name 列 `canonical_name`。其它类型不变。
- 测试：`test_orchestrator_dish_history_table_mapping` —— mock pool，assert dish 走 `dim_canonical_dish` / `canonical_name`，store 仍走 `dim_store`。

#### Task 3 — dish deterministic agent (规则层精确)
- 新 `agents/dish_deterministic.py` (或在 `deterministic.py` 加 DISH 分支)：
  - 输入是 **dim_product 行的 name** (不是 raw)。
  - 用 `RestaurantMenuNormalizer.normalize_by_rules` (已有，去 5 类后缀) 产 `normalized_key`。
  - 精确匹配 `dim_canonical_dish.normalized_key`。命中 → confidence 1.0。
  - **注意**：`RestaurantMenuNormalizer` 是同步 SQLAlchemy 类，但 normalize_by_rules 是**纯字符串方法不碰 DB** —— 可直接 import 用其 regex (或抽 regex 到无状态 helper，避免 Session 依赖)。**推荐抽 `dish_rule_normalize(name) -> str` 纯函数** 到 `materialized_analytics/restaurant/` 复用 (类似已有 `dish_name_normalizer.py`，但用全套 5 类后缀)。
- 测试：`招牌青花椒鱼(单人份)` / `#招牌青花椒鱼(微麻微辣)(一吃)#` / `招牌青花椒鱼[大份]` 三个 dim_product → 同 normalized_key → 同 canonical。`招牌青花椒烤鱼煲` → 不同 key (不误并)。

#### Task 4 — dish embedding/contextual agent 候选集切换
- `embedding.py` / `contextual.py`: DISH 分支查 `dim_canonical_dish` (name=canonical_name, LIMIT 1000) 而非 dim_product。
- context 字段：dish 用 `category` (来自 dish_classifier) + 价格区间 (来自 agg_product_period avg_unit_price)。
- 测试：mock embed_fn 返回固定向量，assert dish 候选集来自 `dim_canonical_dish`；阈值 (embedding 0.90 / contextual 0.85) 不变。

#### Task 5 — dish llm_arbitrator prompt
- `llm_arbitrator.py`: dish 的 prompt 加 context (category / 价格区间 / 出现门店数)。
- **诚实约束** (per fool-proof + relabel 教训)：prompt 明示"宁可返 null 不要猜；字面相似不等于同菜 (红烧牛肉 ≠ 红烧牛腩)"。
- LLM 出境走免费链 + 脱敏 scope (per MEMORY P0 redaction：菜名是客户数据，必须 RedactionScope)。
- 测试：mock llm_fn 返合法 JSON / 幻觉 id (不在候选) / null —— assert 幻觉 id → matched None + confidence 0 (已有 valid_ids 校验 line 160-178，dish 复用)。

#### Task 6 — DishCanonicalizer 离线编排器
- 新 `canonical/dish_canonicalizer.py`：
  - 入口 `canonicalize_factory(factory_id)`：
    1. 拉该 factory 所有 `dim_product` (含 name / category / canonical_dish_id)。
    2. 已有 canonical_dish_id 的跳过 (幂等)。
    3. 先**规则层聚类** (同 normalized_key 的 dim_product 直接归一组) —— 高置信，但**仍走人工确认** (per #364 不自动毕业)。
    4. 剩余的逐个跑 orchestrator.resolve(EntityType.DISH)：
       - ship (≥agent threshold) → **不直接写 dim_product.canonical_dish_id**，而是 propose 进 admin queue (P4a 全部人工确认)。
       - admin queue → 客户审核。
    5. 全新菜 (无候选) → propose create_new canonical。
  - **tenant safety**: `SET app.factory_id` 在每个 conn (per orchestrator docstring + FORCE RLS)。
- 测试：合成 qhj 样例 (39 个招牌变体 + 6 米饭 + 14 冰粉) → 规则层聚成 3 组 + 剩余进 queue。assert 不直接写 canonical_dish_id (全 propose)。

#### Task 7 — 人工确认毕业写 history (#389 核心修法)
- 扩 `data_quality_queue_admin.py` 的 `resolve_queue` / `batch_resolve_queue`：
  - 当 `entity_type='dish'` 且 `action='confirm'` 成功后，**best-effort upsert 进 `entity_resolution_history`** (per #389：确认要写 history 不只 queue)：
    ```sql
    INSERT INTO entity_resolution_history
      (factory_id, entity_type, a_name, b_name, b_entity_id,
       confidence, decided_by_agent, reasoning)
    VALUES ($1, 'dish', $raw_name, $canonical_name, $resolved_canonical_id,
            1.0, 'admin', 'admin confirmed dish canonical')
    ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE
      SET confidence = 1.0, decided_by_agent = 'admin';
    ```
  - **同时**：把 `dim_product.canonical_dish_id` 真正写上 (确认后才落库) + `dim_canonical_dish.member_count` 自增。
  - `create_new`: 新建 canonical dish 行，挂当前 dim_product。
  - **fail-open** (per #364：history upsert 失败不阻塞确认，但**必须 log WARNING + 可观测**，不能静默吞)。
- **entity_type 白名单** (per #389 防注入)：只对 `'dish'`/'store'/'product'/'staff' 做 history upsert。
- 测试：
  - `test_resolve_dish_writes_history` —— confirm dish → history 真有行 (conf=1.0, decided_by_agent='admin')。
  - `test_resolve_dish_sets_canonical_dish_id` —— dim_product.canonical_dish_id 落库。
  - `test_history_upsert_failure_does_not_block_confirm` —— history insert 抛错 → 确认仍成功 + log WARNING (不是静默)。
  - **真库 E2E** (per #390：mock 单测抓不到 grant gap，必真库 E2E)：在 test smartbi_db 真 confirm 一条 dish → 查 `entity_resolution_history` 真有行。

#### Task 8 — 人工审核 UI (web-admin)
- 复用现有数据质量队列页 (`/list` + `/resolve` 已有)，加 dish entity_type 过滤 tab。
- **防呆 (per fool-proof Rule 2)**：每个待审项显示 —— raw 菜名 + 候选 canonical + 哪些门店有这个名 + 销量/营收 sample (从 agg_product_period 取) + agent confidence。
- **防呆 (Rule 3)**：confirm / reject / create_new 是 dropdown 动作，不是自由文本。
- **防呆 (Rule 5)**：空队列态给 next-action ("暂无待归一菜品，可重跑 canonicalize")。
- headed Playwright E2E (per `playwright-headed-mode.md`)。

#### Task 9 — 回填 unmatched_product_names (消化死胡同)
- 新 `backfill_unmatched_reviews.py`：
  - 拉 `dim_review_summary.unmatched_product_names` (JSONB 数组)。
  - 每个 unmatched 名跑 dish canonicalization → 若命中已确认 canonical → 找对应 dim_product → 回填 `fact_review_event.product_id` + `dim_review_summary.product_id`。
  - **仅回填已人工确认的** (canonical_dish_id 非 NULL 的 dim_product)，不自动猜。
  - 回填后从 `unmatched_product_names` JSONB 移除已消化的。
- 测试：合成 unmatched ["青花椒味鱼"] + 已确认 canonical "招牌青花椒鱼" (含 dim_product) → 回填 product_id；未确认的留 JSONB。

### Phase P4b — 接分析 (后续 PR，本 spec 仅列路线，不实现)

#### (P4b) Task 10 — 分析层切 canonical 聚合
- gold 模板 / P2 问答 / 经营驾驶舱畅销菜：`JOIN dim_product.canonical_dish_id` 聚合到 `dim_canonical_dish`。
- **灰度策略**：未归一 (canonical_dish_id NULL) 的 dim_product 仍按原 name 显示 (graceful，不丢数据)。
- **诚实标注**：归一覆盖率 < 80% 时 UI 标"部分菜品尚未归一，排行可能分散"。

#### (P4b) Task 11 — 评价 × 销售按 canonical 关联
- 解锁"卖得最好的菜评价怎么样" (P2 评价类问题真正打通)。
- review (canonical) JOIN sales (canonical) on canonical_dish_id。
- **小样本注明** (per relabel 教训)：某 canonical 菜评价数 < 10 时标"评价样本小"。

#### (P4b) Task 12 — 集团统一菜单分析
- 8 店 canonical 菜品矩阵：哪些菜全店有 / 哪些是单店特色 / 跨店价差。
- 桥接 `restaurant_dish_alias` (§5.3)。

### 桥接已有 restaurant_dish_alias (§5.3, P4a Task 6 内顺带)
- canonicalize 时，先查 `restaurant_dish_alias` 已确认的 `original_name → canonical_name` 作为**规则层种子** (客户之前在改进 1 审核过的，直接信)。
- 不强行迁移全部 alias 数据 (避免 blast radius)，只在 canonicalize 命中时作为 deterministic 高置信信号。

---

## 6. 风险 (高 blast radius 专项)

| # | 风险 | 严重度 | 缓解 |
|---|---|---|---|
| R1 | **错合并污染 canonical** —— 把"红烧牛肉"和"红烧牛腩"并成一道，所有按 canonical 的销量/评价/营收全错，且**难发现** (数字看着合理) | 🔴 P0 | (a) P4a **绝不自动毕业**，全人工确认 (per #364)；(b) llm prompt 明示"字面相似 ≠ 同菜"；(c) `dim_canonical_dish.merged_into_id` 支持误建后软回退；(d) `member_count` + 审核 UI 显示成员 dim_product 让人工肉眼核 |
| R2 | **entity_resolution history 脆弱 + grant gap** —— 新表/列漏 GRANT DML → 静默 0 行写入 (per #390，已复发 2 次) | 🔴 P0 | (a) 迁移**必带 GRANT DML + sequence** (§4.1)；(b) Task 7 **真库 E2E** 验 history 真落行 (mock 抓不到)；(c) `_record_history` dish 特判表名 (否则查 `dim_dish` 不存在报错 fail 整个 resolve) |
| R3 | **FORCE-RLS 静默 0 行** —— canonicalizer / 回填没 `SET app.factory_id` → RLS 默认 NULL match → 0 行，看着"没数据" | 🟠 P1 | 每个 conn 显式 `SET app.factory_id` (per orchestrator docstring)；测试覆盖"未设 GUC → 0 行"反例 |
| R4 | **两套归一系统 (alias vs entity_resolution) 数据漂移** —— 客户在改进 1 审核的 canonical 跟 P4 的 canonical 不一致 | 🟠 P1 | §5.3 桥接：alias 已确认结果作 deterministic 种子，**单向信任** (alias → canonical)，不反向写 |
| R5 | **dim_product.canonical_dish_id 加列触发现有 silver writer 行为变化** | 🟡 P2 | 加 NULL 列 + ON DELETE SET NULL，现有 INSERT/UPSERT 不写该列 (默认 NULL)，零行为变化；review_writer 等不动 (P4a) |
| R6 | **LLM 仲裁出境泄露菜名** (菜名 = 客户数据，P0 主权) | 🟠 P1 | dish llm_arbitrator 走免费链 + RedactionScope (per MEMORY P0 redaction)；审计 `smart_bi_llm_egress_audit` sanitized=true |
| R7 | **规则层过度合并** (normalize_by_rules 把"青花椒鱼" 和 "青花椒虾" 的后缀去太狠) | 🟡 P2 | 规则层只去**已知后缀** (份量/口味/吃法/制作)，不动主体词；规则聚类仍走人工确认 (不自动毕业) |
| R8 | **P4b 切分析层 blast radius** —— 改 gold/问答聚合可能让现有营收数字波动 (per #316 营收回归教训) | 🔴 P0 (P4b) | P4b 独立 PR + 多轮 E2E + 灰度 (未归一按原 name 不丢数据)；本 spec P4a **不碰任何线上查询** |
| R9 | **canonicalize 性能** —— embedding agent 对 dim_product × dim_canonical_dish 全表 O(n²) cosine | 🟡 P2 | LIMIT 1000 已有；先规则层消化大部分 (qhj 39 招牌变体规则层就并掉)，剩余才 embedding；离线批跑非实时 |

---

## 7. 验收标准 (P4a)

1. 迁移 apply 成功，`smartbi_user` 对 `dim_canonical_dish` 有 INSERT 权限 (真库验，per #390)。
2. qhj (RES_3101_009) 真数据跑 canonicalize：39 个招牌变体规则层聚成 ≤5 组 propose；6 米饭变体聚 1 组；14 冰粉变体聚合理组数 (人工核)。
3. 人工 confirm 一条 dish → `entity_resolution_history` **真有行** (真库 E2E，conf=1.0 decided_by_agent='admin') + `dim_product.canonical_dish_id` 落库。
4. 回填：合成 unmatched 评价名 → 命中已确认 canonical → `fact_review_event.product_id` 回填，`unmatched_product_names` JSONB 移除。
5. **零 blast radius**：P4a 部署后，现有经营驾驶舱营收 / 畅销菜 / 评价数字**完全不变** (P4a 不切分析层)。
6. 审核 UI headed Playwright：dish tab 显示 raw 名 + 候选 + 门店 + 销量 sample + confidence；confirm/reject/create_new dropdown；空态 next-action。

---

## 8. 并行工作建议

### Subagent: ✅ 部分并行
- Task 3/4/5 (三个 dish agent) 互相独立，可并行 subagent (改不同 agent 文件)。
- Task 1 (迁移) 必须先完成 (Task 2-9 全依赖 schema)。
- Task 7 (history 毕业) 依赖 Task 1+2+6。

### 多Chat: ⚠️ 谨慎
- P4a 与其它 chat 改 `entity_resolution/*` / `data_quality_queue_admin.py` 会冲突 (#389/#390 同区域)。**冲突风险高** —— 用 git worktree off origin/main 隔离 (per `worktree-and-main-only-deploy.md`)，commit 前 `git status` 锁 scope (per `concurrent-edit-safety.md` Rule 5b)。
- ⚠️ 本工作树当前在 stale `feat/restaurant-dashboard-default-allgold` 分支，且 #389/#390 的 history 毕业修法在 origin/main 上**可能已存在** —— **开 worktree 前先 `git fetch && git log origin/main -- backend/python/smartbi/api/data_quality_queue_admin.py`** 确认 main 是否已有 history upsert，避免重做/冲突。

---

## 9. 关键文件清单 (实施参考)

| 文件 | 角色 | P4 动作 |
|---|---|---|
| `backend/python/smartbi/canonical/entity_resolution/orchestrator.py` | EntityType + _record_history | 加 DISH enum + dish 特判 history 表名 |
| `backend/python/smartbi/canonical/entity_resolution/agents/deterministic.py` | 规则精确 | 加 DISH 分支 (或新 dish_deterministic.py) |
| `backend/python/smartbi/canonical/entity_resolution/agents/embedding.py` `contextual.py` `llm_arbitrator.py` | 向量+LLM | DISH 候选集切 dim_canonical_dish |
| `backend/python/smartbi/services/restaurant/menu_normalizer.py` | 规则层去 5 类后缀 | 抽纯函数 `dish_rule_normalize` 复用 (不带 Session) |
| `backend/python/smartbi/canonical/silver_writers/review_writer.py` | unmatched_product_names 来源 | P4a 不动；P4 回填脚本读它 |
| `backend/python/smartbi/api/data_quality_queue_admin.py` | admin queue resolve/reject | Task 7 加 dish confirm → 写 history + canonical_dish_id |
| `backend/python/smartbi/database/migrations/V20260602_01__p4_canonical_dish.sql` | schema | 新建 (Task 1) |
| `backend/python/smartbi/database/migrations/V20260602_02__p4_extend_entity_type_dish.sql` | CHECK 扩 dish | 新建 (Task 1) |
| `backend/python/smartbi/canonical/dish_canonicalizer.py` | 离线编排 | 新建 (Task 6) |
| `backend/python/smartbi/canonical/backfill_unmatched_reviews.py` | 回填 | 新建 (Task 9) |
| `shared/alias_normalizer.py` + `restaurant_dish_alias` 表 | 旧 (改进 1) 单店 alias | §5.3 作 deterministic 种子，单向信任，不重写 |

---

## 10. 诚实标注承诺 (贯穿全 spec)

- canonical 归一覆盖率不到 100% 时，UI/分析**明示"部分菜品尚未归一"**，不假装全归一。
- canonical 合并**全部经人工确认**，系统**不编造**合并 (高置信也只 propose)。
- 评价 × 销售按 canonical 关联时，小样本 (< 10 评价) **注明"样本小"** (per relabel 教训)。
- 空队列 / 未归一 / 回填失败**给 next-action 不 dead-end** (per fool-proof Rule 5)。
```
