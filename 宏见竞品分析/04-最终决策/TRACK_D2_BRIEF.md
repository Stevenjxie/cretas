# Track D2 Brief — 工序管理 + 工序通用 Bug

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **来源**: 从原 TRACK_D_BRIEF.md 拆分 (2026-05-14 dispatch 调整 4→6 chats)
> **接收方**: Chat 7 (Track D2 worker, 即第 6 个 worker chat — Chat 1=organizer, Chat 2-4=Track A/B/C, Chat 5=Track D1, Chat 7=Track D2)
> **派发方**: Organizer (Chat 1)
> **生效日**: 2026-05-14
> **总工时**: 名义 7 工作日 / Claude 加速预期 4-5 工作日
> **本文件目标**: 给到一份完全 self-contained 的 brief, 你不需要任何额外 context 就能立即上手干活

---

## §1 项目 Onboarding

### 1.1 这是什么项目

**Cretas (白垩纪) 食品溯源系统** — 一套面向**食品加工厂 + 餐饮企业**双主线的全链路溯源 + 生产管理 SaaS。

技术栈:
- **后端**: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6) — 端口 10010
- **前端**: Expo 53+ React Native + TypeScript + React Navigation 7+ — 端口 3010
- **AI 服务**: Python FastAPI + LLM (Aliyun Qwen / DashScope) — 端口 8083
- **Embedding**: Java gRPC — 端口 9090
- **DB**: PostgreSQL (cretas_db = test, cretas_prod_db = prod) — 端口 5432

项目状态: Phase 3 核心完成 (~82-85%). 现处 ASAP Phase 0 + Sprint 1 ("六扇门交付冲刺")。

### 1.2 客户背景: 六扇门 F006 卤制品工厂

- **factoryId**: F006 (六腾门)
- **行业**: 卤制品加工 (猪蹄 / 猪头肉 / 牛肉等)
- **节奏**: ASAP 1.5 月交付 P0 客户测试关键功能
- **关键产品**: 珠奇 / 鲁猪蹄 / 牛肉等卤制品
- **客户已开过 4 次会议** — 第四次 (May 10) 提出本 brief 涉及的工序痛点

测试时**用 F006 prod 账号** (16 个真实角色账号已存在生产 DB), 不用 F001 dev seed。账号清单 see `reference_f006_liutengmen_prod_accounts.md` memory entry (organizer 知)。本地测试用 F001/cretas_db。

### 1.3 你在做什么

**你是 Track D2** (第 6 个 worker chat, 即 "Chat 7"), 6 个并行 chat 之一 (organizer + A + B + C + D1 + D2), 由 Organizer (Chat 1) 协调:
- Track A — Canvas 死代码修复 (9d)
- Track B — AI 钉钉 + 抄码 + PDF (12d)
- Track C — Attachment + 打印 + 三价 + RBAC (11d)
- Track D1 — BOM 配方 + BOM 物料选择器 + 单位转换 (9d, 并行 chat)
- **Track D2 — 工序管理 + 工序通用 bug (7d)** ← **你**

**原 Track D 太大 (16d), 拆成 D1 + D2 让 2 chat 并行**. 工序子轨道 (你) 跟 BOM 子轨道 (D1) 独立, 但可能在 product_type_id 共享上需要轻量协调 (per §11.3)。

---

## §2 任务范围与工时

### 2.1 2 个 sub-project 总览

| # | 项目 | 工时 | P 级 | 说明 |
|---|---|---|---|---|
| 1 | **M-WP-1/2 工序管理 + 产品工序配置** | 5d | P0 | 后端 Controller 齐, 前端 grep 0 |
| 2 | **Bug 修: 生产工序"通用 P 过来"未关联** | 2d | P0 | 第四次会议 bug, partial #567, follow-up #622/#623 open |

**Total**: 7 工作日 (名义). Claude 加速 1.7-2x → **预期 ~4-5 工作日完成**。

### 2.2 为什么这 2 项打包给 Track D2

- 工序 (项目 1) + 工序 bug (项目 2) 同源 — 都涉及 `work_process_tasks` + `product_work_processes` + ProductionBatch
- 工序是六扇门 1.5 月交付的两大功能验收点之一
- "通用 P 过来"bug 是客户实测发现的, 跟工序密切关联, 一并修
- BOM 相关 (原 Track D 项目 1+4+5) 拆出去给 Track D1, BOM 跟工序关联面小, 适合并行

### 2.3 关键节点

- **Day 5 末**: 工序管理完整链路 (新增工序 → 产品工序配置 → 生成工序任务 → 状态机)
- **Day 6 末**: "通用 P 过来"bug 修复 (这是客户实测发现的 P0)
- **Day 7 末**: 2 项全部 ship, 准备 demo 录制

### 2.4 ProductionBatch Entity Fork 需谨慎

`ProductionBatch` 当前是单表服务餐饮 + 工厂双主线, 但语义差异大 (餐饮 = 中央厨房批次, 工厂 = 车间生产批次)。**长期需要 fork 成 `RestaurantBatch` + `FactoryBatch`**, 但**不在本 track 范围**。你在本 track 中**不要动 ProductionBatch Entity**, 如发现必须改, **先 ping Organizer**。这个 fork 决策留 organizer 协调 Sprint 2 处理。

**特别注意**: Day 5 spawn_tasks 时, `work_process_tasks` 单方面 FK 引用 ProductionBatch.id 即可, 不需要在 ProductionBatch 加新字段。

---

## §3 文件 Ownership (你的 / 不准动)

### 3.1 你拥有的目录/文件

**后端 (Java)**:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/workprocess/` (新建包) — `WorkProcessTask.java` (新增)
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workprocess/WorkProcessTaskRepository.java` (新增)
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workprocess/` (新建包) — WorkProcessTaskService 接口 + impl
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WorkProcessTaskController.java` (新增)
- 现有 `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WorkProcessController.java` + `ProductWorkProcessController.java` (保持不动, 仅必要时小补丁)
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/` (新建包) — 5 个 Tool

**Flyway 迁移**:
- `backend/java/cretas-api/src/main/resources/db/migration/V20260516_03__work_process.sql` (新增, 工序任务表)
- (Flyway tracker 由 Cretas 默认机制, 不手动 INSERT)

**前端 (RN)**:
- `frontend/CretasFoodTrace/src/screens/factory/workprocess/WorkProcessListScreen.tsx` (新增)
- `frontend/CretasFoodTrace/src/screens/factory/workprocess/WorkProcessCreateScreen.tsx` (新增)
- `frontend/CretasFoodTrace/src/screens/factory/workprocess/ProductWorkProcessConfigScreen.tsx` (新增)
- 生产计划页 (Bug "通用 P") 涉及的 ProductionPlan 创建/工序选择 — 现有页, 你修, 文件路径见 §4.6
- `frontend/CretasFoodTrace/src/services/api/workProcessApiClient.ts` (新增)
- `frontend/CretasFoodTrace/src/types/workProcess.ts` (新增)

### 3.2 你不准改的 (改必先 ping Organizer)

- `backend/.../entity/BaseEntity.java` — 项目根 Entity, 影响所有表
- `backend/.../service/impl/IntentExecutorServiceImpl.java` — AI 意图路由核心
- `frontend/.../services/api/aiApiClient.ts` — AI API client 共享层
- `CLAUDE.md` + `.claude/rules/` — 项目规范
- `backend/.../entity/ProductionBatch.java` — 餐饮/工厂共享, fork 决策需 organizer
- Track A 目录: `frontend/.../screens/lowcode/` + `service/impl/DecorationServiceImpl.java` + `ai/tool/impl/pagedesign/` + `ai/tool/impl/decoration/`
- Track B 目录: `service/dingtalk/` + `entity/integration/` + `screens/shared/LabelScanScreen.tsx` + `ai/tool/impl/material/`
- Track C 目录: `entity/Attachment.java` + `service/attachment/` + `screens/smartbi/` + `ai/tool/impl/finance/RBACAuditTool.java`
- **Track D1 目录**: `backend/.../entity/bom/` (BomRecipe + BomRecipeItem) + `backend/.../service/bom/` + `backend/.../controller/BomRecipeController.java` + `frontend/.../screens/factory/bom/` + `frontend/.../components/MaterialSelectModal.tsx` + `frontend/.../screens/management/MaterialSpecManagementScreen.tsx` + `ai/tool/impl/bom/`
- 餐饮端工序相关页 (若有) — 不准破坏 (餐饮端继续用)

### 3.3 共享文件 (轻量改动 OK, 大改先 ping)

- `frontend/.../types/navigation.ts` — 加新 screen 到 Stack 是 OK 的 (你的新页面 WorkProcessListScreen 等需要加)
- `frontend/.../i18n/locales/` — 加新 i18n key 是 OK 的
- 生产计划页 (你 Day 6 要修, 但属于现有页, 改动需保守)

### 3.4 Git Worktree 策略

强烈推荐用 git worktree 隔离:

```bash
cd C:/Users/Steve/my-prototype-logistics
git worktree add ../my-prototype-logistics-track-d2 -b feature/asap-track-d2 HEAD
cd ../my-prototype-logistics-track-d2
# 在此 worktree 内工作, 防止跟 main worktree + Track D1 worktree 冲突
```

Branch 命名: `feature/asap-track-d2-{编号}` 如 `feature/asap-track-d2-m-wp-1`。

---

## §4 Day-by-Day 执行计划

### Day 1 — 工序管理 spec + 后端现状 audit

**目标**: 掌握 `work_processes` (已有) + `product_work_processes` (已有) + `work_process_tasks` (新建) 三表关系

**动作**:
1. 读 SCHEMA_DESIGN §2.5 (line 940-1115)
2. 读六扇门第四次会议 line 49-104 — 工序管理的客户场景 (拆包→分割→卤制→分切)
3. audit:
   - 看 `backend/.../controller/WorkProcessController.java` (已有, 不动) — 暴露的 API
   - 看 `backend/.../controller/ProductWorkProcessController.java` (已有, 不动) — 产品工序模板 API
   - 看 `backend/.../entity/.../WorkProcess.java` + `ProductWorkProcess.java` Entity 字段
   - grep 前端: `Grep "WorkProcess" frontend/CretasFoodTrace/src/screens/` 确认零命中
4. 列前端 3 个 screen 设计:
   - WorkProcessListScreen (查看所有工序定义, 用 GET /work-processes)
   - WorkProcessCreateScreen (新增工序, 用 POST /work-processes, 字段: workProcessName / category / outputUnit / estimatedMinutes — see 客户原话 line 76-91)
   - ProductWorkProcessConfigScreen (产品 → N 工序绑定, 用 POST /product-work-processes)

**产出**: Day 1 STATUS + 前端架构图

### Day 2 — WorkProcessListScreen + WorkProcessCreateScreen

**目标**: 工厂用户能在 RN App 新增工序定义

**动作**:
1. 创建 `frontend/.../services/api/workProcessApiClient.ts`:
   - 调用现有 `WorkProcessController` 的 GET / POST / PUT / DELETE 端点
   - 调用现有 `ProductWorkProcessController` 的端点
   - **不调用** WorkProcessTaskController (Day 4 才有)
2. 创建 `frontend/.../types/workProcess.ts`:
   - `interface WorkProcess { id, factoryId, processName, category: 'PRE_PROCESS'|'PROCESSING'|'PACKAGING'|'QUALITY', outputUnit, estimatedMinutes, ... }`
   - `interface ProductWorkProcess { id, productTypeId, workProcessId, processOrder, ... }`
3. 创建 `WorkProcessListScreen.tsx`:
   - 列表 GET /work-processes
   - FAB → 跳 Create
   - 长按 → 编辑 / 删除
4. 创建 `WorkProcessCreateScreen.tsx`:
   - 表单字段 (per 客户原话 line 76-91):
     - 工序名称 (必填, 如"拆包")
     - 工序列表 = category select (前处理 / 处理 / 包装 / 质检) — 客户原话 "选前处理"
     - 产出单位 (必填, 工金/件/份, 客户原话 "产出单位是工金")
     - 预估工时 (必填, 分钟数)
   - 保存 → POST /work-processes
5. navigation 加 Stack
6. 本地跑通

**产出**: 工序新增 + 列表可用

### Day 3 — ProductWorkProcessConfigScreen

**目标**: 工厂用户能给产品绑定多个工序

**动作**:
1. 创建 `ProductWorkProcessConfigScreen.tsx`:
   - 顶部 product select (从 product_types) — **注意**: Track D1 BOM 也用 product_types 字典, 你们读同一字典, 不冲突
   - 中部: 该产品当前已绑定的工序列表 (按 processOrder 排序)
   - "添加工序"按钮 → 弹出 work_process Select 模态框 → 选好后追加到末尾 (processOrder = 当前 max + 1)
   - 拖拽排序 (用 react-native-draggable-flatlist 或简单上下箭头)
   - 删除工序绑定
2. 接口:
   - GET /product-work-processes?productTypeId=X
   - POST /product-work-processes
   - PUT /product-work-processes/{id}
   - DELETE /product-work-processes/{id}
3. **关键 UX**: 客户原话 line 95 "宣传计划, 新建计划, 订单, 产品行, 注射, 叮咚, 工序, 哎, 这工序还是只有通用" — 意思是说**生产计划界面应该自动带出该产品配置的工序**。这是项目 2 bug 的根因, Day 6 修。
4. 本地跑通: 给"猪蹄"产品绑定 [拆包, 分割, 卤制, 分切, 装筐] 5 道工序

**产出**: 产品 × 工序绑定可用

### Day 4 — 生成工序任务 + 状态机

**目标**: 生产批次启动时自动 spawn 工序任务实例, 状态机跑通

**动作**:
1. 写 `V20260516_03__work_process.sql` Flyway:
   - `CREATE TABLE work_process_tasks (...)` (照搬 SCHEMA_DESIGN line 953-1003)
   - 4 个索引
2. 创建 `WorkProcessTask.java` Entity (line 1009-1071, 注意 Status enum: PENDING/IN_PROGRESS/COMPLETED/SKIPPED/CANCELLED)
3. 创建 `WorkProcessTaskRepository.java`:
   - `List<WorkProcessTask> findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(...)`
   - `Page<WorkProcessTask> findByFactoryIdAndStatus(...)`
4. 创建 `WorkProcessTaskService` 接口 + impl:
   - `spawnTasks(batchId)` — 从 `product_work_processes` 模板生成任务 (per batch, 一次性 spawn)
   - `start(taskId)` — PENDING → IN_PROGRESS, 记录 actualStartAt
   - `complete(taskId, actualQuantity)` — IN_PROGRESS → COMPLETED, 算 actualMinutes
   - `skip(taskId, reason)` — 任意 → SKIPPED (需主管, notes 必填)
   - `assign(taskId, userId)` — 分配责任人
   - 状态机校验: 用 enum + switch, 非法转换抛 BusinessException + actionHint
5. 创建 `WorkProcessTaskController.java` (8 endpoint, SCHEMA_DESIGN line 1093-1101)
6. 前端 (轻量, 主要后端):
   - 在生产批次详情页加"工序任务"tab — 调 GET /work-process-tasks?batchId=X
   - 每个任务卡片有"开始" / "完成" / "跳过"按钮
   - 这部分代码可能动到 ProductionBatch 详情页, **改前 ping Organizer** 确认是否安全
7. 跑单元测试 (用 H2 mock — Cretas 现有 pattern)
8. 提醒: **注意 PostgreSQL parameter-side IS NULL 用 CAST AS string** (per `.claude/rules/database-entity-sync.md`)

**产出**: 工序任务生成 + 状态机可用

### Day 5 — AI Tool + 工序 PR

**目标**: AIChat 一句话配置 + ship 项目 1 PR

**动作**:
1. 创建 5 个 AI Tool (`ai/tool/impl/workprocess/`):
   - `WorkProcessTaskSpawnTool` — 一句话: "给批次 BAT-001 生成工序任务" (调 spawnTasks)
   - `WorkProcessTaskStartTool` — `work_process_task_start`
   - `WorkProcessTaskCompleteTool` — `work_process_task_complete` (带录入产量)
   - `WorkProcessTaskAssignTool` — 分配责任人
   - `WorkProcessConfigUpdateTool` — `work_process_config_update` (改产品×工序绑定, supportsPreview)
2. 注册 Tool: `@Component` 自动注册到 `ToolRegistry`, 无需手动 register, 启动日志确认 `✅ 注册工具: name=work_process_*, class=WorkProcess*Tool`
3. 在数据库 `ai_intent_config` 表插入 5 个 intent 行绑定这些 tool_name (per `.claude/rules/ai-intent-tool-skill-architecture.md`)
4. AIChat 跑通: "给猪蹄加工序: 拆包→分割→卤制→分切→装筐" 一句话配置 (这其实是 Day 3 + Day 4 的组合 — Tool 内部先调 product_work_process 绑定 5 道工序, 然后批次时再 spawn)
5. 跑端到端:
   - 系统管理 → 工序管理 → 新增工序 "拆包"
   - 系统管理 → 产品工序配置 → 选"猪蹄" → 加 [拆包, 分割, 卤制, 分切, 装筐]
   - 生产计划 → 新建批次 → 启动 → 自动 spawn 5 个工序任务
   - 工序任务列表 → 点"开始" / "完成" 跑状态机
6. 整理 PR: `[Track-D2] M-WP-1/M-WP-2 工序管理 + 产品工序配置`
   - PR body: 含截图 / 状态机图 / AIChat demo 视频
7. 更新 STATUS

**产出**: M-WP-1/M-WP-2 PR 开

### Day 6 — Bug 修: 生产工序"通用 P 过来"未关联

**目标**: 复现 + 修

**动作**:
1. 复现 bug (客户原话 line 95-99):
   - 在 cretas_db (本地) 给产品"猪蹄"绑定 5 道工序 (Day 3 的工作)
   - 新建生产计划 → 选"猪蹄" → 工序下拉 → **预期**显示 [拆包, 分割, 卤制, 分切, 装筐] / **实际**只有"通用"
2. 看现有 partial 修复:
   - `gh pr view 567 --json files,body` (本地必须用 gh CLI 在 PowerShell 跑)
   - 看 PR #622 / #623 (follow-up open, 看是否还有 open scope)
3. 定位代码:
   - 前端: 生产计划新建页 (grep "通用" + "工序" 找), 大概率在 `frontend/.../screens/production/` 或 `frontend/.../screens/factory/production/`
   - 后端: ProductionPlanController 创建批次时是否带 product_work_processes 查询
4. 修复路径:
   - 前端: 选产品后, 触发 GET /product-work-processes?productTypeId=X, 把返回的工序列表注入工序下拉 (不是 fallback 到"通用")
   - 后端: 如果是后端没暴露这个 endpoint, 加暴露 (但 ProductWorkProcessController 已有, 应该是前端 wiring 问题)
   - **不要**改 ProductionBatch Entity
5. 加单元测试: 给定 productTypeId X 有 5 个 product_work_process 配置, GET /product-work-processes 应返回 5 条
6. 加 E2E 验证: 本地跑一次完整流程, 截图 before/after

**产出**: Day 6 STATUS + 修复定位

### Day 7 — 工序 bug PR + 整体收尾

**目标**: ship 项目 2 PR + 整体演示准备

**动作**:
1. 写测试 (单元 + E2E)
2. 自测全跑过
3. PR: `[Track-D2] Bug 修: 生产工序"通用 P 过来"未关联 (#567 follow-up + #622/#623 close)`
4. 在 PR body 中 link #567 / #622 / #623 状态
5. 整体 demo 视频脚本:
   - 系统管理 → 工序管理 → 新增 5 道工序
   - 产品工序配置 → 绑定到"猪蹄"
   - 生产计划 → 新建 → 选猪蹄 → 工序下拉自动出 5 道工序 (不再是"通用")
   - AIChat → "给猪蹄加工序: 拆包→分割→卤制→分切→装筐" → 成功
6. 写 final STATUS update — 列举所有 2 个 PR + screenshot 链接
7. **不录 demo 视频**, 由 Organizer 录

**产出**: 2 项全 ship, Track D2 完结

---

## §5 关键参考文档

### 5.1 必读 (按顺序)

| 优先级 | 文档 | 用途 |
|---|---|---|
| ★★★ | `宏见竞品分析/01-客户档案/SCHEMA_DESIGN.md` §2.5 (line 905-1115) | work_process_tasks 完整 spec (DDL + Entity + API + Tool) |
| ★★★ | `宏见竞品分析/01-客户档案/六扇门第四次-May10.md` | 客户原话场景 (工序最详细的客户语料, line 49-104) |
| ★★ | `.claude/rules/ai-intent-tool-skill-architecture.md` | AI Tool-Skill 架构规范, 加 AI Tool 必读 |
| ★★ | `.claude/rules/database-entity-sync.md` | PostgreSQL 严格 GROUP BY + parameter-side IS NULL CAST AS string |
| ★★ | `.claude/rules/field-naming-convention.md` | Java entity camelCase / DB snake_case / JSON camelCase |
| ★★ | `.claude/rules/api-response-handling.md` | 统一 `{success, data, message}` 格式, 禁降级 |
| ★ | `宏见竞品分析/03-审计过程/AUDIT_FRESH_C_CODE.md` | Cretas 代码现状, 知道哪些已 ship |
| ★ | `CLAUDE.md` 项目根 | 全局规范快查 |

### 5.2 现有相关 PR (历史 context)

| PR | 摘要 |
|---|---|
| #293 | B1 工序 + B7 弹窗宽度 + B8 BOM 联动 quick wins — 历史 quick fix |
| #567 / #622 / #623 | "通用 P 过来"工序 bug 链路, **你 Day 6 需要看** |

### 5.3 现有 API/Service 现状 (代码地址)

- 后端 WorkProcess Controller: `backend/.../controller/WorkProcessController.java` (已有, 不动)
- 后端 ProductWorkProcess Controller: `backend/.../controller/ProductWorkProcessController.java` (已有, 不动)
- 后端 ProductionBatch Entity: `backend/.../entity/ProductionBatch.java` (餐饮+工厂共享, **不动**)
- 前端工序: grep 0 (你建)
- 前端生产计划页 (Day 6 bug 涉及): grep "通用" 或 "工序" 找, 大概率在 `frontend/.../screens/production/` 或 `frontend/.../screens/factory/production/`

---

## §6 接口契约 (重点细节)

### 6.1 WorkProcess + ProductWorkProcess API (现有, 不动)

只需了解 (调 from 前端):
- GET `/api/mobile/{factoryId}/work-processes` — 列出工序定义
- POST `/api/mobile/{factoryId}/work-processes` — 新增工序定义 (Day 2 工序新增页用)
- GET `/api/mobile/{factoryId}/product-work-processes?productTypeId=X` — 查产品×工序绑定
- POST `/api/mobile/{factoryId}/product-work-processes` — 新增绑定

### 6.2 WorkProcessTask API (你 Day 4 新建)

照 SCHEMA_DESIGN line 1093-1101:
- POST `/api/mobile/{factoryId}/production/batches/{batchId}/spawn-tasks` — 从模板生成任务
- GET `/api/mobile/{factoryId}/work-process-tasks?batchId=X&status=PENDING` — 列表
- GET `/api/mobile/{factoryId}/work-process-tasks/{id}` — 详情
- PUT `/api/mobile/{factoryId}/work-process-tasks/{id}/start` — PENDING → IN_PROGRESS
- PUT `/api/mobile/{factoryId}/work-process-tasks/{id}/complete` — IN_PROGRESS → COMPLETED (必填 actualQuantity)
- PUT `/api/mobile/{factoryId}/work-process-tasks/{id}/skip` — 任意 → SKIPPED (notes 必填, 主管权限)
- PUT `/api/mobile/{factoryId}/work-process-tasks/{id}` — 修改 (分配责任人/计划时间)
- DELETE `/api/mobile/{factoryId}/work-process-tasks/{id}` — 软删

### 6.3 AIChat Tool 命名建议

照 SCHEMA_DESIGN line 1106-1112:

**工序** (5 个):
- `work_process_task_spawn`
- `work_process_task_start`
- `work_process_task_complete`
- `work_process_task_assign`
- `work_process_config_update` (WRITE preview)

**Tool 类命名**: `XxxTool extends AbstractBusinessTool`, `@Component` 自动注册。

### 6.4 WorkProcessTask Entity 重点字段

照 SCHEMA_DESIGN line 1009-1071:
- 主键: `id BIGSERIAL` (auto-increment) — 跟 BOM Recipe 的 UUID 不同
- `factory_id` + `production_batch_id` + `work_process_id` 三向关联
- `process_order` 排序 (跟 ProductWorkProcess.processOrder 一致)
- `status` enum: PENDING / IN_PROGRESS / COMPLETED / SKIPPED / CANCELLED
- `planned_start_at`, `planned_end_at`, `actual_start_at`, `actual_end_at`
- `planned_minutes`, `actual_minutes`
- `assigned_to_user_id` FK
- `actual_quantity` (完成时填, 配合 outputUnit)

### 6.5 ProductionBatch Entity Fork (跟 Organizer 协调)

`ProductionBatch` 单表服务餐饮+工厂双主线。Day 4 spawn_tasks 时, batch 关联 5 个新工序任务, 但 ProductionBatch Entity **不需要加新字段** (task 单方面 FK 过来即可)。

**若发现确需改 ProductionBatch.java**:
1. STOP 你的修改
2. 在 STATUS 写 blocker: "Day X 发现需在 ProductionBatch 加 XXX 字段, 原因 YYY"
3. ping Organizer, 让 organizer 决定:
   - 加字段 (短期解决, fork 推迟)
   - fork (长期方案, 但跨 chat 协调)
4. **不擅自动手**

---

## §7 PR / Status Update 流程

### 7.1 每日 STATUS 同步

每天在 `宏见竞品分析/04-最终决策/STATUS/TRACK_D2_STATUS.md` 追加 1 段 (如果文件不存在, 你自己创建):

```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: 项目内容 X / Y / Z
- 🟡 进行中: 项目 A (剩 N%)
- ❌ Blocker: 描述 (需 organizer 协调)
- 明日计划: 项目 B / C
```

Organizer 每天 review 你的 STATUS。

### 7.2 PR 流程

**每完成 1 项 sub-project 即 PR 1 次** (总共 2 个 PR), 不要憋大爆炸:
1. 起 branch `feature/asap-track-d2-{编号}` (如 `feature/asap-track-d2-m-wp-1`)
2. PR 标题: `[Track-D2] N# 编号 项目名` (本 brief 用 M-WP-1 / M-WP-2 编号)
3. PR body 模板:
   ```
   ## Summary
   - 项目: M-WP-1/M-WP-2 工序管理 + 产品工序配置
   - 涉及文件: (列举 ~10 个关键)
   - 测试方式: 本地端到端截图 + AIChat demo
   - 风险点: ProductionBatch fork 推迟

   ## Test plan
   - [ ] 本地 cretas_db migration 跑通
   - [ ] 单元测试 PASS
   - [ ] 工厂端能新增工序 + 产品绑定 + 任务生成 + 状态机
   - [ ] AIChat 一句话配置工序跑通

   🤖 Generated with Claude Code
   ```
4. Push 前在自己 worktree 内**两次 git status** (本 brief §3 + `.claude/rules/concurrent-edit-safety.md` 强调) 确保没意外文件
5. 推送后跟 Organizer 说"PR open, 等 review"
6. Organizer review + merge

### 7.3 大改动先 ping (必须)

以下情况**必须先 ping Organizer**:
- ProductionBatch Entity fork (任何动作前) — 跨 track 共享 Entity
- 你想改 §3.3 共享文件中的某个 (改 navigation.ts 加 screen 不需要 ping, 改字段类型需要)
- 修改生产计划页 (Day 6 bug fix) 时, 改动超过工序下拉那一段逻辑
- 跟 Track D1 在 product_type_id 或共享字典上的字段语义有协调需求

### 7.4 碰到 Blocker

任何 unknown / 阻塞 → STATUS 写 "❌ Blocker: ...", organizer 帮你解决。

---

## §8 不要做

### 8.1 不要 refactor 现有 WorkProcessController

- `WorkProcessController` 和 `ProductWorkProcessController` 已有, 不要改 API 签名
- 新功能全部在 `WorkProcessTaskController` (新建) 上做

### 8.2 不要改 ProductionBatch Entity

- fork 决策需 organizer 拍板 (餐饮 vs 工厂语义差异问题)
- Day 4 spawn_tasks 时, work_process_tasks 单方面 FK 过来即可
- 若发现确需改, ping organizer

### 8.3 不要写新 N# 编号

- 现有编号 M-WP-1 / M-WP-2 已映射到 SCHEMA_DESIGN
- 不要发明 D-XXX-1 之类新编号

### 8.4 不要扩大生产计划页修改 scope

- Day 6 bug fix 只针对"工序下拉自动注入"那一段逻辑
- 不要顺手改 ProductionPlan 其他业务逻辑 (有可能炸别的链路)

### 8.5 不要静默失败 / 降级处理

- 项目规范禁止: `.claude/rules/api-response-handling.md` 强调统一 `{success, data, message}` 格式
- 工序状态机非法转换 → 明确返回 `{success: false, message: "...", code: "INVALID_TRANSITION", actionHint: "..."}` + 前端 Alert
- 不要 try-catch 吞掉错误后返回假数据

### 8.6 不要硬编码 LLM key / DB 密码

- 凭证全部走环境变量 (`.claude/rules/CREDENTIAL-MANAGEMENT.md`)
- 你的 AI Tool 通过 `IntentExecutorServiceImpl` 间接调 LLM, 不直接拿 key

### 8.7 不要并发改同一文件

- 你跟 Track A/B/C/D1 不会共享文件 (per §3.2 ownership)
- 但如果你开了 multi-worktree, 用 git worktree 隔离 (`.claude/rules/concurrent-edit-safety.md`)
- commit 前 `git status --short` 确认 staging 区只有预期文件 (规则 5)

### 8.8 不要碰 Track D1 范围

- BOM 相关 (`BomRecipe`, `BomRecipeItem`, BomConfigScreen, BomEditorScreen, MaterialSelectModal, MaterialSpecManagementScreen, 单位转换) 是 Track D1 负责
- 你工序跟 BOM 在数据层接触面仅是 `product_type_id` 共享 (产品字典), 这是只读引用, 不会冲突

---

## §9 验收清单 (Track D2 完成判定)

### 9.1 2 项 sub-project 全 ship

- [ ] M-WP-1 + M-WP-2: 工序管理 + 产品工序配置 + 生成工序任务 + 状态机
- [ ] Bug 2 (工序"通用 P"): 新建生产计划时工序下拉显示该产品配置的工序, 不再是"通用"

### 9.2 AIChat 一句话场景跑通

- [ ] "给猪蹄加工序: 拆包→分割→卤制→分切→装筐" → 一句话配置完成
- [ ] "给批次 BAT-001 跳过质检工序, 原因: 客户加急免检" → work_process_task_skip
- [ ] "给批次 BAT-001 生成工序任务" → work_process_task_spawn 触发

### 9.3 客户实测场景跑通

- [ ] 客户原话 "工序流程: 拆包→分割→卤制" → 产品工序配置 + 任务生成
- [ ] 客户原话 "第一个工序叫拆包... 工序我选前处理, 然后产出单位是工金" → WorkProcessCreateScreen 表单完整
- [ ] 客户原话 "宣传计划, 新建计划, ..., 工序还是只有通用" → 生产计划工序下拉自动出该产品配置的工序

### 9.4 不破坏现有

- [ ] 现有 WorkProcessController + ProductWorkProcessController 端点 + API 签名没改
- [ ] ProductionBatch Entity 没动
- [ ] 现有生产业务不破坏 (生产计划创建/批次启动/批次完成 link OK)

### 9.5 2 个 PR 全 merge

- [ ] PR 1: `[Track-D2] M-WP-1/M-WP-2 工序管理 + 产品工序配置` merged
- [ ] PR 2: `[Track-D2] Bug 修: 生产工序"通用 P 过来"未关联 (#567 follow-up)` merged

---

## §10 客户场景对照表

| 客户原话 | 我的实现 | 涉及项目 | 涉及代码 |
|---|---|---|---|
| "工序管理, 哦, 看到了。产品, 产品工序配置是吧?" (line 60-66) | WorkProcessListScreen + ProductWorkProcessConfigScreen | 项目 1 | 2 个新 screen |
| "第一个工序叫拆包... 工序我选前处理, 然后产出单位是工金, 然后下面是预估工时" (line 77-78) | WorkProcessCreateScreen 表单字段 (name + category select + outputUnit + estimatedMinutes) | 项目 1 | WorkProcessCreateScreen |
| "工序流程就是拆包, 分割, 卤制, 拆股, 分配" (line 104) | 产品工序配置 → 5 道工序绑定到"猪蹄" | 项目 1 | ProductWorkProcessConfigScreen |
| "回到宣传计划, 然后新建计划, 订单, 产品行, 注射, 叮咚, 工序, 哎, 这工序还是只有通用" (line 95) | 生产计划工序下拉自动 GET /product-work-processes?productTypeId=X 注入 | 项目 2 | 生产计划新建页 + #567 follow-up |
| "门枪没有添加的, 我看没有添加, 就是在产品工序配置里面还得添加" (line 99) | 引导客户先在产品工序配置加, 然后生产计划自动出 | 项目 1 + 2 | UX 引导 |

---

## §11 风险 + 应急

### 11.1 已知风险

| 风险 | 缓解 |
|---|---|
| ProductionBatch 餐饮/工厂语义混乱 | fork 决策推迟到 organizer 拍板, 本 track 不动 |
| 工序"通用 P"bug 牵扯到 ProductionPlan / ProductionBatch / Dispatcher 多模块 | Day 6 先复现, 定位最小修复点, 不扩大 scope |
| AIChat Tool 跟现有 337 tool 名字冲突 | 起名前 grep `getToolName()` 确认 unique |
| WorkProcessTask spawn 跟 ProductionBatch 启动时机耦合 | 用单独 endpoint `/spawn-tasks`, 让前端/AI 显式调用, 不在 ProductionBatch.start 内自动 spawn |
| 跟 Track D1 (BOM) 的 product_type_id 共享语义 | 都只读 product_types 字典, 不会冲突; 你 ProductWorkProcessConfigScreen 用 product Select, D1 BomEditorScreen 也用, 两边语义一致即可 |

### 11.2 应急联系点

- **任何 blocker** → STATUS 写明, ping Organizer (Chat 1)
- **跑偏方向** → STATUS 写"我想做 X, 但是 Y, 请确认", organizer 反馈
- **碰到 Track A/B/C/D1 文件** → STATUS 写, organizer 协调 (你不擅动)

### 11.3 跟 Track D1 协调

- D1 (Chat 5) 做 BOM + 物料选择器 + 单位转换, 跟你接触面仅是 `product_type_id` 字典共享
- 都通过 `product_types` 表读, 不冲突
- 如果 D1 在 BomEditorScreen 里用了 product Select, 你 ProductWorkProcessConfigScreen 用的是同一个字典, 两边语义一致即可
- 若发现 D1 改了某共享字段或共享文件 (如 product_types 表结构), ping organizer 协调

---

## §12 启动 Checklist (Day 0)

接到本 brief 后, 你应该:

1. [ ] 读完整本 brief (10-15 分钟)
2. [ ] 读 SCHEMA_DESIGN.md §2.5 (10 分钟, 工序部分)
3. [ ] 读六扇门第四次会议 line 49-104 (10 分钟, 工序部分)
4. [ ] 浏览 `.claude/rules/` 7 个核心规范文件 (10 分钟)
5. [ ] 起 git worktree: `git worktree add ../my-prototype-logistics-track-d2 -b feature/asap-track-d2 HEAD`
6. [ ] 创建 STATUS 文件: `宏见竞品分析/04-最终决策/STATUS/TRACK_D2_STATUS.md` 写 Day 0 onboarding 完成
7. [ ] 找 organizer 报到: "Track D2 收到 brief, Day 1 开始 M-WP-1/M-WP-2 spec 吸收 + 现状 audit"
8. [ ] Day 1 开干

---

**祝顺利。任何疑问 ping Organizer (Chat 1)。**