# 统一时间融合数据源 架构设计 (Unified Time-Fused Data Source)

> **状态**: 架构 spec(待 Steve 审)。**本文只定架构与分阶段,不含逐任务实现计划**(实现计划另行 `writing-plans`)。
> **触发**: 2026-06-14 Steve 观察:"数据分析还是需要去选择,理论上我们是智能分析,然后上传的数据就按照时间去融合到一个数据源了"。
> **关键纪律**: 本 spec 的方向不是凭代码审计拍的——是 prod 真实库四轮实测**推翻了**初版代码审计结论后重排的(见 §3)。

**Goal**: 让"上传 → 按时间自动融合进一个统一源 → 智能分析/AI问答 直接读它,免手动选数据源"成为真实可用的架构,覆盖全业务域(财务/生产/销售/采购/库存)。

**Architecture**: 复用并激活现有的"智能识别 + LLM 兜底 + 自学习"映射栈,把它从"在跑但不稳"修成"确定性受控",再在其上建各域统一类型时序表 + 读取侧全时间线融合。

**Tech Stack**: Python FastAPI (`backend/python/smartbi`) + PostgreSQL (`smartbi_prod_db`, FORCE RLS, GUC `app.factory_id`) + Vue3 web-admin + DashScope embedding/LLM。

---

## 1. 问题:两套并行分析系统,只有一套是"智能"的

| | 驾驶舱 / AI问答(gold 路径) | 数据分析页(Excel 上传路径) |
|--|--|--|
| 数据源 | 按时间融合的统一类型表(`smart_bi_finance_data` / `agg_*`) | 每个上传一个孤立数据集(`smart_bi_dynamic_data` 按 `upload_id` 隔离) |
| 选数据源 | 不用选,自动跨整条时间线 | **≥2 个批次就弹下拉手动选**(`UploadSwitcher.vue` `v-if="batches.length>1"`) |
| 分析范围 | 全时间线 | 一次只读一个 upload(`chat.py` `ORDER BY … LIMIT 1`;Java `RestaurantFinancialMetricsFetcher.filterToLatestUpload()` 取 `max(upload_id)`) |

"号称智能分析、却还要手动选数据源"的割裂感,根因:**Excel 上传走旧的"按文件"模型,从没按时间融合进统一源**;而驾驶舱已经是想要的形态(融合、免选),只是它的数据来自运营库 ETL,不来自上传。

**愿景**:上传任何业务 Excel → 识别类型 → 抽取的行按时间 upsert 进统一类型时序表 → 分析 + AI问答 跨整条时间线读它、免选。

---

## 2. 现状勘察(代码层,三块并行调研)

### 2.1 摄取(ingest)
- 同步 `POST /api/smartbi/excel/auto-parse`(`excel.py:644`)与异步 `auto-parse-async`(`excel_async.py:122`)两条路径。**prod 实际走异步路径**。
- 解析三件套:`StructureDetector.detect()`(规则+LLM 找 header)、`SemanticMapper.map_fields()`(列名→标准字段,3 层:规则字典 → DashScope `text-embedding-v3` 向量 cos≥0.65 → LLM 免费链兜底)、`TableClassifier.classify()`(纯规则)。
- 异步路径落库:原始行写 `smart_bi_dynamic_data`(JSONB `row_data`,**原始中文列名**),语义映射写 `smart_bi_pg_field_definitions`(每列一行:`original_name/standard_name/field_type/semantic_type/is_dimension/is_measure/is_time/agg_strategy`)。
- 同步路径的 `field_mappings`(jsonb on `smart_bi_pg_excel_uploads`)经 `FixedExecutor` 规范化列名——**但 prod 几乎不用此路径**。
- 财务专用抽取 `finance_extract.py`(关键词匹配行标签,**不读 field_mappings**)→ 写统一表 `smart_bi_finance_data`。

### 2.2 统一类型时序表 + gold
- **统一类型时序表仅 `smart_bi_finance_data` 一张**:`(factory_id, upload_id[可空,ETL 哨兵=0], record_date, record_type[REVENUE/COST/AR/BUDGET], department, category, …度量)`,幂等键 `UNIQUE(factory_id, upload_id, record_date, record_type, category) WHERE deleted_at IS NULL`。**双源**:运营 ETL 写哨兵行 + 上传写真 upload_id 行。**生产/销售/采购/库存无对应统一表。**
- gold 层:`agg_daily/agg_product/agg_channel/agg_daily_order_type_meal/agg_restaurant_*`(餐饮 POS)、`fact_production_batch/report` + `agg_factory_batch_daily`(工厂生产)、`agg_supplier_price`(供应商价)。
- ETL:`factory_production_etl.py` / `restaurant_ops_etl.py` / `supplier_price_ingest_etl.py` / `restaurant_finance_etl.py`,每日 timer `cretas-gold-etl-refresh.timer` → `gold_etl_daily_refresh.py`(03:30 CST)。
- RLS:`agg_*`/`fact_*`/`smart_bi_finance_data` 全 FORCE RLS;Python 必须**事务内** `set_config('app.factory_id', $1, true)`(见 `feedback_asyncpg_rls_guc_must_be_in_transaction`)。

### 2.3 读取侧(分析页 + AI问答)
- 数据分析页:`AIQuery.vue` 拉 `getUploadHistory` → `UploadSwitcher` 选一个 upload → 后端 `WHERE upload_id=$1` 读 `smart_bi_dynamic_data`。
- AI问答:`chat.py` `general_analysis`(~913)+ `general_analysis_stream`(~1340),共享 5 段 dispatch:`[1]` 趋势早退 → `[2]` 综合合成 → `[3]` gold-ops(`restaurant_ops_router`,**仅餐饮**)→ `[4]` 模板路由(per-upload)→ `[5]` LLM 兜底(per-upload 取最新)。工厂问题穿透 [3]→[4]→[5] → 返回泛泛"操作已完成"。
- gold resolver 注册:`restaurant_ops_router.py` 的 `_RESOLVERS` + `resolve_by_code`,各 resolver 直读 `agg_*`(`WHERE factory_id`,天然全时间线)。
- **选数据源 enforcement 共 11 处**(改造清单见 §7)。

---

## 3. 实测推翻代码审计(这是 spec 重排的依据)

代码审计初版结论:"映射栈架构够硬,只是词表偏财务"。**prod `smartbi_prod_db` 四轮只读实测推翻了它**:

| 实测项 | 结果 | 结论 |
|--|--|--|
| 映射在跑吗 | `field_definitions` 18817 条 / **1042 个上传有定义(覆盖 84%)** | ✅ 在跑(初版看 `field_mappings` 只 7 条是看错表) |
| 财务映射 | `budget_amount` 6828 行 / `actual_amount` 5055,高频一致 | ✅ 财务稳——驾驶舱 coherent 的原因 |
| 非财务(销售/POS/餐饮) | RES_GML_001:112 列→**110 坏**;餐饮"评价下载":30→**30 全坏** | 🔴 **95–100% 失败**,不是"词表窄"是近乎全败 |
| 同列漂移(同工厂 F001 内) | `日期→{period,report_date}`(71 上传)、`商品名称→{category_name,product,product_name}`(29)、`区域→{region,category_name_6}`(48) | 🔴 **同工厂同列跨上传映到不同规范名**——纯非确定性 |
| 垃圾/失败 | 462 个 `数量金额_N` + 298 个空 + 950 个原文回退(/18817) | 硬败 ~9%,加漂移后"可对齐"比例远更低 |
| 自学习 | 候选表全网 **52 条 / 2 工厂**,晋升 18 条(静态),手动触发 | 🔴 **空管**——漂移的根因 |
| 类型检测 | 1042 个里 **978 个是 `general`** | 🔴 域路由没在工作 |

**核心判断**:智能识别在跑、**只有财务这条线真稳**;其它域映射近乎全败、且同列跨上传会漂。漂移根因 = **自学习是关着的**(无 memoize → 每次重猜 → 飘)。

**对愿景的致命含义**:`standard_name` 不稳定 → **不能**作为跨上传聚合键。现在直接"按时间融合",会把 `销售金额` 拆进 `销售金额/数量金额_4/数量金额_6/原文` 四个桶,融出来是垃圾。**映射一致性是真正的长杆,不是建表。**

---

## 4. 目标架构

```
上传 Excel ──► [解析] ──► [映射: 受控规范字典 + 确定性固定(自学习)] ──► [域反推]
                                      │ 规范行(稳定 canonical 键)
                                      ▼
                        ┌──────────────────────────────────────┐
                        │  统一类型时序源(各域一张表, 双源)      │
                        │  smart_bi_finance_data(已有) +          │
                        │  smart_bi_{production,sales,...}_data    │
                        │  键: (factory_id, period_date, canonical)│
                        └──────────────────────────────────────┘
                                      ▲ 运营 ETL 也写(哨兵 upload_id=0)
            ┌─────────────────────────┴── 读取层 coalesce ──┐
            ▼                                                ▼
   分析页(全时间线, 免选)                         AI问答(全域 resolver, 全时间线)
```

**四个不可妥协的设计支柱:**
1. **映射先稳**:统一源的质量上限 = 喂它的映射质量。映射不稳 → 融合即垃圾。所以**Phase 0 = 把映射钉死**,是关键路径前置,不是输入假设。
2. **复用不另起炉灶**:不新建 mapper。扩现有 `SemanticMapper` 的受控词表 + **激活**现有 `smart_bi_learning_candidates`/`learning_promotion` 自学习闭环。
3. **双源统一表**:沿用 `smart_bi_finance_data` 已验证的"运营 ETL(哨兵)+ 上传(真 upload_id)+ 幂等键"模式推广到各域。
4. **运营 gold 不动**:真客户在跑 gold,爆炸半径最小化——读取层 coalesce,不把上传并进 gold。

---

## 5. 分阶段设计

### Phase 0 — 映射确定化 + 补域(关键路径前置)

**0.1 受控规范字典(per 域)**
- 定义各域 canonical 字段枚举:财务(已有)、生产(`output_quantity/good_quantity/defect_rate/yield_rate/process_category/labor_cost/material_cost/work_minutes/operation_volume/efficiency_per_hour`,实测 `field_definitions` 已有这些好样本)、销售/POS(`sales_amount/sales_qty/unit_price/discount_amount/actual_receive/refund_amount/channel/product_name/order_time`)、采购(`purchase_qty/purchase_price/supplier/material_name/delivery_date`)、库存(`stock_qty/turnover_rate/safety_stock/expiry_date`)、通用维度(`period/category/department/region/product`)。
- `SemanticMapper` 只能映进这个固定词表;**映不出 → 进复核队列**,不再产生 `数量金额_N` 垃圾或空。
- 字典作为单一事实源(文件 or 表),版本化。

**0.2 激活自学习成"确定性固定"(杀漂移)**
- 现状:`smart_bi_learning_candidates`(`source_key/target_value/factory_id/method/confidence/occurrences`)在捕获,但晋升手动、`promoted_learnings.json` 仅 18 条。
- 改造:`(factory_id, normalized_original_column) → canonical_field` 一旦决定就 **memoize**(pin 表 or 提升 candidates 为权威);以后**同列永远同名**,跳过 LLM 重猜。
- 低置信(< 阈值)走**一次性人工确认**(防呆:批次首见的新列让运营确认一次,之后记住——符合 `fool-proof-design` Rule 3 约束选择)。
- 自动晋升:同 candidate 在 N 个工厂/行业达成共识 → 升行业/全局词表(`learning_promotion.py` 逻辑已在,改为自动 apply + 加守卫防中毒)。
- 可选:与 6/11 自我蒸馏语料打通(映射决策 → 语料,反哺词表),但**非本期必需**。

**0.3 域从映射结果反推(不靠 `detected_table_type`)**
- 实测 94% 是 `general`,域路由不可依赖它。
- 改为**后验**:映射完成后看 canonical 字段落在哪个域字典(映到 `revenue/cost`→财务;`output_quantity/yield`→生产;`sales_amount/unit_price`→销售)→ 决定写入哪张统一表。多域混表 → 拆。

**Phase 0 验收**:同一工厂同一列跨上传 100% 同 canonical;非财务样本(RES_*)映射失败率从 ~95% 降到可接受阈值(复核队列兜底);`数量金额_N`/空/原文回退归零(全进字典或复核队列)。

### Phase 1 — 财务/经营 读取侧免选(快,已验证,先收割)

财务映射本来就稳、`smart_bi_finance_data` 已是双源全时间线表——**无需等 Phase 0**:
- 读取从"取最新一次上传"改为"读全时间线":去掉 Java `filterToLatestUpload()` 的 `max(upload_id)` 收窄、`chat.py` 的 `LIMIT 1`。
- 财务/经营分析页 `UploadSwitcher` 从"分析必选"降级为"数据来源/溯源"查看(默认全量)。
- 最高价值的经营分析立刻"免选"。

**Phase 1 验收**:财务/经营分析页打开即全时间线、无强制选择;同租户多月数据一次性呈现。

### Phase 2 — 非财务写入统一时序源

- 各域建一张 `smart_bi_{production,sales,purchase,inventory}_data` 表,镜像 `smart_bi_finance_data` 模式(`factory_id, period_date, canonical 维度/度量, upload_id[哨兵/真], 幂等键, audit 字段`)。
- 上传完成 → Phase 0 规范化行 → 按域路由 → **按周期 UPSERT**(同月重传=替换,不是新增;周期推断复用 `merge_inferred_period_*`)。
- 运营 ETL 可选也写哨兵行(与上传同表,双源)。

**Phase 2 验收**:非财务上传落各域统一表;同月重传幂等;跨多月上传按时间累积成一条线。

### Phase 3 — 分析页 + AI问答 读统一源(全域全时间线,收工厂 AI问答缺口)

- 读取层 **coalesce**:某域有运营 gold(生产 `agg_factory_batch_daily`、餐饮 `agg_daily`)→ 优先读 gold;只有上传 → 读 `smart_bi_*_data`;都按 `factory+时间`、都免选。运营 gold 一行不动。
- AI问答:`restaurant_ops_router` 的 `_RESOLVERS` 扩到全域(`resolve_by_code` 已自动分发新 code,`chat.py` dispatch 块基本不动)——顺手收掉工厂 AI问答返回"操作已完成"的缺口。
- `general_analysis` + `general_analysis_stream` 抽共享 `_build_analysis_context`,两条路一起改不漏(见 `feedback_intent_gate_must_cover_all_execution_paths`)。
- 数据分析页读取改为按 `factory_id` 全时间线(§7 的 11 处 enforcement)。

**Phase 3 验收**:工厂/餐饮 AI问答能答跨时间产量/成本/出成率/营收;数据分析页全域免选;`feedback_terminal_review_verify_integration_not_just_code`——前后端路径逐字核、真路径实跑。

---

## 6. 数据模型

### 6.1 各域统一类型时序表(镜像 `smart_bi_finance_data`)
```sql
CREATE TABLE smart_bi_sales_data (        -- production/purchase/inventory 同构
  id            bigserial PRIMARY KEY,
  factory_id    varchar(50)  NOT NULL,
  upload_id     bigint,                    -- 上传=真 id, 运营ETL=哨兵 0, 可空
  period_date   date         NOT NULL,
  canonical_*   ...,                        -- 该域受控 canonical 度量/维度列
  source_type   varchar(20),               -- UPLOAD / OPERATIONAL_ETL (溯源)
  created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), deleted_at timestamptz,
  CONSTRAINT uq_sales UNIQUE (factory_id, upload_id, period_date, /*canonical dims*/)  -- 幂等键, WHERE deleted_at IS NULL
);
-- FORCE ROW LEVEL SECURITY + policy: factory_id = current_setting('app.factory_id', true)
```

### 6.2 映射固定(pin)表
```sql
CREATE TABLE smart_bi_field_pin (
  factory_id        varchar(50) NOT NULL,
  normalized_column varchar(200) NOT NULL,   -- 归一化原始列名
  canonical_field   varchar(100) NOT NULL,   -- 受控字典里的 canonical
  domain            varchar(20),
  confidence        numeric(4,3),
  confirmed_by      varchar(50),             -- 人工一次性确认者(可空)
  source_method     varchar(20),             -- rule/embedding/llm/human
  created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(),
  PRIMARY KEY (factory_id, normalized_column)
);
```
> 复用既有 `smart_bi_learning_candidates` 升级为权威亦可——二选一在实现计划定。

### 6.3 受控规范字典
- 形态:版本化 JSON(`backend/python/smartbi/data/canonical_fields_by_domain.json`)或表;每域 `{canonical_field, aliases[], dtype, agg_strategy, is_dimension/measure/time}`。
- `SemanticMapper` 的目标空间 = 此字典;`learning_promotion` 晋升进此字典。

---

## 7. 读取侧"选数据源"改造清单(11 处,实测定位)

| # | 位置 | 当前 | 改为 |
|---|------|------|------|
| 1 | `chat.py:1641` | `int(request.sheet_id)` 单整数 | `None`(全量)/可选数组 |
| 2 | `chat.py:1866` | `load_materialization_results(pool, upload_id)` | 按 factory 全量 |
| 3 | `chat.py:1997-2007` | 自动选最大 upload | 按 factory 全时间线 |
| 4 | `chat.py:2022` | `WHERE upload_id=$1 LIMIT 200` | 读统一时序表 / `= ANY($1)` |
| 5 | `chat.py:2031-2040` | 字段定义按 upload_id | 按 factory / 合并 |
| 6 | `chat.py:2071-2077` | `_agg_cache[upload_id]` | key 改 `factory_id` |
| 7 | `chat.py:2189-2398` | C1/C2/C3 聚合 `WHERE upload_id=$N` | 读统一表 / `= ANY` + 归并 |
| 8 | `AIQuery.vue:215-222` | `selectedUploadId: ref<number\|null>` | 去掉 / 全选 |
| 9 | `AIQuery.vue:777-792` | `pickDefaultDataSource()` 选单个 | 去掉 / 全量 |
| 10 | `AIQuery.vue:1288` | `uploadId: String(selectedUploadId)` | 不传 / 数组 |
| 11 | `upload.ts:363-379` + Java `RestaurantFinancialMetricsFetcher.filterToLatestUpload()` | 单 upload | switcher 降级溯源;Java 去 `max(upload_id)` 收窄 |

---

## 8. 关键设计决策(已与 Steve 对齐)

| 决策 | 选择 | 理由 |
|--|--|--|
| 数据域范围 | **全业务域** | Steve 拍板 |
| 映射做法 | **复用+扩展现有 SemanticMapper**,不新建 | 实测它在跑且能映全域,缺的是稳定性 |
| 映射目标空间 | **受控字典**(映不出进复核队列) | 杀 `数量金额_N` 垃圾 + 给融合稳定键 |
| 杀漂移 | **激活自学习成确定性 pin**(memoize + 一次性确认) | 实测同工厂同列也漂,根因是无 memo |
| 域路由 | **从 canonical 字段后验反推** | 实测 `detected_table_type` 94% 是 general 不可靠 |
| 存储模型 | **每域一张类型时序表**(镜像 finance) | 已验证模式;比通用 EAV 更利精确分析 |
| 运营 gold 重叠 | **读取层 coalesce,不并进 gold** | 真客户在跑,爆炸半径最小 |
| 阶段顺序 | **映射先稳 → 财务免选(快) → 非财务融合 → 全域读** | 映射是长杆;财务已稳可先收割 |

---

## 9. 迁移 / 回填
- 财务:`smart_bi_finance_data` 已有数据,Phase 1 仅改读取,无需回填。
- 非财务:Phase 0 字典+pin 就绪后,用确定化映射**回放历史上传**(`smart_bi_dynamic_data` + `field_definitions` 已存)→ 写各域统一表。
- 回填幂等(按周期 UPSERT),可分租户增量跑。

## 10. 风险 / 未决
- **复核队列 UX**:一次性确认不能变成运营负担(防呆设计:批量首见列集中确认,不是逐行)。实现计划需设计该界面。
- **回放历史映射成本**:大量历史上传重映射的 LLM 成本——优先用 pin/规则,LLM 仅兜底(`feedback_llm_cost_lanes_beyond_router`)。
- **canonical 字典的权威归属**:字典 vs `field_definitions` vs `smart_bi_finance_data` 列,三者对齐需在实现计划锁定单一事实源。
- **运营 ETL 与上传双写同表的冲突**:同 (factory, period) 既有运营行又有上传行时的优先级(建议 source_type 优先级 + 幂等键含 source 维度)。

## 11. 非目标(Out of scope)
- 不重写 gold/运营 ETL;不把上传并进 gold 物理表。
- 不做 VL(Excel 截图视觉)映射(`structure_detector` 的 VL 路径未实现,本期不依赖)。
- 不改餐饮已稳的 gold resolver 行为(只扩工厂/其它域)。
- 不在本 spec 写逐任务实现计划(后续 `writing-plans`)。

## 12. 成功标准
1. 同一工厂同一列跨上传 **100% 同 canonical**(漂移归零)。
2. 非财务上传映射"可对齐率"达标(复核队列兜底,无 `数量金额_N`/空)。
3. 数据分析页 + AI问答 **全域、全时间线、零手动选数据源**。
4. 工厂 AI问答能答真实跨时间 产量/成本/出成率(当前返回"操作已完成"的缺口闭合)。
5. 运营 gold 行为零回归(真客户驾驶舱不受影响)。

---

## 附:实测证据指针(prod `smartbi_prod_db`, 2026-06-14 只读)
- 覆盖/词汇:`field_definitions` 18817 行 / 1042 上传;`standard_name` 570 distinct(财务高频:budget_amount 6828 / actual_amount 5055)。
- 失败:非财务 RES_* 上传 95–100% 坏;462 `数量金额_N` + 298 空 + 950 原文回退。
- 漂移:F001 内 `日期/商品名称/区域/营业日期` 等同列多 canonical。
- 自学习:`smart_bi_learning_candidates` 52 行 / 2 工厂;`promoted_learnings.json` 18 条静态。
- 类型:978/1042 上传 = `general`。
