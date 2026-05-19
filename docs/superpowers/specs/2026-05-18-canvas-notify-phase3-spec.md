# Canvas-Notify Phase 3 — 5 渠道通知 + 模板系统

**Status**: Skeleton (this PR) → Implementation Phase 3 sister chat (2-3 days est.)
**Author**: skeleton-ship subagent (2026-05-18)
**Parent vision**: `docs/superpowers/specs/2026-05-18-canvas-business-rule-engine-vision.md` §1-3, §5.3
**Phase 1 dep**: PR #862 (Canvas-Workflow, `WorkflowEngineServiceImpl#case "notify"` 仅写 history)

---

## §1 目标

Canvas 业务规则引擎 Phase 3：把工作流 `notify` 节点从"写 history 字符串"升级为**真发推送**。覆盖 5 渠道（企业微信 / 钉钉 / 邮件 / SMS / 站内信），加模板系统支持 `{{var}}` 占位符替换 + 变量 schema 校验。Canvas 加 "通知模板" Tab 给 factory_super_admin / permission_admin 可视化管理。

---

## §2 范围

### 包含

- **5 渠道 Sender**：`NotifySender` 接口 + `WeChatSender` / `DingTalkSender` / `EmailSender` / `SmsSender` / `InAppSender` 实现（本 PR 仅 skeleton 抛 `UnsupportedOperationException`）。
- **NotifyTemplate / NotifyLog 实体**：模板 CRUD + 发送审计日志。
- **TemplateEngine**：渲染 `{{var}}` 占位符（本 PR skeleton, sister 实施时建议 SpEL 或简单 regex）。
- **REST API**：`/api/mobile/{factoryId}/notify/templates` CRUD + `/notify/logs` 查询。
- **5 AI Tools**：`notify_template_create/update/delete/send/log_query`（本 PR skeleton）。
- **Flyway migrations**：`notify_templates` + `notify_logs` 表。
- **Canvas Tab "通知模板"**：sister chat 在 web-admin 实施（本 PR 仅占位 sketch）。

### 不包含（Phase 3 follow-up）

- Phase 1 `WorkflowEngineServiceImpl#case "notify"` 接入 `NotifySender` — **留 follow-up issue**。本 PR 不动 Phase 1 任何文件。
- 真实 SDK 集成（weixin-java-mp / Spring Mail / aliyun-sms 等 pom.xml 依赖）。
- Canvas Tab UI 实现（仅留 design sketch）。

---

## §3 数据模型

### 3.1 NotifyTemplate

```sql
CREATE TABLE notify_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  template_code VARCHAR(100) NOT NULL,         -- 业务 key 如 PO_APPROVAL_PENDING
  title VARCHAR(255),                           -- 通知标题（也可含 {{var}}）
  body_template TEXT,                           -- 正文模板支持 {{var}}
  channels JSONB DEFAULT '[]'::jsonb,           -- ["WECHAT","EMAIL"]
  variables_schema_json JSONB DEFAULT '{}'::jsonb, -- {"amount":"number","approverName":"string"}
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL,
  UNIQUE(factory_id, template_code)
);
```

### 3.2 NotifyLog

```sql
CREATE TABLE notify_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  template_code VARCHAR(100),
  recipient_user_id BIGINT,
  channel VARCHAR(20) NOT NULL,                 -- WECHAT/DINGTALK/EMAIL/SMS/IN_APP
  status VARCHAR(20) NOT NULL,                  -- SENT/FAILED
  error_msg TEXT,
  sent_at TIMESTAMP DEFAULT NOW(),
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);
CREATE INDEX idx_notify_logs_factory_sent ON notify_logs (factory_id, sent_at DESC)
  WHERE deleted_at IS NULL;
```

---

## §4 NotifySender 接口

```java
public interface NotifySender {
    NotifyResult send(NotifyRequest request);
    boolean supports(NotifyChannel channel);
}

public record NotifyRequest(
    String factoryId,
    List<Long> recipientUserIds,
    List<NotifyChannel> channels,
    String templateCode,
    Map<String, Object> params  // 用于 {{var}} 替换
) {}

public record NotifyResult(
    NotifyChannel channel,
    NotifyStatus status,
    String errorMsg
) {}
```

调度策略（sister chat 实施）：fan-out per channel，每个 `Sender#supports()` 命中即调用，统一回收 `NotifyResult`，全部写 `NotifyLog`。

---

## §5 Canvas Tab "通知模板" Sketch

页面位置：`Canvas → 通知模板`（factory_super_admin / permission_admin 可见，与"工作流"Tab 平级）。

```
+-- Canvas 通知模板 ---------------------------------+
| [+ 新建模板]  搜索 [____]  渠道筛选 [全部 ▾]      |
|---------------------------------------------------|
| 编码                  | 标题            | 渠道   |
|---------------------------------------------------|
| PO_APPROVAL_PENDING  | 您有待审采购单 | 微信+邮 |
| MO_DELIVERY_DELAY    | 订单交付预警   | 钉钉   |
| ...                                                |
+---------------------------------------------------+

模板编辑 dialog:
  编码 [PO_APPROVAL_PENDING]
  标题 [您有 {{count}} 笔待审采购单]
  正文 [请审核 {{poNumber}}, 金额 {{amount}} 元]
  渠道 [☑ 微信] [☑ 钉钉] [☐ 邮件] [☐ SMS] [☐ 站内信]
  变量 schema (auto-detected): {{count}}:number {{poNumber}}:string {{amount}}:number
  [测试发送] [取消] [保存]
```

---

## §6 AI Tools

| Tool name | 作用 | Required params |
|---|---|---|
| `notify_template_create` | 创建通知模板 | templateCode, title, bodyTemplate, channels |
| `notify_template_update` | 更新模板 | templateCode, ...(任一) |
| `notify_template_delete` | 删除模板（软删） | templateCode |
| `notify_send` | 立即发送（测试用） | templateCode, recipientUserIds, params |
| `notify_log_query` | 查通知发送记录 | （可选 channel/status/recipientUserId） |

全部 `@Component extends AbstractBusinessTool`，本 PR skeleton 抛 `UnsupportedOperationException`。

---

## §7 7 项验收标准 (Phase 3 sister chat)

1. `notify_templates` 表 + `notify_logs` 表 deployment 后存在 + factory_id 隔离。
2. POST `/api/mobile/{factoryId}/notify/templates` 创建成功 + UNIQUE 冲突返 409 actionHint。
3. PUT 更新模板字段（title / body / channels / variables_schema_json）persisted。
4. POST `/notify/templates/test-send` 用样例 params 渲染 + 5 渠道 fan-out + 写 `notify_logs`（status=SENT 或 FAILED + errorMsg）。
5. TemplateEngine 替换 `{{var}}` 正确，未提供变量值时显式抛错（fool-proof Rule 1 — 不静默用 `""`）。
6. 5 AI Tools 经 ChatBot 调用全部 doExecute 成功 + LLM description 准确无歧义。
7. Phase 1 follow-up issue 完成：`WorkflowEngineServiceImpl#case "notify"` 注入 `NotifySender` + 从 nodeConfig 读 templateCode + 调 send + 落 NotifyLog；执行后 currentNodeIds advance（仍走 history，但 status=NOTIFY_SENT）。

---

## §8 Phase 3 Follow-up Issue (留给 sister chat 起 issue)

**Title**: `[phase-3] Wire Phase 1 NotifyNodeHandler to NotifySender`
**Body**:
- WorkflowEngineServiceImpl line ~578 `case "notify"` 当前只 `history.add(...)`，未真发推送。
- 接入 `NotifySender` 子类 fan-out + `templateEngine.render(template, ctx)`。
- nodeConfig schema 加 `templateCode` (required) + `recipientStrategy` (USER_LIST | ROLE | SPEL_EXPRESSION) + `recipientValue` (List<Long> | role_name | SpEL).
- recipient 解析：SPEL 求值（用 SandboxedSpelEvaluator）拿 List<Long>。
- 失败处理：单 channel 失败不阻塞其他 channel，全 fail 才 advance 到 `errorBranch`（如配置）。

---

## §9 Sister Chat 时间估算

| 任务 | 工时 |
|---|---|
| TemplateEngine `{{var}}` 实现 + 单测 | 2h |
| WeChatSender (weixin-java-mp 集成) | 4h |
| DingTalkSender (机器人 webhook) | 3h |
| EmailSender (Spring Mail) | 2h |
| SmsSender (aliyun-sms SDK) | 3h |
| InAppSender (写 in_app_messages 表 + WebSocket push) | 4h |
| 5 AI Tools doExecute 实现 + 单测 | 4h |
| Controller endpoints 实现（CRUD + test-send） | 3h |
| Canvas Tab Vue 组件 (web-admin) | 8h |
| Phase 1 Workflow notify 节点接入 (§8 follow-up) | 4h |
| E2E + 文档 | 4h |
| **合计** | **41h ≈ 2-3 天 1 chat** |

---

## §10 fool-proof + concurrent-edit 规范遵循

- **Rule 1 边界预显**：Canvas Tab 模板编辑器，"测试发送"前预渲染显示最终标题+正文+渠道+收件人 list。
- **Rule 2 上下文**：dialog header `"编辑通知模板 — {{templateCode}}"`。
- **Rule 3 dropdown 约束**：channels 多选 checkbox 而非 textarea。recipientStrategy enum。
- **Rule 4 幂等**：`(factory_id, template_code) UNIQUE` 防重复创建。test-send 5min dedup 防误点。
- **Rule 5 dead-end 导航**：模板未配置时，工作流 notify 节点报错附 actionHint `跳转到 Canvas → 通知模板 配置`。

---

字数: ~720 词 (中英混排)
