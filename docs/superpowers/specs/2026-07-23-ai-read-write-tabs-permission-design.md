# AI 只读咨询 / 操作 双 Tab 分块 + 权限对齐设计

**日期**: 2026-07-23
**状态**: 设计定稿（Steve 拍板双 tab 方向），待 P1 实施
**范围**: 工厂 AI + 餐饮 AI 的问答入口（web-admin 为主，RN 为 P3）
**前置审计**: 本文档基于 2026-07-23 读写权限现状全面审计（详见 §2）

---

## 1. 目标与决策

**客户诉求**（Steve 原话）: "优化工厂 AI 还有餐饮 AI 的只读和操作两个区块，因为这个要和权限分开" + "把操作和只读咨询分成两个 tab 吧"。

**已拍板的关键决策**:

| 决策点 | 结论 | 理由 |
|---|---|---|
| 入口形态 | **双 tab**: 「咨询」(只读) +「操作」(读写全能力) | Steve 拍板；tab 可见性 = 权限的可视化，操作员/老板心智模型清晰 |
| 只读 tab 遇写请求 | 拦截 + 提示卡一键跳转操作 tab | 防呆 Rule 5: dead-end 改导航 |
| 操作 tab 遇查询 | **内联回答，不反向踢人** | 操作员做操作前常需先查（"库存还剩多少→入库 50"），单 tab 闭环 |
| 权限判定源 | 统一到 `PermissionServiceImpl.PERMISSION_MATRIX` (`module:action`) | 工具层 (`ToolRbacEnforcer`) 已在用；意图层并轨，弃用 requiredRoles 子串匹配 |
| 写确认交互 | 接通既有 `confirmableAction` TCC 流（预览→confirmToken→确认执行） | 后端全套已建成，UI 侧从未消费（审计确认 grep 零命中） |

**非目标**: 不改 tiered 路由架构；不动 337 工具的业务逻辑；不做审批链 UI（CRITICAL 级审批仍走既有 PENDING_APPROVAL 流）。

---

## 2. 现状（2026-07-23 审计结论摘要）

后端"读写分块"机关已齐全，缺口在 UI 与意图层权限并轨：

| 层 | 现状 | 缺口 |
|---|---|---|
| 写判定 | `WriteGuardService` 唯一判定源（54 种写后缀 + sensitivity HIGH/CRITICAL），防伪造（服务端确认标记不可从 JSON 复现） | 无 |
| 写确认 TCC | 预览 → `confirmToken`（单次消费）→ `confirm()` 二阶段执行；38 写工具支持 doPreview，其余 fail-closed | **UI 从未消费 confirmableAction** |
| 工具级权限 | `ToolRbacEnforcer.TOOL_PERMISSION_MAP` ~60 敏感写工具 → `module:action`，与 HTTP @RequirePermission 同矩阵 | 无 |
| 意图级权限 | `AIIntentConfig.requiredRoles` JSON 角色数组 + **子串匹配** | 与工具层两套体系；子串匹配有隐患 |
| 识别层 | 写意图对无权限用户照常识别，执行时才 NO_PERMISSION | 浪费 + 体验差 |
| UI | `WRITE_CONFIRM_REQUIRED`/`PERMISSION_DENIED` 渲染成普通聊天文本 | 无任何读写区分 |
| Demo 锁 | `DemoReadOnlyInterceptor` 拦 HTTP 写端点，但 **`/ai-intents/` POST 在放行名单** → AI 路径写操作对 demo 租户未在 HTTP 层拦截 | ⚠️ 需在 AI 写执行处补 demo 闸 |

关键文件: `WriteGuardService.java` / `ToolRbacEnforcer.java` / `IntentExecutionOrchestrator.java`(确认/审批/RBAC 接线) / `ToolDispatchService.java`(SITE B) / `PermissionServiceImpl.java`(矩阵) / `AIQuery.vue`(唯一聊天 UI)。

---

## 3. 总体架构

```
                        ┌─ tab 可见性: hasAnyWritePermission(user)
                        │
┌───────────────────────┴────────────────────────────┐
│  AI 问答界面 (web-admin AIQuery / workdesk 抽屉)     │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │ 咨询 tab      │  │ 操作 tab (有写权限才显示)   │   │
│  │ mode=READ    │  │ mode=OPERATE              │   │
│  └──────┬───────┘  └──────────┬───────────────┘   │
└─────────┼─────────────────────┼───────────────────┘
          │ execute(mode=READ)  │ execute(mode=OPERATE)
          ▼                     ▼
┌─────────────────────────────────────────────────────┐
│ Java IntentExecutionOrchestrator                     │
│  0.2 mode=READ → 写意图候选剔除 + 写请求拦截卡        │
│  识别管道: 目录按 (mode, user permissions) 预过滤     │
│  响应: 新增 aiMode: READ|WRITE + 权限上下文           │
│  写路径: WriteGuard 预览 → confirmableAction         │
│          → confirm(confirmToken) → 执行             │
│          → demo 租户: 预览可看, 执行拦截(演示不落库)   │
└─────────────────────────────────────────────────────┘
```

**语义**:
- **咨询 tab (mode=READ)**: 服务端强制只读——写意图从识别候选剔除；万一命中写路径（兜底），返回 `READ_MODE_WRITE_BLOCKED` + 跳转提示。老板/viewer 的安全区。
- **操作 tab (mode=OPERATE)**: 全能力（查询内联答 + 写操作走确认卡）。写意图目录按用户 `module:action` 权限过滤——没有 `inventory:write` 的用户说"入库"直接得到"需要 库存:写 权限"卡，不进 slot-filling。

---

## 4. P1 — 后端（1 个 Java PR + 1 个 Python 小 PR）

### 4.1 请求/响应协议

- `IntentExecuteRequest` 增 `mode: "READ" | "OPERATE"`（缺省 OPERATE = 完全兼容现状，老客户端零破坏）。
- `IntentExecuteResponse` 增:
  - `aiMode: "READ" | "WRITE"`（由 `WriteGuardService.isWriteIntent/isWriteTool` 判定，前端据此选卡片形态）
  - `requiredPermission: string|null`（PERMISSION_DENIED 时告知缺哪个码，防呆 Rule 5 的"需要 X 权限"文案数据源）

### 4.2 意图层权限并轨

- `ai_intent_config` 增列 `required_permission VARCHAR(64) NULL`（`module:action` 码）。
- 迁移回填: 绑定了 tool 的意图，从 `ToolRbacEnforcer.TOOL_PERMISSION_MAP` 反推；未映射的写意图（sensitivity HIGH/CRITICAL）按 `intent_category`→module 规则批量给（人工复核 CSV 后执行）。
- 校验顺序: `required_permission` 非空 → 走 `PermissionService.hasAnyPermission`；空 → 兼容期回落 requiredRoles 旧逻辑；全量回填后删除 requiredRoles 路径（P1 不删，留观察期）。

### 4.3 识别层目录过滤

- Java 候选构建处 + Python matcher 请求: 传 `mode` 与用户权限码集合。
- `mode=READ`: 剔除全部写意图（`WriteGuardService.isWriteIntent` 或 `required_permission` 以 `:write/:read_write` 结尾者）。
- `mode=OPERATE`: 剔除用户无权限的写意图（查询意图不过滤）。
- Python 侧: `ai_intent_configs` snapshot SELECT 增 `required_permission` 列；`filter_intents_for_request` 增 mode/permissions 参数（Java `PythonAiMatcherClient` 透传）。
- 餐饮 0.35 反转不变（写动词本就不进 tiered）；`mode=READ` 时 0.35 照常（tiered 全只读）。

### 4.4 写请求拦截（咨询 tab 兜底）

- `mode=READ` 且最终路由落到写工具/写意图 → 不执行，返回 `status=READ_MODE_WRITE_BLOCKED`，message="这是操作类请求，请切换到【操作】页处理"，`aiMode=WRITE`（前端渲染跳转卡）。

### 4.5 Demo 租户写闸（审计发现的缺口）

- `WriteGuardService` 确认执行阶段（confirm() 二阶段 + withServerConfirmation 注入点）增 demo 租户检查（复用 `cretas.demo.factory-ids` 配置）: demo 租户预览照常（演示价值），**执行阶段拦截**，返回"演示环境不执行真实写入，已展示操作预览"。
- 这同时封住"demo 用户经 AI 路径完成真实写入"的现存漏洞（HTTP 层 DemoReadOnlyInterceptor 放行了 /ai-intents/ POST）。

### 4.6 测试

- 编排器测试: mode=READ 写拦截 / OPERATE 无权限意图不出现在候选 / aiMode 标记正确性 / demo 写闸。
- 电池不受影响（餐饮全只读）；补 2 个工厂写路径 case（有权限→确认卡结构；无权限→requiredPermission 文案）。

---

## 5. P2 — web-admin 前端

### 5.1 Tab 结构

- `AIQuery.vue`（及 workdesk 各抽屉复用组件）顶部双 tab: 「咨询」「操作」。
- 「操作」tab 渲染条件: `userStore` 权限集中存在任意 `*:write` / `*:read_write` 码；否则整个 tab 不渲染（viewer 只见咨询）。
- 各自独立 sessionId（避免咨询上下文污染操作 slot-filling 会话）。

### 5.2 操作确认卡（核心新组件 `AiOperationCard.vue`）

按防呆五规则设计:

```
┌──────────────────────────────────────────┐
│ 📦 入库操作 — 五花肉 200kg (PO-20260723-01) │  ← Rule 2: 品名/单号上下文
│                                          │
│  改动预览:                                │  ← Rule 1: 预先显示边界
│  库存现量 320kg → 入库后 520kg             │     (doPreview diff)
│  本单已收 0/200kg，可入上限 260kg(含30%超收) │
│                                          │
│  [✓ 确认执行]      [✗ 取消]               │  ← confirmToken TCC
└──────────────────────────────────────────┘
```

- 状态映射: `WRITE_CONFIRM_REQUIRED`→确认卡；`PREVIEW_UNSUPPORTED`→"该操作暂不支持安全预览，请到 XX 页面手工操作"+跳转（Rule 5）；`PERMISSION_DENIED`→"需要 {requiredPermission 中文名} 权限，请联系管理员"；`PENDING_APPROVAL`→审批中卡（只读展示）；执行成功→结果卡（绿）；demo 拦截→"演示环境不落库"标签 + 预览保留。
- 确认按钮 → `POST /ai-intents/confirm`（既有端点），带 confirmToken；幂等（token 单次消费，重复点击 409 → Rule 4）。
- 取消原因不采集（AI 场景轻交互，区别于表单场景的 Rule 3）。

### 5.3 咨询 tab 拦截卡

`READ_MODE_WRITE_BLOCKED` → 卡片"这是操作类请求" + [前往操作页] 按钮（携带原句自动重发）。

### 5.4 权限中文名映射

`module:action` → 中文（`库存:写` 等）在前端一张字典表，与权限管理页文案一致。

---

## 6. P3 — 后续（不在本轮）

- RN App 同款双 tab + 操作卡（⚠️ 涉及 operator/仓管/报工/入库/出库/盘点 → **RN 屏幕设计前必须先过 `ux-flow` skill Phase 1**，per CLAUDE.md UX Flow Gate）。
- 手机 mobile-rest-ai 页保持纯咨询（demo 展示面，无操作 tab）。
- 权限管理页增"AI 能力"矩阵视图（哪些角色能用哪些 AI 操作）。
- requiredRoles 旧路径下线。

---

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| mode 缺省 OPERATE，老客户端行为不变 | 协议向后兼容，P2 上线前无任何行为变化 |
| 目录过滤误伤查询意图 | 过滤仅作用于 `isWriteIntent`/write 权限码意图；电池 + 新增测试守护 |
| required_permission 回填错码 | 回填走 CSV 人工复核；兼容期保留 requiredRoles 回落 |
| demo 写闸误伤 logistics demo | 闸只对 `cretas.demo.factory-ids` 名单（DEMO_REST/DEMO_FACTORY*），DEMO_LOGISTICS 本就不在只读名单 |
| confirmToken 过期/被抢占 | 既有 PreviewTokenService 语义（单次+TTL），前端过期→提示重新发起 |

---

## 8. 分发总览（multi-model-dispatch）

| 任务 | 规模 | 可并行 | 隔离 |
|---|---|---|---|
| P1-J Java 协议+过滤+demo闸+测试 | ~1.5d | 与 P1-P 并行 | worktree `cretas-ai-rw-java` |
| P1-P Python snapshot 列+过滤参数 | ~0.5d | 与 P1-J 并行（接口先定契约） | worktree `cretas-ai-rw-py` |
| P1-M 迁移+回填 CSV | ~0.5d | 依赖 P1-J 的码表 | 同 P1-J |
| P2 web-admin 双tab+操作卡 | ~2d | 依赖 P1 部署到 test | worktree `cretas-ai-rw-fe` |

冲突风险: P1-J 触 `IntentExecutionOrchestrator`（高频并发改动文件）→ 里程碑式 commit + 完成即 merge，不过夜。
