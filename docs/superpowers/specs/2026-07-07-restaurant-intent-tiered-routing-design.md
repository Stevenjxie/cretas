# 餐饮 AI 问答分层意图路由 + Answer Contract 设计

**日期**: 2026-07-07
**范围**: Python 餐饮侧（chat.py + restaurant_ops_router 链路）。Java Tool-Skill 体系本轮不动。
**目标**: 把餐饮老板问答从"关键词模板路由"升级为「分层意图识别（关键词 → 向量 → LLM 结构化解析）+ 确定性 Gold 查询 + Answer Contract 校验 + 飞轮晋升」，让换说法也能稳定得到正确时间窗、正确指标、正确建议。

---

## 1. 背景与判断

### 1.1 问题

`restaurant_ops_router._OPS_PATTERNS` 是纯关键词组合匹配，词表持续膨胀（Apr 25 两次修复：删"最好"、删裸"菜"——加词修一个 case 破两个 case 的典型症状）。"最近两个月的营收情况如何，赚钱了吗"曾因时间/利润词没被完整覆盖而退化回全历史汇总。

### 1.2 关键事实：目标架构的大部分基建已存在于生产

| 能力 | 现状 | 位置 |
|---|---|---|
| 关键词+向量混合路由 | ✅ 生产运行，阈值 0.78/0.70 两轮 prod 调参 | `smartbi/services/template_rag.py` (`hybrid_match`) |
| sample_query 向量索引 | ✅ gte-base-zh + pgvector，幂等 populate | `smartbi/services/template_embedding_index.py` |
| 晋升飞轮（capture→gate→人审→promote） | ✅ 多域学习毕业闭环 | `smartbi/services/learning_promotion.py` |
| 餐饮意图样例库 | ✅ `SAMPLE_QUERIES`（注释明示"用于 RAG 语义路由 Phase 3"），**从未接线** | `smartbi/gold/restaurant_ops_router.py` |
| 确定性槽位解析（时间/利润意图/中文数字） | ✅ `_resolve_sales_date_range` / `_profit_intent` / `_parse_small_count` | 同上 |
| LLM 路由（计费安全、`enable_thinking:false`） | ✅ 7/1 上线 | SmartBI LLM router |
| LLM fallback 日志 / 蒸馏捕获 | ✅ | `llm_fallback_logger.py` / `distillation_capture.py` |

**结论**: 本次升级 = 接线 + QuerySpec 泛化 + Answer Contract，不造新轮子。

### 1.3 架构原则（不可违反）

1. **LLM 不编数字、不算日期** —— 只产结构化 QuerySpec；真实数值一律来自 Gold/DB resolver；日期一律由确定性解析器计算（LLM 只输出 `{type:relative, unit:month, count:2}` 这类描述）。
2. **分工**: 意图选择 = 关键词 → 向量 → LLM 递进；槽位抽取 = 确定性解析器（全意图共享），LLM 仅补规则解析不出的槽。
3. **词表冻结**: `_OPS_PATTERNS` 保留为 T1 fast path，**不再扩词表**；新说法由 T2/T3 承接，经飞轮晋升进向量索引。
4. **业态隔离**: T2/T3 仅对餐饮租户启用；向量检索按 `RESTAURANT_OPS_` 前缀过滤，餐饮查询不得命中工厂模板，反之亦然。
5. **零回归**: T1 命中时行为与现状完全一致；T2/T3 纯增量（原本落入 LLM 兜底/模板 miss 的查询才走到）。
6. **飞轮不静默毕业**: 沿用 learning_promotion 哲学，晋升候选需 contract-pass + 人审（`--apply`）。

### 1.4 token 经济学（为什么这样分层）

qwen-flash/turbo + thinking off 的一次 QuerySpec 解析 ≈ 800 in + 150 out ≈ <¥0.001。费用不是主要矛盾；分层的真实收益 = 延迟（T0/T1 <1ms、T2 ~30ms vs LLM 秒级）、确定性（可离线测试、同问同答）、配额/429 风险隔离。因此 T3 放心作为正确性保底，不为"零 LLM"牺牲准确率。飞轮把 LLM 占比从初期 ~30% 收敛到 <10%。

---

## 2. 目标架构

```
用户问题（餐饮租户, chat.py 三个调用点）
  ├─ T1 关键词规则 match_restaurant_ops（现状保留, 词表冻结, <1ms）
  ├─ T2 向量路由 cosine_topk(code_prefix='RESTAURANT_OPS_')（~30ms, 0 LLM token）
  │     ├─ sim ≥ 0.78 → 选定 ops code
  │     └─ 0.70–0.78 → 作为 hint 传给 T3
  ├─ T3 LLM 结构化解析（现有 LLM router, thinking off, 只输出 QuerySpec JSON）
  │     ├─ confidence ≥ 0.6 → 选定 ops code + 槽位提示
  │     └─ confidence < 0.6 → 返回澄清问题（clarification）
  └─ miss → None（落回 chat.py 现有 fallback 链, 不改变现状）

选定 code 后（所有层统一）:
  确定性槽位解析（时间窗/利润意图/维度）→ RestaurantQuerySpec
  → resolve_by_code / resolve_sales（Gold 确定性查询, 现状）
  → OpsAnswer
  → Answer Contract 校验（§4）→ 不满足先补查, 补不了明说缺什么
  → 流式返回
  → 全链路日志（tier/code/confidence/contract 结果）→ 飞轮候选捕获（§5）
```

---

## 3. 组件设计

### 3.1 `smartbi/gold/restaurant_intent.py`（新建）

```python
@dataclass(frozen=True)
class RestaurantQuerySpec:
    intent: str                    # RESTAURANT_OPS_* code
    domain: str                    # 固定 "restaurant"
    date_range: Tuple[Optional[date], Optional[date]]
    window_label: str              # "最近2个月" 等（回答必须回显）
    relative_window: bool
    metrics: Tuple[str, ...]       # revenue / gross_profit / gross_margin / orders / avg_ticket
    wants_margin: bool
    asks_profitability: bool       # "赚钱了吗" → 回答必须给盈亏判断
    dimensions: Tuple[str, ...]    # store / dish / ingredient …
    comparison: Optional[str]      # wow / mom / yoy / None
    confidence: float
    source_tier: str               # "keyword" | "vector" | "llm"
    clarification_needed: bool = False
    clarification_question: Optional[str] = None

async def parse_restaurant_query(query, pool, *, factory_id, history=None) -> Optional[RestaurantQuerySpec]
```

- T1 命中 → confidence=0.95, tier=keyword。
- T2 命中 → confidence=similarity, tier=vector。
- T3：prompt 含可用 intent 列表 + 每个 intent 一句描述 + T2 hint（若有）+ few-shot；强制 JSON 输出；`time_range` 只允许结构化描述（relative/named/absolute），落地日期由 `_resolve_sales_date_range` 家族计算。解析结果按 query 归一化后缓存（demo 重复问题命中率高）。
- 任何层失败/异常 → fail-open 返回 None，绝不阻断现有链路（镜像 template_rag "never raises" 哲学）。

### 3.2 槽位解析共享化（`restaurant_ops_router.py` 内部轻重构）

`_resolve_sales_date_range` / `_profit_intent` / `_parse_small_count` / `_relative_period_match` 提为模块级可复用函数（保持现签名，供 restaurant_intent 引用）。**不改变任何现有行为**（105 个既有测试必须全绿）。

### 3.3 向量索引接餐饮 ops 码

- `template_embedding_index.py` 新增 `populate_restaurant_ops(pool)`：把 `SAMPLE_QUERIES` 各条 embed 后 upsert 进 `smart_bi_template_embeddings`（template_code = ops code，天然带 `RESTAURANT_OPS_` 前缀命名空间）。启动 populate 与 rebuild 端点各挂一次。
- `cosine_topk` 增加可选参数 `code_prefix: Optional[str] = None`（None = 现行为，零回归）：
  - 传 `'RESTAURANT_OPS_'` → SQL 加 `WHERE template_code LIKE $prefix || '%'`（餐饮路径）。
  - **工厂路径（template_rag.hybrid_match）必须传排除条件**（`NOT LIKE 'RESTAURANT_OPS_%'`，实现可为 `exclude_prefix` 参数），防工厂查询误命中餐饮码 —— 双向隔离。

### 3.4 chat.py 接线（三个调用点）

~954（general_analysis）、~1551（trend 前置）、~1676（stream ops 路由）三处：`match_restaurant_ops(q)` → `parse_restaurant_query(...)`。规则：
- T1 命中路径产生的 resolve 调用与现状 byte 级等价（现有调用把 `query` 原样传给 resolver 内部再解析槽位——保持，spec 作为附加 kwarg 传入供 resolver 优先使用，resolver 不认识则回落自行解析，`resolve_by_code` 已有 kwargs 按签名过滤机制）。
- **业态门控**: T2/T3 仅当租户为餐饮业态才执行。业态判断复用现有租户 business_type 判定（实现者先找现成 helper，如 domain_inference / 餐饮 dashboard section 的判定；确实没有则以"该 factory 在 agg_restaurant_daily_* 有数据"为门槛并缓存结果）。T1 不加门（保持现状，词表本身即隐式门）。
- clarification_needed → 直接返回澄清问题文本（不查数）。

### 3.5 T3 LLM 解析细节

- 走现有 SmartBI LLM router（计费安全注册表），选最便宜档（qwen-flash 类），`enable_thinking:false`，温度 0。
- 超时 5s；超时/异常/JSON 解析失败 → fail-open 返回 None。
- 意图枚举 = 现有 8 个 RESTAURANT_OPS_* code（本轮**不新增** tool；menu_bundle / staffing / review / 天气客流等新领域数据源未就绪，defer——见 §7）。

---

## 4. Answer Contract（`smartbi/gold/answer_contract.py` 新建）

```python
def required_elements(spec: RestaurantQuerySpec) -> List[str]
    # 规则:
    #  - 用户给了时间 → 回答必须回显 window_label 或具体日期区间
    #  - asks_profitability → 回答必须含盈亏判断词（赚/亏/盈利/毛利为正…）
    #  - wants_margin → 回答必须含 毛利/毛利率 数值或"缺成本卡"明示
    #  - dimensions 含 store/dish → 回答必须提及对应对象（门店名/菜品名）
def validate(spec, answer_text, kpis, meta) -> ContractResult  # missing: List[str]
```

- 执行点：chat.py 拿到 OpsAnswer 之后、流式输出之前。
- missing 非空 → 一次补查机会（如缺 margin → 调用 margin resolver 补一段）；仍缺 → 在回答末尾**明确追加**"⚠️ 缺少 X 数据（原因），以上仅覆盖 Y"，禁止静默忽略。
- 校验结果进日志（`contract_pass` 字段），是飞轮质量信号，也是回归防线（对 T1 关键词路径同样生效——当初"最近两个月"事故这层就能拦住）。
- 纯文本启发式校验（含关键词/数字模式），不再调 LLM 判卷。

---

## 5. 飞轮 v1（capture + 人审 rebuild，不自动毕业）

- 每次 parse 记录：query、tier、code、confidence、contract_pass、served（复用 `llm_fallback_logger` 表模式，或其 restaurant 变体）。
- T3 解析成功且 contract_pass 的 query 作为**晋升候选**落库。
- 提供 admin 端点/脚本列出候选；人审通过后 append 进 `SAMPLE_QUERIES`（代码 PR）或直接 upsert 向量索引。自动两级 gate（learning_promotion 集成）为 Phase 2。

---

## 6. 测试计划

文件：`backend/python/tests/test_restaurant_intent.py`（新）+ 现有 `test_restaurant_ops_router.py`（105 个必须全绿，不改断言）。

- **≥30 个中文老板问题变体**（时间×指标×对象矩阵）：最近两个月/近3周/这个月/上周/今天/过去十天 × 赚钱了吗/亏不亏/毛利多少/营收多少 × 哪家店拖后腿/菜品毛利/库存盘亏/损耗/领料。
- 分层路由单测：T1 命中不触 T2/T3（mock 断言零调用）；T1 miss + T2 高相似 → vector tier；T2 低分 + T3 mock JSON → llm tier + 日期由确定性解析器算出（断言 LLM 给的日期被忽略/重算）；全 miss → None。
- Contract 单测：缺时间回显 → missing；缺盈亏判断 → missing；补查后 pass；补不了 → 明示缺失文案。
- 澄清路径：低 confidence → clarification_question 非空。
- 业态隔离：`code_prefix` 过滤 SQL 断言；工厂路径排除餐饮码断言。
- follow-up（"那明天先做什么"）→ 断言仍路由 owner-action registry（现状行为回归测试）。
- 运行：`$env:PYTHONPATH='backend/python;backend/python/smartbi'; python -m pytest backend/python/tests/test_restaurant_ops_router.py backend/python/tests/test_restaurant_intent.py -q`

---

## 7. 明确不做（本轮 defer）

- 新领域 tool（menu_bundle_recommendation / staffing_schedule / customer_review_analysis / mall_event_weather_traffic_context / inventory_warning 独立化）——数据源与 resolver 未就绪，QuerySpec.intent 枚举预留扩展即可。
- 多轮对话上下文注入 T3（history 参数预留，v1 不实现）。
- learning_promotion 两级 gate 自动化集成（v1 只 capture + 人审 rebuild）。
- Java Tool-Skill 对齐/迁移。
- web-admin UI 改动（无需求）。

---

## 8. 交付与部署

1. 实现 + 单测全绿（新旧两个测试文件）。
2. commit 到 main（`git commit -- <files>` 锁 scope）并 push。
3. `./scripts/deploy/deploy-smartbi-python.sh --env prod`（含 populate 新样例向量；确认无 smartbi migration 需求——本设计不改 schema，向量表已存在）。
4. 线上验证 https://admin.cretaceousfuture.com/demo 餐饮演示 4 问：
   - "最近两个月的营收情况如何，赚钱了吗"（回归基准：2个月窗口 + 盈亏判断 + 毛利额/率）
   - "这周营收比上周差在哪里，今天先做哪几个动作"
   - "哪家店拖后腿，是客流问题还是客单价问题"
   - "根据毛利和销量，今天要不要推小套餐"
   - 另加 2 个词表外改述（如"这两个月生意咋样，挣着钱没"）验证 T2/T3 增量价值。
5. 期望：白话、直接、可执行；时间窗与盈亏判断必回显；缺数据明说。

## 9. 安全

- 不提交任何密钥；LLM 调用走现有 router 的凭证管理。
- 营收/毛利脱敏沿用现有 role 转发机制（resolve_by_code 已带 role kwarg），新路径不得绕过。
