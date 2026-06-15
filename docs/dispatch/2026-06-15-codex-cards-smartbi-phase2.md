# Codex 分发卡 — SmartBI Phase 2 统一源融合 v1 (production)

> Steve courier 给 Codex。每张卡**自包含**(Codex 无 `.claude/rules`,相关规则已内联)。
> 全 context: spec `docs/superpowers/specs/2026-06-15-smartbi-phase2-unified-source-fusion-design.md` + plan `docs/superpowers/plans/2026-06-15-smartbi-phase2-fusion-v1-production.md`(都在 origin/main)。
> T2 extractor 已 in-harness 派发(不在此)。本文 = T1/T3/T4/T5/T6。

## ⛔ 所有卡共享铁律(每张都适用)
1. **stale local**: 本地 main 落后 origin/main 很多 + 主工作目录脏。**每张卡第一条命令** = `git worktree add -b <分支> ../<dir> origin/main` 然后只在该 worktree 干活(绝对路径)。⛔ 永不读/改主工作目录 `C:\Users\Steve\my-prototype-logistics`(stale,会假报"文件不存在")。
2. **🔒 只到 PR,不部署**: 所有卡只做到 `gh pr create --repo Stevenjxie/cretas --base main --head <分支>`。⛔ 不 merge、不部署 prod/test。Opus organizer 终审 + 从 main 部署 test→prod。
3. **commit 锁 scope**: `git commit -m "..." -- <你改的具体文件>`(--only 模式,防并发 staged 污染)。
4. **asyncpg RLS GUC 铁律**(T3/T4/T5 必守): `smart_bi_timeseries` 是 FORCE RLS 表。**所有运行时查询必须在事务内先 `await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)`**,否则 FORCE RLS 返 0 行(静默!)。pattern: `async with pool.acquire() as conn: async with conn.transaction(): set_config(...); ...query...`。
5. **fail-soft/fail-open + 禁假数据**: 写路径(T3)抽取失败 = log warning 不破坏上传主流程。读路径(T4)异常/空 = 返 `[]`(调用方回退现有单上传),不报错给用户、不编数据。
6. **诚实纪律**: 你无法 live 跑(Python 端口仅服务器本地)。写单测(可 mock pool)。端点/函数签名逐字匹配本卡,organizer live 验。别报你没真验的。
7. **web-admin worktree 装包**(仅 T6): `cd <worktree>/web-admin && npm install --prefer-offline --legacy-peer-deps`。⛔ 禁 `mklink /J` 共享 node_modules(Windows worktree remove 会掏空主 repo)。
8. 交付报告必含: 改了哪些文件 + 测试结果(贴输出)+ `git diff origin/main...HEAD --stat`(scope 干净无 sister 文件)+ PR 链接 + 任何偏离说明。

## 共享接口(各卡按此对齐,organizer 会查一致性)
```python
# TimeseriesRow (T2 定义, T3/T5 复用同名同字段):
#   factory_id:str template_key:str domain:str|None period:str canonical_field:str
#   value_num:float|None dims:dict dims_hash:str source_upload_id:int
# extract_timeseries(rows, field_mappings, *, factory_id, template_key, domain,
#                    source_upload_id, period_of, category_of) -> list[TimeseriesRow]
# write_timeseries(factory_id, template_key, rows, source_upload_id) -> int   # T3
# query_timeseries(factory_id, *, template_key=None, domain=None, start=None, end=None,
#                  canonical_fields=None, dims_filter=None) -> list[dict]       # T4
```

---

## 卡 T1 → Codex(🔒 migration/RLS/GRANT) — **可立即开,无依赖**
**分支**: `feat/p2-migration`  **worktree**: `git worktree add -b feat/p2-migration ../cretas-p2-mig origin/main`
**目标**: 建 `smart_bi_timeseries` 迁移。
**文件**: Create `backend/python/smartbi/database/migrations/V<YYYYMMDD>_NN__create_smart_bi_timeseries.sql`。
- **迁移号**: 查 `backend/python/smartbi/database/migrations/` 现有最大 `V*` 号,+1。⛔ 防撞号: PR 前再 `git fetch && ls` 看 origin/main 最新号,撞了就改名。
- **SQL(逐字,来自 spec §3)**:
```sql
CREATE TABLE IF NOT EXISTS smart_bi_timeseries (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    template_key VARCHAR(16) NOT NULL,
    domain VARCHAR(32),
    period VARCHAR(32) NOT NULL,
    canonical_field VARCHAR(255) NOT NULL,
    value_num DOUBLE PRECISION,
    value_text VARCHAR(255),
    dims JSONB NOT NULL DEFAULT '{}'::jsonb,
    dims_hash VARCHAR(64) NOT NULL DEFAULT '',
    source_upload_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_ts UNIQUE (factory_id, template_key, period, canonical_field, dims_hash)
);
CREATE INDEX IF NOT EXISTS idx_ts_factory_domain_period ON smart_bi_timeseries (factory_id, domain, period);
CREATE INDEX IF NOT EXISTS idx_ts_factory_template ON smart_bi_timeseries (factory_id, template_key);
ALTER TABLE smart_bi_timeseries ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_timeseries FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='smart_bi_timeseries' AND policyname='tenant_isolation') THEN
    CREATE POLICY tenant_isolation ON smart_bi_timeseries
      USING ((factory_id)::text = current_setting('app.factory_id', true))
      WITH CHECK ((factory_id)::text = current_setting('app.factory_id', true));
  END IF;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_timeseries TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_timeseries_id_seq TO smartbi_user;
```
- ⛔ **GRANT smartbi_user + FORCE RLS 一个都不能漏**(历史 GRANT bug 让 prod fail-open 救场)。⛔ 不本地 apply。PR 即可,organizer 跑 runner 部署 + 核对 `\dp`(GRANT=arwd)`\d`(force rls)。

## 卡 T4 → Codex(resolver) — **可立即开**(对着上面 schema 写,单测 mock pool)
**分支**: `feat/p2-resolver`  **目标**: Create `backend/python/smartbi/services/timeseries_resolver.py` + `backend/python/tests/test_timeseries_resolver.py`。
- 实现 `query_timeseries(...)`(签名见共享接口): GUC 事务内(铁律 4)+ fail-open(铁律 5)。SELECT period, canonical_field, dims, value_num FROM smart_bi_timeseries WHERE factory_id=$1 [+ template_key/domain/period BETWEEN/canonical_field=ANY/dims @>] ORDER BY period。**写时已去重 → 读侧不做 dedup**。
- 测试(mock asyncpg pool/conn): 多 upload 融合返连续序列、时间窗过滤、domain/template 过滤、空表 fail-open 返 []、GUC set_config 被调用。
- 跑 `pytest tests/test_timeseries_resolver.py -v`。

## 卡 T3 → Codex(🔒 writer + excel_async hook) — **依赖 T1 schema + T2 接口**(可对接口先写,organizer 在 T1+T2 merge 后部署)
**分支**: `feat/p2-writer`  **目标**: Create `backend/python/smartbi/services/timeseries_writer.py` + test;Modify `backend/python/smartbi/api/excel_async.py`(worker 末尾)。
- `write_timeseries(factory_id, template_key, rows, source_upload_id) -> int`: GUC 事务内(铁律 4)。`periods={r.period for r in rows}`。**newest-wins 守卫**: 若 `source_upload_id < max(existing source_upload_id for these periods)` → return 0(skip,不回退更新的期)。否则 **per-period replace**: `DELETE FROM smart_bi_timeseries WHERE factory_id=$1 AND template_key=$2 AND period = ANY($periods)` 再 bulk INSERT rows。
- **挂 excel_async**: `_async_worker_impl` worker 末尾(field_defs+dynamic_data+field_mappings+infer_domain 都写完之后),try/except **fail-soft**(铁律 5)调 `extract_timeseries`(从 `smartbi.services.timeseries_extractor` import,T2 提供)+ `write_timeseries`。`period_of` 复用现有 `_fmt_period` + period 列定位;`category_of` 用 `from smartbi.services.domain_standard_fields import STANDARD_FIELDS; lambda c: STANDARD_FIELDS.get(c,{}).get("category")`。
- 测试(mock pool): per-period replace(重叠期被新 upload 覆盖、非重叠期保留)、older upload skip 返 0。
- ⛔ 影响**所有上传**写路径 → fail-soft 必须裹住(抽取失败绝不破坏 dynamic_data 主流程)。

## 卡 T5 → Codex(backfill 脚本) — **依赖 T2+T3 接口**
**分支**: `feat/p2-backfill`  **目标**: Create `backend/python/smartbi/scripts/backfill_timeseries.py`。
- 遍历 `smart_bi_pg_excel_uploads`(`upload_status='COMPLETED'`,支持 `--factory <id>` / `--domain production` / `--dry-run` 过滤),按 `id` 升序 → 取 `field_mappings`(upload.field_mappings JSONB)+ dynamic_data rows(`SELECT row_data FROM smart_bi_dynamic_data WHERE upload_id=$1`)→ 复用 `extract_timeseries` + `write_timeseries` 幂等回填(per-period replace 幂等)。
- 连接/RLS pattern 参照 `backend/python/scripts/backfill_silver.py`。`--dry-run` 打印计划不写。
- ⛔ 不运行(organizer 在 test→prod 执行 + 核对行数)。

## 卡 T6 → Codex(production 读路径 + UploadSwitcher 融合选项) — **依赖 T4 resolver 接口**
**分支**: `feat/p2-production-ui`  **目标**: production 域分析接 resolver + UploadSwitcher 加「全部(按时间融合)」。
- 定位: `git grep -n "smart_bi_dynamic_data" -- 'backend/python/**'` 找 production 域读单 `upload_id` 的主入口;`git grep -ln "UploadSwitcher\|switchUpload\|当前数据源\|dataSource" -- 'web-admin/src/**'` 找切换器。
- UploadSwitcher 加选项 value `__FUSED__`(label「全部(按时间融合)」)。选中 → 分析按 template_key/domain 调 `query_timeseries`(铁律: 后端入口 fused 模式调 resolver,否则原单 upload 路径,**向后兼容**)。可设 production 默认 fused。
- web-admin `npm run build:check` 绿(铁律 7 先装包)。⛔ 不动 chat.py 其它站点(后续 plan)。
- ⚠️ 若 resolver(T4)还没 merge,对着共享接口签名写前端 + 后端 wiring,organizer 在 T4 merge 后接。

---
## 依赖/courier 顺序建议
- **立即可派**: T1、T4(都对 schema/接口写,无需别的 merge)。
- **T3** 对接口先写没问题,但 organizer 在 T1+T2 merge 后才部署验。
- **T5** 待 T2+T3 接口稳;**T6** 待 T4 接口稳。
- 全部 PR → Opus organizer 终审(`gh pr diff` 验远端)→ 按波次 merge + test→prod 部署 + T7 live e2e(organizer)。
