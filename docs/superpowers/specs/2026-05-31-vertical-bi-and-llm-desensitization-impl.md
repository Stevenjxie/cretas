# 垂直 BI 分析 + LLM 出境脱敏/审计 — 落地实现包

**日期**: 2026-05-31
**来源**: workflow (wcbs8e360) 4 阶段(探索→产出→对抗审查→整合), 8 agents, 读真实 backend 产出。
**关联**: `docs/decisions/2026-05-31-cloud-agent-strategy-and-system-changes.md`, memory `project_2026_05_31_cloud_agent_moat_strategy.md`
**状态**: 已对抗审查 + 源码核实, 待落地。

---

## 0. 核实证据(P0 修正均经源码验证)

- **A1 (数据裸奔)**: `data_summarizer.py:113-114` `df.head(3).to_dict('records')` + `json.dumps` 把原始行(含中文客户名/门店名)塞进 prompt; choke point 只跑 PII 正则, **中文专名正则永远抓不到** → 数据裸奔出境。治法: 已知值字符串替换 + dump 前 `redact_dict`。
- **A2**: `restaurant_llm_composite.py:180` `SELECT p.name, p.category` → 单店招牌菜真名入 topItems → 占位化(category 保留)。
- **A3**: `llm_router.py:386→405` 脱敏点在 `for account in chain:` 循环内 → retry 间占位不稳 → 移到循环外。
- **B1 (预存陷阱 #590)**: `V20260515_01__llm_usage_allow_internal_sentinel.sql` 记载: pool setup(`tenant_ctx.py:80`)每次 checkout 写 `set_config('app.factory_id','__internal__')`, bg-flush 的 GUC 永远是 `__internal__`; 原 `IS NULL OR =''` RLS 策略永不命中 → 审计表一行写不进。新审计 migration **必须**补 `OR = '__internal__'` 三分支。
- **D2 (参数链不存在)**: `generator.py:63/146/303` + `prompt_builder.py:308` 签名**无** `factory_id`/`business_type` → 垂直/业态门控必须先做"参数链前置工程"。

---

## 1. 总览 — 改哪几个文件

### 阶段 0 — 参数链前置工程(D2, E/F 硬前提)
`generate_insights`/`build_cacheable_system_prompt` 签名加 `factory_id`/`business_type`, 向下透传 + 5 个 call-site(`chat.py`/`excel.py`/`insight.py`/`unified_analyzer.py`/`smart_analyzer.py`)透传; 拿不到传 `None` → 安全回落, 行为同现状。纯参数透传, 可独立 merge 验回归。

### 阶段 1 — P0 脱敏 + 出境审计
| 文件 | 操作 |
|---|---|
| `common/llm_redactor.py` | 新建: 脱敏器(结构化字段 + PII 正则 + **已知敏感值字符串替换** A1 + 稳定占位 + 还原) |
| `common/llm_egress_audit.py` | 新建: 审计写入(queue + bg-flush, 复用 llm_metrics 模式) |
| `smartbi/database/migrations/V20260531_01__create_llm_egress_audit.sql` | 新建: 审计表 + **含 `__internal__` 三分支 RLS** (B1) |
| `smartbi/api/llm_egress_audit_admin.py` | 新建: CSV 导出(admin-tier) |
| `smartbi/services/insights/data_summarizer.py` | 改: dump 原始行**之前** `redact_dict` (A1 治本点, line 113-114) |
| `smartbi/api/restaurant_llm_composite.py` | 改: dish `name` 占位化 (A2, line 180/345) |
| `common/llm_router.py` | 改: choke point 脱敏+审计移到**循环外** (A3, call_chain 363 / call_chain_stream 486); 出境后回填 `status_code` (B2) |
| `common/llm_metrics.py` | 改: 加 `_llm_data_window` ContextVar |
| `main.py` | 改: lifespan `enable_egress_audit()` + 挂 admin router + Java 来源 caller_context |

### 阶段 2 — BI 垂直 harness
| 文件 | 操作 |
|---|---|
| `smartbi/services/insights/domain_context.py` | 新建: `Fact`/`FactBook`/`DomainContext` 装配(画像/纵向基线/完整度), **FactBook label 用占位** (D3 与脱敏共享 placeholder_map) |
| `smartbi/services/insights/domain_rules.py` | 新建: 领域判读指引 registry (**删硬编码行业区间** C3, 区间仅 fallback 且仅无基线时用) |
| `smartbi/services/insights/fact_reconciler.py` | 新建: 对账器(哨兵拦截编造 + 数字回填确定性值 + **中文万/亿归一化** D5) |
| `smartbi/services/insights/generator.py` | 改: 出口加 reconcile + restore |

### 阶段 3 — 垂直 prompt
`prompt_builder.py` 注入餐饮/工厂两套专家 system role(删硬区间)+ few-shot(**推导链版**, 不凭空给万元数 C2)+ 引用约束铁律 + 业态门控(接阶段 0 参数)。

**落地铁律**: migration 经 `./scripts/deploy/deploy-smartbi-python.sh --env <env>` 自动 apply, **禁止手动 psql -f** (per `server-operations.md`)。worktree off `origin/main`, prod 只从 main (per `worktree-and-main-only-deploy.md`)。

---

## 2. P0 脱敏器核心代码 (`common/llm_redactor.py`)

**核心洞察(A1)**: 中文专名(人名/店名/菜品名)**正则永远抓不到**。唯一可靠手段 = 从 df 列名命中敏感字段的列**预抽全部值**, 文本里做精确字符串替换(长值优先, 防 '青花椒' 误伤 '青花椒大融城店')。

```python
# backend/python/common/llm_redactor.py  (NEW) — 关键部分
_SENSITIVE_KEYS = {  # key 小写 → 占位类型前缀
    "customer_name":"客户","store_name":"门店","brand_name":"品牌","supplier_name":"供应商",
    "company_name":"公司","factory_name":"工厂","contactname":"联系人","realname":"姓名",
    "owner":"负责人","phone":"电话","mobile":"电话","address":"地址","email":"邮箱","id_card":"证件",
    "dish_name":"菜品","product_name":"产品","sku_name":"商品",  # A2: 单店专名占位; category(品类)不在名单,保留
}
_PII_PATTERNS = [("证件",r"\d{17}[\dXx]"),("邮箱",r"[\w.+-]+@[\w-]+\.[\w.-]+"),
                 ("电话",r"1[3-9]\d{9}"),("电话",r"0\d{2,3}-?\d{7,8}")]

class PlaceholderAllocator:  # 稳定占位: 同一明文→同一占位(类型+字母序号). A3: 跨 retry 复用同一实例
    ...
def redact_text(text, *, allocator, factory_id=None, known_values=None):
    # (1)已知敏感值精确替换[长优先](中文专名核心轨道) (2)factory_id→FACTORY_<6hex> (3)PII正则兜底
    ...
def redact_dict(obj, *, allocator, factory_id=None):  # A1: data_summarizer dump 原始行之前调
    ...
def restore_text(text, placeholder_map):  # LLM 输出占位还原真名(长占位优先)
    ...
```
完整代码见 workflow 输出 (task wcbs8e360) §2.1 — 落地时按该全文。

边界决策表: 客户/门店/品牌/公司/供应商名、人名/电话/邮箱/证件/地址、菜品/产品/SKU 单店专名 → 占位; `factory_id`→`FACTORY_<6hex>`; 数字/金额/占比/日期/品类(牛肉/卤味通用词) → 保留; 审计表只存 `prompt_sha256` 不存明文。

## 3. 审计表 migration (B1 — RLS 必含三分支)

```sql
-- V20260531_01__create_llm_egress_audit.sql  RLS 关键(对齐 V20260515_01, 防 #590 复发):
CREATE POLICY tenant_insert_egress ON smart_bi_llm_egress_audit FOR INSERT WITH CHECK (
  (factory_id IS NULL AND (current_setting('app.factory_id',true) IS NULL
     OR current_setting('app.factory_id',true)='' 
     OR current_setting('app.factory_id',true)='__internal__'))        -- ← 三分支关键
  OR (factory_id IS NOT NULL AND (current_setting('app.factory_id',true) IS NULL
     OR current_setting('app.factory_id',true)=''
     OR current_setting('app.factory_id',true)='__internal__'           -- ← 三分支关键
     OR factory_id=current_setting('app.factory_id',true))));
-- 表字段: call_site/slot/provider/model/factory_id/sanitized/redacted_count/redacted_fields/
--        sent_field_keys(B3 业务字段名)/data_window/prompt_chars/prompt_sha256(不存明文)/status_code(B2 出境后回填)
-- apply 后必跑 verification: SET ROLE smartbi_user; set_config('app.factory_id','__internal__'); INSERT factory_id=NULL → 必 PASS
```
完整 SQL + 审计写入(`llm_egress_audit.py` queue+bg-flush) + choke point 集成(`llm_router.py` 脱敏循环外+出境后审计) 见 workflow 输出 §2.2-2.6。

## 4. 垂直 harness 要点

- **FactBook label 用占位** (D3): 对账在含占位的 LLM 原始输出上做(能匹配), 最后统一 `restore_text` 还原真名; FactBook 消费 choke point 同一 placeholder_map。
- **纵向基线**: 本月 vs 上月 vs 去年同月(`series[-13]`, calendar year 非 ISO, Decimal HALF_UP); 无基线 → `None` → "—(无上月)", **禁用行业基准假装基线**。
- **领域规则当指引** (C3): 删具体百分比区间(火锅 30-40% 等不进 system role), 只给方向(看食材成本率/看出成率), 达标交纵向基线; 区间数字仅 fallback 且仅 `has_baseline=False` 时注入。
- **对账器** (D5): 数字提取先归一化中文万/亿; LLM 在无数据指标编了数字 → 删 + confidence≤0.4; LLM 数字与 FactBook 钦定值差 >5% → 回填 Java 真值。
- **画像并发** (D4): N 个 Java 指标 `asyncio.gather` 并发(非串行), 3s 上限, 优先读已落库 smartbi 表。

## 5. 两套 system prompt + 业态分支

- **餐饮**: 资深连锁餐饮经营顾问 persona; 诊断顺序 营收/客单 → 菜品毛利结构(揪负毛利/高毛利低销量, 四象限) → 损耗/渠道占比 → 可执行调整。
- **工厂**: 食品加工厂生产/成本顾问; 诊断顺序 出成率 → 良率/品控 → 人效 → 成本/三价异常 → 安全库存; 出成率低先分"加工浪费(归车间) vs 原料出成低(归采购)"。
- **few-shot 推导链版** (C2): 不给"预计月增毛利 ¥3.4 万"凭空数字, 给推导链"增量 ¥8.4/份 ×【本月外卖单量, 代入事实表算】= 月增毛利"。与哨兵"禁编造数字"一致。
- **引用约束铁律**(进 system prompt): 金额/比率/天数必须引用确定性事实表, 禁自行重算/编造; 事实表标"无数据"的指标禁给数字; 口径标注([毛]/[净]/[按营业额])与事实表一致。
- **业态分支**: `business_type` 硬门控优先(RESTAURANT/FACTORY), 缺失才回落 `detect_analysis_scenario`(接阶段 0 参数)。

## 6. 测试计划(摘要)

- **脱敏无泄露**: 含「张权」「青花椒大融城店」「秘制猪舌」的 df → 断言输出 0 真名残留, 有占位; 数字 0 改动; retry 占位稳定(A3); `grep client.post/stream` 确认无旁路出境点。
- **审计有效**: apply 后跑 RLS verification SQL(`__internal__` INSERT 必 PASS, 防 #590); bg-flush ≤5s 真写入; status_code 回填非 None; 表无明文。
- **分析垂直**: F006(FACTORY 卤味)走 FACTORY_EXPERT 非 restaurant(防误判); 数字回填 Java 真值; 哨兵拦截编造(confidence≤0.4); 中文「3.4 万」vs 34000 不误判(D5); 纵向基线用 series[-13]; few-shot 数字可溯源。
- **无回归**: `factory_id=None,business_type=None` 行为同现状; 5 call-site 无 TypeError; 现有 SmartBI E2E(headed per `playwright-headed-mode.md`); 画像并发 insight 增量 <3s。

## 7. 落地顺序 + 风险

**顺序**: 阶段 0(参数链, 独立 merge 验回落) → 阶段 1(P0 脱敏+审计, apply 后立即跑 RLS verification) → 阶段 2(harness, 3 新文件可并行) → 阶段 3(prompt)。

**风险**: 审计 RLS 复发 #590(已在 migration 修, apply 必验) | 脱敏漏中文专名(已知值替换轨道 + 单测 0 残留 + grep 无旁路) | 脱敏与对账互斥(FactBook 共享 placeholder_map, 对账在含占位输出做最后还原) | D2 改动量低估(阶段 0 独立先验) | Java 指标 RPC 拖慢(并发+3s 上限+读 smartbi 表) | few-shot 诱导编造(推导链版+断言可溯源) | 流式还原占位被切 chunk(SSE 聚合后最外层一次性 restore) | 并发部署覆盖(prod 只从 main, 部署后 grep 核对运行代码)。

---

完整代码全文(redactor / migration / audit / choke-point / harness / prompts)在 workflow 输出 task wcbs8e360。落地按该全文。
