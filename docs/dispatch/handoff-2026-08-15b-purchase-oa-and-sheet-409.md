# 交接 2026-08-15（第二轮）—— 采购 OA 打通 + Sheet 409 收口 + 14 条存量红测试

**范围**: 承接 `handoff-2026-08-15-web-rn-e2e-and-dead-paths.md` 的三项未核实 + 两项待拍板
**环境**: 生产 `https://admin.cretaceousfuture.com`
**写入租户**: 仅 **F006**（团队测试租户）。⛔ 全程未碰 **LIUSHANMEN**

---

## 一、已上线（全部合入 main 并验证）

| PR | 内容 | 验证方式 |
|---|---|---|
| #2662 | 上一轮的交接文档进 main | 此前只在未合并分支上，导致本轮开局读不到 |
| #2664 | 新建计划下拉在 Android 划不动（4 处）+ skills 里的 F001 死凭证（7 文件） | AST 闸 + 变异红/绿 |
| #2667 | 采购新建幂等闸补内容维度 | 2 条变异精确命中；**运行中 jar 验到 `requestContentSignature`** |
| #2670 | 14 条在 origin/main 上一直红的采购域用例 | 230→236 tests，**0 failures** |
| #2671 | 摘掉工厂超管那个永远空的「审批」tab | 变异红/绿 |
| #2673 | RN 新建采购单的物料按供应商收敛 | 变异红/绿 |
| #2674 | 财务经理待办改按 OA 实例当前节点取 + 审批接活端点 | **prod 端到端实测**（见三） |

---

## 二、四个 owner 裁定（Steve 2026-08-15）

| # | 决策 | 结果 |
|---|---|---|
| 1 | 采购 409 幂等闸 | **加内容维度**（不是放宽窗口、不是移除） |
| 2 | OA 待办中心定位 | **维持财务/出纳专用**，摘掉超管那个空 tab |
| 3 | 财务「盖章」功能 | **不做** ——「就是一个 OA 就够了」 |
| 4 | 14 条存量红测试 | **修掉** |

---

## 三、🔑 本轮最重要的事实：采购 OA 从「零实例」到跑通

### 起点（prod 实测，只读）

```
purchase_orders             9 张   DEMO_REST 2 / F002 5(2026-02-21 种子) / RES_3101_009 2
F006 + LIUSHANMEN           0 张   ← 含软删除计 0, 从来没有过
approval_workflow_instances 0 行   RLS=false, 不是被挡
approval_history            0 行
approval_workflows         61 条   ← 只是【配置】

有采购审批链的工厂: F006, LIUSHANMEN
有采购单的工厂:     DEMO_REST, F002, RES_3101_009
```

**两个集合完全不相交** ⇒ 零实例**不是缺陷，是没有使用**：配了链的工厂从没建过采购单，
`submitOrder` 一次都没被调用过。

⚠️ 这一条同时解释掉三件看起来各自独立的事：
`procurement/finance-review` 模块为什么永远空、超管「审批」tab 为什么永远空、
`findFinanceApproval` 为什么永远返 null。

### 冒烟（Steve 授权，写入只落 F006）

建了 F006 **有史以来第一张采购单** `PO-20260815-0001`（¥40,000，超 ¥30000 阈值）→ 提交：

```
status = WORKFLOW_RUNNING,  financeReviewedBy = null
approval_workflow_instances: F006 | PURCHASE_ORDER | RUNNING | ["approval_finance"]
approval_history: 0 → 1
```

**管道端到端是通的。**

### 冒烟顺带炸出的真缺陷（已修，#2674）

那张单停在 `approval_finance` 等 finance_manager，而 `fetchPurchaseFinanceReview`
**查的是 `PurchaseOrderStatus.PENDING_FINANCE_REVIEW`** —— 全库该状态数 = **0**。

原因：OA 投影只有 `RUNNING→WORKFLOW_RUNNING` / `APPROVED→FINANCE_APPROVED`，
**根本不经过** `PENDING_FINANCE_REVIEW`。
⇒ **财务经理的待办里永远看不到正在等他审批的采购单。** 接口 200、返回空列表，读数完全正常。

**连带**：卡片上的通过/驳回打的是 `/finance-approve`，该端点 2026-07-21 (#1557) 已停用抛 **410**。
之所以两个月没人发现 —— **卡片根本不出现**，死按钮藏在一个永远空的列表后面。
**取数一修好，按钮当场变成用户点得到的**，所以两件必须同一个 PR 改。

### 修完的 prod 实测（用真·finance_manager 账号）

```
GET /api/mobile/F006/my-todos   (f006_finance_mgr)
  type= PURCHASE_FINANCE_REVIEW
    refNumber= PO-20260815-0001  amount= 40000.0
    instanceId= 55fd660f-…       expectedNodeId= approval_finance
```

驳回也实测通过（正好验了 RN 现在要打的那个调用）：

```
POST /workflow/instances/{id}/actions  {action:REJECT, expectedNodeId, idempotencyKey}
→ workflowStatus=REJECTED, businessStatus=FINANCE_REJECTED
```

**测试单已清理**：`PO-20260815-0001` = `FINANCE_REJECTED`，财务待办只剩原有的销售单。

---

## 四、Sheet「采购订单新建 409」的收口

⚠️ 上一轮交接件写「已定位是 60s 防双击幂等闸」，**那是推断不是证据** ——
`createPurchaseOrder` 路径上能抛 409 的有**五处**，文案不同、修法不同：

| # | 文案 | 状态 |
|---|---|---|
| 1 | 「60 秒内已对该供应商创建过内容相同的采购单」 | ✅ 已修（#2667）—— 它本身就是缺陷，见下 |
| 2 | 「供应商已暂停合作」 | ✅ 不会从 RN 新建屏发生（用的 `getActiveSuppliers`） |
| 3 | 「该供应商未启用所选物料的供应关系」 | ✅ 已修（#2673）—— **最可能的真凶** |
| 4 | 「供应商与物料的供应关系不存在」 | ✅ 同上 |
| 5 | 「供应商包装规格与原料包装换算不一致」 | ⬜ 未处理（配置冲突，缺实例） |

**3/4 的根因**：RN 新建屏加载**全厂所有原料**，选择器只按搜索词过滤、不看供应关系 ——
用户选完供应商能选到没有供应关系的物料，**填完整张单提交才被拒**。
web-admin 一直是对的（`resolveSupplierMaterialRelations` + 提交前校验），只有 RN 这处漂了。

**1 为什么本身就是缺陷**：它是同族 7 道 R4 幂等闸里**唯一没有内容维度**的。
同毛病的 `InternalTransfer` 已于 2026-06-18 因「备料被彻底卡住」整道移除，
其墓志铭写着「唯独调拨没有 —— 它是异类」，**而那句当时就不成立**：采购是第二个异类。

📌 **仍希望拿到 Sheet 那一行的原始文案/截图**，用来确认用户撞的到底是哪一条。

---

## 五、本轮留下的闸（都做过变异验证）

| 闸 | 守什么 | 自保 |
|---|---|---|
| `nestedScrollablePickersContract` | 同向嵌套 ScrollView 必须带 `nestedScrollEnabled` | **AST 数结构**；只认真嵌套（第一版拿 maxHeight 当代理判据报 43 条误报，会被关掉） |
| `PurchaseOrderIdempotencyContentDimensionTest` | 双击仍拦 / 不同内容放行 | 放行用例**都配阳性对照**（verify 候选单真被查、行项目真被比对），否则返空时恒真 |
| `purchaseOrderMaterialPickerContract` | 物料选择器必须按供应商收敛 | 剥注释后断言 |
| `oaTodoUsesLiveApprovalEndpointContract` | OA 待办不许再打已停用(410)的端点 | 剥注释后断言 |
| `roleNavigatorBoundariesContract`（改） | Boss 不再挂空的审批 tab | **改成剥注释后断言** —— 见下 |

⚠️ **两道闸差点被自己的文档喂成假绿**：移除组件时我在原地留了说明注释，
注释里**写着那个组件名**，不剥注释的 `toContain` 会继续通过而组件其实已经不挂了。
凡是断言「源码里不该有 X」的闸，**必须先剥注释**。

---

## 六、下一个人必须知道的环境事实

- **服务实例会换**。本轮前半在服务的是 `cretas-backend-green`(10020)，后半变成
  `cretas-backend`(10010)。我第一次用写死的服务名查 jar，**连阳性对照都返回 0**，读数当场作废。
  ⇒ 查制品前**先问「现在在服务的是哪个实例」**（`ss -lntp | grep :100` 反查 pid）。
- **`--base-sha` 是【起点】不是【本次提交】**。传成合并提交自己 → `git diff BASE HEAD` 为空 →
  `RELEASE_SELECTION=none`，跑完一次完整构建**却什么都没发**，而且 `BUILD SUCCESS`。
  抓到它的是 **`RELEASE_FINAL_STATUS` 一次都没出现**。
- **CI 产出了制品反而会让部署失败**。`CI_ARTIFACT_CANDIDATE` 存在时脚本会走
  10.66.66.1 那条取件链路；该链路不通 → 整轮 exit 1（本地构建其实已成功）。
  逃生门是脚本自带的 **`--no-prefer-ci-artifact`**（别去动 VPN/SSH）。
  ⚠️ 上一轮之所以顺利，是因为 CI **还没产出制品**，压根没碰那条链路 —— 不是环境变好了。
- **部署锁要用 Windows 原生方式判活**。MSYS 的 `ps -ef` 看不全原生进程树：
  我据它判定「持锁进程已退出」，实际**还活着**。用
  `Get-CimInstance Win32_Process -Filter "ProcessId=N"` 能直接拿到命令行，一眼看出是谁。
  ⛔ 别清别人的 prod 部署锁 —— 那就是 5-30 事故的形态。
- **别的 session 从 main 部署会顺带把你的改动带上线**。#2674 就是这么上去的（prod = main 的设计效果）。
  ⇒ 部署失败时**先去运行中的 jar 里找标记**，可能根本不用再发一次。
- **`/tmp` 在 Git Bash 与 Python 之间不是同一个目录**。curl 写进去、python 读不到。
  跨工具传文件用 scratchpad 的绝对路径。
- **含中文/转义的 JSON 不要经 shell 传给 curl**：直接落文件 + `--data-binary @file`。
- `frontend/CretasFoodTrace/src/types/navigation.ts` 是 **CRLF 文件**（同目录多数是 LF）。
  按 LF 打补丁会静默 miss（不报错，只是 `count==0`）。改前先测该文件自己的行尾。

---

## 七、方法上的教训（本轮实际拦住我的）

**共同形态：从代码推因果，而否定它的东西在我没读的那几行、或没查的那张表里。**
一轮里犯了 4 次，每次都改变了结论：

| # | 我报的 | 真相 | 否定它的东西在哪 |
|---|---|---|---|
| 1 | 「第一次部署什么都没发出去」 | 它**成功了** | 我读的是一份**还在写入中**的日志（122 行，最终 362 行） |
| 2 | 「采购审批按钮已死 25 天」 | 端点确实是 410，但**卡片从来不出现**，够不到 | 那张表的行数 |
| 3 | 「自动盖章导致财审模块没有输入」 | 模块空是因为**全库仅 9 张采购单且没一张属于配了链的工厂** | **数据库**，我一次没查 |
| 4 | 「价格未知的采购单会被当成 ¥0 跳过财务审批」 | `submitOrder` 早有 `PURCHASE_PRICE_REQUIRED`(422) | **同一个方法里**，被我 `grep -B4` 过滤掉的那一行 |

**判据**：
- 判「某个保护不存在」**必须读全方法**，不能用关键词切片 ——
  **缺失无法用 grep 证明**，grep 只能证明存在。
- 判「某功能为什么没有输入」**先数上游那张表的行数**，再去读代码。
- 读日志前先确认**进程已经结束**（背景任务的日志会边跑边长）。
- 交接件里的「已定位 X」当成**候选之一**，先问它的证据是什么。

---

## 八、未做 / 仍开放

- **Sheet 409 的原始文案** —— 五个候选里到底是哪一条（见四）。
- **第 5 类 409**（包装规格换算不一致）未处理，缺复现实例。
- **RN 改动需要 app 构建才能到用户手上** —— `release-cretas.sh` 只发 Java + web-admin。
  本轮 #2664 / #2673 / #2674 的 RN 侧都还在仓里，未随后端上线。
- **一条既有 tsc 错误**：`src/__tests__/integration/screens/ProcessTaskListScreen.test.tsx(227,53)`
  `totalPages` 不在类型里。与本轮无关，一直存在。
- **`java-build-test` 的选择器很窄**（`*RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest`，
  54 条）。新写的服务层测试**编译进 CI 但不会被执行** —— 别拿 CI 绿当那些测试的证据，
  用 `release-cretas.sh --tests` 把它挂进制品背书。
