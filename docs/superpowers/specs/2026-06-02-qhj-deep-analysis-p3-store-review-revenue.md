# P3 设计 spec — 青花椒「门店评分 × 营收」跨数据集分析

**日期**: 2026-06-02
**作者**: 架构师 (workflow 产出)
**范围**: 青花椒 (RES_3101_009) 评价数据 与 gold 营收数据 的门店级关联 + 跨数据集分析问答
**优先级**: P3 (P4 = 评价×销售按菜，本 spec 明确不做)
**关联 rules**: `.claude/rules/fool-proof-design.md`、`feedback_smartbi_table_grant_gap`、`feedback_self_evidence_disqualified_cross_verify_required`、`.claude/rules/python-java-port.md`(本模板用 float() 即可)

---

## 0. 一句话目标

为青花椒老板回答「评分高的门店是不是更赚钱？」：把 **评价门店名**（28 个，来自大众点评「评价下载」jsonb，如 `鲜行者X顺德小馆(虹口龙之梦店)`、`青花椒·外卖卫星店(五角场店)`）映射到 **gold `dim_store`** 的 19 个 POS 门店（如 `青花椒徐汇日月光店`），再 join「该店评价聚合评分」与「该店 gold 营收」做散点/相关性分析。

**诚实标注是第一约束**：覆盖率天花板 ~12-19/28（约 43%-68%），必须在 UI/返回里明确标注「已关联 N 家 / 未关联 M 家（含外卖卫星店、鲜行者品牌可能无 POS）」，绝不编造数字、绝不把未关联门店当 0 营收混入相关性。

---

## 1. 背景与现状（已读代码确认）

### 1.1 两套门店命名空间，0 精确匹配
| 数据集 | 来源 | 门店数 | 命名风格 | 例 |
|---|---|---|---|---|
| **评价门店名** | `smart_bi_dynamic_data` 的 `row_data` jsonb（大众点评「评价下载」），canonical key `store_name` | 28 | 品牌+括号地标 | `鲜行者X顺德小馆(虹口龙之梦店)` / `青花椒·外卖卫星店(五角场店)` |
| **gold dim_store** | POS 流水 ETL → `dim_store.name` | 19 | 品牌+地标店 | `青花椒徐汇日月光店` |

- `normalize_for_dim`（`agents/deterministic.py`）归一后两边 **0 精确匹配**（品牌前缀、括号地标、"·"、"X" 连接符差异）。
- 人工目测：按**括号内地标**（如 `(五角场店)` ↔ `…五角场…`）模糊匹配约 **12/28** 能对上；放宽到品牌+地标 token 重叠可能到 ~19/28。
- 剩余 ~9-16 个：**外卖卫星店**（线上专营，POS 无对应实体店）、**鲜行者品牌**（可能整条品牌线无 POS 营收）→ 结构上无法关联，**不是 bug，是真实数据缺口**。

### 1.2 现有实体解析框架（复用对象，不重写）
- `backend/python/smartbi/canonical/entity_resolution/`：`EntityType=STORE/PRODUCT/STAFF`，5-agent 链（deterministic → embedding → contextual → transitive → llm_arbitrator），orchestrator 首达阈值即 ship，否则 tentative（≥0.80）或进 admin queue。
- 三张持久表（`V20260426_01`，全 FORCE RLS）：`entity_resolution_history`（审计 + 人工金标准毕业，per MEMORY #389/#390）、`entity_resolution_admin_queue`（待裁决）、`entity_resolution_labels`（gold standard）。
- `ReviewWriter`（`silver_writers/review_writer.py`）**已经**在写 `fact_review_event` 时对 `store_name` 调 `_resolve_store` → 但它走的是通用 orchestrator，**对评价门店名命中率极低**（因为 dim_store 是 POS 名），未匹配的评价行 store_id 为 NULL，summary 也就 anchor 不到正确门店。这正是 P3 要解决的：**为「评价门店名 → gold store_id」建一张专用 curated alias 表**，让模糊地标匹配 + 人工确认的结果可复用、可毕业。

### 1.3 gold 营收读取（join 的右半边）
- `gold/queries.py::finance_summary` 返回 `top_stores`（`{store_id, store_name, revenue, bill_count}`，按 `agg_daily.net_amount` 聚合）。
- Java 侧 `GoldFinanceClient.fetchFinanceSummary` 转发 `X-User-Role`，Python `_apply_rbac_strip` 按角色剥金额（非 price-view 角色 revenue 置 null）。**P3 的营收读取必须沿用这条 RBAC 链**，不能绕过。
- gold 端点注册在 `api/gold_reads.py`（`router = APIRouter(prefix="/gold")`），内部 secret + `X-Factory-Id` + `tenant_ctx`。

---

## 2. 核心设计

### 2.1 新表 `dim_store_review_alias`（smartbi 库）

**职责**：把一个「评价门店名」绑定到一个 gold `dim_store.store_id`，带置信度与决策来源，支持自动入库（高置信）+ 人工确认毕业（低置信）。这是评价↔营收 join 的**唯一桥**。

```sql
-- V20260602_01__dim_store_review_alias.sql
-- 评价门店名 → gold dim_store.store_id 别名映射桥。
-- 评价数据(大众点评导出)门店名 与 POS dim_store 名 0 精确匹配, 靠括号地标/品牌
-- 模糊匹配 + 人工确认。高置信自动入库, 低置信进确认队列, 复用实体解析毕业模式。
--
-- 迁移 runner 以 postgres 超级用户跑 → 表归 postgres → 必须 GRANT 给 smartbi_user
-- (per feedback_smartbi_table_grant_gap: 漏 GRANT 会导致写路径 permission denied 被
--  fail-open 静默吞, 表 0 行无人发现; entity_resolution_history 已踩过 2 次)。

CREATE TABLE IF NOT EXISTS dim_store_review_alias (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50)  NOT NULL,
    review_store_name  TEXT         NOT NULL,            -- 评价侧原始门店名 (未归一, 保真)
    store_id           BIGINT,                           -- 映射到的 gold dim_store.store_id; NULL = 确认无对应(外卖卫星/鲜行者)
    confidence         NUMERIC(3,2) NOT NULL DEFAULT 0.0,-- 0.00-1.00
    match_method       VARCHAR(32)  NOT NULL,            -- 'landmark' | 'brand_landmark' | 'exact_norm' | 'admin' | 'no_match'
    decided_by         VARCHAR(32)  NOT NULL,            -- 'auto' | 'admin' | 'unmapped'
    landmark           TEXT,                             -- 抽到的地标 token (审计/调试用, 如 '五角场')
    -- 防表爆炸 + 同名重复确认: 每 (factory, 评价名) 唯一绑定一个映射
    UNIQUE (factory_id, review_store_name),
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

ALTER TABLE dim_store_review_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE dim_store_review_alias FORCE ROW LEVEL SECURITY;
-- per V20260502_05 模式: USING + WITH CHECK 双绑, 含 __internal__ sentinel 旁路
-- (物化/内部任务以 SET app.factory_id='__internal__' 跨租户写, per #590 教训)。
CREATE POLICY tenant_isolation ON dim_store_review_alias
    USING (factory_id = current_setting('app.factory_id', true)
           OR current_setting('app.factory_id', true) = '__internal__')
    WITH CHECK (factory_id = current_setting('app.factory_id', true)
           OR current_setting('app.factory_id', true) = '__internal__');

CREATE INDEX idx_store_review_alias_lookup
    ON dim_store_review_alias (factory_id, store_id);
-- 确认队列视角: 列出待人工确认 (低置信 auto)
CREATE INDEX idx_store_review_alias_pending
    ON dim_store_review_alias (factory_id, created_at DESC)
    WHERE decided_by = 'auto' AND confidence < 0.90;

-- ⛔ GRANT (HARD, per feedback_smartbi_table_grant_gap): 不写则整个写路径死。
GRANT SELECT, INSERT, UPDATE, DELETE ON dim_store_review_alias TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE dim_store_review_alias_id_seq TO smartbi_user;

COMMENT ON TABLE dim_store_review_alias IS
  '评价门店名 → gold dim_store.store_id 别名桥 (P3 门店评分×营收)。'
  'store_id NULL + decided_by=unmapped = 确认无 POS 对应 (外卖卫星店/鲜行者品牌)。'
  'Added 2026-06-02 per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p3-store-review-revenue.md';
```

**置信度语义（关键）**：
| confidence | decided_by | 含义 | 是否进 join |
|---|---|---|---|
| 1.0 | admin | 人工确认正确 | ✅（金标准，优先级最高） |
| 0.90-1.0 | auto | 高置信自动（exact_norm 或唯一地标命中） | ✅（自动入库直接可用） |
| 0.50-0.89 | auto | 低置信自动（地标命中但有歧义/多候选） | ❌ 进确认队列，确认前**不参与 join** |
| 0.0 | unmapped | 确认/判定无 POS 对应 | ❌ 永久排除，计入「未关联 M 家」 |

> **诚实约束**：只有 `decided_by='admin'` 或 `confidence>=0.90` 的行才进 join。低置信猜测**绝不静默并入营收相关性**，否则会拿错门店的营收污染结论。

### 2.2 候选生成（模糊匹配脚本）

**输入**：
- 评价门店名集合：`SELECT DISTINCT row_data->>'store_name'`（或 canonical key）`FROM smart_bi_dynamic_data WHERE upload_id IN (该 factory 的评价上传)`。
- gold 门店：`SELECT store_id, name FROM dim_store WHERE factory_id=$1`。

**匹配策略（rules-first，per repo rules-first-llm-fallback；LLM 只在确实歧义时兜底，本期可不接 LLM）**：

1. **exact_norm**：`normalize_for_dim(review_name)` == `normalize_for_dim(dim_store.name)` → confidence=1.0, method=exact_norm。（预期命中 0，但写上以防未来 POS 名改齐）
2. **landmark**：从评价名括号 `(…)` 抽地标 token（去掉尾「店」字：`五角场店`→`五角场`）。在 dim_store 名里找包含该地标 token 的门店。
   - 唯一命中 → confidence=0.92, method=landmark, decided_by=auto（自动入库可用）。
   - 多候选命中 → confidence=0.60, method=landmark, decided_by=auto（进确认队列，不参与 join），candidates 存进 reasoning/landmark 字段。
   - 0 命中 → 进下一步。
3. **brand_landmark**：评价名去括号后的品牌主体 token 与 dim_store 名做 token 重叠（jaccard / 公共子串）。阈值 ≥0.5 且唯一 → confidence=0.85, method=brand_landmark, decided_by=auto（<0.90 → 进确认队列）。
4. **no_match**：以上全不中 → 不写自动行（留给人工显式标 unmapped，或脚本可选写 `decided_by=unmapped, store_id=NULL, confidence=0` 以记录「已知无对应」，避免每次重跑都重新尝试）。

**地标抽取参考实现**（骨架）：
```python
import re
_BRACKET_RE = re.compile(r"[(（]([^)）]+)[)）]")
_TRAIL_STORE_RE = re.compile(r"店$")

def extract_landmark(review_store_name: str) -> str | None:
    """从评价门店名抽括号地标 token, 去尾'店'。无括号返 None。"""
    m = _BRACKET_RE.search(review_store_name or "")
    if not m:
        return None
    raw = m.group(1).strip()
    return _TRAIL_STORE_RE.sub("", raw) or None
```

**自动入库 + 确认队列规则（复用实体解析毕业模式）**：
- confidence >= 0.90 且唯一候选 → INSERT (decided_by=auto)，**直接可用**。
- 0.50 <= confidence < 0.90 → INSERT (decided_by=auto)，由 `idx_store_review_alias_pending` 暴露给确认队列，**确认前不参与 join**。人工确认（CLI/UI）→ UPDATE store_id + confidence=1.0 + decided_by=admin（毕业）。
- 已存在 `admin` 行 → 脚本**不覆盖**（人工金标准优先，per MEMORY #389 教训：机器猜测不得覆盖人工确认）。

### 2.3 人工确认（先 CLI，UI 可选后续）

仿 `scripts/promote_learnings.py` 双段式 CLI（dry-run + --apply）：
```
python -m smartbi.scripts.confirm_store_alias --factory RES_3101_009 --list-pending
python -m smartbi.scripts.confirm_store_alias --factory RES_3101_009 \
    --confirm "鲜行者X顺德小馆(虹口龙之梦店)" --store-id 137
python -m smartbi.scripts.confirm_store_alias --factory RES_3101_009 \
    --unmap "青花椒·外卖卫星店(五角场店)"   # 显式标无 POS 对应
```
确认成功 → UPDATE 该行 store_id + confidence=1.0 + decided_by=admin + updated_at=NOW()。
**毕业镜像（可选增强，per MEMORY #389）**：确认成功后 best-effort upsert 进 `entity_resolution_history`（entity_type='store', a_name=review_store_name, b_entity_id=store_id, confidence=1.0, decided_by_agent='admin'），fail-open 不阻塞确认 —— 让 transitive agent 未来也能复用这条人工金标准。

UI（可选，本期不强制）：在 web-admin 现有「实体解析待确认队列」页加一个 review-alias tab，复用 admin_queue 列表样式。

### 2.4 跨数据集分析工具（join 评分 × 营收）

**Python 查询函数**（新 `gold/store_review_revenue.py`，骨架见 §4）：
```python
async def store_review_vs_revenue(
    pool, factory_id: str, date_range: tuple[date, date], *, min_confidence: float = 0.90,
) -> dict:
    """门店评分 × 营收 关联。仅 confidence>=min_confidence 或 admin 确认的 alias 进 join。"""
```
返回形状（诚实标注内建）：
```json
{
  "factory_id": "RES_3101_009",
  "start_date": "2025-01-01", "end_date": "2025-12-31",
  "linked_stores": [
    {"store_id": 137, "gold_store_name": "青花椒徐汇日月光店",
     "review_store_name": "鲜行者X顺德小馆(虹口龙之梦店)",
     "avg_rating": 4.79, "review_count": 1203,
     "revenue": 2864120.0, "bill_count": 38210,
     "alias_confidence": 1.0, "alias_decided_by": "admin"},
    ...
  ],
  "linked_count": 14,
  "total_review_stores": 28,
  "total_gold_stores": 19,
  "unlinked_review_stores": ["青花椒·外卖卫星店(五角场店)", ...],   // M 家未关联, 含名字
  "unlinked_count": 14,
  "correlation": {                       // 仅 linked_count >= 4 时计算, 否则 null
     "metric": "pearson_rating_vs_revenue",
     "value": 0.31,
     "n": 14,
     "note": "样本量小 (n=14), 仅供参考; 评分与营收弱正相关"
  },
  "honest_note": "已关联 14/28 评价门店到 19 个 POS 门店; 14 家评价门店无 POS 营收对应 (含外卖卫星店/鲜行者品牌, 不计入相关性)。评分来自大众点评导出, 营收来自 POS 流水, 二者为同店不同来源。"
}
```

**铁律（诚实标注）**：
- `correlation` 仅在 `linked_count >= 4` 时计算（n<4 散点无统计意义），否则 `correlation=null` + note「关联门店不足 4 家，无法计算相关性」。
- `unlinked_review_stores` 必返门店名列表（不只数字），让老板知道是哪几家没对上。
- `honest_note` 必带，说明数据来源差异 + 未关联原因。
- 空数据（无 alias / 无评价 / 无营收）→ 返回结构化空态 + next-action（「请先在确认队列确认门店映射 / 暂无评价数据上传」），**不 dead-end**（per fool-proof Rule 5）。

**HTTP 端点**：`GET /api/smartbi/gold/store-review-revenue`（注册进 `api/gold_reads.py`，复用 `_apply_rbac_strip` —— 营收字段对非 price-view 角色剥零；评分/评价数不剥）。

**意图/工具接入**（Java 侧，可选后续）：仿 `AbstractReviewGoldTool` 模式新增 `StoreReviewRevenueTool` → `GoldFinanceClient` 加 `fetchStoreReviewRevenue`（转发 `X-User-Role`）→ 绑定意图 `RESTAURANT_RATING_REVENUE_CORRELATION`（priority ~115）。本期可先只交付 Python 端点 + CLI，Java 工具列为后续 task。

---

## 3. Bite-sized Tasks（TDD，subagent-driven 友好）

> 每个 task 独立可测、可 commit。⛔ 真实 PG E2E 是验收硬门槛（per MEMORY 教训：mock/H2 漏报 grant gap / RLS / 静默 0 行）。

| # | Task | 交付物 | 验收（必真库） |
|---|---|---|---|
| **T1** | 迁移建表 `dim_store_review_alias` | `V20260602_01__dim_store_review_alias.sql` | psql 真库建表成功 + `\d dim_store_review_alias` 看到列 + `role_table_grants` 确认 smartbi_user 有 INSERT/UPDATE/DELETE + sequence USAGE（per grant gap 教训）+ RLS policy 含 `__internal__` 旁路 |
| **T2** | 地标抽取 + 模糊匹配核心（纯函数） | `gold/store_alias_matcher.py`：`extract_landmark`、`match_review_store(review_name, dim_stores) -> list[Candidate]` | 单测：`(五角场店)`→`五角场`；唯一地标命中→conf 0.92；多候选→conf 0.60+candidates；无括号→brand_landmark/None；繁简/标点归一复用 `normalize_for_dim` |
| **T3** | 候选生成脚本（写表） | `scripts/generate_store_review_aliases.py`（dry-run + --apply） | 真库 E2E：对 RES_3101_009 跑 → 高置信 auto 行真落库（查行数 + 抽样）；低置信进 pending（`idx_store_review_alias_pending` 命中）；已存在 admin 行不被覆盖；幂等重跑不堆叠（UNIQUE 生效） |
| **T4** | 人工确认 CLI | `scripts/confirm_store_alias.py`：`--list-pending` / `--confirm` / `--unmap` | 真库：confirm → store_id + conf=1.0 + decided_by=admin；unmap → store_id NULL + decided_by=unmapped；可选毕业镜像写 entity_resolution_history（fail-open 验证：history grant 在则写、不在则不崩） |
| **T5** | join 查询函数 `store_review_vs_revenue` | `gold/store_review_revenue.py` | 单测：mock alias+评价+营收 → linked/unlinked 计数正确；min_confidence 门槛生效（0.60 行不进 join）；n<4 → correlation=null；honest_note/unlinked 列表必返；空态结构化 |
| **T6** | HTTP 端点 + RBAC | `api/gold_reads.py` 加 `GET /gold/store-review-revenue` | 真库 E2E：price-view 角色见 revenue；非 price-view 角色 revenue=null 但 avg_rating 保留；factory_id 不匹配 → RLS 空 |
| **T7** | 真实数据 E2E + 覆盖率核对 | E2E 脚本 / 手测记录 | RES_3101_009 真跑全链路：alias 生成 → 确认若干 → 端点返回，**亲见** linked_count / unlinked_count（核对 ~12-19/28 区间）+ unlinked 名单含外卖卫星店；correlation 值合理；headed UI 截图（若接 UI） |
| **T8**（可选） | Java 工具 + 意图绑定 | `StoreReviewRevenueTool` + `GoldFinanceClient.fetchStoreReviewRevenue` + 意图迁移 | headed web-admin AIChat 问「评分高的店是不是更赚钱」→ 返回带图 + 诚实标注；RBAC 角色转发验证（per feedback_java_python_rbac_role_forward） |

依赖：T1→T2→T3→T4；T1+T5→T6→T7；T8 依赖 T6。T2、T5 可并行（纯逻辑）。

---

## 4. 代码骨架

### 4.1 `gold/store_alias_matcher.py`（T2）
```python
"""评价门店名 → gold dim_store 模糊匹配 (rules-first, 无 LLM)。"""
from __future__ import annotations
import re
from dataclasses import dataclass
from typing import Optional
from smartbi.canonical.entity_resolution.agents.deterministic import normalize_for_dim

_BRACKET_RE = re.compile(r"[(（]([^)）]+)[)）]")
_TRAIL_STORE_RE = re.compile(r"店$")

@dataclass(frozen=True)
class Candidate:
    store_id: int
    gold_name: str
    confidence: float           # float() OK per python-java-port (本模板非 byte-parity)
    match_method: str           # 'exact_norm'|'landmark'|'brand_landmark'
    landmark: Optional[str]

def extract_landmark(review_store_name: str) -> Optional[str]:
    m = _BRACKET_RE.search(review_store_name or "")
    if not m:
        return None
    return _TRAIL_STORE_RE.sub("", m.group(1).strip()) or None

def match_review_store(
    review_name: str, dim_stores: list[tuple[int, str]],
) -> list[Candidate]:
    """返回候选列表 (可能 0/1/多)。多候选 = 歧义 → 调用方降置信进确认队列。"""
    norm_review = normalize_for_dim(review_name)
    # 1. exact_norm
    exact = [Candidate(sid, name, 1.0, "exact_norm", None)
             for sid, name in dim_stores
             if normalize_for_dim(name) == norm_review]
    if exact:
        return exact
    # 2. landmark
    lm = extract_landmark(review_name)
    if lm:
        lm_norm = normalize_for_dim(lm)
        hits = [Candidate(sid, name,
                          0.92 if False else 0.0,  # conf set by caller per uniqueness
                          "landmark", lm)
                for sid, name in dim_stores
                if lm_norm and lm_norm in normalize_for_dim(name)]
        if hits:
            conf = 0.92 if len(hits) == 1 else 0.60
            return [Candidate(h.store_id, h.gold_name, conf, "landmark", lm) for h in hits]
    # 3. brand_landmark (token jaccard) — 略, 阈值 0.5 唯一 → 0.85
    return []
```

### 4.2 `scripts/generate_store_review_aliases.py`（T3 — 关键 fail-loud 模式）
```python
# per feedback_smartbi_table_grant_gap + self-learning loop:
# 写入 MUST fail-loud (不 fail-open 静默吞)，否则 grant gap 下 0 行无人发现。
async def upsert_alias(conn, factory_id, review_name, cand, decided_by):
    inserted = await conn.fetchval(
        """
        INSERT INTO dim_store_review_alias
          (factory_id, review_store_name, store_id, confidence, match_method, decided_by, landmark)
        VALUES ($1,$2,$3,$4,$5,$6,$7)
        ON CONFLICT (factory_id, review_store_name) DO UPDATE SET
          store_id     = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                              THEN dim_store_review_alias.store_id      -- 不覆盖人工
                              ELSE EXCLUDED.store_id END,
          confidence   = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                              THEN dim_store_review_alias.confidence
                              ELSE EXCLUDED.confidence END,
          match_method = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                              THEN dim_store_review_alias.match_method
                              ELSE EXCLUDED.match_method END,
          updated_at = NOW()
        RETURNING id
        """,
        factory_id, review_name,
        cand.store_id if cand else None,
        cand.confidence if cand else 0.0,
        cand.match_method if cand else "no_match",
        decided_by, cand.landmark if cand else None,
    )
    if inserted is None:
        # ON CONFLICT 无 RETURNING 行也算正常 (admin 行被保护); 不静默 — 记 metric
        ...
    return inserted
```

### 4.3 `gold/store_review_revenue.py`（T5 — 诚实标注核心）
```python
from statistics import correlation as _pearson  # py3.10+; 或自实现, 仅 n>=4 调用
async def store_review_vs_revenue(pool, factory_id, date_range, *, min_confidence=0.90):
    start, end = date_range
    async with pool.acquire() as conn:
        # 1. 评价聚合 (按评价门店名, 从 fact_review_event/dim_review_summary 或直接聚合 jsonb)
        # 2. 营收 (复用 gold.finance_summary top_stores, 或直接 agg_daily by store_id)
        # 3. alias 桥 (仅 decided_by='admin' OR confidence>=min_confidence)
        aliases = await conn.fetch(
            """SELECT review_store_name, store_id, confidence, decided_by
               FROM dim_store_review_alias
               WHERE factory_id=$1 AND store_id IS NOT NULL
                 AND (decided_by='admin' OR confidence>=$2)""",
            factory_id, min_confidence,
        )
    # join in Python; 计 linked/unlinked; n>=4 才算 correlation
    linked, unlinked = [], []
    ...
    corr = None
    if len(linked) >= 4:
        ratings = [x["avg_rating"] for x in linked]
        revenues = [x["revenue"] for x in linked]
        corr = {"metric": "pearson_rating_vs_revenue",
                "value": round(_pearson(ratings, revenues), 2),
                "n": len(linked),
                "note": "样本量小, 仅供参考" if len(linked) < 8 else ""}
    return {..., "correlation": corr,
            "unlinked_review_stores": [u["review_store_name"] for u in unlinked],
            "honest_note": "已关联 N/28 ...; M 家无 POS 对应 (外卖卫星/鲜行者) ..."}
```

---

## 5. 诚实标注检查清单（merge 前逐条过）

- [ ] 低置信（<0.90 且非 admin）alias **绝不**进 join — 单测覆盖。
- [ ] `unlinked_review_stores` 返回**门店名列表**，不只数字。
- [ ] `correlation` n<4 → null + 解释；n<8 → note 标「样本小」。
- [ ] `honest_note` 说明评分(大众点评)与营收(POS)来源不同 + 未关联原因（外卖卫星店/鲜行者品牌）。
- [ ] 评分标签如实（avg_rating 来自评价，**不**伪装成营收指标）。
- [ ] 空态（无 alias / 无评价 / 无营收）→ 结构化 + next-action（确认队列链接 / 上传提示），不 dead-end。
- [ ] 不编造任何门店的营收/评分；未关联门店**不**当 0 营收混入。

---

## 6. 风险

1. **覆盖率天花板 ~12-19/28（43%-68%）**：外卖卫星店（线上专营无 POS 实体）、鲜行者品牌（可能整条品牌线无 POS 营收）结构上无法关联 → 相关性样本可能仅 n=12-14，统计意义弱，必须标「仅供参考」，老板不能据此下强结论。
2. **GRANT gap 静默 0 行（已踩 2 次）**：迁移漏 GRANT INSERT/UPDATE + sequence → smartbi_user 写 permission denied，若脚本 fail-open 会静默 0 行无人发现。缓解：T1 验收强制查 `role_table_grants`；T3 脚本 fail-loud（写失败抛异常，不吞）。
3. **RLS `__internal__` 旁路必须带**：候选生成脚本若以内部任务跑（非租户端点），需 `SET app.factory_id` 或 `__internal__`，否则 FORCE RLS 静默丢行（per #590 教训）。
4. **地标歧义**：多个 POS 门店含同一地标 token（如「五角场」可能有多家）→ 多候选必须降置信进确认队列，不能任选一个自动绑定，否则评分挂错店。
5. **评价数据去重**：大众点评「评价下载」jsonb 按「评价ID」可能有重复（per MEMORY：72438→19845 去重）；P3 的评分聚合必须对齐已去重口径，否则 avg_rating 被重复评价拉偏。
6. **相关性 ≠ 因果**：高分店更赚钱可能是「地段好→既高分又高营收」的混淆变量；honest_note 不得暗示「提升评分能提升营收」的因果。
7. **P4 评价×菜（按菜名）明确不做**：菜名命名空间对不上（口味标签 ≠ 菜名），归 P4 backlog，本 spec 不涉及；UI 不得暗示有按菜关联。
8. **Java 工具/意图为可选后续（T8）**：本期主交付 Python 端点 + CLI；若接 Java 必须沿用 `X-User-Role` 转发链（per feedback_java_python_rbac_role_forward），否则营收被 RBAC 剥零回 ¥0。
9. **真库 E2E 是唯一可信验收**：mock/H2/单测会漏 grant gap、RLS 丢行、静默 0 行（per feedback_self_evidence_disqualified_cross_verify_required + MEMORY 多次教训）；report「已验证」前必亲见真库 linked_count/unlinked_count 计数行。

---

## 7. 并行工作建议

### Subagent: ✅ T2（matcher 纯函数）与 T5（join 函数）可并行（无共享状态，各自 mock）；T1 迁移先行后 T3/T4/T6 串行。
### 多 Chat: ⚠️ 可拆 Python 端（T1-T7）与 Java 端（T8）两 chat，但 T8 依赖 T6 端点 ready；冲突风险低（不同文件）。建议单 chat subagent-driven 跑 T1-T7，T8 另起。
