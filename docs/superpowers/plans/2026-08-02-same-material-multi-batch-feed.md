# 同一物料多批次投料 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> ⚠️ **本计划已重写一次。** 初版按「改 web-admin 前端判据 `isMultiSource` 为 `>= 1`」写，
> 实施时被测试推翻（打挂 10 个既有测试），且方向本身是错的 —— 它会让所有工序变多来源，
> 同样无视用户配置。真根因在后端，见 spec §2 与 §8。

**Goal:** 让 Workflow 画布上配的「同一物料可投多批」开关真正生效，客户张权的装箱工序可以一次投 3 批酱制鸭腿并只出 1 个成品批次。

**Architecture:** 后端 `WorkflowClerkSheetServiceImpl` 原来把用户配置丢掉、按端口数重算（`upstreamInputCount > 1`）。改成读运行时快照 `ProductionWorkflowInstance.nodesJson` 里该节点的 `allowMultipleUpstreamSources`，缺失才回落端口数。前端零改动。

**Tech Stack:** Java 21 + Spring Boot + Jackson；测试 JUnit5 + AssertJ（纯函数，不起 Spring）。

## Global Constraints

- 设计文档：`docs/superpowers/specs/2026-08-02-same-material-multi-batch-feed-design.md`，事实以它为准。
- 工作目录：`C:\Users\Steve\cretas-multibatch`（worktree，off `origin/main`）。
- **前端不改**。`ProcessDataTable.vue` 的 `isMultiSource` 判据保持 `> 1`，它是后端给 false 时的兜底。
- 后端扣减逻辑不改（早已逐批 `consumeClerkSemiStrict`）。
- 不加数据库列、不写 Flyway 迁移 —— 配置从 `nodesJson` 快照读。
- 提交用 `git commit -- <明确路径>`（并发安全，见 `.claude/rules/concurrent-edit-safety.md` Rule 5b）。

---

### Task 1: 运行时读取图定义里的混批开关 ✅ 已完成

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/WorkflowClerkSheetServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/WorkflowClerkSheetMultiUpstreamTest.java`（新建）

**Interfaces:**
- Consumes: `ProductionWorkflowInstance.getNodesJson()`（已存在，不可变运行时快照）、`WorkProcessTask.getWorkflowNodeId()`
- Produces: `private boolean resolveAllowMultipleUpstreamSources(String instanceNodesJson, String workflowNodeId, boolean portCountFallback)`

- [x] **Step 1: 写失败测试** — `WorkflowClerkSheetMultiUpstreamTest`，5 条（回归 / 配 false 生效 / 缺字段回落 / 找不到节点回落 / 坏 JSON 回落）
- [x] **Step 2: 跑测试确认失败**
- [x] **Step 3: 类加 `@Slf4j`；`buildDescriptor` 加 `instanceNodesJson` 参数；调用处传 `instance.getNodesJson()`**
- [x] **Step 4: 判据改为**

```java
.allowMultipleUpstreamSources(resolveAllowMultipleUpstreamSources(
        instanceNodesJson, task.getWorkflowNodeId(), upstreamInputCount > 1))
```

- [x] **Step 5: 加 `resolveAllowMultipleUpstreamSources` helper**（Jackson 解析，任何异常回落）
- [x] **Step 6: 跑测试** → 5/5 通过
- [x] **Step 7: 变异实证** → 把判据改回 `upstreamInputCount > 1` 且 helper 永远回落 → **2 failed**，红在 `graphConfigWinsOverPortCount`（回归目标）与 `graphFalseIsRespected`；其余 3 条保持绿，证明各钉各的
- [x] **Step 8: 还原 + `mvn clean test` 复验** → 5/5 通过（0.779s）。`mvn clean` 不可省 —— 变异脚本还原源码后 `target/classes` 里仍是变异版 `.class`，直接 `mvn test` 会拿旧字节码跑出假结果
- [x] **Step 9: 提交**

```bash
git commit -m "fix(workflow): 报工单读图定义里的混批开关 —— 原来按端口数重算, 用户配了等于没配" \
  -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/impl/WorkflowClerkSheetServiceImpl.java \
     backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/WorkflowClerkSheetMultiUpstreamTest.java
```

---

### Task 2: 完整回归

**Files:** 无改动，只跑。

- [ ] **Step 1: 后端全量**

```bash
cd backend/java/cretas-api && mvn -o test
```

Expected: 与改动前同一失败集合（本仓既有失败清单见 `.codex/` 台账）。**新增失败即回归**。

- [ ] **Step 2: web-admin 全量**

```bash
cd web-admin && npx vitest run
```

Expected: 全绿。本次前端零改动，若有红说明动到了不该动的。

- [ ] **Step 3: 记录基线差异，任何新增失败都要查根因再往下走**

---

### Task 3: 部署 + 重建 + 真机验证

**Files:** 无代码改动；产出验证记录写进 PR。

- [ ] **Step 1: 合并到 main 后部署后端**

```bash
./scripts/deploy/release-cretas.sh --phase deploy --base-sha <前一个main SHA> \
  --tests 'WorkflowClerkSheetMultiUpstreamTest' --confirm-prod YES-PROD
```

只认 `RELEASE_FINAL_STATUS=deployed` **且** `DEPLOY_EXIT=0`。

- [ ] **Step 2: 重建 LIUSHANMEN 酱鸭腿 workflow**

图定义取自备份（`D:\Temp\cretas-backup\full-closure-backup.sql` 里 LIUSHANMEN + `c57c36e0-c6a9-4758-9468-5710ac73e672` 的最新 revision，已导出为 `duck-nodes.json` / `duck-edges.json`）。三道工序图里 `allowMultipleUpstreamSources` 本来就是 `true`，**不需要改图**。

走 API：`PUT /api/mobile/LIUSHANMEN/product-process-workflows/{productTypeId}/draft` → `POST .../publish-and-activate`。
账号 `liushanmen_admin` / `123456`。

发布会自动同步 BOM（`publish` 注释原文：「自动同步 BOM、发布并启用」），所以清空的配方会随之回来。

- [ ] **Step 3: 建计划 + 开工，让 workflow 实例落地**

必须有 `production_workflow_instances` 行，运行时才有 `nodesJson` 可读。

- [ ] **Step 4: 真机验证（唯一算数的判据）**

打开装箱结单页，确认：
- 「酱制鸭腿(半成品)」下拉能**多选**
- ⊕「加一批」**可点**，点后多一行
- 勾 2 批、各填投入量，提交成功

- [ ] **Step 5: 查库确认逐批扣减**

```bash
ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -A -F' | ' -c \"
SELECT intermediate_batch_no, produced_quantity, consumed_quantity, available_quantity
FROM semi_finished_inventory WHERE factory_id='LIUSHANMEN';\""
```

Expected: 被选中的**两个批次 `consumed_quantity` 各自增加**，而不是只有一条动。
单测只能证明解析对，**证明不了后端真的逐批扣** —— 这一步不可替代。

- [ ] **Step 6: 验通后再推 F006 其余产品**（先一条验通再批量，避免姿势错了要返工）

---

## Self-Review

**1. Spec coverage**

| Spec 章节 | 覆盖 |
|---|---|
| §3 方案（读 nodesJson，缺失回落） | Task 1 Step 3–5 |
| §5 测试 5 条 | Task 1 Step 1 |
| §5 变异实证 | Task 1 Step 7（已完成，2 failed 命中回归条） |
| §5 真机判据 | Task 3 Step 4–5 |
| §4 前端不动 | Global Constraints 明写；Task 2 Step 2 用 vitest 全绿反证 |
| §6 回退 | 改动集中在一个方法 + 一个 helper |

**2. Placeholder scan** — 无 TBD/TODO；每个命令都可直接执行；每步都有明确期望。

**3. Type consistency** — `resolveAllowMultipleUpstreamSources(String, String, boolean)` 签名在 Task 1 定义，Step 4 调用处逐字一致。
