# 统一时间融合数据源 + SmartBI 全局 Python 收口 架构设计

> **状态**: 架构 spec(待 Steve 审,v2 已折入 prod 实测 + 四镜头对抗审计 + 港口勘察)。**只定架构与分阶段,不含逐任务实现计划**(后续 `writing-plans`)。
> **触发**: 2026-06-14 Steve 观察"数据分析还要手动选数据源,理论上是智能分析、上传按时间融合进一个源" → 顺藤摸到 Java/Python split-brain → Steve 拍板"长期放弃整个 Java AI 层,全局 Python"。
> **关键纪律**: 方向经 prod 真实库四轮实测 + 四镜头对抗审计 + 三探子港口勘察校正过,不是凭代码审计拍的(见 §3、§附)。

**Goal**: 让"上传 → 按时间自动融合进统一源 → 智能分析/AI问答 直接读它、免手动选数据源"成为真实可用,覆盖全业务域;同时把 SmartBI 数据层从 Java/Python split-brain 收口为 **Python 端到端独占**。

**Tech Stack**: Python FastAPI (`backend/python/smartbi`) + PostgreSQL (`smartbi_prod_db`, FORCE RLS, GUC `app.factory_id`) + Vue3 web-admin + DashScope embedding/LLM。

---

## 0. 北极星 + 本 spec 定位

**北极星(Steve 拍板,长期)**: AI 的"智能"层(意图识别 + Tool 选择 + Skill 编排 + 分析 + AI问答)全部收口 Python;Java 退成 **运营/业务系统-of-record(cretas_db CRUD)+ auth/RBAC + 业务执行端点(由 Python AI 调用)**。

**关键 nuance(写/动作类 AI Tool)**: 337 个 Java AI Tool 里,**读/分析类**本就大半委托 Python(顺势做完);**写/动作类**(建单/领料/审批)焊死在 Java 业务服务 + `@RequirePermission` + 事务上,其现实终态是"**Python 管意图+编排,调 Java 业务 API 执行**",不是把 ERP 业务逻辑重写进 Python。所以"放弃 Java AI" = 放弃 Java 的 AI 智能/编排,**保留** Java 的业务执行(被 Python 调)。

**本 spec 的定位 = 北极星的 Phase 1**: Python 端到端独占 **smartbi 数据层 + 分析 + AI问答读取**。不在本 spec 里 spec 全量 337-tool 迁移(多季度、自有 roadmap)。

**承接 Phase 2A**: 这不是从零起——Phase 2A 已把 ~50 个 SmartBI **分析端点** Java→Python port(`analysis_*.py`),seam 已宽(`PythonSmartBIClient` 40+ 委托)。本 spec = **收尾 Phase 2A 那条弧线**:补它没做的摄取核心 + 运营同步 + Java AI 取数器,并清理它留下的 6 个"THIN 僵尸"分析服务(HTTP 已切 Python、内部 `enrichUnifiedDashboard` 仍在调,未删净)。

**硬护栏(立即生效)**: 从今往后**新的分析/AI/数据能力一律落 Python,不再新增 Java AI/直读 smartbi 表**——停止把 split-brain 挖深。

---

## 1. 问题:两套并行分析系统 + Java/Python split-brain

**表象**——"号称智能分析却要手动选数据源":

| | 驾驶舱 / AI问答(gold 路径) | 数据分析页(Excel 上传路径) |
|--|--|--|
| 数据源 | 按时间融合的统一类型表 | 每上传一个孤立数据集(`smart_bi_dynamic_data` 按 `upload_id` 隔离) |
| 选数据源 | 不用选 | **≥2 批次弹下拉手动选**(`UploadSwitcher.vue`) |
| 范围 | 全时间线 | 一次只读一个 upload(`chat.py` `LIMIT 1`;Java `filterToLatestUpload()` 取 `max(upload_id)`) |

**根因(两层)**:
1. **数据模型**: Excel 上传走旧的"按文件"模型,从没按时间融合;驾驶舱已是融合/免选形态,但数据来自运营 ETL 不来自上传。
2. **架构**: 同一逻辑操作(ingest/map/store/read)**Java 写一半、Python 写一半、两边都读**——这是审计抓到的跨语言双写冲突、双部署、读取侧漏改的总根。

---

## 2. 现状勘察

### 2.1 摄取(ingest)
- prod 走 **Java 编排**:前端打 `POST /api/mobile/{factoryId}/smart-bi/upload-and-analyze`(`SmartBIUploadController`)→ `SmartBIUploadFlowServiceImpl` 委托 Python 解析(`PythonSmartBIClient.parseExcel` → `/api/excel/auto-parse`)→ **Java 持久化 5 张表**(`smart_bi_pg_excel_uploads`/`field_definitions`/`dynamic_data`/`analysis_cache`/`finance_data`)+ 确认门控(跨两次 HTTP 的有状态机)+ datasource 注册 + 触发 finance-extract。
- 解析三件套(Python):`StructureDetector`(规则+LLM 找 header)、`SemanticMapper`(列→标准字段,3 层:规则字典 → DashScope `text-embedding-v3` 向量 → LLM 兜底)、`TableClassifier`(纯规则)。
- `smart_bi_dynamic_data` 存**原始中文列名**;语义映射写 `smart_bi_pg_field_definitions`(每列:`original_name/standard_name/field_type/semantic_type/is_dimension/measure/time/agg_strategy`)。

### 2.2 统一类型时序表 + gold
- **统一类型时序表仅 `smart_bi_finance_data` 一张**:`(factory_id, upload_id[可空/ETL哨兵=0], record_date, record_type, department, category, …度量)`,幂等键 `UNIQUE(factory_id, upload_id, record_date, record_type, category) WHERE deleted_at IS NULL`。⚠️ **实测:prod 里全是 `upload_id=0/NULL`(ETL 喂),零条上传来源行**——"双源"是 schema 设计,上传半边在 prod 是空的(见 §3 / 审计 C2)。
- gold:`agg_daily/agg_product/agg_channel/agg_restaurant_*`(餐饮 POS)、`fact_production_batch/report` + `agg_factory_batch_daily`(工厂)、`agg_supplier_price`。ETL:`factory_production_etl.py`/`restaurant_ops_etl.py`/`supplier_price_ingest_etl.py`/`restaurant_finance_etl.py`,每日 timer `cretas-gold-etl-refresh.timer`(03:30)。
- RLS:`agg_*`/`fact_*`/`smart_bi_finance_data` 全 FORCE RLS;Python 必须**事务内** `set_config('app.factory_id',$1,true)`。

### 2.3 读取侧 + Java/Python 边界(港口勘察)
- **seam 已很宽**:`PythonSmartBIClient` 40+ 委托方法,分析半边(解析/字段映射/所有 `analysis/*`/gold/LLM 代理/意图分类)**已是 Python**。
- **残留 Java 直读核心**(LIVE,无 Python 等价):`DynamicAnalysisServiceImpl` + `SmartBiDynamicDataRepository` + `UnifiedSmartBIDataServiceImpl`——对 `smart_bi_dynamic_data` 的 per-upload JSONB 动态分析。**这就是"选数据源/只读最新"那套。**
- AI问答(Python):`chat.py` `general_analysis`(~913)+ `general_analysis_stream`(~1340),5 段 dispatch:趋势早退 → 综合合成 → gold-ops(`restaurant_ops_router`,**仅餐饮**)→ 模板路由(per-upload)→ LLM 兜底(per-upload 取最新)。工厂问题穿透 → "操作已完成"。
- 4 个 Java AI 调用点打 `/api/chat/general-analysis`(已委托 Python)。

---

## 3. 实测推翻代码审计(方向依据)

prod `smartbi_prod_db` 四轮只读实测,推翻"映射栈架构够、只是词表偏财务":

| 实测 | 结果 | 结论 |
|--|--|--|
| 映射覆盖 | `field_definitions` 18817 行 / 1042 上传(/1254 总,**83%**) | ✅ 在跑(初版看错 `field_mappings` 只 7 条) |
| 财务映射 | `budget_amount` 6828 / `actual_amount` 5055,高频一致 | ✅ 财务稳 |
| 非财务(销售/POS/餐饮) | RES_GML_001 业务表 ~95% 列映射失败;评价导出类是非业务数据(正确不映,不算 mapper 失败,见审计 I1) | 🔴 真业务表近乎全败,但需区分"非业务数据正确不映" |
| 同工厂(F001)内漂移 | `日期→{period,report_date}`、`商品名称→{category_name,product,product_name}` | 🔴 同列跨上传飘——**部分是 LLM 非确定性,部分是多模板下合法歧义**(审计 C1) |
| 垃圾 | 462 `数量金额*` + 298 空 + 950 原文回退 | mapper 失败回退桶 |
| 自学习 | `smart_bi_learning_candidates` 52 行 / 2 工厂,晋升 18 条静态、手动 | 🔴 空管,漂移根因之一 |
| 类型检测 | `general` 占全部上传 ~92% | 🔴 域路由不可靠 |

**核心**: 智能识别在跑、财务稳;非财务真业务表近乎全败、且同列会漂。**`standard_name` 不稳 → 不能直接当跨上传聚合键**,现在硬融合会串数据。映射一致性是长杆,不是建表。

---

## 4. 目标架构

```
上传 Excel ─┐
运营/POS 数据 ┼─►[Python 端到端: 解析→映射(受控字典+模板感知 pin)→域反推→按周期 upsert]
            ┘                    │ 规范行(稳定 canonical 键)
                                 ▼
                  统一类型时序源(各域一张表, 双源含溯源)
                  smart_bi_finance_data(已有) + smart_bi_{production,sales,purchase,inventory}_data
                                 │
                  ┌── 读取层 coalesce(运营 gold 优先, 上传表兜底, 去重) ──┐
                  ▼                                                       ▼
         数据分析页(全时间线免选)                            AI问答(全域 resolver 全时间线)
         (Java DynamicAnalysis 被 Python 替换)            (Java 不再直碰 smartbi 表)
```

**五个支柱**:
1. **Python 端到端独占数据层**:ingest→map→store→read/analyze 一个语言,溶掉跨语言双写/双部署/读取漏改。
2. **映射先稳**:统一源质量上限=映射质量;不稳则融合即垃圾。Phase 0 钉死映射是关键路径前置。
3. **复用不另起炉灶**:扩现有 `SemanticMapper` 受控词表 + **激活**现有自学习闭环(不新建 mapper)。
4. **双源统一表**:沿用 `smart_bi_finance_data` 的"运营哨兵 + 上传 + 幂等键"推广各域 + **溯源去重**(防 ETL 行与上传行双计)。
5. **运营 gold 不动**:真客户在跑,读取层 coalesce、不并进 gold。

---

## 5. 分阶段

### Phase -1 — SmartBI 数据层 Python 收口(地基,与统一源工程合一)

**洞察**:残留的唯一大块 Java(`DynamicAnalysisServiceImpl`/`SmartBiDynamicDataRepository` 对 `smart_bi_dynamic_data` 的 per-upload 分析)**正是统一源要消灭的东西**。所以不是"先搬 Java 再改造"(做两遍),而是 **新建 Python 统一源直接替换 Java 核心(搬=建,一遍)**。

港口处置(详见 §附 港口清单):
- 🔴 **替换**(由统一源工程完成):Java upload-ingest + dynamic-analysis 核心(`SmartBIUploadFlowServiceImpl`/`DynamicAnalysisServiceImpl`/`SmartBiDynamicDataRepository`/`UnifiedSmartBIDataServiceImpl`)。
- 🟡 **搬**(M):6 个 THIN 僵尸分析服务(重构 `enrichUnifiedDashboard` 调 Python 后删)、`ProductionReportSyncServiceImpl`(把 `AUTO_PRODUCTION` 动态写 + 人效汇总搬 Python,关 Java cron)、`RestaurantFinancialMetricsFetcher`(翻成调 Python)。
- 🟢 **删**(S):`Production/QualityAnalysisServiceImpl`(伪随机 mock)、config-threshold stubs。
- ⚪ **保留 Java**:`SmartBIConfigController`(app 配置非分析数据)、26 个餐饮 gold/* 工具(已调 Python = 目标形态)、4 个 Java AI 调用点(已调 Python)、业务执行端点。

### Phase 0 — 映射确定化 + 补域(关键路径前置)

**0.1 受控规范字典(per 域)**: 定义各域 canonical 枚举(财务已有;生产 `output_quantity/good_quantity/defect_rate/yield_rate/process_category/labor_cost/...`;销售/POS `sales_amount/sales_qty/unit_price/discount/actual_receive/channel/...`;采购/库存类似)。`SemanticMapper` 只映进固定词表;映不出 → **复核队列**(不再产生 `数量金额_N`/空)。⚠️ 字典与现有 `STANDARD_FIELDS`(`semantic_mapper.py:76`)对齐为**单一事实源**,不并存两份(审计 M1)。

**0.2 激活自学习成"确定性固定"(杀漂移)**: `(factory_id, normalized_column, 模板判别)` → canonical 一旦定就 memoize,以后同列同名。⚠️ **pin 键必含模板维度**(`+detected_table_type` 或列集指纹)——审计 C1:同工厂多模板下同列合法歧义(`商品名称`=SKU vs 类目),per-(factory,column) 会硬绑错。⚠️ **晋升仍走人审门**(`learning_promotion.py` 核心不变量"绝不静默自动毕业",审计 C3);Phase 0.2 = 激活捕获 + 用 pin 表实时生效,不是自动全局晋升。pin 表 DB 化(否则 `_TRUNK/_BRANCH` 文件全局变量需重启,审计 I2)。

**0.3 复核队列(真建,审计 C3)**: 现状只有一次性 `POST .../upload/confirm`(Java),无常驻队列。需:复核队列表 + web-admin UI/端点(Python)。⚠️ UX 守 `fool-proof-design`:批量首见列**集中一次确认**(不是逐行/逐上传阻塞),给操作员明确"X 列待标注"而非分析时才报错(Rule 1/5)。

**0.4 域反推**: 从 canonical 字段后验定域(映到 revenue/cost→财务,output_quantity/yield→生产),不靠 92% 是 `general` 的 `detected_table_type`。

### Phase 1 — 财务/经营 读取侧免选(但**不是只读改**,审计 C2 修正)

⚠️ **审计推翻"快赢"**:`smart_bi_finance_data` 在 prod 是 ETL-only;`filterToLatestUpload()`/`LIMIT 1` 正是当前**防 ETL 哨兵行 + 上传行双计**的隔离。naive 去掉会对 **qhj_prod 活客户双计财务**。所以 Phase 1 必须:
- 先实现**溯源 coalesce/去重**(同 `(factory,period,record_type,category)` ETL 行 vs 上传行,按 `source_type` 优先级取一,不双计),**先验 qhj_prod 不变**,再去掉 `max(upload_id)` 收窄。
- 大文件(>50万格)Java 跳过财务抽取 → finance_data 可能空(审计 I3),读取侧容错。

### Phase 2 — 非财务写入统一时序源

- 各域建 `smart_bi_{production,sales,purchase,inventory}_data`(镜像 finance + `source_type` 溯源 + 幂等键 + **模板/周期去重**)。⚠️ 走 smartbi **migration runner**(`V<YYYYMMDD>_<NN>__*.sql`)+ 每表 FORCE RLS policy(3 段)+ GRANT smartbi_user(漏 RLS=跨租户泄露,硬规则)。⚠️ 名字撞 `cretas_db.smart_bi_sales_data`(不同库),用全限定名(审计 I2)。
- 协调既有写入路径(审计 C1/C2):退役 Java `ProductionReportSyncServiceImpl` 的动态写、与 Python `data_sync.py`(`POST /sync-system-data` 的 DELETE+INSERT)统一为 Python upsert、与 SHA-256 去重(`uq_upload_factory_hash`)+ `merge_status` 过滤协调(审计 I1/I5)。

### Phase 3 — 分析页 + AI问答 读统一源(全域全时间线)

- 读取层 **coalesce**:有运营 gold 的域优先 gold,只有上传读统一表,都按 `factory+时间`、免选;运营 gold 不动。
- AI问答:`restaurant_ops_router` `_RESOLVERS` 扩全域(收工厂 AI问答缺口);`general_analysis` + `_stream` 抽共享 `_build_analysis_context` 一起改。
- ⚠️ **Java 读取消费者由 Phase -1 替换消解**(不是 §6 patch):`SmartBiDynamicDataRepository` 6+ 查询 + `DynamicAnalysisServiceImpl` 被 Python 替换后,审计 C4/C5 的"漏改 Java 侧"自动不存在。`standard_name` 也被图表标签用(`smart_analyzer.py`/`db_analysis.py`),漂移今天就影响图表——Phase 0 修了一并受益。

---

## 6. 数据模型

### 6.1 各域统一类型时序表(镜像 `smart_bi_finance_data`)
```sql
CREATE TABLE smart_bi_sales_data (        -- production/purchase/inventory 同构
  id bigserial PRIMARY KEY,
  factory_id  varchar(50) NOT NULL,
  upload_id   bigint,                      -- 上传=真 id, 运营 ETL=哨兵 0, 可空
  source_type varchar(20) NOT NULL,        -- UPLOAD / OPERATIONAL_ETL (溯源 + coalesce 优先级)
  period_date date NOT NULL,
  canonical_* ...,                          -- 该域受控 canonical 度量/维度
  created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), deleted_at timestamptz,
  CONSTRAINT uq_sales UNIQUE (factory_id, source_type, period_date, /*canonical dims*/) WHERE deleted_at IS NULL
);
-- FORCE ROW LEVEL SECURITY + policy(factory_id = current_setting('app.factory_id', true)) + GRANT smartbi_user
```

### 6.2 映射固定(pin)表 — 含模板维度(审计 C1)
```sql
CREATE TABLE smart_bi_field_pin (
  factory_id        varchar(50)  NOT NULL,
  template_key      varchar(64)  NOT NULL,   -- detected_table_type 或 列集指纹(模板判别)
  normalized_column varchar(200) NOT NULL,
  canonical_field   varchar(100) NOT NULL,
  domain            varchar(20),
  confidence        numeric(4,3),
  confirmed_by      varchar(50),             -- 人工一次性确认(可空)
  source_method     varchar(20),
  created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(),
  PRIMARY KEY (factory_id, template_key, normalized_column)
);
```

### 6.3 受控规范字典
版本化(`canonical_fields_by_domain.json` 或表),每域 `{canonical_field, aliases[], dtype, agg_strategy, is_dimension/measure/time}`;**与 `STANDARD_FIELDS` 合并为单一事实源**。

---

## 7. 决策表(与 Steve 对齐)

| 决策 | 选择 | 理由 |
|--|--|--|
| 数据域范围 | **全业务域** | Steve 拍板;各域都有用,只是入口不同(上传/运营同步) |
| 语言收口 | **Python 端到端独占 smartbi 数据层**(北极星 Phase 1) | 溶掉跨语言双写/双部署/读取漏改;seam 已宽,主要是做完迁移 |
| Java 保留 | config CRUD + auth/RBAC + 业务执行端点(被 Python 调) | 写/动作类 AI 焊死业务逻辑,不重写 ERP |
| 大块 Java 核心 | **替换不搬迁**(统一源工程顺手替换) | 它正是统一源要消灭的 per-upload 分析,做两遍浪费 |
| 映射 | 复用+扩展 `SemanticMapper`,受控字典,映不出进复核队列 | 实测在跑且能映全域,缺的是稳定性 |
| 杀漂移 | 激活自学习成 pin,**键含模板维度**,人审晋升门 | 同工厂多模板下同列合法歧义,naive pin 会绑错 |
| 域路由 | canonical 字段后验反推 | `detected_table_type` 92% 是 general |
| 存储 | 每域一张类型时序表(镜像 finance)+ source_type 溯源 | 已验证模式 + 防双计 |
| gold 重叠 | 读取层 coalesce,不并进 gold | 真客户在跑,爆炸半径最小 |
| 阶段顺序 | Phase -1 收口/替换核心 → Phase 0 映射钉死 → Phase 1 财务免选(带去重) → Phase 2 非财务融合 → Phase 3 全域读 | 映射是长杆;财务带去重后先收割 |

---

## 8. 迁移 / 回填
- 财务:`smart_bi_finance_data` 已有 ETL 数据,Phase 1 改读取 + 去重,无需回填上传。
- 非财务:Phase 0 字典+pin 就绪后,用确定化映射**回放历史上传**(`dynamic_data` + `field_definitions` 已存)→ 写各域统一表。
- ⚠️ 回填**优先 pin/规则/embedding,LLM 仅兜底**(控成本,`feedback_llm_cost_lanes_beyond_router`);幂等按 `(模板/周期)` upsert;分租户增量。
- ⚠️ 退役 Java `ProductionReportSync` 前确认 `smart_bi_dynamic_data WHERE source='AUTO_PRODUCTION'` 消费方 + 补人效汇总 ETL(Python ETL 现不写每工人粒度)。

## 9. 风险 / 未决
- **确认门控状态机 port 风险**(Java 跨两次 HTTP 的有状态摄取 + RBAC + 事务)是 Phase -1 最大风险,需先设计 Python 等价(含鉴权)。
- **复核队列 UX** 不能变操作员负担(fool-proof);谁确认/超时/在哪,实现计划定。
- **canonical 字典单一事实源**:字典 vs `STANDARD_FIELDS` vs `field_definitions` 三者对齐,锁定一处。
- **Flyway/migration 撞号**(并发 session),Phase 2 四表编号 + RLS + GRANT 逐项写明。
- **Java/Python 部署时序**:Phase -1/1 跨 Java+Python,需 feature flag 或先 Python 实现再切 Java(零停机)。
- 大文件跳过财务抽取;SHA-256 与周期 upsert 两层幂等协调。

## 10. 非目标
- 不在本 spec spec 全量 337-tool Java-AI→Python 迁移(后续 program / 自有 roadmap);本 spec = 数据层 + 分析 + AI问答读取。
- 不重写 Java 业务执行逻辑(写/动作类 AI 终态 = Python 编排调 Java 业务 API)。
- 不重写/并入运营 gold ETL 物理表;不做 VL(Excel 截图)映射。

## 11. 成功标准
1. 同工厂同模板同列跨上传 **100% 同 canonical**(漂移归零)。
2. 非财务真业务上传"可对齐率"达标,复核队列兜底,无 `数量金额_N`/空。
3. 数据分析页 + AI问答 **全域、全时间线、零手动选数据源**;工厂 AI问答能答真实跨时间产量/成本/出成率。
4. **财务数字零回归**(尤其 qhj_prod 活客户——去掉 latest 过滤后不双计)。
5. 运营 gold 行为零回归;Java 不再直碰 smartbi 表(收口验证)。

---

## 附 A. 实测证据指针(prod `smartbi_prod_db`, 2026-06-14 只读)
`field_definitions` 18817/1042 上传(/1254);财务高频稳(budget_amount 6828);非财务真业务表 ~95% 失败;F001 内同列多 canonical;`learning_candidates` 52/2 工厂;`general` 占 ~92%;`smart_bi_finance_data` 全 `upload_id=0/NULL`(ETL-only)。

## 附 B. 港口清单(Java SmartBI 收口工作量分级)
| 组件 | 现状 | 处置 | 工作量 |
|--|--|--|--|
| `SmartBIUploadFlowServiceImpl`+`DynamicAnalysisServiceImpl`+`SmartBiDynamicDataRepository`+`UnifiedSmartBIDataServiceImpl` | 🔴 LIVE 核心,有状态,无 Python 等价 | 统一源工程**替换** | L(随统一源) |
| 6 域分析服务(Finance/Sales/Procurement/Region/Department/Inventory) | 🟡 THIN 僵尸(HTTP 已切 Python,`enrichUnifiedDashboard` 内部还调) | 重构 enrich 调 Python 后删 | M |
| `ProductionReportSyncServiceImpl`(每天 2am `AUTO_PRODUCTION`) | LIVE,与 `factory_production_etl` 零重叠 | 动态写+人效汇总搬 Python,关 cron | M(低风险) |
| `RestaurantFinancialMetricsFetcher`/`StorePnlOnePagerTool` | LIVE Java 取数器直读 finance_data | 翻成调 Python | M-S |
| `Production/QualityAnalysisServiceImpl` | 🟢 伪随机 mock,Python 等价已存未接 nginx | 接 Python + 删 | S |
| `callConfigThresholds*`/`fetchIndicatorValue` | 🟢 stub 返 null/ZERO | 删 | S |
| 26 餐饮 gold/* 工具、4 个 Java AI 调用点 | 已调 Python = 目标形态 | **保留** | 0 |
| `SmartBIConfigController`(意图/告警/激励/字段 CRUD) | LIVE,无 Python 等价 | **保留**(app 配置非数据层) | — |
