# SmartBI Phase 2 — 统一源融合(unified-source fusion)设计

**日期**: 2026-06-15
**作者**: Opus Organizer(brainstorm with Steve)
**前置**: Phase 0「映射钉死」已 SHIPPED prod(#879/#885/V20261004_03)+ live 验过(`docs/audits/smartbi/2026-06-15-phase0-upload-e2e-verification.md`)。本设计建在 Phase 0 的受控字典 + 确定性 pin + 域反推之上。
**关联**: 北极星 spec PR #867 / memory `project_2026_06_14_smartbi_python_consolidation_northstar` / handoff `docs/dispatch/2026-06-15-handoff-smartbi-unified-source.md`。

---

## 1. 目标 + 决策(brainstorm 已定)

**头条价值「免选/智能」**: 同一模板按月累积的上传,系统自动按时间贯成一条连续时间序列 → 用户不用手动选哪次上传(UploadSwitcher),直接看全史趋势。

| 决策 | 选定 | 备注 |
|---|---|---|
| 主场景 | **同模板按月累积 → 自动贯时间轴** | 非"自动选最新" / 非"跨域融合" |
| 重叠语义 | **最新上传按期覆盖**(newest-per-period) | 重传修正某月/期间重叠 → 最新上传完全拥有它覆盖的期间 |
| 存储 | **durable typed 时序表**(非 read-time) | Steve 选耐久投资 |
| 表形状 | **单一统一「长」表**(Approach A) | 1 表 1 抽取器、域无关、新 canonical 字段零 schema 改;非 per-域 5 宽表 |
| 分解 | **先 pilot 域 production 端到端**,再扩 | 数据量小(工厂年十几次上传)|

**审计已纠**: finance 是独立 ETL(upload_id=0 sentinel,已 typed `smart_bi_finance_data`)→ 不在本范围。真痛在 **general upload 路径** `smart_bi_dynamic_data`(per-upload)+ UploadSwitcher。

---

## 2. 架构总览

```
上传 Excel → excel_async worker
  ├─ (Phase 0 已有) semantic_mapper → field_mappings(原列→canonical)+ review_queue + infer_domain
  ├─ (Phase 0 已有) 写 field_definitions + dynamic_data(per-upload raw rows + period)
  └─ 【本期新增】 TimeseriesExtractor: raw rows + field_mappings → typed 行
        → per-period replace upsert 进 smart_bi_timeseries     ← 写时去重(newest-per-period)

分析读 → 【本期新增】 TimeseriesResolver.query_timeseries(factory, template/domain, range)
        → 直接 SELECT smart_bi_timeseries(写时已去重 → 读侧无需 dedup,这是耐久层的回报)
        → v1 只接 production 域主分析路径 + UploadSwitcher「全部(按时间融合)」选项
```

单元边界(各自一职、可独立测):
- **TimeseriesExtractor**(纯函数为主): `(rows, field_mappings, domain, period_fn) → list[TimeseriesRow]`。不碰 DB,可单测。
- **TimeseriesWriter**: 接 extractor 输出 + per-period replace 写 DB(事务、GUC、fail-soft)。
- **TimeseriesResolver**: 读侧查询(GUC 事务内、fail-open)。
- **backfill 脚本**: 复用 extractor + writer 回填存量。

---

## 3. 数据模型 — `smart_bi_timeseries`(长事实表)

```sql
CREATE TABLE smart_bi_timeseries (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    template_key    VARCHAR(16)  NOT NULL,
    domain          VARCHAR(32),                 -- infer_domain 结果(finance/production/...）
    period          VARCHAR(32)  NOT NULL,       -- 复用 excel_async _fmt_period(2026-01 / 2026-W01 / 2026-01-15)
    canonical_field VARCHAR(255) NOT NULL,       -- measure 的 canonical 名(revenue/output_quantity/...)
    value_num       DOUBLE PRECISION,            -- measure 数值(非数值→NULL)
    value_text      VARCHAR(255),                -- 备用(非数值 canonical;v1 measure 都数值,基本不用)
    dims            JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- 维度上下文 {department/product/region/customer_name/...}
    dims_hash       VARCHAR(64)  NOT NULL DEFAULT '',           -- dims 的确定性 sha256(进 unique 键)
    source_upload_id BIGINT,                     -- provenance + newest-wins 守卫
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_ts UNIQUE (factory_id, template_key, period, canonical_field, dims_hash)
);
CREATE INDEX idx_ts_factory_domain_period ON smart_bi_timeseries (factory_id, domain, period);
CREATE INDEX idx_ts_factory_template      ON smart_bi_timeseries (factory_id, template_key);

ALTER TABLE smart_bi_timeseries ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_timeseries FORCE ROW LEVEL SECURITY;          -- 表 owner 也走 RLS
CREATE POLICY tenant_isolation ON smart_bi_timeseries
    USING ((factory_id)::text = current_setting('app.factory_id', true))
    WITH CHECK ((factory_id)::text = current_setting('app.factory_id', true));
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_timeseries TO smartbi_user;   -- ⛔ 血教训: 必 GRANT
GRANT USAGE, SELECT ON SEQUENCE smart_bi_timeseries_id_seq TO smartbi_user;
```

**measure vs dimension 判定**(按 Phase 0 canonical 的 category):
- **measure** = category ∈ {amount, rate, quantity} → 发 value 行(value_num)。
- **dimension** = category ∈ {category} + 明确维度 canonical(department/product/region/customer_name/supplier_name/material_name/warehouse)→ 进 `dims`。
- **period**(category=time 且映到 `period`)= 时间轴,不进 dims。
- **id**(category=id: batch_number/order_number/po_number/work_order)→ **不进 dims**(高基数,会把事实表打成行级拷贝,破坏时序粒度)。v1 丢弃;若将来要溯源用 source_upload_id。

迁移文件: `backend/python/smartbi/database/migrations/V<YYYYMMDD>_NN__create_smart_bi_timeseries.sql`,经 `deploy-smartbi-python.sh` runner apply(Step 3.5)。两库(test smartbi_db / prod smartbi_prod_db)。

---

## 4. 写路径 — TimeseriesExtractor + per-period replace

挂在 `excel_async.py:_async_worker_impl`,在 field_defs + dynamic_data + field_mappings + infer_domain 都写完之后(已有那些数据在手),新增一步:

```
periods_in_upload = set()
ts_rows = []
for row in parsed_rows:
    period = _fmt_period(row[period_col])            # 复用现有
    if not period: continue                          # 无期间的行跳过(诚实)
    periods_in_upload.add(period)
    dims = { canonical: row[orig] for (orig,canonical) in field_mappings
             if category(canonical) is dimension and row[orig] not empty }
    dims_hash = sha256(canonical_json(dims))
    for (orig, canonical) in field_mappings:
        if category(canonical) is measure:
            v = to_number(row[orig])                 # 非数值→skip(不写脏 value)
            if v is None: continue
            ts_rows.append(TimeseriesRow(factory, template_key, domain, period,
                                         canonical, v, dims, dims_hash, upload_id))

# per-period replace（同一事务、GUC 已设）:本次上传完全拥有它覆盖的期间
DELETE FROM smart_bi_timeseries
 WHERE factory_id=$f AND template_key=$tk AND period = ANY(periods_in_upload);
bulk INSERT ts_rows;
```

- **per-period replace** 干净处理 新增/删除/改值 的 dim-combo(整期间替换)。非覆盖期间保留(来自旧上传)。
- **newest-wins 守卫**: 正常上传 id 单调递增、按序处理 → replace 天然最新赢。防御旧上传乱序重处理: replace 前 `AND $upload_id >= COALESCE((SELECT max(source_upload_id) FROM smart_bi_timeseries WHERE factory_id=$f AND template_key=$tk AND period=ANY(...)), 0)` 判断;若本次 upload_id 更旧则 skip 抽取(不回退已有更新的期间)。
- **fail-soft**: 整个抽取步 try/except,失败 log warning + 不破坏上传主流程(dynamic_data 已写,现有单上传分析不受影响)。
- excel_async 重处理同一 upload 时(它已 `DELETE dynamic_data WHERE upload_id`)→ 抽取也按 period replace 幂等。

---

## 5. 读路径 — TimeseriesResolver

```python
async def query_timeseries(factory_id, *, template_key=None, domain=None,
                           start=None, end=None, canonical_fields=None,
                           dims_filter=None) -> list[dict]:
    # GUC 事务内(FORCE RLS),fail-open(空表/无 pool/异常 → [],调用方回退现有单上传路径)
    # SELECT period, canonical_field, dims, value_num FROM smart_bi_timeseries
    #  WHERE factory_id=$1 [AND template_key/domain/period range/canonical_field/dims]
    #  ORDER BY period
    # 写时已 newest-per-period 去重 → 读侧无需 dedup
```

**v1 接线范围(只 production 域)**:
- production 域的**主分析路径**(找出该域当前读 `smart_bi_dynamic_data WHERE upload_id=$1` 的主入口,改成可走 resolver)。
- **UploadSwitcher 新增「全部(按时间融合)」选项**(web-admin):选中时分析按 template_key/domain 调 resolver,而非单 upload_id。可设为该域默认(= 免选体验)。
- ⛔ **不**一次性重写 chat.py 的 ~10 个 `WHERE upload_id=$1` 站点 + insight/whatif/financial_dashboard。增量,后续 plan 按域/按站点扩。

---

## 6. 迁移/回填

- 建表迁移(§3)走 runner。
- **backfill 脚本** `backend/python/smartbi/scripts/backfill_timeseries.py`: 遍历指定 factory(或全量)的 `smart_bi_pg_excel_uploads`(COMPLETED)→ 取其 field_mappings + dynamic_data rows → 复用 TimeseriesExtractor + writer 幂等回填。按 upload_id 升序处理(保 newest-wins)。v1 只回填 production 域上传(或全部,extractor 域无关)。幂等(per-period replace)。

---

## 7. 错误处理 / RLS / 安全(🔒 红线)

- **新表必 GRANT smartbi_user + FORCE RLS + tenant policy(GUC)**(Phase 0 血教训)。
- 所有运行时查询 **GUC 事务内** `set_config('app.factory_id',$1,true)`(否则 FORCE RLS 返 0 行,`feedback_asyncpg_rls_guc_must_be_in_transaction`)。
- 抽取 fail-soft(不破坏上传);resolver fail-open(空/异常 → 回退现有单上传行为,不报错给用户)。
- **无自动晋升 / 无静默假数据**(北极星不变量):resolver 只读已写入的真实行;无数据返空,不编。

---

## 8. 测试

- **TimeseriesExtractor 单测**(纯函数): measure/dimension 拆分正确;id 字段不进 dims;无期间行跳过;非数值 measure 跳过;dims_hash 确定性。
- **TimeseriesWriter 单测**: per-period replace(重传覆盖期间 → 旧行删、新行入;非覆盖期间留);newest-wins 守卫(旧 upload_id 乱序 → skip)。
- **TimeseriesResolver 单测**: 融合多上传(连续序列)、时间窗过滤、domain/template 过滤、空表 fail-open、GUC 租户隔离(别租户 0 行)。
- **live e2e**(test 8084): 传两份重叠期间的 production Excel(份1: 1-3月, 份2: 3-5月含3月修正)→ 查 smart_bi_timeseries: 1-2月来自份1、3-5月来自份2(3月被覆盖)→ resolver 返连续 1-5月最新值。清理 synthetic factory。

---

## 9. 范围 / 分解(本 spec = v1)

**v1(本 spec → 一个 plan)**:
- migration 建表(🔒)
- TimeseriesExtractor + Writer(挂 excel_async,🔒 影响所有上传写路径)
- TimeseriesResolver
- backfill 脚本
- **production 域**: 主分析路径接 resolver + UploadSwitcher 融合选项
- 测试 + live e2e

**follow-on(后续独立 plan,复制样板)**:
- 其它域(sales/purchase/inventory/general)接 resolver(extractor 域无关,主要是各域读路径接线 + 回填 + 验)
- chat.py ~10 站点 + insight/whatif/financial_dashboard 全面切 resolver
- finance 路径 reconciliation(独立,finance 已 typed)
- 北极星 Java-AI→Python 全量迁移(多季度,另立)

---

## 10. 红线 + 隔离(执行纪律)

- 🔒 migration / RLS / GRANT / 影响所有上传的写路径 → 执行者**只到 PR off origin/main**,Opus 终审 + 从 main 部署 **test 先行 → prod**(jar/迁移/force_rls/GRANT 核对)。
- mapper/抽取影响所有上传 → 格外稳,fail-soft/fail-open。
- 工作树 off origin/main(本地 main 落后 + 脏,⛔ 不在主目录干);commit 锁 scope。
- Codex 卡自包含(无 .claude/rules → 相关规则摘要内联)。

## 11. 开放/待定

- production 域「主分析路径」具体入口 = 实现时定位(找该域当前单 upload 读点)。
- UploadSwitcher 融合选项设不设为**默认**(免选 vs 保留选单上传)→ 实现时按现有 UploadSwitcher 交互定,倾向"全部融合"为默认 + 可下拉切回单次。
- dims 维度白名单是否需 per-域裁剪(避免无意义维度)→ v1 用全维度 canonical,production 实测后裁。
