# Handoff — 餐饮 AI chat 多轮上下文继承 (multi-turn topic inheritance)

**日期**: 2026-06-04
**来源**: 接 #494/#495 餐饮 chat QA 修复 (R1 路由 / C1 澄清 / X1 多轮 / F1 前端) —— 4 个里 3.5 个已上线验证, **多轮上下文继承是唯一 defer 项**, 本文交接。
**关联 memory**: `project_2026_06_04_restaurant_chat_qa_fixes`, `project_2026_06_03_intent_classifier_w1b_negation_twin`(同类架构盲点), `feedback_intent_gate_must_cover_all_execution_paths`, `feedback_self_evidence_disqualified_cross_verify_required`。

---

## 1. 要解决的问题 (用户面)

餐饮 chat 多轮对话, 第二轮的"指代/省略"应继承上一轮的话题:

| 第一轮 | 第二轮 | 期望 | 现状(prod 实测) |
|---|---|---|---|
| 营收趋势 | 上个月呢 | 上个月营收趋势 | ❌ → `PROCESS_TASK_ANALYSIS`(LLM 瞎编) |
| 哪个菜卖得最好 | 上个月呢 | 上个月畅销菜品 | ❌ → `PROCESS_TASK_ANALYSIS` |
| 哪家店业绩最好 | 那家店的客单价呢 | 该店客单价 | ❌ → `ORDER_STATISTICS`(部分: 含领域名词, 见下) |
| 哪个菜卖得最好 | 它的趋势怎么样 | 该菜趋势 | ❌ → RAG 低置信兜底 |

`conversationRound` 已修(现返回轮次, 之前 null) —— 那是 X1 Part A, **已上线生效**。本任务只剩**话题继承 (Part B)**。

---

## 2. 根因 (live log 已定位 —— 这是关键, 别重复踩)

execute 路径有**两套独立的会话系统**, X1 把继承加错了层:

### 系统 A — `ConversationMemoryService` (X1 + 识别层用)
- 实体 `entity/conversation/ConversationMemory.java` (有 `lastIntentCode` 字段 ~line 116)。
- DTO `dto/conversation/ConversationContext.java` (`lastIntentCode` ~line 68)。
- `service/impl/ConversationMemoryServiceImpl.java`: `getOrCreateContext` (~line 112) → `buildContext` (~line 526) 用 `.lastIntentCode(memory.getLastIntentCode())` (~line 534) 填充; `setLastIntentCode` 在 ~line 302 (addMessage 时) / ~line 450。
- **存对话记忆 + lastIntentCode**。X1 的 `maybeAugmentContinuation` 读这里的 `context.getLastIntentCode()`。

### 系统 B — `ConversationService` (orchestrator 用, 槽位填充/参数收集)
- `service/impl/ConversationServiceImpl.java`: `continueConversation` (~line 166), `startParameterCollection` (~line 112), 用 `ConversationSessionRepository` + `ConversationSession` 实体。
- **存"参数收集会话"** (e.g. "创建订单" → "哪个产品?" → 槽位填充续接)。**承重路径, 勿乱碰。**
- `continueConversation` 找不到 session 时返回 `status=CANCELLED, message="会话不存在或已过期"` (~line 171-177)。

### orchestrator 的拦截 (`service/execution/IntentExecutionOrchestrator.java`)
- 对带 `sessionId` 的请求, 先走 `handleConversationContinuation` (~line 671): log `检测到会话延续` (~673) → `conversationService.continueConversation(...)` (~676)。
- session 不存在(CANCELLED) → `return null` (~line 678-681) → "Continue normal flow" 回落正常识别流。

### 为什么 X1 不生效 (live log 证据)
prod log (sessionId=qa-verify-...) 实测序列:
```
IntentExecutionOrchestrator - 检测到会话延续: sessionId=qa-verify-...
ConversationServiceImpl - 继续多轮对话: session=..., reply=上个月呢
ConversationServiceImpl - 会话不存在: qa-verify-...     ← 系统 B 没有这个 session
```
**且全程没有 `[X1-Continuation]` log** (X1 的 augment marker, IntentRecognitionPipelineServiceImpl ~line 531)。即: orchestrator 走系统 B 失败回落后, X1 在识别层的 augment **没被可靠触达** —— 要么回落路径没进 `recognizeIntentWithConfidence` 的 `sessionId != null` 块 (IntentRecognitionPipelineServiceImpl ~line 518-535), 要么进了但 `lastIntentCode` 是 null。

**教训 (同 W1b)**: X1 单测 mock 了 `ConversationContext`(lastIntentCode 设好) 通过, 但**真实执行路径绕过了它**。这是 `feedback_self_evidence_disqualified_cross_verify_required` + `feedback_intent_gate_must_cover_all_execution_paths` 的复发 —— **必须在真实 execute 路径 + 真实 session 验证, 不能只靠 mock 单测**。

---

## 3. X1 已做的 (惰性, 无回归, 可复用)

`IntentRecognitionPipelineServiceImpl.java`:
- `maybeAugmentContinuation(processedInput, context)` (~line 3780+): 保守门控 —— (1) `context.getLastIntentCode()` 在白名单 `CONTINUATION_CANONICAL_PHRASE`(5 个 gold 意图: REVENUE_TREND/DAILY_REVENUE/STORE_REVENUE_RANK/BESTSELLER/ORDER_STATISTICS → 代表短语); (2) 长度 ≤ 8; (3) 无领域名词 (`CONTINUATION_DOMAIN_NOUN` 正则); (4) 剥尾部语气词后是纯时间(`CONTINUATION_BARE_TIME`)/纯代词(`CONTINUATION_BARE_PRONOUN`)/空。命中则 `processedInput = timePrefix + canonicalPhrase` (e.g. "上个月" + "收入趋势")。
- 调用点 ~line 529, 在 `if (sessionId != null)` 块 (518-535) 内, coref/preprocess 之后。**standalone 查询(sessionId=null, 含全部 IntentParityTest)永不触发** —— 这个安全性是对的, 保留。
- 这套逻辑**本身没问题**, 问题是 (a) 没被真实路径触达 + (b) `lastIntentCode` 在真实 session 下是否被持久化未验证。

**已知残缺**(终审 MINOR): `CONTINUATION_BARE_TIME` 缺 今天/昨天/前天/本周末/上半年/近N个月 等日粒度词 —— 修好主路径后顺手补。

---

## 4. 建议的解决路径 (待 brainstorm 定夺, 别直接动手)

这是 brainstorm→spec→plan→subagent-driven 的活, **不是急修**。核心抉择:

**方案 A — 让 orchestrator 回落后真正触达 X1**: 确认 `handleConversationContinuation` 返 null 后, 正常流是否带 sessionId 进 `recognizeIntentWithConfidence` 的 518 块; 确认 `lastIntentCode` 在**成功轮**(非只 no-match 轮)被写进 ConversationMemory。最小改动, 风险低。**优先验这条**。
- 先验: 跑一轮成功查询后, 直接查 `ConversationMemory` 表该 session 的 `last_intent_code` 是否被写。若 null → 持久化 gap (成功路径没写); 若有值 → X1 触达 gap (回落没进 518 块)。

**方案 B — 把继承挪进 orchestrator/系统 B**: 在 `ConversationService` 层处理话题继承。改动大, 碰承重的槽位填充路径, 风险高。**不推荐, 除非 A 不通**。

**方案 C — 两套会话系统 reconcile**: 长期正解但大工程。本任务可能只需 A 的小修就够。

---

## 5. 约束 (硬)

- **不能破坏槽位填充/参数收集会话** (`ConversationService` 是承重路径: "创建订单"等写操作的多轮收参靠它)。
- standalone 查询(无 session)**绝不受影响** —— `IntentParityTest` 70/70 + `IntentGoldenAssertionTest` 15/15 必须保持绿(它们都 sessionId=null)。
- 部署只从 main(worktree off origin/main → PR → merge → 蓝绿部署); 验证必须**真实 execute 路径 + 真实 session**, 不能只 mock 单测。

---

## 6. 验证方法 (复用, 必须 live)

prod 真实租户 **RES_3101_009 (qhj_prod)** 真数据, execute 端点带同一 `sessionId` 跑 2 轮:
```python
# 服务器 localhost(绕 SSH RST): cd /www/wwwroot/cretas/code/backend/python && venv38/bin/python
# 活跃端口蓝绿轮换(10010 blue / 10020 green), 先 health 探测
sid = str(uuid.uuid4())
ask("营收趋势", sid)      # 第一轮
ask("上个月呢", sid)      # 第二轮 → 期望 intentCode 含 RESTAURANT_ + 时间生效, 非 PROCESS_TASK_ANALYSIS
# 抓 log: ssh root@47.100.235.168 "grep -aE 'X1-Continuation|会话延续|会话不存在|<sid>' /www/wwwroot/cretas/logs/cretas-backend.log | tail"
```
qhj_prod JWT (role=factory_super_admin, factoryId=RES_3101_009 在 token payload 不在 user 对象)。
判据: 第二轮 intentCode 继承第一轮话题域(营收→DAILY_REVENUE/REVENUE_TREND, 菜品→BESTSELLER, 门店→STORE_REVENUE_RANK)且时间生效; `[X1-Continuation]` log 出现。

---

## 7. 关键文件速查

| 文件 | 作用 |
|---|---|
| `service/execution/IntentExecutionOrchestrator.java` ~671 `handleConversationContinuation` | orchestrator 对 session 请求先走系统 B, CANCELLED→return null 回落 |
| `service/impl/ConversationServiceImpl.java` ~166 `continueConversation` | 系统 B(槽位填充), 会话不存在返 CANCELLED |
| `service/impl/ConversationMemoryServiceImpl.java` ~112 `getOrCreateContext` / ~302 `setLastIntentCode` | 系统 A(对话记忆 + lastIntentCode) |
| `service/intent/impl/IntentRecognitionPipelineServiceImpl.java` ~518 sessionId 块 / ~529 调用 / ~3780 `maybeAugmentContinuation` / ~531 `[X1-Continuation]` log | X1 识别层继承(惰性) |
| `dto/conversation/ConversationContext.java` ~68 / `entity/conversation/ConversationMemory.java` ~116 | lastIntentCode 字段 |

---

## 8. 不在本任务范围 (其它 backlog, 别混进来)

- 付款渠道(微信/美团/支付宝)/菜品分类 真实占比 → 需新 gold ETL。
- `RESTAURANT_AVG_TICKET` 孤儿意图(无 executor) → 给独立 gold executor(当前客单价走 STORE_REVENUE_RANK 派生)。
- 广义 COMMON-误标制造业意图审计(MATERIAL_BATCH_QUERY/PROCESSING_BATCH_LIST 等 → FACTORY) → 让 C1 候选过滤对它们也生效(当前 default-swap 已挡主泄露)。
- Track C 自愈对长/无 session 问题透传 processedInput (来自 #485 backlog)。
