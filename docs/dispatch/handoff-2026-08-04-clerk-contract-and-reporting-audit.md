# 交接 — 单位契约钉死 + 报工三面查清 + 悬空引用检测（2026-08-04）

**状态**: ✅ 无待合并、无待部署、无阻塞。**本轮 4 个 PR 全部零行为改动**，线上 jar 未变。
**上一份**: `docs/dispatch/handoff-2026-08-04-unit-f006-closeout.md`（单位口径治理 + F006 双拓扑闭环）

---

## 0. 一句话现状

线上 Java jar 仍是 **`fb0ca03d0b`**，与 main **无行为差异**（本轮全部是注释/测试/脚本）。
下轮 `--base-sha` **仍用 `fb0ca03d0b`**，别拿本轮任何 merge commit —— 它们从没部署过，
而且部不部署对线上没区别。四个服务全 active。

---

## 1. 本轮 4 个 PR（全部 MERGED，全部零行为改动）

| PR | 内容 | 判据 |
|---|---|---|
| #2262 | 上一轮的交接文档（接手时发现它还 OPEN，而交接开头写着「无待合并」） | 已合并 |
| #2265 | clerk 路径 `RawInput` 单位契约写进三处承载点 + `ClerkRawInputUnitContractTest` | 变异做实：改成 g 特判 `movePointRight(3)` → `expected: 5 but was: 5000` 红 → 回退复绿 |
| #2271 | **修正 #2265 两处失实**（见 §4） | 124 tests / 0 failures |
| #2272 | `scripts/audit/dangling-polymorphic-refs.sql` 悬空多态引用检测 + 判据修正 | 两次实际对 prod 跑通 |

---

## 2. 接手时核查上一轮交接的结果

**代码面全部属实**：12 个 PR 全 MERGED；`fb0ca03d0b..origin/main` 在 `backend/java` 下
只有 README 一个文档改动 → 「无未上线代码差异」成立。

**prod §4 四条判据全中**：单位混写 1（羊排合法包装单位）/ 个67·只3·件2 且 pcs 无行 /
box 8·case 7 且中文无行 / `BY_PRODUCT` ACTIVE 1。

**两处出入已补掉**：
- 交接自己的文档 PR #2262 当时还 OPEN（已合并）
- §5.2 主仓 `web-admin/node_modules/playwright` 被掏空（已 `npm install --prefer-offline
  --legacy-peer-deps` 修复，`require('playwright')` 验证通过。`cretas-bom-dup` 绕法**可以不用了**）

---

## 3. 🔴 报工到底有几个面（本轮最有价值的事实，交接文档里没有）

**三个面，只有两个在用，扣料只在其中一个。**

| 端点 | 谁在调 | 扣不扣原料 |
|---|---|---|
| `POST .../process-sheet/row` | **web-admin 工序单**（`src/api/processSheet.ts`） | ✅ **扣料只走这条** |
| `POST .../work-reporting/reports` | **RN 手机端**操作员（`OperatorAssignedProcessScreen` → `useReportWorkflow` → `workReportingApiClient`） | ❌ **不扣** |
| `POST .../process-entry` | **零前端调用** | —（无人走） |

**手机端不扣料的判据**（不是单次 grep）：`WorkReportingServiceImpl` 只注入
`ProductionReport` / `BatchWorkSession` / `ProductionBatch` / `User` / `EventPublisher` 五个依赖，
`MaterialConsumption`·`MaterialBatch`·`usedQuantity` **命中均为 0**；它发的 `BatchCompletedEvent`
的**三个真实监听者**（`BomYieldSuggestionEventListener` / `SupplyChainOrchestrator` /
`ProductionDataCollectorService`）同样 0 命中。
⚠️ 提到该事件类名的文件有 **10 个**且其中几个大量碰物料 —— **必须先筛出真带 `@EventListener` 的**再看。

它收的字段是产量/良品/次品/工时/人数/照片，**一个投料字段都没有** —— 是计件与效率文档，不是物料账。

### ⚠️ 手机端报工在 prod 从未发生过一次

- `batch_work_sessions` = **0 行**（RN 签到签退表）
- `production_reports` 全库 **6 行**，全带 `cost_category`（`SEASONING`/`LABOR`）且
  `reporter_name`·`schema_id` 全空 —— 那是工序单链路 `writeSeasoningReport`/`writeLaborReport`
  的签名；**RN 必设的那两个字段全空 → 没有一条来自手机端**
- `work_process_tasks` 有 **18 条**，但 **`assigned_to` 全部为空**

RN 那个屏走 `listAssignedWorkProcessTasks`（**按「指派给我」查**）→ 没有任何任务指派到人 →
**每个操作员看到的都是空列表，手机端无从开始报工**。

📌 **这是下一轮最值得问客户的一件事**：现场操作员到底有没有在用手机报工？
如果本来就没投用 → 不是缺陷；如果试过但看不到任务 → 去查 web-admin 建工序任务时
有没有「指派给谁」这个入口、它写不写 `assigned_to`。

---

## 4. 文员录入（web-admin 工序单）审计：不变量全过

| 检查 | 结果 |
|---|---|
| 负库存批次 | **0** ✅ |
| 重复消耗行（同批次+同来源+同量） | **0** ✅ |
| 消耗指向跨厂批次 | **0** ✅ |
| 已完成生产批次却零消耗（幻库存） | **0** ✅（唯二零消耗的是 1 个 IN_PROGRESS、1 个 CANCELLED） |
| 单位维度 | **0 缺陷**（`g` 批次 4 条消耗全一致；全库尺度异常扫描 0 行） |
| 未结算消耗 9 条 | **正常** —— 批次与计划均 `IN_PROGRESS`，属设计内「小结才扣」；7 条 `production_plan_id` 为空也是刻意的（防成本双计） |

**在查过的范围内，文员录入没有问题。** ⛔ 但没审：并发/重复提交、审批流、工时与计件算法、
次品返工、离线同步、草稿与幂等。

---

## 5. 🔴 271 条悬空多态引用（本轮挖出的真问题，非文员录入造成）

`material_batches.source_doc_id` 是 varchar 的**多态外键**（按 `source_doc_type` 指向不同的表），
**无法加 FK**，所以没有任何东西阻止它指向已删除的行。

| source_doc_type | 总数 | 悬空 |
|---|---|---|
| `PRODUCTION_BATCH` | 259 | **247** |
| `MATERIAL_REQUISITION` | 28 | **24** |
| `OPENING` | 32 | 不适用（值是标签 `LSM-REBUILD-OPENING-20260801`，非行 id） |

### 影响面（已验，不是推测）

`OrderCostBreakdownService#traceCost:621-624` 在上游查不到消耗时**优雅降级成叶子**，
返回该消耗行自身的 `totalCost`：

- **不崩、不返回 null** —— 不是线上故障
- **成本总额正确**（投料时按批次固化的 `unitPrice` 已算好）
- **坏的是成本分桶**（上游那段拆不出 raw/labor/seasoning）**与批次溯源链**

### 是一次性事件，已停止

分水岭 = 现存最早的 `production_batches`（id 10623，**2026-08-02 19:44:16**）：
07-30~08-01 全悬空 / 08-02 当天 17 条中 9 条悬空（**均在 19:44 前**）/ **08-03 起 4 条 0 悬空**。
界限干净 → **一次性删除，没有活动中的触发条件。**

### 谁删的：追不出来，但窗口卡到 9.5 小时

| 时刻 | 依据 |
|---|---|
| 08-02 **10:10** | `HANDOFF-2026-08-02-factory-sweep.md` 写于此时，文中记录当时有 **1666 条**生产批次 |
| 08-02 **19:44** | 现存最早的 `production_batches`，它及之后全部幸存 |

窗口内**无 flyway 迁移执行**（V48 在 10:00:44 窗口前，V49 在次日）。四条留痕途径全排除：
应用代码 `productionBatchRepository.delete*` **全仓 0 处** / flyway 无 `DELETE FROM production_batches`
（V46 只是 `NOT EXISTS` **读**它）/ 清场台账 `backup_lsm_cleanup_20260801` 记了 18 类对象
**恰恰没有 production_batches** / git log 无相关 commit。

→ **一次库外手工 psql，零痕迹。⛔ 别再追了，证据链到此为止，再花时间只能是猜。**

> 讽刺：那份 8/2 10:10 的交接把这 1666 条明确列在「**查过但不是缺陷的（别再重复查）**、
> 不需要迁移」一节里。写完这句话之后几小时内，它们被删了。

---

## 6. ⏸ 刻意没做的 3 件

1. **包装单位批次进不了可投量** —— 上一轮 §5.1 原样保留。技术阻塞真实
   （`kgToStorageQuantity` 对「箱」原样返回，放开会超扣 10 倍），要连 `UnitContractService#convert`
   反算 + `ProcessSheetServiceImpl` 消费路径一起验，独立一轮的量。
   **影响面查实：全库仅 1 条**（`MT-20260716-3809` 羊排，箱/档案 kg）且 EXPIRED。
   ⚠️ 别被「18 条非质量单位批次」吓到 —— 其余 17 条全是包材（成品盒/外箱/封膜/打包盒/吸塑盒），
   **档案单位 == 批次单位**，本就不需要跨量纲换算，`planNative` 字面匹配已经对了。
2. **271 条悬空不补数据** —— 不崩、成本总额对，补不补取决于六膳门与 F006 要不要完整溯源，
   属业务判断。⚠️ §4 里 F006 七月还有 3 条 **100 kg**（10 万 g）在制品仍 AVAILABLE 挂着悬空来源。
3. **不给 `production_batches` 加删除防护** —— 加 FK 做不到（多态列）；触发器管不住
   （删除方在库外且应用侧无删除路径）。**对多态引用，周期性检测是唯一可行手段** → 即 #2272。

---

## 7. 🔴 本轮反复应验的判据（都是我自己踩出来的）

1. 🔴 **同一个毛病栽了三次：搜索面太窄就下结论。**
   - 把 CSS 类名 `.process-entry-layout` / 注释里的分支名 `fix/process-entry-cache-and-blend-cost`
     当成接口调用 → 判定一条死路径在服役
   - 只在 `production-plans` 前缀下找手机端 → `work-reporting` 挂在**另一个顶级前缀**，整个漏掉
     （**Steve 直接点破**：「我们报工就在生产计划里只有一个入口，手机端应该是那个吧」——他是对的）
   - 用 `equals(user.getFactoryId())` 搜租户守卫 → **假阴性**，真实写法是
     `urlFactoryId.equals(tokenFactoryId)`（变量名不同），差点报一个不存在的越权洞

   判据=**判「某后端路径在不在用」要去前端 API 层 grep 真实端点串**
   （web-admin `src/api/`、RN `src/services/api/`）；**按功能搜别按前缀搜**；
   **grep 报 0 命中时先确认模式没写窄**。

2. 🔴 **判「A 漏了 B 做的事」之前先读 A 的入参契约 —— 缺字段 ≠ 缺逻辑。**
   我从两个调用点就断定「clerk 漏了单位换算」，还做成选项推给 Steve 选；选完才读 DTO，
   发现 `RawInput` **压根没有 unit 字段**，没东西可折也就没东西可 fail on —— **那个选项根本不成立**。
   调用点只说明「没做」，只有入参能说明「有没有东西可做」。**给选项前先确认每个选项可实现。**

3. 🔴 **我自己写进脚本的判据，把我自己带沟里了。**
   #2272 §3 原注释写「出现在当月 = 还在持续产生」——**错的**。那列是材料批次的**创建时间**，
   不是**变成悬空的时刻**；一次性删除会让新旧同时变悬空。已加 §3b（用现存表的时间下界当分水岭
   按天对比）并给 §3 打 ⛔ 标注。**判据本身也要被验证，不能写完就当真。**

4. **宣布一个改动「防住了什么」之前，先确认被防的那条路径有人走。**
   #2265 我说它「防止有人补换算把账扣烂」，前提是那条路径在服役 —— 它不在，价值被我说高了。

5. **「提到 X 的文件」≠「真的用 X 的文件」。** 提到 `BatchCompletedEvent` 的有 10 个文件、
   几个大量碰物料，但真正带 `@EventListener` 的只有 3 个且全部 0 命中。先筛承载点再看数字。

6. **散文与断言可以不一致，要两边都查。** #2265 的注释说「补换算会**少扣** 1000 倍」，
   而同一条 commit message 里引的变异结果是**多扣**（`5000`）。测试两个方向的断言一直都在，
   **错的只是散文**（#2271 已修）。

---

## 8. 环境（与上一份一致，补充两点）

- prod: `root@47.100.235.168`，库 `cretas_prod_db`，`sudo -u postgres psql`
- Web: `https://admin.cretaceousfuture.com`（网关 `139.196.165.140`，**不是**主服务器）
- F006 账号 `f006_admin` / `123456`
- ⚠️ 密集 ssh 会触发封禁。判据：**GitHub 通但两台 Cretas 都不通 = 我被封**，一测就知道
- ✅ **主仓 playwright 已修好**，`require('playwright')` 可直接用，不必再指向 `cretas-bom-dup`
- 🔴 **发布三步照抄上一份 §7**，`--tests` 必须用 CI 选择器 `'*RepositoryQueryValidationTest'`

---

## 9. 相关文档

- `docs/dispatch/handoff-2026-08-04-unit-f006-closeout.md` — 上一轮（单位口径治理 + F006 闭环）
- `scripts/audit/dangling-polymorphic-refs.sql` — 本轮新增，**§3b 才是判「是否还在新增」的那节**
- `HANDOFF-2026-08-02-factory-sweep.md` — 其「四、查过但不是缺陷的」记录了被删前的 1666 条
