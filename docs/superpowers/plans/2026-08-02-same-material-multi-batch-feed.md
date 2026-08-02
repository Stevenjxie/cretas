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

- [x] **Step 1: 后端全量** → HEAD~1 基线 40 个失败类 vs 改动后 40 个, **集合逐字一致**(新增 0 / 消失 0)。我的 5 条在全量里 5/5, 既有 `WorkflowClerkSheetServiceTest` 12/12

```bash
cd backend/java/cretas-api && mvn -o test
```

Expected: 与改动前同一失败集合（本仓既有失败清单见 `.codex/` 台账）。**新增失败即回归**。

- [x] **Step 2: web-admin 全量** → 317 passed / 1 skipped, 2390 tests 全绿(前端零改动)

```bash
cd web-admin && npx vitest run
```

Expected: 全绿。本次前端零改动，若有红说明动到了不该动的。

- [x] **Step 3: 记录基线差异，任何新增失败都要查根因再往下走**

---

### Task 3: 部署 + 重建 + 真机验证

**Files:** 无代码改动；产出验证记录写进 PR。

- [x] **Step 1: 合并到 main 后部署后端** → PR #2208 合入 `d3d1fe1eb0`; `RELEASE_FINAL_STATUS=deployed` + `DEPLOY_EXIT=0`; blue→green(10020); 运行 jar 含 `resolveAllowMultipleUpstreamSources`

```bash
./scripts/deploy/release-cretas.sh --phase deploy --base-sha <前一个main SHA> \
  --tests 'WorkflowClerkSheetMultiUpstreamTest' --confirm-prod YES-PROD
```

只认 `RELEASE_FINAL_STATUS=deployed` **且** `DEPLOY_EXIT=0`。

- [x] **Step 2: 重建 LIUSHANMEN 酱鸭腿 workflow** → workflow 137 / revision 239 / 7 节点 6 边, 已 PUBLISHED + 激活(activation id 57)

图定义取自备份（`D:\Temp\cretas-backup\full-closure-backup.sql` 里 LIUSHANMEN + `c57c36e0-c6a9-4758-9468-5710ac73e672` 的最新 revision，已导出为 `duck-nodes.json` / `duck-edges.json`）。三道工序图里 `allowMultipleUpstreamSources` 本来就是 `true`，**不需要改图**。

走 API：`PUT /api/mobile/LIUSHANMEN/product-process-workflows/{productTypeId}/draft` → `POST .../publish-and-activate`。
账号 `liushanmen_admin` / `123456`。

⚠️ **原计划这里写错了**：以为「发布会自动同步 BOM，所以清空的配方会随之回来」。实测**不成立** ——
`publish-and-activate` 只同步到**已生效**的 BOM，没有就直接 409：

```
{"code":409,"message":"当前产品没有生效 BOM","errorCode":"WORKFLOW_BOM_SYNC_USER_INPUT_REQUIRED"}
bom-sync-preflight: classification=USER_INPUT_REQUIRED, missingItems=[WORKFLOW_ACTIVE_BOM_REQUIRED]
```

**正确顺序是 BOM 先、workflow 后**：

1. `POST /bom/recipes/ensure-draft` 带 `workflowRevisionId` → 按图脚手架出草稿（本次自动带出 1 项：YL-DL-冷冻鸭腿，数量为空）
2. `PUT /bom/recipes/{recipeId}` 填数量/单价/税率
3. `POST /bom/recipes/{recipeId}/activate` → ACTIVE + isCurrent
4. 这时 preflight 才是 `READY`，`publish-and-activate` 才会过

判据：**「A 会自动带出 B」这类假设，动手前先跑一次 preflight 看它到底要什么** —— 本次 preflight 一句话就说清了缺什么。

- [x] **Step 3: 建计划 + 开工，让 workflow 实例落地** → `PLAN-1785671039093-AD5D9AC2` / 批次 10623 / 实例 60 ACTIVE（nodes_json 7 节点）

必须有 `production_workflow_instances` 行，运行时才有 `nodesJson` 可读。

- [x] **Step 4: 真机验证（唯一算数的判据）** → 装箱页 ⊕ 可点, 点后「投入来源」1 项→2 项, 两批各选各量, 提交成功

打开装箱结单页，确认：
- 「酱制鸭腿(半成品)」下拉能**多选**
- ⊕「加一批」**可点**，点后多一行
- 勾 2 批、各填投入量，提交成功

- [x] **Step 5: 查库确认逐批扣减** → `...41182` 已用 20kg / `...72980` 已用 20kg, **各自扣减**; `row_payload.upstreamSources` 两条各 20kg; 产出 35kg **一个**成品批次

⚠️ **这里原来的查询语句查错了表**：`semi_finished_inventory` 对 clerk 逐道报工链路**是空的**（0 行），
按它判断会得出「一条都没扣」的错误结论。CLK-W-* 批次的真实落点是 `process_sheet_rows.row_payload`：

```bash
ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -A -t -c \"
SELECT jsonb_pretty(row_payload::jsonb) FROM process_sheet_rows
WHERE factory_id='LIUSHANMEN' AND process_code='qidiao' ORDER BY created_at DESC LIMIT 1;\"" \
 | grep -E 'inputQuantity|outputQuantity|sourceBatchNumber|feedQuantityKg'
```

实拍结果：

```json
"inputQuantity": 40, "outputQuantity": 35,
"upstreamSources": [
  {"feedQuantityKg": 20, "sourceBatchNumber": "CLK-W-20260802-41182"},
  {"feedQuantityKg": 20, "sourceBatchNumber": "CLK-W-20260802-72980"}
]
```

配合页面「双出成率总览」逐行核对（两批 `已用` 各 20kg / `剩余` 各 0kg，装箱行「来源批次」同时列两个批次）。

Expected: 被选中的**两个批次各自扣减**，而不是只有一条动。
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
