# 画布落库 · 后续（2026-08-09）

> ✅ **原本列的两项都已在本支内做掉了**（Steve 授权我按最长期的方案自行决定）。
> 下面保留原文与「实际怎么解的」，因为**解法与原方案不同**，理由值得留着。

本文件记录 `feat/canvas-patch-write` **刻意没做**的两件事，以及打开开关的前置条件。
⛔ 别把它们当成「漏了」——都是拍过板的。

---

## 1. ✅ 已解 —— 但解法与原计划不同（更长期）

**原计划**：让网关/控制器把 productTypeId 带进 context。
**实际解法**：`belongsToStoredProduct()` —— 判据取自**库里存着的那张图的节点 id**。

为什么换：节点 id 是建图时生成、跟着图走的。用户在产品 A 的画布上提问，
提交的 definition 带的就是 A 的节点 id；库里 B 的图带的是 B 的 id，**一个都对不上**。
所以「与库里的图至少共享一个节点 id」就足以区分「在改这个产品」和「把别人的图搬过来」。

⛔ 不需要动所有工具共用的 context 传参链路 —— 同一条原则：**判据取自库里存着的真值**。

⚠️ 库里没有图时放行（新产品第一张草稿没有基线）。残余风险窄得多：
AI 得先猜中一个**恰好还没有任何工艺图**的产品。

⚠️ 开关**仍然默认关**，但理由变了：不再是「有洞」，而是**这条链没有一次真人端到端走过**。

### （以下为原始记录）
### ⛔ 原本「打开前必须先做的事」

**现状**：开关默认 `false`。落库能力已实现、已测、已合入，但**关着**。

**为什么关着**：决定「覆写哪张画布」的 `productTypeId` **完全由 AI 决定**。
`factoryId` 已经钉在执行 context 上（AI 改不了），但 context 里**没有** `productTypeId`
（只有 `factoryId` / `tenantId` / `businessType` / `userId` / `userRole` / `roleCode` / `permissions`），
所以后端没有可信来源可以拿来比对，只能挡住空值。

**会怎么错**：厂长在「盐水鸭」画布上提问，模型把 `definition.productTypeId` 填成同厂的
「酱鸭」→ `requireWorkflowOwner` 放行（确实是本厂产品）→ 给酱鸭**新建**一张
内容是盐水鸭的草稿。不报错、无症状，可能很久没人发现。

**前置条件**：让网关/控制器把「用户当前打开的是哪个产品」带进 context，
并在 `ProductProcessWorkflowConfigTool` 里比对。

⛔ **不要因为「测试都绿」就打开** —— 这个洞在单元测试里看不见，
它需要的是 context 里那个字段真的存在。

---

## 2. ✅ 已解 —— 加了诚实的新档 `DECLINED`

`GatewayResultCode.TOOL_DECLINED` + 网关识别 `status == "DECLINED"`。
**纯增量**：今天没有别的工具发 `DECLINED`，既有行为一个字节没变。

⛔ 没走「让工具谎报 `NEED_MORE_INFO`」那条捷径 —— 那是用不准确的状态码骗中间层。

### （以下为原始记录）
### 原始问题描述

**现状**：`DefaultToolExecutionGateway` 对 `success:false` 只认一种干净失败
（`status == "NEED_MORE_INFO"`），其余一律 → `OUTCOME_UNKNOWN` + 台账 `IN_DOUBT` + 清空 payload。

**后果**：工具说「涉及调料克数只能预览，请去产品配置页确认」，
用户看到的是「执行结果需要人工对账」，台账记成疑似写入。
**一个结构上确定没有写入的拒绝，被记成需要人工对账。**

**为什么现在才暴露**：网关这个行为一直如此，但此前 `execute` 恒定拒绝、没人真调它。
现在它是活的写入口，四条拒绝分支（`WORKFLOW_AI_PREVIEW_ONLY` /
`WORKFLOW_OWNER_REQUIRED` / `WORKFLOW_PATCH_REJECTED` / `WORKFLOW_DEFINITION_REQUIRED`）
全会撞上去。

**Steve 拍板：改网关（让它认识「明确拒绝」这一类），排在本支之后。**

⛔ **不许走「改工具去迁就网关」那条路** —— 给拒绝响应套上网关认识的
`NEED_MORE_INFO`，改动确实更小，但那是**用一个不准确的状态码去骗网关**，
会留下一个将来很难查的坑。

⚠️ 网关是所有工具共用的，改它影响面大，需要单独评估。

---

## 3. 已知但判为不阻断的（整支审查的 Minor）

- 分流闸跑在全量 `validateForDraft` **之后** —— 一条既违规又含克数的补丁会报
  `WORKFLOW_PATCH_REJECTED` 而非 `PREVIEW_ONLY`。**不影响安全**（两种都不写），
  只是 agent 拿到的原因取决于两类问题的先后。
- `saved.getUnitWarnings()` 被丢掉 —— REST 路径会把单位告警回给页面，工具路径没有。
  草稿带着会在发布时抛 400 的单位错误，而 agent 不会提。
- `requireFactoryId` 抛 `IllegalArgumentException` 被「补丁被拒」那条 catch 收走，
  于是 context 缺 `factoryId` 报的是 `WORKFLOW_PATCH_REJECTED`（已有测试覆盖行为，
  但错误码不够准确）。
