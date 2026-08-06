# 餐饮发现层 (domain="restaurant") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 往已有 `FindingService` 注册 `domain="restaurant"` 的两条损耗规则，并把今天在说假话的 `RestaurantWastageAnomalyTool` 换成它们的出口。

**Architecture:** 规则落在 Python（数据在 smartbi_prod_db，且 Java 端 `smartbi_user` 无 BYPASSRLS、连接池上设 GUC 会跨租户泄漏）。Java 侧两个 `FindingProvider` 只做 dict→`Finding` 的形状转换，零判定。共享发现层新增第三态 `skippedRules`（「数据没采集到」），与既有 `checkedRules`（正常）/`failedRules`（查询失败）区分开。

**Tech Stack:** Python 3 + FastAPI + asyncpg + pytest ｜ Java 21 + Spring Boot 3.2.12 + JUnit 5 + Mockito + OkHttp

**Spec:** `docs/superpowers/specs/2026-08-06-restaurant-finding-layer-design.md`

## Global Constraints

- **禁止降级处理**：Python 不可达 / 返回 `success:false` → provider **抛异常**（落 `failedRules`），**绝不返回空列表**。空列表只表示「真的查过了，没异常」。
- **一个指标一个定义**：不得重定义 `ingredient_waste_rate`（已由 `health_check_metrics.py` 的 `DiagnosticsEngine` 拥有）。类型可行动性配置 `ACTIONABLE_WASTAGE_TYPES` **只允许存在于** `smartbi/gold/restaurant/wastage_findings.py`，禁止在 Java 侧或 web-admin 再写一份。
- **零 LLM**：异常检测全程规则，话术走模板。
- **窗口边界用 `>` 不用 `>=`**：`date >= CURRENT_DATE - 7` 取到的是 **8 天**且两窗口会在 `CURRENT_DATE-7` 重叠。spec 探索期的 SQL 用的是 `>=`，**不要照抄**。
- **`wastage_type` 是中文自由文本**（实测值：`加工损耗` / `客诉退菜` / `变质`）。建表注释写的 `EXPIRED/DAMAGED/SPOILED/PROCESSING/OTHER` **是陈旧的**，不要按它写代码。
- **字段命名**：Java camelCase ↔ JSON camelCase；Python 内部 snake_case，跨 HTTP 时由 provider 显式映射。
- **worktree 隔离**：全部工作在 `C:\Users\Steve\cretas-rest-finding`（已建，off `origin/main`，分支 `codex/claude-rest-finding`）。
- **commit 用 `--only` 模式**：`git add <files>` 后 `git commit -m "msg" -- <files>`，防并发 session 的 staged 文件被吞。
- **两条部署链有顺序**：Python 先（`deploy-smartbi-python.sh`），Java 后（`release-cretas.sh`）。顺序错了只会显示「检查失败」，不会产生假数据。

## 既有事实（实现者必读，不要重新推断）

`Finding` 是 record，构造参数顺序 **固定**为：
```java
new Finding(String code, String domain, Finding.Severity severity, int actionability,
            String subjectId, String subjectName, Map<String,Object> facts)
```
`Finding.Severity` 只有 `CRITICAL(3) / WARNING(2) / INFO(1)`。

`FindingProvider` 三个方法：`String domain()` / `String ruleName()` / `List<Finding> detect(String factoryId)`。
`detect` **不声明 `throws`** —— 所以新异常必须是 `RuntimeException` 子类。

`FindingService.Result` 当前是 **5 参数** record：
`(findings, checkedRules, totalCount, countsByCode, failedRules)` + `complete()` 方法。
构造点共 **12 处**：`FindingServiceImpl.java:68` 1 处，`FindingTextRendererTest` 9 处，
`FindingActionPlanToolTest.java:53` 1 处，`MaterialStockSummaryToolTest` 若干处。
→ **Task 4 加第 6 个分量时必须同时加 5 参数重载构造器**，否则这 12 处全部编译失败。

Python gold 端点住在 `smartbi/api/gold_reads.py`，`router = APIRouter(prefix="/gold")`，
挂载后完整路径是 `/api/smartbi/gold/<path>`。租户解析统一走该文件的 `_resolve_tenant(factory_id)`。

`smartbi/gold/queries.py` 的 `tenant_conn(pool, factory_id)` 是**唯一**允许 `pool.acquire()` 的地方；
`test_gold_queries_tenant_context.py` 会扫描并禁止裸 `pool.acquire()`。新模块必须用 `tenant_conn`。

`PythonSmartBIClient#getRestaurantHealthCheckReport` 失败时 **返回 null**（供 bridge「诚实跳过」）。
本计划的新方法**不得沿用**这个约定 —— 见 Task 7。

---

## File Structure

**Python — Create (2)**

| 文件 | 职责 |
|---|---|
| `backend/python/smartbi/gold/restaurant/wastage_findings.py` | 两条规则 + `ACTIONABLE_WASTAGE_TYPES` 唯一定义 |
| `backend/python/smartbi/gold/tests/test_wastage_findings.py` | 两条规则的单测（含 07-30 阶跃 fixture） |

**Python — Modify (2)**

| 文件 | 改动 |
|---|---|
| `backend/python/smartbi/gold/__init__.py` | import 块 + `__all__` **双注册** |
| `backend/python/smartbi/api/gold_reads.py` | 新增 `GET /gold/restaurant-wastage-findings` |

**Java — Create (4)**

| 文件 | 职责 |
|---|---|
| `service/finding/FindingNotApplicableException.java` | 第三态信号（`RuntimeException`） |
| `service/finding/impl/RestaurantWastageShareSpikeProvider.java` | R1 转发 |
| `service/finding/impl/RestaurantWastageConcentrationProvider.java` | R2 转发 |
| `service/finding/impl/RestaurantWastageFindingReader.java` | 两个 provider 共用的 Python 调用 + dict→Finding 转换 |

**Java — Modify (5)**

| 文件 | 改动 |
|---|---|
| `service/finding/FindingService.java` | `Result` 加 `skippedRules` + `SkippedRule` + 5 参数重载 |
| `service/finding/impl/FindingServiceImpl.java` | 新增 `catch (FindingNotApplicableException)` 分支 |
| `service/finding/FindingTextRenderer.java` | 第三态分支 + 两个 code 的模板 |
| `client/PythonSmartBIClient.java` | 新增 `getRestaurantWastageFindings` |
| `ai/tool/impl/restaurant/RestaurantWastageAnomalyTool.java` | 身子换成发现层，删主库读取 |

**Java — Test (5)**：与源文件同包镜像路径下的 `*Test.java`。

> **为什么多一个 `RestaurantWastageFindingReader`**：两个 provider 的「调 Python → 判 `applicable` → dict→Finding」逻辑逐字相同，只有 rule 名和 code 不同。放进各自 provider 会是复制粘贴两份；抽出来后两个 provider 各自只剩 20 行声明。

---

## 全部 Task 一览

| Task | 交付物 | 依赖 |
|---|---|---|
| 0 | worktree 就绪 + 基线绿 | — |
| 1 | Python R2 `detect_type_concentration` | 0 |
| 2 | Python R1 `detect_share_spike` + 两道闸 | 0 |
| 3 | Python 双注册 + 端点 + 真跑 import | 1,2 |
| 4 | Java 第三态：异常 + `Result.skippedRules` | 0 |
| 5 | Java `FindingServiceImpl` skip 分支 | 4 |
| 6 | Java `FindingTextRenderer` 三态 + 两模板 | 4 |
| 7 | Java `PythonSmartBIClient` 新方法 | 0 |
| 8 | Java `Reader` + 两个 provider | 4,7 |
| 9 | Java `RestaurantWastageAnomalyTool` 换身子 | 5,6,8 |
| 10 | 变异验证（5 条，每条必须真变红） | 9 |
| 11 | 部署 + 真机验收 | 10 |

---

### Task 0: worktree 就绪 + 基线绿

**Files:** 无（环境准备）

**Interfaces:**
- Consumes: 无
- Produces: 一个 off `origin/main` 的干净工作目录，且已知基线状态

- [ ] **Step 1: 确认 worktree 与基底**

Run:
```bash
cd C:/Users/Steve/cretas-rest-finding
git status --short && git log --oneline -1
```
Expected: 只可能出现本计划文件；`git log` 显示 `45427ec2c5` 或其后代（该 commit 的父是 `origin/main` 的 `0e260359a6`）。

- [ ] **Step 2: Java 基线绿**

Run: `cd backend/java/cretas-api && mvn -q -Dtest='FindingTest,FindingServiceImplTest,FindingTextRendererTest,LowStockFindingProviderTest' test`
Expected: BUILD SUCCESS。**若失败先停下排查环境** —— 否则后面无法区分「我写挂了」和「基线本来就挂」。

- [ ] **Step 3: Python 基线绿**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_gold_reads_restaurant.py -q`
Expected: 全绿。同样，红了先排查环境。

---

### Task 1: Python R2 `detect_type_concentration`

**Files:**
- Create: `backend/python/smartbi/gold/restaurant/wastage_findings.py`
- Test: `backend/python/smartbi/gold/tests/test_wastage_findings.py`

**Interfaces:**
- Consumes: `smartbi.gold.queries.tenant_conn(pool, factory_id)`
- Produces:
  - `ACTIONABLE_WASTAGE_TYPES: dict[str, bool]`
  - `async detect_type_concentration(pool, factory_id: str, *, window_days: int = 7) -> dict`
  - 返回 dict 形状（两条规则共用，Task 2 复用）：
    ```python
    {"rule": str, "applicable": bool, "skip_reason": str | None, "findings": list[dict]}
    ```
  - R2 的 finding dict：`{"code","subject_id","subject_name","severity","actionability","facts"}`
    其中 `facts = {"cost","share","windowDays","totalCost"}`

- [ ] **Step 1: 写失败的测试**

Create `backend/python/smartbi/gold/tests/test_wastage_findings.py`:

```python
"""Unit tests for restaurant wastage finding rules.

Fake asyncpg pool/conn — no real DB. Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_wastage_findings.py -v
"""
from __future__ import annotations

import asyncio

import pytest

from smartbi.gold.restaurant.wastage_findings import (
    ACTIONABLE_WASTAGE_TYPES,
    detect_type_concentration,
)


class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _SeqConn:
    """Returns queued responses in call order — the rules issue several
    different queries, so one fixed row-set (as in test_gold_reads_restaurant)
    would feed the wrong rows to the wrong query."""

    def __init__(self, fetch_responses, fetchrow_responses=None):
        self._fetch = list(fetch_responses)
        self._fetchrow = list(fetchrow_responses or [])
        self.sqls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        self.sqls.append(sql)
        return [_FakeRecord(r) for r in self._fetch.pop(0)]

    async def fetchrow(self, sql, *a, **k):
        self.sqls.append(sql)
        row = self._fetchrow.pop(0)
        return _FakeRecord(row) if row is not None else None


class _SeqPool:
    def __init__(self, fetch_responses, fetchrow_responses=None):
        self.conn = _SeqConn(fetch_responses, fetchrow_responses)

    def acquire(self):
        return self.conn


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


# ── R2: type concentration ────────────────────────────────────────────

def test_r2_reports_only_actionable_type_over_threshold():
    """MOCK_REST 实测形状：加工损耗 52.9% 结构性不报，变质 37.2% 可行动要报。"""
    pool = _SeqPool([[
        {"wastage_type": "加工损耗", "cost": 413206.52},
        {"wastage_type": "变质", "cost": 291112.44},
        {"wastage_type": "客诉退菜", "cost": 77403.93},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is True
    assert out["skip_reason"] is None
    names = [f["subject_name"] for f in out["findings"]]
    assert names == ["变质"], f"加工损耗是结构性的不该报, 客诉退菜 9.9% 未过闸: {names}"
    assert out["findings"][0]["code"] == "WASTAGE_TYPE_CONCENTRATION"


def test_r2_structural_type_never_reported_even_at_high_share():
    """加工损耗占 90% 也不报 —— 它是切配常态, 店长知道也动不了。"""
    pool = _SeqPool([[
        {"wastage_type": "加工损耗", "cost": 900.0},
        {"wastage_type": "变质", "cost": 100.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"] == []
    assert out["applicable"] is True


def test_r2_unknown_type_defaults_to_actionable():
    """未知新类型宁多报不漏报。"""
    pool = _SeqPool([[
        {"wastage_type": "运输破损", "cost": 800.0},
        {"wastage_type": "变质", "cost": 200.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert [f["subject_name"] for f in out["findings"]] == ["运输破损"]


def test_r2_below_30pct_not_reported():
    pool = _SeqPool([[
        {"wastage_type": "变质", "cost": 299.0},
        {"wastage_type": "加工损耗", "cost": 701.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"] == []


def test_r2_no_rows_is_applicable_with_no_findings():
    """真的没有 —— 不是 skip, 不是失败。"""
    pool = _SeqPool([[]], fetchrow_responses=[{"total_cost": 0.0}])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is True
    assert out["skip_reason"] is None
    assert out["findings"] == []


def test_r2_skips_when_by_type_kpi_unmaterialized():
    """by_type 全零但 totals 有钱 = materialize 没跑过。不得对一堆零排序。"""
    pool = _SeqPool([[]], fetchrow_responses=[{"total_cost": 894270.0}])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is False
    assert "未物化" in out["skip_reason"]
    assert out["findings"] == []


def test_r2_severity_warning_above_half():
    pool = _SeqPool([[
        {"wastage_type": "变质", "cost": 600.0},
        {"wastage_type": "加工损耗", "cost": 400.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"][0]["severity"] == "WARNING"


def test_r2_window_boundary_is_exclusive_on_the_old_side():
    """`>=` 会取到 8 天并与基线窗重叠 —— 断言 SQL 用的是 `>`。"""
    pool = _SeqPool([[{"wastage_type": "变质", "cost": 100.0}]])

    _run(detect_type_concentration(pool, "MOCK_REST"))

    sql = pool.conn.sqls[0]
    assert "date > CURRENT_DATE" in sql
    assert "date >= CURRENT_DATE" not in sql


def test_actionable_config_is_the_only_definition():
    """配置在这个模块里, 不许别处再写一份。"""
    assert ACTIONABLE_WASTAGE_TYPES["变质"] is True
    assert ACTIONABLE_WASTAGE_TYPES["客诉退菜"] is True
    assert ACTIONABLE_WASTAGE_TYPES["加工损耗"] is False
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: `ModuleNotFoundError: No module named 'smartbi.gold.restaurant.wastage_findings'`

- [ ] **Step 3: 写最小实现**

Create `backend/python/smartbi/gold/restaurant/wastage_findings.py`:

```python
"""餐饮损耗发现规则 (domain="restaurant")。

⛔ 本模块只产出结构化数字, 不产出话术 —— 渲染由 Java 侧 FindingTextRenderer 负责。
⛔ 本模块不重定义 ingredient_waste_rate。那个指标由 health_check_metrics.py 的
   DiagnosticsEngine 拥有 (损耗成本 / (领料成本 + 损耗成本), 对标行业 benchmark)。
   这里的两条规则是**不同的指标**: 一条看份额相对自身基线的漂移, 一条看类型集中度。

数据源: gold `agg_restaurant_daily_ops` (与 resolve_wastage_top 同源, 口径一致)。
  kpi_kind='wastage_cost'          dim_value_id  -> dim_ingredient.ingredient_id
  kpi_kind='wastage_cost_by_type'  dim_value_str -> 损耗类型 (中文自由文本)
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from smartbi.gold.queries import tenant_conn

logger = logging.getLogger(__name__)


# ⛔ 唯一定义处。不得在 Java 侧或 web-admin 再写一份。
#    取值是**中文自由文本** (实测: 加工损耗 / 客诉退菜 / 变质)。建表注释里的
#    EXPIRED/DAMAGED/SPOILED/PROCESSING/OTHER 是陈旧的, 库里没有这些值。
ACTIONABLE_WASTAGE_TYPES: Dict[str, bool] = {
    "变质": True,       # 可行动: 备货量 / FIFO / 冷链
    "客诉退菜": True,   # 可行动: 出品质量 / 菜品调整
    "加工损耗": False,  # 结构性: 切配边角料是常态, 店长知道也动不了
}

#: 未知新类型的默认归属。宁多报不漏报 —— 漏报一个真问题比多报一条噪音贵。
_UNKNOWN_TYPE_ACTIONABLE = True

_TYPE_CONCENTRATION_MIN_SHARE = 0.30
_TYPE_CONCENTRATION_WARNING_SHARE = 0.50
_TYPE_CONCENTRATION_ACTIONABILITY = 70


def _skip(rule: str, reason: str) -> Dict[str, Any]:
    """「数据没采集到」—— 与「真的没有」(applicable=True, findings=[]) 严格区分。"""
    return {"rule": rule, "applicable": False, "skip_reason": reason, "findings": []}


def _ok(rule: str, findings: List[Dict[str, Any]]) -> Dict[str, Any]:
    return {"rule": rule, "applicable": True, "skip_reason": None, "findings": findings}


async def detect_type_concentration(
    pool, factory_id: str, *, window_days: int = 7,
) -> Dict[str, Any]:
    """单一损耗类型占比过高。单窗口, 无基线 —— 任何租户第一天可用。

    触发 = 类型属于可行动类型 AND 占比 >= 30%。
    刻意不设绝对金额闸: 占比对不同规模门店自动适配。
    """
    rule = "type_concentration"
    async with tenant_conn(pool, factory_id) as conn:
        rows = await conn.fetch(
            """
            SELECT dim_value_str AS wastage_type, SUM(value_num)::float AS cost
              FROM agg_restaurant_daily_ops
             WHERE factory_id = $1
               AND kpi_kind = 'wastage_cost_by_type'
               AND date > CURRENT_DATE - $2::int
               AND date <= CURRENT_DATE
             GROUP BY 1
            """,
            factory_id, window_days,
        )
        total = sum(float(r["cost"] or 0.0) for r in rows)

        if total <= 0:
            # 分不清「这家店本期真没损耗」和「per-type KPI 没物化」。
            # totals 表是直接从 Silver 算的, 拿它当阳性对照。
            totals_row = await conn.fetchrow(
                """
                SELECT COALESCE(SUM(wastage_cost_total), 0)::float AS total_cost
                  FROM agg_restaurant_daily_totals
                 WHERE factory_id = $1
                   AND date > CURRENT_DATE - $2::int
                   AND date <= CURRENT_DATE
                """,
                factory_id, window_days,
            )
            silver_total = float((totals_row or {}).get("total_cost") or 0.0)
            if silver_total > 0:
                return _skip(
                    rule,
                    f"损耗类型 KPI 未物化: totals 表本期有 ¥{silver_total:,.2f} "
                    f"但 wastage_cost_by_type 全为空",
                )
            return _ok(rule, [])

    findings: List[Dict[str, Any]] = []
    for r in rows:
        wastage_type = r["wastage_type"]
        cost = float(r["cost"] or 0.0)
        share = cost / total
        actionable = ACTIONABLE_WASTAGE_TYPES.get(wastage_type, _UNKNOWN_TYPE_ACTIONABLE)
        if not actionable or share < _TYPE_CONCENTRATION_MIN_SHARE:
            continue
        findings.append({
            "code": "WASTAGE_TYPE_CONCENTRATION",
            "subject_id": wastage_type,
            "subject_name": wastage_type,
            "severity": "WARNING" if share >= _TYPE_CONCENTRATION_WARNING_SHARE else "INFO",
            "actionability": _TYPE_CONCENTRATION_ACTIONABILITY,
            "facts": {
                "cost": round(cost, 2),
                "share": round(share * 100, 1),
                "windowDays": window_days,
                "totalCost": round(total, 2),
            },
        })
    findings.sort(key=lambda f: f["facts"]["cost"], reverse=True)
    return _ok(rule, findings)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: 9 passed

- [ ] **Step 5: Commit**

```bash
git add backend/python/smartbi/gold/restaurant/wastage_findings.py \
        backend/python/smartbi/gold/tests/test_wastage_findings.py
git commit -m "feat(wastage-findings): add type-concentration rule with actionable-type config" -- \
  backend/python/smartbi/gold/restaurant/wastage_findings.py \
  backend/python/smartbi/gold/tests/test_wastage_findings.py
```

---

### Task 2: Python R1 `detect_share_spike` + 两道同质闸

**Files:**
- Modify: `backend/python/smartbi/gold/restaurant/wastage_findings.py`（追加）
- Test: `backend/python/smartbi/gold/tests/test_wastage_findings.py`（追加）

**Interfaces:**
- Consumes: Task 1 的 `_skip` / `_ok` / `tenant_conn`
- Produces: `async detect_share_spike(pool, factory_id: str, *, cur_days: int = 7, base_days: int = 21) -> dict`
  - R1 的 finding dict `facts = {"costCur","shareCur","shareBase","amplification","windowDays","unit"}`

> **两道闸各管一种失效**：份额归一化管**幅度**跳变（全店一起涨在分子分母对消），Jaccard 闸管**口径**跳变（食材名单换了）。缺任何一道，2026-07-30 那次数据回填都会漏成 25 条假警报。
> 闸 A（历史长度）**单独挡不住** 07-30 那个 case —— base 窗有 21 天数据会通过。这正是必须有闸 B 的原因。

- [ ] **Step 1: 写失败的测试**

追加到 `backend/python/smartbi/gold/tests/test_wastage_findings.py` 末尾：

```python
# ── R1: share spike ───────────────────────────────────────────────────

from smartbi.gold.restaurant.wastage_findings import detect_share_spike  # noqa: E402


def _spike_pool(cur_rows, base_rows, base_days=21):
    """R1 依次发 3 个查询: cur 按食材 / base 按食材 / base 天数。"""
    return _SeqPool(
        [cur_rows, base_rows],
        fetchrow_responses=[{"days": base_days}],
    )


def test_r1_flags_ingredient_growing_faster_than_the_store():
    """份额 5% -> 10% = 放大 2 倍, 过 1.4 闸。"""
    cur = [
        {"dim_value_id": 1, "name": "鸡腿肉", "unit": "kg", "cost": 100.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 900.0},
    ]
    base = [
        {"dim_value_id": 1, "name": "鸡腿肉", "unit": "kg", "cost": 50.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 950.0},
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["applicable"] is True
    assert [f["subject_name"] for f in out["findings"]] == ["鸡腿肉"]
    assert out["findings"][0]["code"] == "WASTAGE_SHARE_SPIKE"
    assert out["findings"][0]["facts"]["amplification"] == 2.0


def test_r1_skips_on_the_2026_07_30_regime_change():
    """🔴 真实形状: base 13 种食材, cur 25 种 (12 种是 08-01 才出现的)。
    Jaccard = 13/25 = 0.52 < 0.8 -> 必须 skip, 不得喷 25 条。"""
    base = [
        {"dim_value_id": i, "name": f"食材{i}", "unit": "kg", "cost": 5000.0}
        for i in range(1, 14)
    ]
    cur = [
        {"dim_value_id": i, "name": f"食材{i}", "unit": "kg", "cost": 118000.0}
        for i in range(1, 26)
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["applicable"] is False, "食材名单从 13 变 25, 两期不可比"
    assert "名单不可比" in out["skip_reason"]
    assert "25" in out["skip_reason"] and "13" in out["skip_reason"]
    assert out["findings"] == []


def test_r1_uniform_scale_up_produces_no_findings():
    """全店一起涨 24 倍 (07-30 的幅度) 但名单不变 -> 份额不变 -> 0 条。
    这是份额归一化本身的作用, 与 Jaccard 闸无关。"""
    base = [
        {"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 100.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 300.0},
    ]
    cur = [
        {"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 2400.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 7200.0},
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["applicable"] is True
    assert out["findings"] == []


def test_r1_skips_when_baseline_history_too_short():
    cur = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 100.0}]
    base = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 50.0}]
    out = _run(detect_share_spike(_spike_pool(cur, base, base_days=6), "MOCK_REST"))

    assert out["applicable"] is False
    assert "历史不足" in out["skip_reason"]
    assert "6" in out["skip_reason"]


def test_r1_ingredient_without_baseline_is_dropped_not_infinite():
    """只在 cur 出现的食材没有基线, 不参与计算 —— 除零得不到「涨了无穷倍」。"""
    base = [
        {"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 500.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 500.0},
        {"dim_value_id": 3, "name": "虾", "unit": "kg", "cost": 500.0},
        {"dim_value_id": 4, "name": "蟹", "unit": "kg", "cost": 500.0},
    ]
    cur = base + [{"dim_value_id": 5, "name": "新食材", "unit": "kg", "cost": 9000.0}]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    # Jaccard = 4/5 = 0.8 恰好过闸
    assert out["applicable"] is True
    assert "新食材" not in [f["subject_name"] for f in out["findings"]]


def test_r1_below_5pct_share_not_reported():
    """放大 10 倍但当前份额只有 1% —— 金额太小, 不值得占用提示位。"""
    cur = [
        {"dim_value_id": 1, "name": "红糖", "unit": "kg", "cost": 10.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 990.0},
    ]
    base = [
        {"dim_value_id": 1, "name": "红糖", "unit": "kg", "cost": 1.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 999.0},
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["findings"] == []


def test_r1_window_boundaries_are_exclusive_and_non_overlapping():
    cur = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 100.0}]
    base = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 50.0}]
    pool = _spike_pool(cur, base)

    _run(detect_share_spike(pool, "MOCK_REST"))

    cur_sql, base_sql = pool.conn.sqls[0], pool.conn.sqls[1]
    assert "date >= CURRENT_DATE" not in cur_sql
    assert "date >= CURRENT_DATE" not in base_sql
    # base 的新端 = cur 的旧端, 用 <= / > 拼接, 不重叠
    assert "date <= CURRENT_DATE - $3::int" in base_sql
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: `ImportError: cannot import name 'detect_share_spike'`

- [ ] **Step 3: 写最小实现**

追加到 `backend/python/smartbi/gold/restaurant/wastage_findings.py` 末尾：

```python
_SHARE_SPIKE_MIN_AMPLIFICATION = 1.4
_SHARE_SPIKE_MIN_CUR_SHARE = 0.05
_SHARE_SPIKE_WARNING_AMPLIFICATION = 2.0
_SHARE_SPIKE_ACTIONABILITY = 60
_SHARE_SPIKE_MIN_BASE_DAYS = 14
_SHARE_SPIKE_MIN_JACCARD = 0.8


async def detect_share_spike(
    pool, factory_id: str, *, cur_days: int = 7, base_days: int = 21,
) -> Dict[str, Any]:
    """某食材的损耗份额相对自身基线放大。

    share(i, w)      = 食材 i 在窗口 w 的 wastage_cost / 窗口 w 全店 wastage_cost
    amplification(i) = share(i, cur) / share(i, base)
    触发 = amplification >= 1.4 AND share(i, cur) >= 5%

    分母用**全店总损耗**: 全店一起放大时分子分母对消, 所以 2026-07-30 那种
    「整个租户的损耗数据跳了 24 倍」不会被误读成 25 条食材异常。

    但份额归一化对消不了**食材名单变了** (同日 13 种 -> 25 种), 所以另有 Jaccard 闸。
    """
    rule = "share_spike"
    total_days = cur_days + base_days
    async with tenant_conn(pool, factory_id) as conn:
        cur_rows = await conn.fetch(
            """
            SELECT a.dim_value_id, i.name, i.unit,
                   SUM(a.value_num)::float AS cost
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON i.ingredient_id = a.dim_value_id
             WHERE a.factory_id = $1
               AND a.kpi_kind = 'wastage_cost'
               AND a.date > CURRENT_DATE - $2::int
               AND a.date <= CURRENT_DATE
             GROUP BY 1, 2, 3
            """,
            factory_id, cur_days,
        )
        base_rows = await conn.fetch(
            """
            SELECT a.dim_value_id, i.name, i.unit,
                   SUM(a.value_num)::float AS cost
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON i.ingredient_id = a.dim_value_id
             WHERE a.factory_id = $1
               AND a.kpi_kind = 'wastage_cost'
               AND a.date > CURRENT_DATE - $2::int
               AND a.date <= CURRENT_DATE - $3::int
             GROUP BY 1, 2, 3
            """,
            factory_id, total_days, cur_days,
        )
        days_row = await conn.fetchrow(
            """
            SELECT COUNT(DISTINCT date)::int AS days
              FROM agg_restaurant_daily_ops
             WHERE factory_id = $1
               AND kpi_kind = 'wastage_cost'
               AND date > CURRENT_DATE - $2::int
               AND date <= CURRENT_DATE - $3::int
            """,
            factory_id, total_days, cur_days,
        )

    # ── 闸 A: 基线历史长度 ──────────────────────────────────────────
    observed_base_days = int((days_row or {}).get("days") or 0)
    if observed_base_days < _SHARE_SPIKE_MIN_BASE_DAYS:
        return _skip(
            rule,
            f"基线历史不足: 前 {base_days} 天窗口内仅 {observed_base_days} 天有数据"
            f"(需 >= {_SHARE_SPIKE_MIN_BASE_DAYS} 天)",
        )

    cur_ids = {r["dim_value_id"] for r in cur_rows}
    base_ids = {r["dim_value_id"] for r in base_rows}
    union = cur_ids | base_ids
    if not union:
        return _ok(rule, [])

    # ── 闸 B: 食材名单同质 ──────────────────────────────────────────
    # 闸 A 挡不住 2026-07-30 那个 case (base 窗有 21 天数据会通过), 必须有这道。
    jaccard = len(cur_ids & base_ids) / len(union)
    if jaccard < _SHARE_SPIKE_MIN_JACCARD:
        return _skip(
            rule,
            f"两期食材名单不可比: 近 {cur_days} 天 {len(cur_ids)} 种 / "
            f"基线 {len(base_ids)} 种 (重合度 {jaccard:.0%}, 需 >= "
            f"{_SHARE_SPIKE_MIN_JACCARD:.0%})",
        )

    cur_total = sum(float(r["cost"] or 0.0) for r in cur_rows)
    base_total = sum(float(r["cost"] or 0.0) for r in base_rows)
    if cur_total <= 0 or base_total <= 0:
        return _ok(rule, [])

    base_by_id = {r["dim_value_id"]: float(r["cost"] or 0.0) for r in base_rows}

    findings: List[Dict[str, Any]] = []
    for r in cur_rows:
        base_cost = base_by_id.get(r["dim_value_id"], 0.0)
        if base_cost <= 0:
            # 只在 cur 出现的食材没有基线。不参与计算 —— 除零得不到
            # 「涨了无穷倍」这种结论, 它只是没有基线。名单变化已由闸 B 兜住。
            continue
        cur_cost = float(r["cost"] or 0.0)
        share_cur = cur_cost / cur_total
        share_base = base_cost / base_total
        amplification = share_cur / share_base
        if amplification < _SHARE_SPIKE_MIN_AMPLIFICATION:
            continue
        if share_cur < _SHARE_SPIKE_MIN_CUR_SHARE:
            continue
        findings.append({
            "code": "WASTAGE_SHARE_SPIKE",
            "subject_id": str(r["dim_value_id"]),
            "subject_name": r["name"],
            "severity": (
                "WARNING" if amplification >= _SHARE_SPIKE_WARNING_AMPLIFICATION else "INFO"
            ),
            "actionability": _SHARE_SPIKE_ACTIONABILITY,
            "facts": {
                "costCur": round(cur_cost, 2),
                "shareCur": round(share_cur * 100, 1),
                "shareBase": round(share_base * 100, 1),
                "amplification": round(amplification, 2),
                "windowDays": cur_days,
                "unit": r["unit"] or "",
            },
        })
    findings.sort(key=lambda f: f["facts"]["amplification"], reverse=True)
    return _ok(rule, findings)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: 16 passed

- [ ] **Step 5: Commit**

```bash
git add backend/python/smartbi/gold/restaurant/wastage_findings.py \
        backend/python/smartbi/gold/tests/test_wastage_findings.py
git commit -m "feat(wastage-findings): add share-spike rule with history and roster gates" -- \
  backend/python/smartbi/gold/restaurant/wastage_findings.py \
  backend/python/smartbi/gold/tests/test_wastage_findings.py
```

---

### Task 3: Python 双注册 + 端点 + 真跑一次 import

**Files:**
- Modify: `backend/python/smartbi/gold/__init__.py`
- Modify: `backend/python/smartbi/api/gold_reads.py`
- Test: `backend/python/smartbi/gold/tests/test_wastage_findings.py`（追加）

**Interfaces:**
- Consumes: Task 1/2 的两个 detect 函数
- Produces:
  - `from smartbi.gold import detect_share_spike, detect_type_concentration` 可用
  - `GET /api/smartbi/gold/restaurant-wastage-findings?factory_id=<fid>&rule=<share_spike|type_concentration>`

> 🔴 **函数写在模块里不等于能 import**：`gold_reads.py` 从 `smartbi.gold` 包导入，必须在 `__init__.py` 的 **import 块**和 **`__all__`** 都注册。判据是**真跑一次 import**，不是看代码。

- [ ] **Step 1: 写失败的测试**

追加到 `backend/python/smartbi/gold/tests/test_wastage_findings.py` 末尾：

```python
# ── 注册与路由 ────────────────────────────────────────────────────────

def test_both_rules_importable_from_package_root():
    """gold_reads 从 smartbi.gold 导入 —— import 块和 __all__ 缺一不可。"""
    import smartbi.gold as g

    assert hasattr(g, "detect_share_spike")
    assert hasattr(g, "detect_type_concentration")
    assert "detect_share_spike" in g.__all__
    assert "detect_type_concentration" in g.__all__


def test_endpoint_registered_on_gold_router():
    import smartbi.api.gold_reads as gr

    paths = {r.path for r in gr.router.routes}
    assert "/gold/restaurant-wastage-findings" in paths
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q -k "importable or endpoint"`
Expected: FAIL — `AttributeError: module 'smartbi.gold' has no attribute 'detect_share_spike'`

- [ ] **Step 3: 改 `__init__.py`（双注册）**

在 `backend/python/smartbi/gold/__init__.py` 的 `from smartbi.gold.queries import (...)` 之后、`from smartbi.gold.review_queries import (...)` 之前插入：

```python
from smartbi.gold.restaurant.wastage_findings import (
    detect_share_spike,
    detect_type_concentration,
)
```

并在 `__all__` 列表里（保持字母序，插在 `"discount_summary",` 之后）加入两行：

```python
    "detect_share_spike",
    "detect_type_concentration",
```

- [ ] **Step 4: 改 `gold_reads.py`（加端点）**

在 `backend/python/smartbi/api/gold_reads.py` 的 `from smartbi.gold import (` 导入块内加入两项：

```python
    detect_share_spike,
    detect_type_concentration,
```

在文件末尾追加端点：

```python
_WASTAGE_RULES = {
    "share_spike": detect_share_spike,
    "type_concentration": detect_type_concentration,
}


@router.get("/restaurant-wastage-findings")
async def get_restaurant_wastage_findings(
    request: Request,
    rule: str = Query(..., description="share_spike | type_concentration"),
    factory_id: Optional[str] = Query(None, description="belt-and-suspenders; defaults to JWT tenant"),
):
    """餐饮损耗发现规则。一次只算被问的那一条 (Java 侧一条规则一个 provider)。

    返回 {rule, applicable, skip_reason, findings[]}。
    applicable=False 表示「数据没采集到」, 与 findings=[] 的「真的没有」不同 ——
    Java 侧据此分别落进 skippedRules / checkedRules。
    """
    detector = _WASTAGE_RULES.get(rule)
    if detector is None:
        raise HTTPException(
            status_code=400,
            detail=f"unknown rule {rule!r}; expected one of {sorted(_WASTAGE_RULES)}",
        )
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    return await detector(pool, fid)
```

- [ ] **Step 5: 真跑一次 import（不是看代码）**

Run:
```bash
cd backend/python && python -c "from smartbi.gold import detect_share_spike, detect_type_concentration; print('import ok')"
```
Expected: 打印 `import ok`。**报 ImportError 就是没注册成功**，回到 Step 3。

- [ ] **Step 6: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: 18 passed

- [ ] **Step 7: 确认没打破既有 gold 测试**

Run: `cd backend/python && python -m pytest smartbi/gold/tests/ tests/test_gold_queries_tenant_context.py -q`
Expected: 全绿。`test_gold_queries_tenant_context.py` 会扫描裸 `pool.acquire()` —— 新模块用的是 `tenant_conn`，应当通过。

- [ ] **Step 8: Commit**

```bash
git add backend/python/smartbi/gold/__init__.py \
        backend/python/smartbi/api/gold_reads.py \
        backend/python/smartbi/gold/tests/test_wastage_findings.py
git commit -m "feat(gold-reads): expose restaurant wastage findings endpoint" -- \
  backend/python/smartbi/gold/__init__.py \
  backend/python/smartbi/api/gold_reads.py \
  backend/python/smartbi/gold/tests/test_wastage_findings.py
```

---

### Task 4: Java 第三态 —— 异常 + `Result.skippedRules`

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingNotApplicableException.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingService.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingServiceResultTest.java`

**Interfaces:**
- Consumes: `Finding`（既有）
- Produces:
  - `FindingNotApplicableException extends RuntimeException`，构造 `(String reason)`，getter `String reason()`
  - `FindingService.SkippedRule` — record `(String ruleName, String reason)`
  - `FindingService.Result` **6 参数**：`(findings, checkedRules, totalCount, countsByCode, failedRules, skippedRules)`
  - `FindingService.Result` **5 参数重载构造器**（`skippedRules` 默认 `List.of()`）

> 🔴 **必须加 5 参数重载**：既有 12 处构造点（`FindingServiceImpl.java:68`、`FindingTextRendererTest` 9 处、`FindingActionPlanToolTest.java:53`、`MaterialStockSummaryToolTest` 若干）全是 5 参数。不加重载 = 12 处编译失败，且会诱使实施者去改测试断言 —— 那些断言是对的，不该动。

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingServiceResultTest.java`:

```java
package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingService.Result} 的第三态与向后兼容。 */
class FindingServiceResultTest {

    @Test
    @DisplayName("UT-RES-01: 5 参数重载仍可用，skippedRules 默认为空")
    void fiveArgOverloadKeepsExistingCallSitesCompiling() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("低库存"), 0, Map.of(), List.of());

        assertNotNull(r.skippedRules());
        assertTrue(r.skippedRules().isEmpty());
    }

    @Test
    @DisplayName("UT-RES-02: 🔴 skippedRules 不影响 complete() —— 「判不了」不是「查询失败」")
    void skippedDoesNotMakeResultIncomplete() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("损耗类型集中度"), 0, Map.of(), List.of(),
                List.of(new FindingService.SkippedRule("食材损耗离群", "基线历史不足")));

        assertTrue(r.complete(),
                "complete() 的语义是「没有规则报错」。数据不足是诚实跳过, 不是故障, "
                        + "混进去会让调用方把两种状况当成同一件事");
        assertEquals(1, r.skippedRules().size());
    }

    @Test
    @DisplayName("UT-RES-03: failedRules 才让 complete() 为假")
    void failedRulesMakeResultIncomplete() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of(), 0, Map.of(), List.of("临期"));

        assertFalse(r.complete());
    }

    @Test
    @DisplayName("UT-RES-04: SkippedRule 同时带规则名和理由 —— 只有名字说不出为什么")
    void skippedRuleCarriesReason() {
        FindingService.SkippedRule s =
                new FindingService.SkippedRule("食材损耗离群", "两期食材名单不可比");

        assertEquals("食材损耗离群", s.ruleName());
        assertEquals("两期食材名单不可比", s.reason());
    }

    @Test
    @DisplayName("UT-RES-05: FindingNotApplicableException 是非受检异常")
    void exceptionIsUnchecked() {
        assertTrue(RuntimeException.class.isAssignableFrom(FindingNotApplicableException.class),
                "FindingProvider#detect 不声明 throws, 受检异常会打断全部既有实现");
        assertEquals("基线历史不足", new FindingNotApplicableException("基线历史不足").reason());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceResultTest test`
Expected: 编译失败，`cannot find symbol: class FindingNotApplicableException`

- [ ] **Step 3: 写 `FindingNotApplicableException`**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingNotApplicableException.java`:

```java
package com.cretas.aims.service.finding;

/**
 * 规则**诚实跳过**：数据不足以判断，而不是「查过了没异常」，也不是「查询失败」。
 *
 * <p>三态里的第三态。为什么不复用普通异常（落 {@code failedRules}）：那会把
 * 「历史不够」说成「查询失败」，用户看到的是同一句话，而这两件事的处置完全不同
 * —— 前者要等数据攒够，后者要去查服务。禁止降级处理不只是「别把失败说成正常」，
 * 也包括「别把两种不同的坏消息说成同一种」。
 *
 * <p>⚠️ 必须是 {@link RuntimeException}：{@code FindingProvider#detect} 的既有
 * 签名不声明 {@code throws}，改成受检异常会打断包括
 * {@code LowStockFindingProvider} 在内的全部既有实现。
 */
public class FindingNotApplicableException extends RuntimeException {

    private final String reason;

    public FindingNotApplicableException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** 给用户看的跳过理由，会原样出现在「暂不判断」那句话里。 */
    public String reason() {
        return reason;
    }
}
```

- [ ] **Step 4: 改 `FindingService.java`**

把 `record Result(...)` 整段替换为：

```java
    /**
     * @param findings     已排序并截断到 inline 上限的发现（可能为空）
     * @param checkedRules **实际成功跑完**的规则名。抛异常的规则不在此列——
     *                     否则 UI 会说出「已检查 X，均正常」这种假话。
     * @param totalCount   截断前的发现总数，用于「还有 N 项」
     * @param countsByCode 按 code 分组的**截断前**计数，供调用方复用
     *                     （如 lowStockCount = countsByCode.get("LOW_STOCK")）
     * @param failedRules  执行时抛异常、因而被从 {@code checkedRules} 剔除的规则名。
     *                     非空表示本次结果不完整——消费方不得把 countsByCode /
     *                     findings 当作「已确认无异常」来展示（禁止降级处理）。
     *                     用 {@link #complete()} 判断。
     * @param skippedRules 数据不足以判断而**诚实跳过**的规则（见
     *                     {@link FindingNotApplicableException}）。与
     *                     {@code failedRules} 严格区分：跳过不是故障，
     *                     **不影响** {@link #complete()}。
     */
    record Result(
            List<Finding> findings,
            List<String> checkedRules,
            int totalCount,
            Map<String, Integer> countsByCode,
            List<String> failedRules,
            List<SkippedRule> skippedRules
    ) {
        /**
         * 5 参数重载。既有 12 处构造点（inventory 域与其测试）全是 5 参数，
         * 保持它们逐字不变 —— 那些断言是对的，不该为了新字段去改。
         */
        public Result(List<Finding> findings, List<String> checkedRules, int totalCount,
                      Map<String, Integer> countsByCode, List<String> failedRules) {
            this(findings, checkedRules, totalCount, countsByCode, failedRules, List.of());
        }

        /** true = 所有匹配 domain 的规则都跑完了；false = 至少一条规则失败，结果不完整。 */
        public boolean complete() {
            return failedRules.isEmpty();
        }
    }

    /** 一条被诚实跳过的规则：名字 + 为什么。只有名字说不出为什么。 */
    record SkippedRule(String ruleName, String reason) {}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceResultTest test`
Expected: PASS，5 个测试全绿

- [ ] **Step 6: 🔴 确认既有 12 处构造点没被打破**

Run: `cd backend/java/cretas-api && mvn -q -Dtest='FindingTextRendererTest,FindingServiceImplTest,FindingActionPlanToolTest,MaterialStockSummaryToolTest' test`
Expected: BUILD SUCCESS，**零测试文件被修改**。若有编译错误，说明重载没写对 —— 改重载，不要改测试。

- [ ] **Step 7: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingNotApplicableException.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingService.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingServiceResultTest.java
git commit -m "feat(finding): add skipped-rule third state distinct from failure" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingNotApplicableException.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingService.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingServiceResultTest.java
```

---

### Task 5: Java `FindingServiceImpl` skip 分支

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java:39-70`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/FindingServiceImplTest.java`（追加）

**Interfaces:**
- Consumes: Task 4 的 `FindingNotApplicableException` / `SkippedRule`
- Produces: `detectInline` 返回的 `Result.skippedRules()` 非空当且仅当有 provider 抛 `FindingNotApplicableException`

- [ ] **Step 1: 写失败的测试**

追加到 `FindingServiceImplTest.java` 类体末尾（沿用该文件既有的 `stub` / `exploding` / `finding` 辅助方法风格）：

```java
    /** 必定诚实跳过的假 provider。 */
    private static FindingProvider skipping(String domain, String ruleName, String reason) {
        return new FindingProvider() {
            @Override public String domain() { return domain; }
            @Override public String ruleName() { return ruleName; }
            @Override public List<Finding> detect(String factoryId) {
                throw new com.cretas.aims.service.finding.FindingNotApplicableException(reason);
            }
        };
    }

    @Test
    @DisplayName("UT-FSI-07: 🔴 跳过的规则进 skippedRules，不进 checkedRules 也不进 failedRules")
    void skippedRuleLandsInItsOwnBucket() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存"),
                skipping("inventory", "食材损耗离群", "基线历史不足: 仅 6 天")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("低库存"), r.checkedRules(),
                "跳过的规则留在 checkedRules 里, UI 会说「已检查 食材损耗离群, 均正常」");
        assertTrue(r.failedRules().isEmpty(),
                "数据不足不是故障, 混进 failedRules 会让用户以为服务坏了");
        assertEquals(1, r.skippedRules().size());
        assertEquals("食材损耗离群", r.skippedRules().get(0).ruleName());
        assertEquals("基线历史不足: 仅 6 天", r.skippedRules().get(0).reason());
    }

    @Test
    @DisplayName("UT-FSI-08: 跳过不影响 complete()")
    void skippedKeepsResultComplete() {
        FindingService svc = new FindingServiceImpl(List.of(
                skipping("inventory", "食材损耗离群", "两期食材名单不可比")), 2);

        assertTrue(svc.detectInline(FACTORY_ID, "inventory").complete());
    }

    @Test
    @DisplayName("UT-FSI-09: 跳过与失败可以同时发生，各归各的桶")
    void skippedAndFailedCoexist() {
        FindingService svc = new FindingServiceImpl(List.of(
                skipping("inventory", "食材损耗离群", "基线历史不足"),
                exploding("inventory", "损耗类型集中度")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("食材损耗离群"),
                r.skippedRules().stream().map(FindingService.SkippedRule::ruleName).toList());
        assertEquals(List.of("损耗类型集中度"), r.failedRules());
        assertFalse(r.complete());
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: FAIL — UT-FSI-07 报「跳过的规则留在 checkedRules 里」（当前实现把它当普通异常吞进 failedRules，且 `skippedRules()` 恒空）

- [ ] **Step 3: 改 `FindingServiceImpl`**

在 import 区加入：

```java
import com.cretas.aims.service.finding.FindingNotApplicableException;
```

把 `detectInline` 方法体替换为：

```java
    @Override
    public Result detectInline(String factoryId, String domain) {
        List<Finding> all = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();

        for (FindingProvider provider : providers) {
            if (!provider.domain().equals(domain)) {
                continue;
            }
            try {
                all.addAll(provider.detect(factoryId));
                checked.add(provider.ruleName());
            } catch (FindingNotApplicableException notApplicable) {
                // 数据不足以判断 —— 诚实跳过, 不是故障。必须在 catch(Exception)
                // 之前, 否则会被下面那条当成失败吞掉, 用户看到的就成了「服务坏了」。
                log.info("Finding 规则数据不足, 诚实跳过: rule={}, domain={}, factoryId={}, reason={}",
                        provider.ruleName(), domain, factoryId, notApplicable.reason());
                skipped.add(new SkippedRule(provider.ruleName(), notApplicable.reason()));
            } catch (Exception e) {
                log.warn("Finding 规则执行失败, 已从 checkedRules 剔除: rule={}, domain={}, factoryId={}",
                        provider.ruleName(), domain, factoryId, e);
                failed.add(provider.ruleName());
            }
        }

        Map<String, Integer> countsByCode = new LinkedHashMap<>();
        for (Finding f : all) {
            countsByCode.merge(f.code(), 1, Integer::sum);
        }

        all.sort(Comparator.comparingInt(Finding::rankScore).reversed());
        int total = all.size();
        List<Finding> top = total > inlineMax ? all.subList(0, inlineMax) : all;

        return new Result(List.copyOf(top), List.copyOf(checked), total,
                Map.copyOf(countsByCode), List.copyOf(failed), List.copyOf(skipped));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: PASS，9 个测试全绿（既有 6 + 新增 3）

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/FindingServiceImplTest.java
git commit -m "feat(finding): route not-applicable rules to skippedRules bucket" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/FindingServiceImplTest.java
```

---

### Task 6: Java `FindingTextRenderer` 三态 + 两个模板

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTextRendererTest.java`（追加）

**Interfaces:**
- Consumes: Task 4 的 `SkippedRule`
- Produces: `renderInline` 在 `skippedRules` 非空时**额外**输出一行 `ℹ️ <ruleName>：<reason>，暂不判断。`；新增 `WASTAGE_SHARE_SPIKE` / `WASTAGE_TYPE_CONCENTRATION` 两个模板

> 🔴 **断言只能针对 skip 那一行**，不能断言整段输出不含「正常」—— 三态可以同时出现（一条规则跑完无发现 → 「均正常」，另一条 skip），那时整段里出现「正常」是对的。断言写错会逼实施者把正确行为改坏。
> 🔴 **R1 话术只能说「涨得比全店快 N 倍」，不得说「涨了 N 倍」**：份额是零和的，某食材份额上升有一部分是别的食材下降的机械结果。

- [ ] **Step 1: 写失败的测试**

追加到 `FindingTextRendererTest.java` 类体末尾：

```java
    private static FindingService.SkippedRule skip(String rule, String reason) {
        return new FindingService.SkippedRule(rule, reason);
    }

    private static Finding shareSpike(String name) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("costCur", 81943.91);
        facts.put("shareCur", 10.5);
        facts.put("shareBase", 7.1);
        facts.put("amplification", 1.48);
        facts.put("windowDays", 7);
        facts.put("unit", "kg");
        return new Finding("WASTAGE_SHARE_SPIKE", "restaurant",
                Finding.Severity.INFO, 60, "M-" + name, name, facts);
    }

    private static Finding typeConcentration(String type) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("cost", 291112.44);
        facts.put("share", 37.2);
        facts.put("windowDays", 7);
        facts.put("totalCost", 782722.89);
        return new Finding("WASTAGE_TYPE_CONCENTRATION", "restaurant",
                Finding.Severity.INFO, 70, type, type, facts);
    }

    @Test
    @DisplayName("UT-FTR-08: 🔴 只有跳过时说「暂不判断」，且那一行不含「正常」")
    void skippedOnlyRendersUndecided() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of(), 0, Map.of(), List.of(),
                List.of(skip("食材损耗离群", "两期食材名单不可比：近7天 25 种 / 基线 13 种")));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("食材损耗离群"), text);
        assertTrue(text.contains("两期食材名单不可比"), text);
        assertTrue(text.contains("暂不判断"), text);
        assertFalse(text.contains("正常"),
                "本例没有任何规则跑完, 整段里不该出现「正常」: " + text);
    }

    @Test
    @DisplayName("UT-FTR-09: 🔴 组合态 —— 一条均正常 + 一条判不了，两句都要说")
    void checkedAndSkippedBothSpoken() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("损耗类型集中度"), 0, Map.of(), List.of(),
                List.of(skip("食材损耗离群", "基线历史不足：仅 6 天")));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("已检查 损耗类型集中度"), text);
        assertTrue(text.contains("均正常"), text);
        assertTrue(text.contains("食材损耗离群"),
                "只说前半句 = 把「判不了」渲染成了「都正常」: " + text);
        assertTrue(text.contains("暂不判断"), text);
    }

    @Test
    @DisplayName("UT-FTR-10: 有发现时跳过行仍然要说")
    void findingsAndSkippedCoexist() {
        FindingService.Result r = new FindingService.Result(
                List.of(typeConcentration("变质")), List.of("损耗类型集中度"), 1,
                Map.of("WASTAGE_TYPE_CONCENTRATION", 1), List.of(),
                List.of(skip("食材损耗离群", "基线历史不足")));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("变质"), text);
        assertTrue(text.contains("暂不判断"), text);
    }

    @Test
    @DisplayName("UT-FTR-11: 三个桶全空仍然返回空串（既有行为不变）")
    void nothingAtAllStillRendersNothing() {
        assertEquals("", renderer.renderInline(new FindingService.Result(
                List.of(), List.of(), 0, Map.of(), List.of(), List.of())));
    }

    @Test
    @DisplayName("UT-FTR-12: 🔴 R1 话术说「涨得比全店快」，不得说「涨了 N 倍」")
    void shareSpikeMustNotClaimAbsoluteGrowth() {
        FindingService.Result r = new FindingService.Result(
                List.of(shareSpike("鸡腿肉")), List.of("食材损耗离群"), 1,
                Map.of("WASTAGE_SHARE_SPIKE", 1), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("鸡腿肉"), text);
        assertTrue(text.contains("涨得比全店快"),
                "份额是零和的, 说「涨了 1.48 倍」是把别人下降的份额算到它头上: " + text);
        assertFalse(text.contains("涨了"), text);
        assertTrue(text.contains("1.48"), text);
        assertTrue(text.contains("10.5"), text);
        assertTrue(text.contains("7.1"), text);
    }

    @Test
    @DisplayName("UT-FTR-13: R2 模板说出类型/金额/占比")
    void typeConcentrationTemplate() {
        FindingService.Result r = new FindingService.Result(
                List.of(typeConcentration("变质")), List.of("损耗类型集中度"), 1,
                Map.of("WASTAGE_TYPE_CONCENTRATION", 1), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("变质"), text);
        assertTrue(text.contains("291112.44"), text);
        assertTrue(text.contains("37.2"), text);
        assertFalse(text.contains("null"), text);
    }
```

若该测试类尚未 import `LinkedHashMap`，在 import 区加入 `import java.util.LinkedHashMap;`。

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTextRendererTest test`
Expected: FAIL — UT-FTR-08 拿到空串（当前实现 `checkedRules` 为空就直接 `return ""`）

- [ ] **Step 3: 改 `FindingTextRenderer`**

把 `renderInline` 与 `renderOne` 之间的内容替换为（`renderOne` 的 `LOW_STOCK` 分支与兜底保持原样，只在其前面插入两个新分支）：

```java
    public String renderInline(FindingService.Result result) {
        String skipText = renderSkipped(result);

        if (result.checkedRules().isEmpty()) {
            // 一条规则都没成功跑完 —— 绝不能渲染成「均正常」(禁止降级处理)。
            // 但若有**诚实跳过**的规则, 仍要把它说出来: 否则「判不了」和
            // 「什么都没发生」对用户是同一个空白, 三态就塌回两态了。
            return skipText;
        }

        String checked = String.join(" / ", result.checkedRules());

        if (result.findings().isEmpty()) {
            // checkedRules 非空只能证明"至少一条规则跑完了", 不能证明"全部规则
            // 都跑完了"——2+ 条规则时可能一条成功零发现、另一条同时炸了。此时
            // 不能只说"均正常"(那对没跑完的那条是假话), 必须点名跑失败的规则,
            // 让用户知道这不是一次完整的检查结果 (禁止降级处理)。
            if (!result.complete()) {
                return withSkip("⚠️ 已检查 " + checked + "，均正常；另有 "
                        + String.join(" / ", result.failedRules())
                        + " 检查失败，暂无法判断。", skipText);
            }
            return withSkip("✅ 已检查 " + checked + "，均正常。", skipText);
        }

        String lines = result.findings().stream()
                .map(this::renderOne)
                .collect(Collectors.joining("\n"));

        int remaining = result.totalCount() - result.findings().size();
        String more = remaining > 0 ? "\n还有 " + remaining + " 项待查看" : "";

        return withSkip("⚠️ 顺带 " + result.findings().size() + " 件事：\n" + lines + more,
                skipText);
    }

    /**
     * 「数据没采集到」那一态。刻意**不含**「正常」二字 —— 这一行的全部意义就是
     * 告诉用户这条规则这次没给出结论，说成正常就是把缺数据渲染成了健康。
     */
    private String renderSkipped(FindingService.Result result) {
        if (result.skippedRules().isEmpty()) {
            return "";
        }
        return result.skippedRules().stream()
                .map(s -> "ℹ️ " + s.ruleName() + "：" + s.reason() + "，暂不判断。")
                .collect(Collectors.joining("\n"));
    }

    private String withSkip(String base, String skipText) {
        return skipText.isEmpty() ? base : base + "\n" + skipText;
    }
```

在 `renderOne` 方法体的 `if ("LOW_STOCK".equals(f.code())) { ... }` **之前**插入：

```java
        if ("WASTAGE_SHARE_SPIKE".equals(f.code())) {
            // ⛔ 只能说「涨得比全店快」。份额是零和的: 某食材份额上升有一部分是
            //    别的食材下降的机械结果, 说「涨了 N 倍」是把别人的下降算到它头上。
            return String.format(" · %s 近%s天损耗 ¥%s，占全店 %s%%（基线 %s%%），涨得比全店快 %s 倍",
                    f.subjectName(),
                    f.facts().get("windowDays"),
                    f.facts().get("costCur"),
                    f.facts().get("shareCur"),
                    f.facts().get("shareBase"),
                    f.facts().get("amplification"));
        }
        if ("WASTAGE_TYPE_CONCENTRATION".equals(f.code())) {
            return String.format(" · %s损耗近%s天 ¥%s，占全店损耗 %s%%",
                    f.subjectName(),
                    f.facts().get("windowDays"),
                    f.facts().get("cost"),
                    f.facts().get("share"));
        }
```

> `renderOne` 现在是 3 个分支的 if 链。**先不抽策略表** —— 3 个分支还读得动，等第 4 个 code 进来再抽（YAGNI）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTextRendererTest test`
Expected: PASS，13 个测试全绿（既有 7 + 新增 6）

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTextRendererTest.java
git commit -m "feat(finding): render the undecided state and two wastage templates" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTextRendererTest.java
```

---

### Task 7: Java `PythonSmartBIClient#getRestaurantWastageFindings`

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java`（在 `getRestaurantHealthCheckReport` 之后追加）
- Test: 无独立单测（该类是薄 HTTP 封装，行为由 Task 8 的 provider 测试以 mock 覆盖）

**Interfaces:**
- Consumes: 既有 `serviceBaseUrl` / `requireFactoryId` / `executeWithRetry` / `config`
- Produces:
  ```java
  Map<String, Object> getRestaurantWastageFindings(String factoryId, String rule)
          throws PythonServiceUnavailableException
  ```
  成功返回 Python 的原始 Map；**任何失败都抛 `PythonServiceUnavailableException`，绝不返回 null**

> 🔴 **不得沿用 `getRestaurantHealthCheckReport` 的 `return null`**：那个约定服务的是 alert bridge 的「诚实跳过整次 sweep」。在发现层里返回 null 会被 provider 变成空列表 → 渲染成「均正常」= 把查询失败说成了正常。这里必须让异常穿出去，落进 `failedRules`。

- [ ] **Step 1: 写实现**

在 `PythonSmartBIClient.java` 的 `getRestaurantHealthCheckReport` 方法之后插入：

```java
    /**
     * 餐饮损耗发现规则 (domain="restaurant" 的 FindingProvider 用)。
     *
     * <p>⛔ 与 {@link #getRestaurantHealthCheckReport} 不同, 本方法**失败即抛**,
     * 绝不返回 null。发现层的调用方会把 null/空当作「查过了, 没异常」渲染成
     * 「均正常」—— 那就是把查询失败说成了健康 (禁止降级处理)。异常穿出去才能
     * 落进 FindingService 的 failedRules, 让用户看到「检查失败」。
     *
     * @param rule {@code share_spike} 或 {@code type_concentration}
     * @return Python 原始响应 {rule, applicable, skip_reason, findings[]}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRestaurantWastageFindings(String factoryId, String rule)
            throws PythonServiceUnavailableException {
        if (!config.isEnabled()) {
            throw new PythonServiceUnavailableException("Python SmartBI 服务未启用");
        }

        final String trustedFactoryId;
        try {
            trustedFactoryId = requireFactoryId(factoryId);
        } catch (IOException invalidFactory) {
            throw new PythonServiceUnavailableException(
                    "factoryId 非法: " + invalidFactory.getMessage());
        }

        HttpUrl url = serviceBaseUrl.newBuilder()
                .addPathSegments("api/smartbi/gold/restaurant-wastage-findings")
                .addQueryParameter("factory_id", trustedFactoryId)
                .addQueryParameter("rule", rule)
                .build();

        Request httpRequest = new Request.Builder()
                .url(url)
                .header("X-Factory-Id", trustedFactoryId)
                .get()
                .build();
        log.info("调用 Python 餐饮损耗发现: factoryId={}, rule={}", trustedFactoryId, rule);

        try {
            Map<String, Object> body = executeWithRetry(httpRequest, Map.class);
            if (body == null) {
                throw new PythonServiceUnavailableException(
                        "餐饮损耗发现返回空响应: rule=" + rule);
            }
            return body;
        } catch (IOException e) {
            throw new PythonServiceUnavailableException(
                    "餐饮损耗发现调用失败: rule=" + rule + ", " + e.getMessage());
        }
    }
```

- [ ] **Step 2: 确认编译通过**

Run: `cd backend/java/cretas-api && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。若 `PythonServiceUnavailableException` 的构造签名不接受单 String，按该类实际签名调整（不要改那个类）。

- [ ] **Step 3: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java
git commit -m "feat(python-client): add fail-loud restaurant wastage findings call" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java
```

---

### Task 8: Java `Reader` + 两个 provider

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReader.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageShareSpikeProvider.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageConcentrationProvider.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReaderTest.java`

**Interfaces:**
- Consumes: Task 7 的 `getRestaurantWastageFindings`；Task 4 的 `FindingNotApplicableException`
- Produces:
  - `RestaurantWastageFindingReader#read(String factoryId, String rule) : List<Finding>`
  - `RestaurantWastageShareSpikeProvider` — `domain()="restaurant"`, `ruleName()="食材损耗离群"`, rule=`share_spike`
  - `RestaurantWastageConcentrationProvider` — `domain()="restaurant"`, `ruleName()="损耗类型集中度"`, rule=`type_concentration`

> **信封是 snake_case，facts 是 camelCase**：Python 侧 `subject_id` / `subject_name` / `skip_reason` 是信封字段；`facts` 里的键（`costCur` / `shareCur` / …）Python 已按 camelCase 产出，**原样透传**，不做转换。

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReaderTest.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.exception.PythonServiceUnavailableException;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingNotApplicableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RestaurantWastageFindingReader}. */
@ExtendWith(MockitoExtension.class)
class RestaurantWastageFindingReaderTest {

    private static final String FACTORY_ID = "MOCK_REST";

    @InjectMocks
    private RestaurantWastageFindingReader reader;

    @Mock
    private PythonSmartBIClient pythonSmartBIClient;

    private static Map<String, Object> body(Object applicable, Object skipReason, List<Object> findings) {
        return Map.of(
                "rule", "type_concentration",
                "applicable", applicable,
                "skip_reason", skipReason == null ? "" : skipReason,
                "findings", findings);
    }

    @Test
    @DisplayName("UT-RWR-01: dict → Finding 形状转换，facts 原样透传")
    void mapsDictToFinding() throws Exception {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(true, null, List.of(Map.of(
                        "code", "WASTAGE_TYPE_CONCENTRATION",
                        "subject_id", "变质",
                        "subject_name", "变质",
                        "severity", "INFO",
                        "actionability", 70,
                        "facts", Map.of("cost", 291112.44, "share", 37.2)))));

        List<Finding> findings = reader.read(FACTORY_ID, "type_concentration");

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("WASTAGE_TYPE_CONCENTRATION", f.code());
        assertEquals("restaurant", f.domain());
        assertEquals(Finding.Severity.INFO, f.severity());
        assertEquals(70, f.actionability());
        assertEquals("变质", f.subjectName());
        assertEquals(291112.44, f.facts().get("cost"));
        assertEquals(37.2, f.facts().get("share"));
    }

    @Test
    @DisplayName("UT-RWR-02: 🔴 applicable=false → 抛 FindingNotApplicableException 并带上理由")
    void notApplicableBecomesSkip() throws Exception {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(false, "两期食材名单不可比：近7天 25 种 / 基线 13 种", List.of()));

        FindingNotApplicableException e = assertThrows(FindingNotApplicableException.class,
                () -> reader.read(FACTORY_ID, "share_spike"));
        assertTrue(e.reason().contains("名单不可比"), e.reason());
    }

    @Test
    @DisplayName("UT-RWR-03: 🔴 Python 不可达必须上抛，绝不返回空列表")
    void unavailableMustNotBecomeEmptyList() throws Exception {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenThrow(new PythonServiceUnavailableException("connection refused"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> reader.read(FACTORY_ID, "share_spike"));
        assertFalse(e instanceof FindingNotApplicableException,
                "服务不可达是故障, 不是「数据不足」—— 混淆会让用户以为只是没数据");
    }

    @Test
    @DisplayName("UT-RWR-04: 🔴 缺 applicable 字段视为故障，不得当成 applicable")
    void missingApplicableIsFailureNotSuccess() throws Exception {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(Map.of("rule", "share_spike", "findings", List.of()));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> reader.read(FACTORY_ID, "share_spike"));
        assertFalse(e instanceof FindingNotApplicableException);
    }

    @Test
    @DisplayName("UT-RWR-05: applicable=true 且 findings 为空 → 空列表（真的没有）")
    void applicableWithNoFindingsReturnsEmpty() throws Exception {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(true, null, List.of()));

        assertTrue(reader.read(FACTORY_ID, "type_concentration").isEmpty());
    }

    @Test
    @DisplayName("UT-RWR-06: 未知 severity 降级为 INFO 而不是抛异常")
    void unknownSeverityFallsBackToInfo() throws Exception {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(true, null, List.of(Map.of(
                        "code", "WASTAGE_TYPE_CONCENTRATION",
                        "subject_id", "变质", "subject_name", "变质",
                        "severity", "SOMETHING_NEW", "actionability", 70,
                        "facts", Map.of()))));

        assertEquals(Finding.Severity.INFO,
                reader.read(FACTORY_ID, "type_concentration").get(0).severity());
    }

    @Test
    @DisplayName("UT-RWR-07: 两个 provider 的 domain / ruleName / rule 参数")
    void providerMetadata() {
        RestaurantWastageShareSpikeProvider spike =
                new RestaurantWastageShareSpikeProvider(reader);
        RestaurantWastageConcentrationProvider conc =
                new RestaurantWastageConcentrationProvider(reader);

        assertEquals("restaurant", spike.domain());
        assertEquals("restaurant", conc.domain());
        assertEquals("食材损耗离群", spike.ruleName());
        assertEquals("损耗类型集中度", conc.ruleName());
    }
}
```

> 若 `PythonServiceUnavailableException` 不在 `com.cretas.aims.exception` 包下，按其实际包名调整 import（用 `grep -r "class PythonServiceUnavailableException" backend/java` 确认）。

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=RestaurantWastageFindingReaderTest test`
Expected: 编译失败，`cannot find symbol: class RestaurantWastageFindingReader`

- [ ] **Step 3: 写 `RestaurantWastageFindingReader`**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReader.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingNotApplicableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 两个餐饮损耗 provider 共用的「调 Python → 转成 Finding」。
 *
 * <p>⛔ 本类**不做任何判定**。阈值、窗口、同质闸全部在 Python 侧
 * ({@code smartbi/gold/restaurant/wastage_findings.py})，因为数据在
 * smartbi 库、而 Java 的 smartbi 数据源用的 {@code smartbi_user} 没有
 * BYPASSRLS，且是连接池 —— 在池化连接上设 {@code app.factory_id} 会跨租户泄漏。
 * 在这里再算一遍等于同一个指标有两处定义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantWastageFindingReader {

    private final PythonSmartBIClient pythonSmartBIClient;

    @SuppressWarnings("unchecked")
    public List<Finding> read(String factoryId, String rule) {
        Map<String, Object> body;
        try {
            body = pythonSmartBIClient.getRestaurantWastageFindings(factoryId, rule);
        } catch (Exception e) {
            // 上抛 → FindingService 的 failedRules → 「检查失败，暂无法判断」。
            // 绝不 return List.of()：那会被渲染成「均正常」，把故障说成健康。
            throw new IllegalStateException(
                    "餐饮损耗发现调用失败: rule=" + rule + ", factoryId=" + factoryId, e);
        }

        if (!Boolean.TRUE.equals(body.get("applicable"))) {
            Object reason = body.get("skip_reason");
            if (reason == null || reason.toString().isBlank()) {
                // applicable 不为 true 又给不出理由 = 响应不合契约。当作故障，
                // 不能当作「数据不足」——后者会告诉用户「等数据攒够」，而真正
                // 该做的是去查服务。
                throw new IllegalStateException(
                        "餐饮损耗发现响应缺少 applicable/skip_reason: rule=" + rule);
            }
            throw new FindingNotApplicableException(reason.toString());
        }

        List<Map<String, Object>> raw =
                (List<Map<String, Object>>) body.getOrDefault("findings", List.of());
        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> f : raw) {
            findings.add(new Finding(
                    (String) f.get("code"),
                    "restaurant",
                    toSeverity((String) f.get("severity")),
                    f.get("actionability") instanceof Number n ? n.intValue() : 0,
                    String.valueOf(f.get("subject_id")),
                    (String) f.get("subject_name"),
                    (Map<String, Object>) f.getOrDefault("facts", Map.of())));
        }
        return findings;
    }

    private Finding.Severity toSeverity(String severity) {
        if ("CRITICAL".equals(severity)) {
            return Finding.Severity.CRITICAL;
        }
        if ("WARNING".equals(severity)) {
            return Finding.Severity.WARNING;
        }
        return Finding.Severity.INFO;
    }
}
```

- [ ] **Step 4: 写两个 provider**

Create `RestaurantWastageShareSpikeProvider.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R1 食材损耗离群：某食材的损耗份额相对自身基线放大。
 *
 * <p>口径完全在 Python 侧。分母用全店总损耗，所以「全店损耗一起跳 24 倍」
 * （2026-07-30 的数据回填）不会被误读成一堆食材异常；食材名单变了则由
 * Jaccard 闸挡下并诚实跳过。
 */
@Component
@RequiredArgsConstructor
public class RestaurantWastageShareSpikeProvider implements FindingProvider {

    private final RestaurantWastageFindingReader reader;

    @Override
    public String domain() {
        return "restaurant";
    }

    @Override
    public String ruleName() {
        return "食材损耗离群";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        return reader.read(factoryId, "share_spike");
    }
}
```

Create `RestaurantWastageConcentrationProvider.java`（同结构，改三处）：

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R2 损耗类型集中度：单一**可行动**损耗类型占比过高。
 *
 * <p>单窗口无基线，任何租户第一天可用。「哪些类型可行动」的配置
 * ({@code ACTIONABLE_WASTAGE_TYPES}) 只存在于 Python 侧那一处 ——
 * 加工损耗是切配常态，店长知道也动不了，报它是噪音。
 */
@Component
@RequiredArgsConstructor
public class RestaurantWastageConcentrationProvider implements FindingProvider {

    private final RestaurantWastageFindingReader reader;

    @Override
    public String domain() {
        return "restaurant";
    }

    @Override
    public String ruleName() {
        return "损耗类型集中度";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        return reader.read(factoryId, "type_concentration");
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=RestaurantWastageFindingReaderTest test`
Expected: PASS，7 个测试全绿

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReader.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageShareSpikeProvider.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageConcentrationProvider.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReaderTest.java
git commit -m "feat(finding): register restaurant wastage providers backed by gold rules" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReader.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageShareSpikeProvider.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageConcentrationProvider.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReaderTest.java
```

---

### Task 9: `RestaurantWastageAnomalyTool` 换身子

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyTool.java`（整个 `doExecute` + 字段 + import）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyToolTest.java`

**Interfaces:**
- Consumes: `FindingService#detectInline`（Task 5）、`FindingTextRenderer#renderInline`（Task 6）
- Produces: `doExecute` 返回 Map，键为 `findings` / `findingsText` / `checkedRules` / `skippedRules` / `failedRules` / `complete` / `message`

> 🔴 **改造前它在说假话**：读的是主库 `material_batches`，而 MOCK_REST 在该表 **0 行**（实测），于是恒定返回「近7天未检测到明显损耗异常，库存管理状态良好」；catch 块另返回「损耗异常检测功能正在建设中」。两处都把「无数据 / 失败」渲染成了正常。
> **主库读取路径整个删除，不保留。** `MaterialBatchRepository` 字段与 import 一并删掉。

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyToolTest.java`:

```java
package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RestaurantWastageAnomalyTool} 的发现层接入。 */
@ExtendWith(MockitoExtension.class)
class RestaurantWastageAnomalyToolTest {

    private static final String FACTORY_ID = "MOCK_REST";

    @InjectMocks
    private RestaurantWastageAnomalyTool tool;

    @Mock
    private FindingService findingService;

    @Mock
    private FindingTextRenderer findingTextRenderer;

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute() throws Exception {
        Method m = RestaurantWastageAnomalyTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, Map.of(), Map.of());
    }

    private static FindingService.Result result(List<Finding> findings, List<String> checked,
                                                List<String> failed,
                                                List<FindingService.SkippedRule> skipped) {
        return new FindingService.Result(findings, checked, findings.size(), Map.of(),
                failed, skipped);
    }

    @Test
    @DisplayName("UT-RWA-01: 🔴 不再持有 MaterialBatchRepository —— 那张表对餐饮租户恒 0 行")
    void noLongerReadsMainDbMaterialBatches() {
        for (Field f : RestaurantWastageAnomalyTool.class.getDeclaredFields()) {
            assertFalse(f.getType().getSimpleName().contains("MaterialBatch"),
                    "主库 material_batches 对 MOCK_REST 是 0 行, 读它只会产出假的全清信号: "
                            + f.getName());
        }
    }

    @Test
    @DisplayName("UT-RWA-02: 用 restaurant 这个 domain 调发现层")
    void usesRestaurantDomain() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "restaurant"))
                .thenReturn(result(List.of(), List.of("损耗类型集中度"), List.of(), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 损耗类型集中度，均正常。");

        execute();

        verify(findingService).detectInline(FACTORY_ID, "restaurant");
    }

    @Test
    @DisplayName("UT-RWA-03: 🔴 全部规则失败时不得说「良好」「正常」「建设中」")
    void failureNeverRendersAsHealthy() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "restaurant"))
                .thenReturn(result(List.of(), List.of(), List.of("损耗类型集中度"), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        String message = (String) execute().get("message");

        assertFalse(message.contains("良好"), message);
        assertFalse(message.contains("正常"), message);
        assertFalse(message.contains("建设中"), message);
        assertTrue(message.contains("失败"), message);
        assertTrue(message.contains("损耗类型集中度"), message);
    }

    @Test
    @DisplayName("UT-RWA-04: message 带上 findingsText，并暴露三个桶")
    void exposesAllThreeBuckets() throws Exception {
        FindingService.Result r = result(
                List.of(), List.of("损耗类型集中度"), List.of(),
                List.of(new FindingService.SkippedRule("食材损耗离群", "基线历史不足")));
        when(findingService.detectInline(FACTORY_ID, "restaurant")).thenReturn(r);
        when(findingTextRenderer.renderInline(any()))
                .thenReturn("✅ 已检查 损耗类型集中度，均正常。\nℹ️ 食材损耗离群：基线历史不足，暂不判断。");

        Map<String, Object> out = execute();

        assertEquals("✅ 已检查 损耗类型集中度，均正常。\nℹ️ 食材损耗离群：基线历史不足，暂不判断。",
                out.get("message"));
        assertEquals(List.of("损耗类型集中度"), out.get("checkedRules"));
        assertEquals(1, ((List<?>) out.get("skippedRules")).size());
        assertTrue(((List<?>) out.get("failedRules")).isEmpty());
        assertEquals(true, out.get("complete"));
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=RestaurantWastageAnomalyToolTest test`
Expected: FAIL — UT-RWA-01 报 `materialBatchRepository` 字段仍在

- [ ] **Step 3: 改 `RestaurantWastageAnomalyTool`**

把整个文件替换为：

```java
package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 损耗异常检测工具 —— 出口，不是规则。
 *
 * <p>判定全部来自 {@code domain="restaurant"} 的 {@link FindingService}。
 *
 * <p>⛔ 2026-08-06 之前本工具读主库 {@code material_batches.findExpiredBatches}。
 * 实测 MOCK_REST 在该表 <b>0 行</b>（餐饮损耗数据在 smartbi 库的
 * {@code fact_restaurant_wastage}，9,458 行 / ¥934,580），于是它恒定返回
 * 「近7天未检测到明显损耗异常，库存管理状态良好」——手里躺着 30 天 ¥894K 的损耗
 * 却告诉店长一切良好。catch 块另返回「功能正在建设中」，同样把失败说成了正常。
 * 主库读取路径已整个删除，不保留。
 *
 * @since 2026-03-07（2026-08-06 换成发现层出口）
 */
@Slf4j
@Component
public class RestaurantWastageAnomalyTool extends AbstractBusinessTool {

    /** 发现层的领域名。与两个 provider 的 {@code domain()} 逐字一致。 */
    private static final String DOMAIN = "restaurant";

    @Autowired
    private FindingService findingService;

    @Autowired
    private FindingTextRenderer findingTextRenderer;

    @Override
    public String getToolName() {
        return "restaurant_wastage_anomaly";
    }

    @Override
    public String getDescription() {
        return "损耗异常检测，识别损耗类型集中和食材损耗离群。" +
                "适用场景：异常预警、成本管控、运营问题排查。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        log.info("执行损耗异常检测 - 工厂ID: {}", factoryId);

        // 刻意不 try/catch：规则级失败已由 FindingServiceImpl 隔离并落进
        // failedRules。在这里再兜一层只会把「哪条规则挂了」的信息吃掉，
        // 退化成上一版那句「功能正在建设中」。
        FindingService.Result result = findingService.detectInline(factoryId, DOMAIN);
        String findingsText = findingTextRenderer.renderInline(result);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", result.findings());
        out.put("findingsText", findingsText);
        out.put("checkedRules", result.checkedRules());
        out.put("skippedRules", result.skippedRules());
        out.put("failedRules", result.failedRules());
        out.put("complete", result.complete());
        out.put("message", buildMessage(result, findingsText));

        log.info("损耗异常检测完成 - findings={} checked={} skipped={} failed={}",
                result.findings().size(), result.checkedRules().size(),
                result.skippedRules().size(), result.failedRules().size());
        return out;
    }

    /**
     * findingsText 为空只发生在「一条规则都没跑完且无跳过」。此时**必须**区分
     * 「全挂了」和「没有可用规则」——统一说一句好话就是上一版那个缺陷。
     */
    private String buildMessage(FindingService.Result result, String findingsText) {
        if (!findingsText.isEmpty()) {
            return findingsText;
        }
        if (!result.complete()) {
            return "损耗检查失败：" + String.join(" / ", result.failedRules()) + "，暂无法判断。";
        }
        return "本次没有可用的损耗检查规则。";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=RestaurantWastageAnomalyToolTest test`
Expected: PASS，4 个测试全绿

- [ ] **Step 5: 跑全部相关测试，确认没打破邻居**

Run:
```bash
cd backend/java/cretas-api && mvn -q -Dtest='FindingTest,FindingServiceResultTest,FindingServiceImplTest,FindingTextRendererTest,LowStockFindingProviderTest,RestaurantWastageFindingReaderTest,RestaurantWastageAnomalyToolTest,MaterialStockSummaryToolTest,FindingActionPlanToolTest' test
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyTool.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyToolTest.java
git commit -m "fix(restaurant): stop faking an all-clear from an empty main-db table" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyTool.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantWastageAnomalyToolTest.java
```

---

### Task 10: 变异验证（证明这些测试真的会响）

**Files:** 临时改动，全部回退，不产生 commit

**Interfaces:**
- Consumes: Task 1–9 的全部产物
- Produces: 无（验证动作）

> **为什么必须做**：断言写了不等于断言能抓错。以下 5 个变异各针对一条**最容易在未来被顺手改坏**的不变量。每个变异都必须**变红**；若某个变异全绿，说明对应测试是哑的，**必须补强测试而不是跳过**。

- [ ] **Step 1: 变异 A —— 删掉 Jaccard 闸**

在 `wastage_findings.py` 的 `detect_share_spike` 里，把闸 B 那段
```python
    if jaccard < _SHARE_SPIKE_MIN_JACCARD:
```
改成
```python
    if False:
```

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q -k regime_change`
Expected: **FAIL** — `test_r1_skips_on_the_2026_07_30_regime_change` 报 `applicable is False` 断言失败

- [ ] **Step 2: 回退 A 并确认恢复**

Run: `git checkout -- backend/python/smartbi/gold/restaurant/wastage_findings.py && cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: 18 passed

- [ ] **Step 3: 变异 B —— 份额归一化换成绝对值比较**

在 `detect_share_spike` 里把
```python
        share_cur = cur_cost / cur_total
        share_base = base_cost / base_total
```
改成
```python
        share_cur = cur_cost
        share_base = base_cost
```

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q -k uniform_scale`
Expected: **FAIL** — `test_r1_uniform_scale_up_produces_no_findings` 报 findings 非空（全店一起涨 24 倍被误读成异常）

- [ ] **Step 4: 回退 B 并确认恢复**

Run: `git checkout -- backend/python/smartbi/gold/restaurant/wastage_findings.py && cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: 18 passed

- [ ] **Step 5: 变异 C —— 把「加工损耗」翻成可行动**

在 `ACTIONABLE_WASTAGE_TYPES` 里把 `"加工损耗": False` 改成 `"加工损耗": True`

Run: `cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q -k "actionable or structural"`
Expected: **FAIL** — `test_r2_reports_only_actionable_type_over_threshold` 与 `test_r2_structural_type_never_reported_even_at_high_share` 双双报错

- [ ] **Step 6: 回退 C 并确认恢复**

Run: `git checkout -- backend/python/smartbi/gold/restaurant/wastage_findings.py && cd backend/python && python -m pytest smartbi/gold/tests/test_wastage_findings.py -q`
Expected: 18 passed

- [ ] **Step 7: 变异 D —— `skippedRules` 并回 `checkedRules`**

在 `FindingServiceImpl#detectInline` 的 `catch (FindingNotApplicableException ...)` 块里，把
```java
                skipped.add(new SkippedRule(provider.ruleName(), notApplicable.reason()));
```
改成
```java
                checked.add(provider.ruleName());
```

Run: `cd backend/java/cretas-api && mvn -q -Dtest='FindingServiceImplTest,FindingTextRendererTest' test`
Expected: **FAIL** — UT-FSI-07 报「跳过的规则留在 checkedRules 里，UI 会说…均正常」

- [ ] **Step 8: 回退 D 并确认恢复**

Run: `git checkout -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java && cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: PASS

- [ ] **Step 9: 变异 E —— Python 不可达时返回空列表而非上抛**

在 `RestaurantWastageFindingReader#read` 的第一个 catch 块里，把
```java
            throw new IllegalStateException(
                    "餐饮损耗发现调用失败: rule=" + rule + ", factoryId=" + factoryId, e);
```
改成
```java
            return List.of();
```

Run: `cd backend/java/cretas-api && mvn -q -Dtest=RestaurantWastageFindingReaderTest test`
Expected: **FAIL** — UT-RWR-03 报 `assertThrows` 没等到异常（查询失败被渲染成「均正常」）

- [ ] **Step 10: 回退 E 并确认全绿**

Run:
```bash
git checkout -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/RestaurantWastageFindingReader.java
cd backend/java/cretas-api && mvn -q -Dtest='FindingTest,FindingServiceResultTest,FindingServiceImplTest,FindingTextRendererTest,LowStockFindingProviderTest,RestaurantWastageFindingReaderTest,RestaurantWastageAnomalyToolTest,MaterialStockSummaryToolTest,FindingActionPlanToolTest' test
```
Expected: BUILD SUCCESS

- [ ] **Step 11: 确认工作区干净（没留下变异残渣）**

Run: `git status --short`
Expected: 无输出

---

### Task 11: 部署 + 真机验收

**Files:** 无代码改动

**Interfaces:**
- Consumes: Task 1–10 的全部产物
- Produces: prod 上可验证的行为

> **顺序不可换**：Python 先。Java 先上线时端点还不存在 → `executeWithRetry` 抛异常 → `failedRules` → 显示「检查失败」。**不会产生假数据**，只会显示失败。反之若 Java 迟迟不上，Python 端点无人调用，也无害。

- [ ] **Step 1: 合并到 main**

本改动碰 `backend/` 代码 → **走 PR，不走 fastlane**（`worktree-and-main-only-deploy.md` 双轨规则）。

```bash
git diff origin/main...HEAD --stat
```
Expected: 只有本计划列出的 13 个文件 + 2 个 docs，无 sister session 的文件。

- [ ] **Step 2: Python 部署**

Run: `./scripts/deploy/deploy-smartbi-python.sh --env prod`
Expected: 部署成功。

- [ ] **Step 3: 🔴 验证 Python 端点真的活了（不是看部署日志）**

Run:
```bash
ssh root@47.100.235.168 'cd /www/wwwroot/cretas && SEC=$(grep -oP "(?<=^INTERNAL_API_SECRET=).*" .env.prod) && \
  curl -s -m 60 -H "X-Internal-Secret: $SEC" -H "X-Factory-Id: MOCK_REST" \
  "http://127.0.0.1:8083/api/smartbi/gold/restaurant-wastage-findings?factory_id=MOCK_REST&rule=type_concentration"'
```
Expected: JSON，`applicable: true`，`findings` 恰好 1 条，`subject_name` 为 `变质`，且**不含**「加工损耗」。

> ⚠️ 必须 ssh 上去打 `127.0.0.1`。从本机 curl 47.100.235.168:8083 会因安全组返回 000，那是网络不是服务。

- [ ] **Step 4: 🔴 R1 必须返回 skip 而不是发现**

Run:
```bash
ssh root@47.100.235.168 'cd /www/wwwroot/cretas && SEC=$(grep -oP "(?<=^INTERNAL_API_SECRET=).*" .env.prod) && \
  curl -s -m 60 -H "X-Internal-Secret: $SEC" -H "X-Factory-Id: MOCK_REST" \
  "http://127.0.0.1:8083/api/smartbi/gold/restaurant-wastage-findings?factory_id=MOCK_REST&rule=share_spike"'
```
Expected: `applicable: false`，`skip_reason` 含「名单不可比」及实际的食材种数，`findings: []`。
**若返回一堆 findings，说明闸 B 没生效，停下排查 —— 那正是 07-30 数据回填造成的假警报。**

- [ ] **Step 5: 金额与直接 SQL 对账**

Run:
```bash
ssh root@47.100.235.168 'cd /www/wwwroot/cretas && SP=$(grep -oP "(?<=^SMARTBI_DB_PASSWORD=).*" .env.prod) && \
  PGPASSWORD=$SP psql -h localhost -U smartbi_user -d smartbi_prod_db -X -c \
  "SET app.factory_id=\"MOCK_REST\"; SELECT dim_value_str, round(SUM(value_num)::numeric,2) FROM agg_restaurant_daily_ops WHERE kpi_kind=\"wastage_cost_by_type\" AND date > CURRENT_DATE - 7 AND date <= CURRENT_DATE GROUP BY 1 ORDER BY 2 DESC;"'
```
Expected: 「变质」那一行的金额与 Step 3 返回的 `facts.cost` **逐位一致**。对不上就是窗口边界或聚合口径写错了。

- [ ] **Step 6: Java 部署**

Run:
```bash
git checkout main && git pull origin main
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<main 上本次合并前的 SHA>' \
  --tests 'FindingServiceResultTest,FindingServiceImplTest,FindingTextRendererTest,RestaurantWastageFindingReaderTest,RestaurantWastageAnomalyToolTest' \
  --confirm-prod YES-PROD
```
Expected: `DEPLOY_EXIT=0` 且 `RELEASE_FINAL_STATUS` **恰好出现 1 次**。

- [ ] **Step 7: 核对运行中的 jar 确含新类**

Run:
```bash
ssh root@47.100.235.168 "unzip -l /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar | grep -c 'RestaurantWastageFindingReader.class'"
```
Expected: `1`。为 0 说明部署的不是本次构建（可能撞上并发发布覆盖了 jar）。

- [ ] **Step 8: 🔴 终验 —— 那句假话必须消失**

调用 `restaurant_wastage_anomaly` 工具（走 AI 对话问「损耗有什么异常」，factoryId=MOCK_REST），检查返回：

Expected:
1. `message` **不含**「库存管理状态良好」
2. `message` **不含**「正在建设中」
3. `message` 含「变质」和该类型的金额
4. `skippedRules` 含「食材损耗离群」且理由说得出食材种数
5. `checkedRules` 含「损耗类型集中度」

**判据是返回内容，不是 `execution_status`** —— 返回空也是 SUCCESS。

---

## 完工检查

- [ ] `git log --oneline origin/main..HEAD` 显示 9 个 commit（Task 1–9）
- [ ] `git diff origin/main...HEAD --stat` 只含本计划列出的文件，无 sister session 的文件
- [ ] 全量编译：`cd backend/java/cretas-api && mvn -q -DskipTests package`
- [ ] Python 全量：`cd backend/python && python -m pytest smartbi/gold/tests/ -q`
- [ ] 变异 5 条全部真变红过，且已全部回退（Task 10 Step 11 工作区干净）

## 刻意不做（scope 边界）

| 不做的事 | 为什么 |
|---|---|
| 重定义 `ingredient_waste_rate` | 已有唯一权威定义（`health_check_metrics.py` 的 DiagnosticsEngine），铁律禁止第二处 |
| 修 `ingredient_waste_rate_high.yaml` 的反向建议缺陷 | 真缺陷（损耗 3.75% 低于标杆却在劝人降损耗），但属诊断链，单独开 issue |
| 接 LLM 润色话术 | 模板不会编数字。异常检测用规则不用 LLM |
| 请求级缓存（消除两次 HTTP） | YAGNI。等真测出延迟问题再加 |
| 把 `renderOne` 抽成策略表 | 3 个分支还读得动。第 4 个 code 进来再抽 |
| R2 的绝对金额闸 | 已拍板按占比。等真实反例出现再加 |
| `WorkdeskRole` 加店长/厨师长 | 第 4 步 |
| 顺带提示接进餐饮查询 | 第 2 步 |
| 主动出口 / 日报 / web-admin 页面 | 第 3 步 |
| 查清 07-30 阶跃是谁造的 | 已确认是 07-29 的一次性回填（5,338 行覆盖 06-29..07-29），成因不影响规则设计 |
| RN 侧展示 | 若要在 RN 屏幕露出，须先过 `ux-flow` 的 UX Flow Gate（CLAUDE.md 硬性要求） |

## Self-Review

**1. Spec 覆盖**

| Spec 要求 | 落在哪 |
|---|---|
| 规则落 Python（RLS/连接池论证） | Task 1–3；Reader javadoc 记录理由 |
| Java 只转发，零判定 | Task 8 `RestaurantWastageFindingReader` javadoc + 实现 |
| 三态：真的没有 / 数据没采集到 / 查询失败 | Task 4（桶）+ Task 5（分流）+ Task 6（渲染） |
| `skippedRules` 不影响 `complete()` | Task 4 UT-RES-02、Task 5 UT-FSI-08 |
| R1 份额归一化 + 闸 A + 闸 B | Task 2 实现 + 4 条测试 |
| R1 窗口边界用 `>` | Task 2 `test_r1_window_boundaries...`；Global Constraints |
| R1 无基线食材不参与（不除零） | Task 2 `test_r1_ingredient_without_baseline...` |
| R1 话术「涨得比全店快」 | Task 6 UT-FTR-12 |
| R2 只报可行动类型，配置唯一 | Task 1 `ACTIONABLE_WASTAGE_TYPES` + 3 条测试 |
| R2 不设绝对金额闸 | Task 1 实现（只有 `_TYPE_CONCENTRATION_MIN_SHARE`） |
| KPI 未物化时 skip 而非排序零 | Task 1 `test_r2_skips_when_by_type_kpi_unmaterialized` |
| 出口换 `RestaurantWastageAnomalyTool`，删主库路径 | Task 9（UT-RWA-01 用反射挡住字段回归） |
| 不可达必须上抛不得返回空 | Task 7 + Task 8 UT-RWR-03 + 变异 E |
| 5 条变异验证 | Task 10 |
| 部署 Python 先 Java 后 + 双注册真跑 import | Task 3 Step 5、Task 11 |
| 真机验收看返回内容不看 execution_status | Task 11 Step 3/4/8 |

**2. 占位符扫描**：无 TODO / TBD / 「similar to Task N」。每个代码步骤都是完整可粘贴的代码。两处「按实际签名调整」（`PythonServiceUnavailableException` 的包名与构造签名）给了确认命令，不是待办。

**3. 类型一致性**：`Finding` 构造参数顺序 `(code, domain, severity, actionability, subjectId, subjectName, facts)` 在 Task 8 与 Task 6 测试中一致；`Result` 6 分量 `(findings, checkedRules, totalCount, countsByCode, failedRules, skippedRules)` 在 Task 4/5/6/9 一致；`SkippedRule(ruleName, reason)` 四处一致；Python 返回信封 `{rule, applicable, skip_reason, findings}` 在 Task 1/2/3/8 一致；`facts` 键名 `costCur/shareCur/shareBase/amplification/windowDays/unit`（R1）与 `cost/share/windowDays/totalCost`（R2）在 Python 实现与 Java 渲染模板间逐字对应。
