# Track B1 — AI 钉钉机器人 PoC (Chat 3)

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **来源**: 从原 TRACK_B_BRIEF.md 拆分 (2026-05-14 dispatch 调整 4→6 chats)
>
> **本文件是 self-contained brief**: 你 (接收方 chat) 不需要任何额外的 conversation context, 读完这份文件即可立即上手干活。所有路径都是绝对路径; 所有参考文档都标了 §节号; 所有客户原话都有引用来源。

---

## §1 项目 onboarding (你必须先读)

### 1.1 Cretas 是什么

**Cretas 食品溯源系统** (白垩纪) 是一套食品工厂 + 中央厨房的全栈业务系统:

| 组件 | 技术栈 | 端口 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA Hibernate 6 | 10010 (prod) / 10011 (test) |
| 前端 | React Native (Expo 53+) + TypeScript + React Navigation 7 | 3010 (dev) |
| AI 服务 | Python FastAPI + LLM (Aliyun A/B / ZhipuAI / DeepSeek 4-provider 路由) | 8083 (prod) / 8084 (test) |
| 向量嵌入 | Java gRPC + ONNX | 9090 |
| DB | PostgreSQL 主库 `cretas_prod_db` / SmartBI 库 `smartbi_prod_db` | 5432 |

**项目状态**: Phase 3 核心完成 (~82-85%)。337+ AI Tool / 16 Skill / 51 测试意图。

### 1.2 你的客户场景

**客户**: **六扇门 (F006)** — 卤制品工厂, 沪上小有名气的卤味连锁品牌。

**deadline**: ASAP 1.5 个月 (约 6 周) 交付 P0 修复 + 战略级新功能。客户是六扇门, 优先级最高。

**你的角色**: 6 个并行 chat 中的 **Track B1** (Chat 3), 负责:
- **C-AI-1 钉钉机器人 PoC** (6d) — 六扇门战略级需求 (第二次会议提了 N 次)

**总工时**: 6 人天名义 / Claude 加速 ~4 工作日。

### 1.3 跟其他 Chat 的关系

| Track | Chat | 焦点 | 跟你的依赖 |
|---|---|---|---|
| **A** | Chat 2 | Canvas 死代码修复 | 不依赖 |
| **B1** (你) | Chat 3 | 钉钉机器人 PoC | — |
| **B2** | Chat 6 | 抄码品 + PDF 扫码 RN | **共享 Flyway migration V20260516_01** (你建表头, B2 加 abaca 字段; 见 §3.1) |
| **C** | Chat 4 | 通用 Attachment + 打印 + 三价 + RBAC | 不依赖 |
| **D** | Chat 5 | BOM + 工序 + 生产 bug | 不依赖 |

**Organizer**: Chat 1 — 协调 + PR review + 合并。你完成一项 PR 一次, 等 organizer review + merge。

---

## §2 任务范围与工时

| 编号 | 名称 | 工时 | 客户原话来源 | 优先级 |
|---|---|---|---|---|
| **C-AI-1** | 钉钉机器人 PoC (Webhook 双向接入 AIChat) | **6d** | 六扇门第二次会议 (多处) | **P0 战略级** |

**Claude 加速预期**: 名义 6d → 实际 ~4 工作日 (1.5x)。

### 2.1 战略价值

**钉钉机器人**: 六扇门客户原话:*"我们出了微信就是钉钉在用嘛, 日常跟系统去交互, 用钉钉也比较方便"* — 钉钉是六扇门战略入口, 客户希望从钉钉群直接 @ 机器人查库存/查订单/触发 AI 分析。不做这个客户上线就抗拒。

---

## §3 文件 Ownership (你能改 vs 不能改)

### 3.1 你拥有的目录/文件 (随便改)

| 路径 | 用途 | 状态 |
|---|---|---|
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/dingtalk/` | **NEW** 钉钉模块 (你新建) | 不存在, 你建 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/integration/DingTalkWebhookLog.java` | **NEW** 钉钉日志实体 | 不存在, 你建 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/integration/DingTalkWebhookController.java` | **NEW** 钉钉 Controller | 不存在, 你建 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/DingtalkWebhookLogRepository.java` | **NEW** 钉钉日志 Repository | 不存在, 你建 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/system/DingTalkSendMessageTool.java` | **NEW** 钉钉发消息 Tool | 不存在, 你建 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/system/DingTalkAlertPushTool.java` | **NEW** 钉钉告警推送 Tool | 不存在, 你建 |
| `backend/java/cretas-api/src/main/resources/db/migration/V20260516_01__abaca_dingtalk.sql` | **SHARED** Flyway migration (你建表头 + dingtalk 部分; Track B2 加 abaca 字段) | 不存在, 你建初始版本, B2 后续 PR 追加 |

**关于共享 V20260516_01**: 你先建 migration 文件包含 dingtalk_webhook_logs 表 DDL + users 表加 dingtalk_user_id 字段。Track B2 会在他们的 PR 里**编辑同一个文件**追加 abaca 字段 DDL。Day 2 完成后 ping organizer 同步给 Track B2。

### 3.2 你不能改的 (共享只读, 改必先 ping organizer)

- `backend/.../entity/BaseEntity.java` (全局基类, 改了影响所有 entity)
- `backend/.../service/impl/IntentExecutorServiceImpl.java` (AIChat 编排核心, 改了影响所有意图)
- `frontend/.../services/api/aiApiClient.ts` (AIChat SSE 客户端, 改了影响所有 AI 调用)
- `CLAUDE.md` 项目规范文件
- 其他 Track 的目录:
  - Track A: `frontend/.../screens/lowcode/` + `backend/.../service/impl/DecorationServiceImpl.java` + `ai/tool/impl/pagedesign/` + `ai/tool/impl/decoration/`
  - Track B2: `backend/.../ai/tool/impl/material/` (抄码品 Tool) + `backend/.../entity/RawMaterialType.java` 抄码扩展 + `backend/.../entity/warehouse/AbacaQuantityLog.java` + `frontend/.../screens/shared/LabelScanScreen.tsx` + `frontend/.../screens/warehouse/shared/WHScanOperationScreen.tsx`
  - Track C: `backend/.../entity/Attachment.java` + `backend/.../service/attachment/` + `frontend/.../screens/smartbi/` + `ai/tool/impl/finance/RBACAuditTool.java`
  - Track D: `backend/.../entity/bom/` + `backend/.../service/workprocess/` + `frontend/.../screens/management/MaterialSpecManagementScreen.tsx`

### 3.3 Git 策略

独立 worktree + 独立 branch:

```bash
# 在主仓库目录运行
git worktree add ../my-prototype-logistics-track-b1 -b feature/asap-track-b1-c-ai-1 main
cd ../my-prototype-logistics-track-b1
```

Branch 命名: `feature/asap-track-b1-c-ai-1`。

完成 PR 后等 organizer review + admin merge。

---

## §4 Day-by-Day 执行计划

### Day 1: 钉钉开放平台调研 + AIChat 链路熟悉

**晨 (3h)**: 钉钉开放平台注册
- 访问 https://open-dev.dingtalk.com/
- 用六扇门企业账号 (organizer 会提供 dingtalk_corp_id) 注册一个 **应用机器人** (内部应用类型)
- 拿到 `appKey` / `appSecret` / `corpId` — 这三个值写入 `backend/.../.env.test` 后用
- 拿到 webhook outgoing endpoint URL (我们的 Cretas 后端要暴露给钉钉) + 配置 webhook URL 为 `https://test.cretaceousfuture.com/api/dingtalk/webhook/inbound` (后面 nginx 路由)
- 阅读钉钉 webhook 协议 (Outgoing Webhook 的 HMAC SHA256 签名机制 + 消息格式)

**下午 (5h)**: 阅 AIChat 链路 (你不能改这条链路但必须懂)
- 必读: `宏见竞品分析/03-审计过程/AUDIT_Z_AICHAT_E2E.md` (8 步流程审计表)
- 关键文件:
  - 后端 SSE Controller: `backend/.../controller/AIIntentConfigController.java:254` — `POST /api/mobile/{factoryId}/ai-intents/execute/stream`
  - 后端 SSE 服务: `backend/.../service/execution/SseStreamingService.java:130`
  - 后端 Orchestrator: `backend/.../service/execution/IntentExecutionOrchestrator.java:161`
  - 后端意图识别: `backend/.../service/impl/AIIntentServiceImpl.java:288`
- 你的钉钉模块 = AIChat 的**另一个入口** (前端 React Native AIChatScreen 是入口 A, 钉钉群是入口 B), 复用所有 Tool/Skill/SSE 编排

**Day 1 产出**:
- 钉钉应用机器人注册完成, 拿到 appKey/appSecret
- `docs/track-b1/dingtalk-poc-notes.md` (你的笔记, 不需要 commit)

### Day 2: 钉钉日志实体 + Flyway migration + Inbound Controller

**晨 (4h)**: 新建 entity + DDL
- 新建 `backend/.../entity/integration/DingTalkWebhookLog.java` (DDL 与 entity 完整 spec 在 `01-客户档案/SCHEMA_DESIGN.md` §2.4, 直接 copy + 适配 imports)
  - 关键字段: `id` (BIGSERIAL PK), `factoryId`, `direction` (INBOUND/OUTBOUND), `messageType`, `dingtalkCorpId`, `dingtalkChatId`, `dingtalkUserId`, `dingtalkUserName`, `dingtalkMessageId`, `webhookUrl`, `messageContent` (TEXT), `messagePayload` (JSONB), `isSensitive`, `userId`, `aiAuditLogId`, `intentCode`, `sessionId`, `status` (PENDING/SENT/DELIVERED/FAILED/IGNORED), `errorMessage`, `retryCount`, `nextRetryAt`, `receivedAt`, `deliveredAt`, `createdAt`, `updatedAt`
  - **不继承 BaseEntity** — 审计日志只读, 不软删
  - JSONB 用 `@Type(JsonBinaryType.class)` 处理 (项目已有依赖 `hypersistence-utils-hibernate-63`)
- 新建 Flyway migration `backend/.../db/migration/V20260516_01__abaca_dingtalk.sql` (你建初始版本, Track B2 会追加 abaca 部分)
  - 含 DingtalkWebhookLog 表 DDL (完整 copy from `SCHEMA_DESIGN.md` §2.4)
  - 给 users 表加 `ALTER TABLE users ADD COLUMN dingtalk_user_id VARCHAR(100)` (供 §Day 3 双向绑定)
  - 在文件顶部加注释 `-- Track B2 will append abaca fields below 钉钉 section`
- 新建 `backend/.../repository/DingtalkWebhookLogRepository.java` (extends JpaRepository<DingTalkWebhookLog, Long>)
  - 需要的 query: `findBySessionId` / `findByStatusAndNextRetryAtBefore` (重试用)

**下午 (4h)**: 新建 Controller
- 新建 `backend/.../controller/integration/DingTalkWebhookController.java`
- 实现 `POST /api/dingtalk/webhook/inbound` (公开端点, **不走 JWT auth**, 但**必须 HMAC SHA256 校验签名**)
  - 请求体: `{ "msgtype": "text", "text": {"content": "..." }, "senderNick": "...", "senderId": "...", "senderCorpId": "...", "conversationId": "...", "msgId": "..." }`
  - 签名校验: 从 header 取 `timestamp` + `sign`, 用 appSecret 计算 HMAC SHA256, 对不上返回 401
  - 校验通过: 写 Redis 队列 `dingtalk:inbound:{factoryId}` (用现有 `CacheService.java`) + 立即返回 200
- 阻塞 ack 不超过 3s (钉钉超时), 实际处理走异步

**Day 2 产出**:
- DingTalkWebhookLog entity + Repository
- Flyway migration V20260516_01 (含钉钉日志表 + users dingtalk_user_id 列; abaca 部分留给 B2)
- DingTalkWebhookController (Inbound endpoint + 签名校验)
- ping Track B2 / organizer 同步 migration 文件已建, B2 可以在他们 PR 里追加 abaca DDL

### Day 3: 适配 webhook payload → 调 AIChat 流式

**晨 (4h)**: Inbound 异步消费器
- 新建 `backend/.../service/dingtalk/DingTalkInboundConsumer.java`
- `@Scheduled(fixedDelay = 5000)` 每 5s 消费 Redis 队列
- 取出消息后:
  1. 写 `dingtalk_webhook_logs` (direction=INBOUND, status=PENDING)
  2. 根据 `senderId` 在 `users` 表查到 Cretas userId (基于 `dingtalk_user_id` 双向绑定字段, 这个字段已在 V20260516_01 给 users 表加)
  3. 把 message text + factoryId + userId 转成 `IntentExecuteRequest`
  4. 调 `aiIntentService.recognizeIntentWithConfidence` → `intentExecutorService.execute(request)` (**注意: 用 non-streaming 版本**, 不要用 SSE; 钉钉群是单次问答场景, 不需要逐 token 推送)
  5. 拿到 `IntentExecuteResponse` 后, 走 §Day 4 出方向

**下午 (4h)**: 单元测试
- `DingTalkWebhookControllerTest.java` — 测签名校验通过 / 失败 / Redis 入队
- `DingTalkInboundConsumerTest.java` — 测消费 → 路由到 AIIntentService → 写日志

**Day 3 产出**:
- Inbound 消费器 (消息从钉钉到 AIChat 完整链路)
- 2 个单测

### Day 4: 双向 — AIChat 结果 → 回钉钉 + AIInsightCard 推送

**晨 (4h)**: Outbound Service
- 新建 `backend/.../service/dingtalk/DingTalkSendService.java`
- `sendToGroup(corpId, chatId, message)` 方法
- 调用钉钉群 webhook URL (使用 outgoing webhook 的 access_token 模式)
- 写 `dingtalk_webhook_logs` (direction=OUTBOUND, status=PENDING) → 发送成功 status=SENT → 收到钉钉回执 status=DELIVERED
- 失败: status=FAILED + nextRetryAt = now + 60s * 2^retryCount (指数退避, 最多 10 次)
- 限流: 钉钉群 20条/分钟, 用 Redis token bucket (`CacheService`) 限速

**下午 (4h)**: 接 AIInsightCard 推送
- 新增 Tool: `backend/.../ai/tool/impl/system/DingTalkSendMessageTool.java` (绑定 intent `DINGTALK_SEND_MESSAGE`)
- 新增 Tool: `backend/.../ai/tool/impl/system/DingTalkAlertPushTool.java` (绑定 intent `DINGTALK_ALERT_PUSH`)
- 这些 Tool 通过 SkillExecutor 被现有 Skill (如 `inventory-analysis`) 触发, 自动把异常告警推到钉钉群
- Tool 实现里调 `dingTalkSendService.sendToGroup(...)`

**Day 4 产出**:
- DingTalkSendService (出方向 + 重试 + 限流)
- 2 个新 Tool (Send + Alert Push)
- 接入 AIInsightCard 触发链路

### Day 5: 错误处理 + 重试 + 集成测试

**晨 (4h)**: 重试 Cron + 日志查询 endpoint
- 新建 `DingTalkRetryScheduler.java` (`@Scheduled(fixedRate = 30000)` 每 30s 扫一次 FAILED 且 nextRetryAt < now 的日志, 重试发送)
- 实现剩余 4 个 API endpoint (per `SCHEMA_DESIGN.md` §2.4 API 契约表):
  - `POST /api/mobile/{factoryId}/dingtalk/send` (主动推送)
  - `GET /api/mobile/{factoryId}/dingtalk/logs` (审计日志查询, 权限 `ai:audit:view`)
  - `GET /api/mobile/{factoryId}/dingtalk/logs/{id}`
  - `POST /api/mobile/{factoryId}/dingtalk/logs/{id}/retry`

**下午 (4h)**: 集成测试
- 启动本地后端 (`mvn spring-boot:run`, 不要 `java -jar` — 见 CLAUDE.md §server-operations 注 11)
- 用 Postman 模拟钉钉发 webhook → 验证 inbound 消费 → AIChat 路由 → outbound 回复
- 验证 AIInsightCard 触发 → 自动推送到钉钉群
- 失败场景: 钉钉群不可达 → 重试 3 次 → 最终 FAILED

**Day 5 产出**:
- 重试调度器 + 4 个剩余 API endpoint
- 集成测试通过 (本地)

### Day 6: E2E + PR

**晨 (4h)**: 部署到 test 环境
- 用 `./scripts/deploy/deploy-backend.sh --env test` 部署 (CLAUDE.md §server-operations §"部署规范" — **必须用脚本, 不要手动 rsync**)
- 配置钉钉应用的 outgoing webhook URL 为 `https://test.cretaceousfuture.com/api/dingtalk/webhook/inbound`
- 在六扇门钉钉测试群里 @ 机器人:*"查询今天的生产任务"* → 验证回复
- 验证 AIInsightCard → 钉钉群异常告警

**下午 (4h)**: PR
- PR 标题: `[Track-B1] C-AI-1 钉钉机器人 PoC (Webhook 双向接入 AIChat)`
- PR body 写:
  - 涉及文件清单 (entity / repo / controller / service / scheduler / tool / migration)
  - 测试方式 (本地 Postman + test 环境真实钉钉群)
  - 风险点 (钉钉签名密钥不能 leak / 重试 10 次后放弃可能漏告警 / 高并发未压测)
  - Phase 2 留待: rate limit production-grade / 多租户钉钉配置管理 / 钉钉 Card 富消息渲染
- ping organizer review

**Day 6 产出**:
- PR 提交并通过 review
- test 环境真实跑通

---

## §5 关键参考文档清单

| 文档绝对路径 | 用途 | 必读 § |
|---|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\SCHEMA_DESIGN.md` | 9 张表完整 DDL + Entity + API spec | **§2.4 钉钉日志** |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\六扇门第二次.md` | 客户钉钉机器人战略需求 | 全文 (~860 行), 重点 line 17 / 85 / 273 / 301 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\03-审计过程\AUDIT_Z_AICHAT_E2E.md` | AIChat 8 步流程审计 (你的钉钉机器人是 AIChat 的另一个入口) | 全文 (~94 行) |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\DISPATCH_OVERVIEW.md` | 6 track 协调总览 | §2 文件 Ownership / §4 STATUS / §5 PR |
| `C:\Users\Steve\my-prototype-logistics\CLAUDE.md` | Cretas 项目规范 (字段命名 / API / JWT / 部署) | §Architecture / §Key Patterns |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\ai-intent-tool-skill-architecture.md` | Tool-Skill 架构 (你新加 Tool 必读) | "添加新 Tool 的步骤" |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\database-entity-sync.md` | Entity ↔ DDL 同步规范 | BaseEntity 必需字段 / PG 注意事项 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\field-naming-convention.md` | camelCase (Entity/TS) vs snake_case (DB) | 全文 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\server-operations.md` | 部署 + systemd + 双环境规范 | 部署规范 + 双环境最佳实践 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` | 并发编辑安全 (你的 chat 跟其他 track 并行, 必看) | Rule 5b 安全 commit + Rule 5 commit 前 git status |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\CREDENTIAL-MANAGEMENT.md` | 凭证 (钉钉 appSecret 怎么存) | 必需环境变量 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\python-services-architecture.md` | Python 服务架构 (你的 LLM 调用通过 Java 调 Python, 仅了解) | 端口 + 路由前缀 |

**memory references**:
- `reference_f006_liutengmen_prod_accounts.md` — 六扇门 F006 16 个 prod 测试账号
- `feedback_test_before_prod_smartbi.md` — 改 Python/env/systemd 一律 `--env test` 先 (你的钉钉机器人也按这个走)
- `feedback_active_e2e_replaces_passive_soak.md` — 每阶段 cutover → smoke → active E2E 15-30min

---

## §6 接口契约

### 6.1 钉钉 webhook payload schema (Inbound)

钉钉发来的请求 body 示例:

```json
{
  "msgtype": "text",
  "text": { "content": "查询今天的生产任务" },
  "msgId": "msg_1234567890",
  "createAt": 1714723200000,
  "conversationType": "2",
  "conversationId": "cidXXXXXX==",
  "conversationTitle": "六扇门生产群",
  "senderId": "$:LWCP_v1:$XXXXX",
  "senderNick": "张三",
  "senderCorpId": "ding12345abc",
  "atUsers": [
    { "dingtalkId": "robotId", "staffId": "" }
  ]
}
```

**关键 headers** (用于签名校验):
- `timestamp`: 钉钉服务器毫秒时间戳
- `sign`: HMAC SHA256(appSecret, timestamp + "\n" + appSecret) base64

签名校验算法 (Java 伪代码):
```java
String stringToSign = timestamp + "\n" + appSecret;
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(appSecret.getBytes(), "HmacSHA256"));
String expectedSign = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes()));
if (!expectedSign.equals(receivedSign)) throw new SecurityException("钉钉签名验证失败");
```

### 6.2 钉钉响应回写 schema (Outbound)

发回钉钉群的消息格式 (文本):

```json
{
  "msgtype": "text",
  "text": { "content": "今日生产任务: PO-20260514-001 牛肉 100kg (进行中)..." },
  "at": { "atUserIds": ["原 sender_id"] }
}
```

Markdown 格式 (推荐, AIChat 结果富文本):

```json
{
  "msgtype": "markdown",
  "markdown": {
    "title": "生产任务查询",
    "text": "### 今日生产任务\n- PO-001 牛肉 100kg ✅ 进行中\n- PO-002 大葱 50kg ⏳ 待开始"
  }
}
```

### 6.3 共享 Flyway migration V20260516_01

| 章节 | Owner | 内容 |
|---|---|---|
| dingtalk_webhook_logs 表 DDL | **Track B1 (你)** | 完整表创建 + 索引 |
| users 表加 dingtalk_user_id 列 | **Track B1 (你)** | ALTER TABLE 一条 |
| raw_material_types 加 3 个 abaca 字段 | Track B2 | ALTER TABLE 3 条 |
| abaca_quantity_log 表 DDL | Track B2 | 完整表创建 + FK + CHECK + 索引 |
| F006 牛肉/猪肉/鸭肉 seed 标记 abaca | Track B2 | INSERT/UPDATE seed |

你 Day 2 建初始版本时, 在文件末尾留注释:
```sql
-- ============================================================
-- 下面由 Track B2 (Chat 6) 追加: abaca 字段 + abaca_quantity_log
-- ============================================================
```

Track B2 在他们 PR 里编辑同一个文件, 追加到注释下方。**两个 PR 不会冲突** (各占文件不同区段)。如果 B2 还没 ready 时你 PR 先 merge, organizer 会先 merge 你这个, B2 之后 follow up。

---

## §7 PR / Status Update 流程

### 7.1 每日 Status 同步

每天结束时, 在 `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_B1_STATUS.md` 追加 1 段:

```markdown
## Day N (YYYY-MM-DD)
- 完成: 钉钉应用注册 / 阅 AIChat 链路
- 进行中: DingTalkWebhookLog entity 草稿
- Blocker: 无 (或: 需要 organizer 提供六扇门钉钉 corpId)
- 明日计划: 完成 entity + Flyway migration + Controller 公开端点
```

如果文件夹 `STATUS/` 不存在, 第一天创建。

### 7.2 PR 流程

完成一项 → 推 PR 到 main:
1. **PR 标题**: `[Track-B1] C-AI-1 钉钉机器人 PoC`
2. **PR body 必含**:
   - 涉及文件清单 (列出所有新建/修改的文件路径)
   - 测试方式 (本地 + test 环境验证步骤)
   - 风险点 (已知 limitation)
   - Phase 2 留待 (砍出去的 scope)
3. **不要 squash 自己的 commit** — organizer review 时要看每个增量
4. **不要 force push** 到 main, 等 organizer admin merge

### 7.3 碰到 Blocker

立即在 STATUS 写 + ping organizer chat (Chat 1):
- "Track B1 Day X 卡在 Y, 需要协调 Z"
- 不要自己改其他 track 的目录解决

### 7.4 commit 规范 (并发安全)

每次 commit 必须用 `git commit -- <file1> <file2>` 形式锁定文件范围 (per `.claude/rules/concurrent-edit-safety.md` Rule 5b):

```bash
# ❌ 不安全
git add backend/foo.java backend/bar.java
git commit -m "feat: my change"

# ✅ 安全 — 只 commit 列出的文件, 即使别 chat staged 了其他文件
git commit -m "feat: my change" -- backend/foo.java backend/bar.java
```

如果项目根有 `scripts/safe-commit.sh`, 优先用它 (pre/post 自动 verify)。

---

## §8 不要做的事

1. **不要改其他 track 的目录** (见 §3.2)

2. **不要 refactor AIChat 整体架构**
   - 你只是给 AIChat 加一个**入口** (钉钉 webhook), 不动 SseStreamingService / IntentExecutionOrchestrator / AIIntentService
   - 钉钉调用走**non-streaming** 路径 (`POST /execute`), 不要试图把 SSE 推到钉钉 (钉钉不支持 SSE)

3. **钉钉机器人是 PoC, 不做生产化**
   - 不做: production-grade rate limit (用简单 Redis token bucket 够了)
   - 不做: 多租户钉钉配置管理 (假设六扇门只 1 个钉钉 corp)
   - 不做: 钉钉 Card 富消息渲染 (Phase 2)
   - 不做: 钉钉用户 → Cretas user SSO 双向绑定 UI (假设 organizer 直接 INSERT 一条绑定记录)
   - 不做: 钉钉群限流容错 (超过 20/min 直接 drop, 不重排)

4. **不要硬编码钉钉 appSecret 在代码里**
   - per `.claude/rules/CREDENTIAL-MANAGEMENT.md`, 一律走环境变量 `DINGTALK_APP_KEY` / `DINGTALK_APP_SECRET` / `DINGTALK_CORP_ID`
   - test 环境的 secret 写在 `/www/wwwroot/cretas/.env.test` (organizer 会设)
   - prod 环境的 secret 写在 `/www/wwwroot/cretas/.env.prod`

5. **不要直接部署到 prod**
   - 默认 test-only deploy (per `feedback_default_test_only_deploy.md` HARD rule)
   - test 环境跑通 + 客户验证 + organizer 批准后, 才上 prod

6. **不要在 brief 没说的地方过度设计**
   - 如果某个 spec 不清, ping organizer 问, 不要自己拍脑袋扩展

---

## §9 验收清单 (6 天结束时必须满足)

### C-AI-1 钉钉机器人 PoC

- [ ] 钉钉应用机器人在六扇门 dingtalk 注册成功
- [ ] test 环境暴露 `/api/dingtalk/webhook/inbound` 公开端点 (HMAC 签名校验)
- [ ] 在六扇门钉钉测试群里 @ 机器人发"查询今天的生产任务" → 5s 内收到 AIChat 回复
- [ ] AIInsightCard 触发 (如库存告警) 自动推送到钉钉群
- [ ] 失败重试机制 work (网络抖动后 60s 内自动重发)
- [ ] 审计日志 endpoint 可查 (admin 能看历史消息)

### 跨项目共同验收

- [ ] PR 通过 organizer review + merge 到 main
- [ ] test 环境部署成功, 健康检查通过 (`curl http://47.100.235.168:10011/api/mobile/health`)
- [ ] STATUS/TRACK_B1_STATUS.md 6 天完整记录

---

## §10 客户场景对照 (你的工作怎么对应客户原话)

### 钉钉机器人 = 六扇门战略级需求

客户原话 (六扇门第二次会议):
- *"我们现在自己呢是用的一搭就是钉钉的一搭, 搭了一个简单的模块化的一个系统在用"* (line 17)
- *"比如说我们把它打到钉钉上, 然后我们所有员工在钉钉上跟他去交互, 嗯, 这样可能在这个交互过程中对他也是一个训练吧"* (line 85)
- *"我们现在出了微信就是钉钉在用嘛, 日常跟系统去交互, 用钉钉也比较方便"* (line 273)
- *"钉钉本身有应用机器人嘛, 然后应用机器人呢, 你可以给他开权限, 就是基本上那个系统里所有权限都能有"* (line 301)

**你的工作**: 让客户能从钉钉群 @ 机器人 → 拿到 AIChat 的能力 → 不用打开 RN App。这是六扇门战略入口, 不做这个客户上线就抗拒。

---

## §11 元注意事项 (临走之前看一眼)

1. **每天结束时一定 push 到 origin** — 即使没做完, 也 commit + push (per `feedback_chat_must_push_before_clear.md` HARD rule)。本 chat 如果突然 /clear 或断线, organizer 可以从 origin 拉回你的 worktree commit。

2. **commit 前一定 `git status` 一次** — 检查 staging 区有没有别 chat 的文件 (per Rule 5)。每 commit 必须用 `git commit -- F1 F2` 锁定范围。

3. **不要用 `as any` 类型断言** — `.claude/rules/typescript-type-safety.md` 禁止, 用类型守卫。

4. **不要返回假数据降级** — `.claude/rules/api-response-handling.md` 核心原则, 错误必须显式抛出。

5. **字段命名严格 camelCase (Entity/TS) ↔ snake_case (DB)** — `.claude/rules/field-naming-convention.md`。

6. **JWT Token 必须 SecureStore 存储** — `.claude/rules/jwt-token-handling.md`。

7. **PostgreSQL 严格 GROUP BY** — `.claude/rules/database-entity-sync.md`, 所有 SELECT 非聚合列必须出现在 GROUP BY。

8. **遇到 PG `IS NULL` 参数类型推断错** — 用 `CAST(:param AS string)`, 不是 `:param IS NULL`。

9. **改 Python/env/systemd/Flyway/Entity/Hibernate** — 默认 test 环境验证 (`--env test`), 不要直接 prod (per `feedback_default_test_only_deploy.md` HARD)。

10. **本地启动 Java 后端用 `mvn spring-boot:run`** — 不要 `java -jar` (会 mmap 锁 fat jar 阻断 deploy)。

11. **client 端任何永久 URL** — 钉钉 webhook URL 必须用专属子域 (`api.cretaceousfuture.com` 或 `dingtalk.cretaceousfuture.com`), 不要直接给钉钉绑 IP (per `feedback_immutable_client_url_dedicated_subdomain.md` HARD)。

12. **共享 Flyway migration 协调**: V20260516_01 由你建头, B2 追加 abaca 部分。Day 2 建完后立即 ping organizer + Track B2 同步。

---

## §12 总结一句话

**你 (Chat 3 / Track B1) 的工作 = 让六扇门客户能从钉钉群里 @ AI 机器人。6 天交付 P0 战略级 PoC。**

读完这份 brief, 立即:
1. `git checkout main && git pull origin main`
2. `git worktree add ../my-prototype-logistics-track-b1 -b feature/asap-track-b1-c-ai-1 main`
3. `cd ../my-prototype-logistics-track-b1`
4. 开始 Day 1 (钉钉应用注册 + 阅 AIChat 链路)

干吧。