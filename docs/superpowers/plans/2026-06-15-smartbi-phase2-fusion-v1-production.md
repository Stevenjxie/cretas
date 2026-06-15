# SmartBI Phase 2 统一源融合 v1 (production pilot) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同模板按月累积的上传自动按时间贯成连续序列(免选),v1 跑通 production 域端到端。

**Architecture:** 统一长 typed 时序表 `smart_bi_timeseries`,upload 后由 TimeseriesExtractor(纯函数,复用 Phase 0 field_mappings 把 raw dynamic_data 抽成 typed 行)→ TimeseriesWriter per-period replace upsert(最新上传按期覆盖)→ TimeseriesResolver 读侧直接 SELECT(写时已去重)。pilot=production:接 production 主分析路径 + UploadSwitcher「全部(按时间融合)」。

**Tech Stack:** Python 3.8 (FastAPI, asyncpg), PostgreSQL (smartbi_db/smartbi_prod_db, FORCE RLS + GUC), Vue3 web-admin。

**Spec:** `docs/superpowers/specs/2026-06-15-smartbi-phase2-unified-source-fusion-design.md`(读它拿全 context)。

**🔒 红线:** Task 1(migration/RLS/GRANT)+ Task 3(写路径挂 excel_async,影响所有上传)→ 执行者只到 PR off origin/main,Opus organizer 终审 + 从 main 部署 test→prod。

**⛔ stale local:** 本地 main 落后 origin/main + 脏。所有 Phase 0/相关文件在 origin/main。worktree off origin/main 干活,⛔ 不读主工作目录,⛔ 不 spawn 探索子 agent(继承 stale cwd)。

---

## File Structure

| 文件 | 责任 | Task |
|---|---|---|
| `backend/python/smartbi/database/migrations/V<YYYYMMDD>_NN__create_smart_bi_timeseries.sql` | 建表 + 索引 + FORCE RLS + policy + GRANT | 1 |
| `backend/python/smartbi/services/timeseries_extractor.py` | 纯函数: rows + field_mappings → list[TimeseriesRow] | 2 |
| `backend/python/smartbi/services/timeseries_writer.py` | per-period replace upsert(GUC 事务,fail-soft) | 3 |
| `backend/python/smartbi/api/excel_async.py`(改) | worker 末尾挂 extractor+writer(fail-soft) | 3 |
| `backend/python/smartbi/services/timeseries_resolver.py` | 读侧查询(GUC,fail-open) | 4 |
| `backend/python/smartbi/scripts/backfill_timeseries.py` | 存量上传幂等回填 | 5 |
| production 主分析读路径(实现时定位)+ `web-admin` UploadSwitcher | production 接 resolver + 融合选项 | 6 |
| `backend/python/tests/test_timeseries_*.py` | extractor/writer/resolver 单测 + e2e | 2/3/4/7 |

依赖: 1 → {3,4}; 2 → 3; 3 → 5; 4 → 6。并行波见末尾 Dispatch。

---

## Task 1: migration — `smart_bi_timeseries` 表 (🔒)

**Files:** Create `backend/python/smartbi/database/migrations/V<YYYYMMDD>_NN__create_smart_bi_timeseries.sql`(编号: 查 `backend/python/smartbi/database/migrations/` 现有最大号 +1,⛔ 防撞号,merge 前再查 origin/main)。

- [ ] **Step 1: 写迁移 SQL**(见 spec §3 完整 DDL,逐字用):建表(id BIGSERIAL PK / factory_id / template_key(16) / domain / period / canonical_field / value_num / value_text / dims JSONB / dims_hash / source_upload_id / created_at / updated_at)+ `UNIQUE (factory_id, template_key, period, canonical_field, dims_hash)` + 2 索引 + `ENABLE/FORCE ROW LEVEL SECURITY` + `CREATE POLICY tenant_isolation USING/WITH CHECK (factory_id = current_setting('app.factory_id', true))` + `GRANT SELECT,INSERT,UPDATE,DELETE ON smart_bi_timeseries TO smartbi_user` + `GRANT USAGE,SELECT ON SEQUENCE smart_bi_timeseries_id_seq TO smartbi_user`。幂等(`CREATE TABLE IF NOT EXISTS` / `DO $$ ... IF NOT EXISTS` for policy)。
- [ ] **Step 2: 本地静态检查** runner 能识别(命名 `V<ver>__desc.sql`,checksum tracker)。⛔ 不本地 apply(无本地 PG)。
- [ ] **Step 3: Commit** `git commit -m "feat(smartbi): smart_bi_timeseries migration (Phase 2)" -- <sql>`。
- 部署: organizer 跑 `deploy-smartbi-python.sh --env test`(runner apply)→ 核对 `\dp`+`\d`(GRANT=arwd, force_rls=t)→ prod。

## Task 2: TimeseriesExtractor (纯函数)

**Files:** Create `backend/python/smartbi/services/timeseries_extractor.py` + Test `backend/python/tests/test_timeseries_extractor.py`。

接口(逐字):
```python
from dataclasses import dataclass, field
@dataclass
class TimeseriesRow:
    factory_id: str; template_key: str; domain: str | None; period: str
    canonical_field: str; value_num: float | None; dims: dict; dims_hash: str
    source_upload_id: int

def extract_timeseries(rows, field_mappings, *, factory_id, template_key, domain,
                       source_upload_id, period_of, category_of) -> list[TimeseriesRow]: ...
```
- `field_mappings`: dict[original_col -> canonical] 或 list[FieldMapping](支持两种,内部归一)。
- `period_of(row) -> str|None`: 注入(复用 excel_async `_fmt_period` + period 列定位)。
- `category_of(canonical) -> str`: 注入(`domain_standard_fields.STANDARD_FIELDS[canonical]["category"]`)。
- measure = category ∈ {amount, rate, quantity} → 发 value 行(value_num,非数值 skip)。dimension = category=='category' 或 canonical ∈ {department,product,region,customer_name,supplier_name,material_name,warehouse} → 进 dims。period(canonical=='period')= 轴,不进 dims。id(category=='id')丢弃。
- `dims_hash = hashlib.sha256(json.dumps(dims, sort_keys=True, ensure_ascii=False).encode()).hexdigest()`。

- [ ] **Step 1: 写失败测试**(test_timeseries_extractor.py):
```python
def test_measure_and_dim_split():
    rows = [{"期间":"2026-01","产品":"A","产出数量":100,"出成率":0.9,"批次号":"B1"}]
    fm = {"期间":"period","产品":"product","产出数量":"output_quantity","出成率":"yield_rate","批次号":"batch_number"}
    cat = {"period":"time","product":"category","output_quantity":"quantity","yield_rate":"rate","batch_number":"id"}
    out = extract_timeseries(rows, fm, factory_id="F1", template_key="tk", domain="production",
                             source_upload_id=7, period_of=lambda r: r["期间"], category_of=lambda c: cat[c])
    # 2 measure 行(output_quantity, yield_rate),dims={product:A}(batch_number=id 丢弃,period 不进 dims)
    assert {r.canonical_field for r in out} == {"output_quantity","yield_rate"}
    assert all(r.dims == {"product":"A"} for r in out)
    assert all(r.period=="2026-01" and r.source_upload_id==7 for r in out)
    assert out[0].dims_hash == out[1].dims_hash and out[0].dims_hash  # 同行同 dims 同 hash
def test_non_numeric_measure_skipped(): ...   # 产出数量="" → 该 measure 不发行
def test_no_period_row_skipped(): ...          # period_of→None → 整行 skip
```
- [ ] **Step 2: 跑测试看失败** `pytest tests/test_timeseries_extractor.py -v` → FAIL (module not found)。
- [ ] **Step 3: 实现** `timeseries_extractor.py`(按上面接口 + measure/dim/id 规则 + dims_hash)。
- [ ] **Step 4: 跑测试看通过** → PASS。
- [ ] **Step 5: Commit** `-- backend/python/smartbi/services/timeseries_extractor.py backend/python/tests/test_timeseries_extractor.py`。

## Task 3: TimeseriesWriter + excel_async hook (🔒)

**Files:** Create `backend/python/smartbi/services/timeseries_writer.py` + Test `tests/test_timeseries_writer.py`; Modify `backend/python/smartbi/api/excel_async.py`(worker 末尾,在 field_defs+dynamic_data+field_mappings+infer_domain 之后)。

接口:
```python
async def write_timeseries(factory_id, template_key, rows: list[TimeseriesRow], source_upload_id) -> int:
    # GUC 事务内 set_config('app.factory_id',$1,true)
    # periods = {r.period for r in rows}
    # newest-wins 守卫: 若 source_upload_id < max(existing source_upload_id for these periods) → return 0 (skip)
    # DELETE FROM smart_bi_timeseries WHERE factory_id=$1 AND template_key=$2 AND period = ANY($periods)
    # bulk INSERT rows ; return len(rows)
```
- [ ] **Step 1: 写失败测试**(用 monkeypatch mock pool/conn,或真 test DB if available):
```python
async def test_per_period_replace_newest_wins():
    # 先写 upload=1 的 2026-01/02;再写 upload=2 的 2026-02/03(02 重叠)
    # 断言 2026-01 来自 upload1,02 来自 upload2(覆盖),03 来自 upload2
async def test_older_upload_skipped():
    # 已有 upload=5 的 02;写 upload=3(更旧)同期 → return 0,02 仍 upload5
```
- [ ] **Step 2: 跑看失败**。
- [ ] **Step 3: 实现** writer(GUC 事务 + per-period replace + newest-wins 守卫 + bulk insert via `executemany`/`copy`)。
- [ ] **Step 4: 跑看通过**。
- [ ] **Step 5: 挂 excel_async**:worker 末尾 try/except(fail-soft,失败 log warning 不破坏上传)调 extract_timeseries + write_timeseries。period_of 复用现有 `_fmt_period` + period 列;category_of 用 STANDARD_FIELDS。
- [ ] **Step 6: Commit** `-- timeseries_writer.py test_timeseries_writer.py excel_async.py`。

## Task 4: TimeseriesResolver

**Files:** Create `backend/python/smartbi/services/timeseries_resolver.py` + Test `tests/test_timeseries_resolver.py`。

```python
async def query_timeseries(factory_id, *, template_key=None, domain=None, start=None,
                           end=None, canonical_fields=None, dims_filter=None) -> list[dict]:
    # GUC 事务内;fail-open(空/无 pool/异常 → []);写时已去重 → 直接 SELECT
    # SELECT period, canonical_field, dims, value_num FROM smart_bi_timeseries
    #  WHERE factory_id=$1 [AND template_key/domain/period BETWEEN/canonical_field=ANY/dims @>]
    #  ORDER BY period
```
- [ ] **Step 1: 失败测试**:融合多 upload 返连续序列;时间窗过滤;domain/template 过滤;空表返 [](fail-open);别租户 GUC 0 行。
- [ ] **Step 2: 跑看失败**。**Step 3: 实现**(GUC + fail-open)。**Step 4: 跑看通过**。
- [ ] **Step 5: Commit**。

## Task 5: backfill 脚本

**Files:** Create `backend/python/smartbi/scripts/backfill_timeseries.py`。

- [ ] **Step 1:** 写脚本:遍历 `smart_bi_pg_excel_uploads`(COMPLETED,可 `--factory`/`--domain production` 过滤)按 id 升序 → 取 field_mappings(upload.field_mappings JSONB)+ dynamic_data rows → 复用 extract_timeseries + write_timeseries 幂等回填。参照 `scripts/backfill_silver.py` 的连接/RLS pattern。
- [ ] **Step 2:** dry-run 模式(`--dry-run` 打印计划不写)。
- [ ] **Step 3: Commit**。运行由 organizer 在 test→prod 执行 + 核对行数。

## Task 6: production 读路径 + UploadSwitcher 融合选项

**Files:** Modify production 主分析读路径(实现时 `git grep "smart_bi_dynamic_data" -- 'backend/python/**'` 定位 production 域读 single upload_id 的主入口)+ `web-admin` UploadSwitcher(`git grep -l "UploadSwitcher\|switchUpload\|当前数据源" web-admin/src`)。

- [ ] **Step 1:** UploadSwitcher 加「全部(按时间融合)」选项(value 如 `__FUSED__`)。选中 → 分析按 template_key/domain 调 resolver 而非单 upload_id。可设 production 默认。
- [ ] **Step 2:** 后端 production 分析入口:当 fused 模式 → 调 `query_timeseries`;否则原单 upload 路径(向后兼容)。
- [ ] **Step 3:** 测试 + vue build:check。Commit。⛔ 不动 chat.py 其它站点(后续)。

## Task 7: live e2e

- [ ] **Step 1:** test 8084 传两份重叠期间 production Excel(份1: 1-3月;份2: 3-5月,3月改值)→ 查 `smart_bi_timeseries`: 1-2月来自份1、3-5月来自份2(3月覆盖)→ resolver 返连续 1-5月最新值。synthetic factory,验后清理。organizer 跑(live 验是 🔒 deploy 的一部分)。

---

## Self-Review

- **Spec 覆盖**: §3 表→T1; §4 写路径→T2+T3; §5 resolver→T4; §6 backfill→T5; §5 读路径+UploadSwitcher→T6; §8 测试→T2/3/4/7。✓ 全覆盖。
- **Placeholder**: 迁移号/主分析入口/UploadSwitcher 文件 = 实现时定位(spec §11 已标 open,给了 grep 命令),非隐藏 TBD。
- **类型一致**: TimeseriesRow 字段在 T2 定义,T3/T5 复用同名;query_timeseries 签名 T4 定义,T6 调用一致。✓

---

## Dispatch（并行波,organizer 用)

| 波 | 任务 | 模型 | 通道 | 依赖 |
|---|---|---|---|---|
| 1 | T1 migration(🔒) | Opus 写 keystone SQL **或** 紧 Codex 卡 | — | 无 |
| 1 | T2 extractor(纯函数) | Sonnet in-harness / Codex | 并行 | 无 |
| 2 | T3 writer+hook(🔒) | Sonnet in-harness | — | T1+T2 |
| 2 | T4 resolver | Sonnet/Codex | 并行 T3 | T1 |
| 3 | T5 backfill | Sonnet/Codex | — | T2+T3 |
| 3 | T6 production读+UploadSwitcher | Codex(前端强) | 并行 T5 | T4 |
| 4 | T7 live e2e | Opus organizer | — | 全部 |

🔒 T1/T3 执行者只到 PR;Opus 终审 + test→prod 部署 + 核对(GRANT/force_rls/迁移/e2e)。
