# 画布即 BOM（方案 B）—— 阶段 1/5/2/4 + 3-1/3-3 完成，3-2 只剩一处判读

**日期**: 2026-08-07
**本地 main**: `401e48c90d merge: 画布即 BOM 阶段 3-3(删 ACTIVE_BOM_REQUIRED 前置 + 从画布投影 BOM)`
**设计定稿**: `docs/superpowers/specs/2026-08-07-canvas-is-bom-design.md`（分支 `codex/claude-bom-canvas-spec`）
**GitHub 仍不可用** → 全部走本地 main 汇合（见 `.claude/rules/worktree-and-main-only-deploy.md` 的「🔌 GitHub 不可用时」）
**✅ 已部署 prod**（2026-08-08，owner 明确指令）—— 离线部署台账见 §八

---

## 一、各阶段 commit

| 阶段 | commit | 分支 | 一句话 |
|---|---|---|---|
| 1 出口关死 | `e9b7b867bd` | `codex/claude-canvas-bom-p1` | 删掉画布跳去 BOM 页的最后两个出口 + `goToBomManagement` |
| 5 删旧 BOM 页 | `1b29eda181` | `codex/claude-canvas-bom-p5` | 51 文件；老地址改 redirect 到画布 |
| 2 副产改真实节点 | `58a4f55b8a` | `codex/claude-canvas-bom-p2` | 浮层 → 工序派生的真实产出节点 |
| 4 画布 AI 扩能 | `ecbe6ec1b8` | `codex/claude-canvas-bom-p4` | 两条 AI 路径 + 五条硬约束 |
| **3-1** 投入明细进定义 | `e651628447` | `codex/claude-canvas-bom-p3` | 改克数 → 换 revisionHash → 新工艺版本 |
| **3-3** 删前置 + 投影 | `f4fe42fc35` | `codex/claude-canvas-bom-p3` | 没有生效 BOM 时从画布投影一份，不再拦发布 |
| **合并** | `077f049ae7` → `d4ac283780` → `401e48c90d` | `main`（worktree `cretas-rest-ai`） | — |

分支是**链式**的（p1 ← p5 ← p2 ← p4 ← p3），合 p3 一次即全部合入。

---

## 二、真机验证的具体数字

### 阶段 2（F006，SSH 隧道打 prod 后端）

产品 `SOP-20260731-01-拓扑成品E`，revision 258（DRAFT）：

```
点「+ 副产」→ 副产 Cell 出现（标「副」·琥珀色·待绑定）
下拉里正好 1 项  YL113 验收-副产-肥油   ← 库里唯一 is_byproduct=true 的物料
选中 → 已绑定 → 回查 prod 库：
  节点  material:output:1786112587055 · SEMI_FINISHED · isByproduct=true
        skuId=RMT_1785513705730   ← 物料表 id，不是产品 id
  端口  output:1786112587055 · OUTPUT · kg
  边    工序 → 副产，sourceHandle=output:1786112587055（真实端口）
删除时确认框自报「同时移除 1 条相连连线」→ 边确实进了拓扑
```

**验收数据已清干净**：rev 258 回到 5 节点，F006 全库 `isByproduct` 节点 = 0。

### 各阶段闸

| 项 | 结果 |
|---|---|
| `vue-tsc -b --force`（合后 main） | 0 error |
| `vitest` 全量（合后 main） | 350 files / 2676 passed / 5 skipped / **0 failed** |
| 后端 `MaterialBindingsInRevisionHashTest`（3-1 机制证明） | 4 用例全绿（含阴性对照：hash 必须覆盖整个 data，而不是只算 id/kind） |
| 前端 `materialBindingsHydration.spec.ts` | 8 用例全绿 |
| 后端 `WorkflowBomProjectionTest`（3-3） | 7 用例全绿（含「绝不编主料用量」「跳过没绑 SKU 的节点」「投影是纯函数式，不查库」） |
| 后端基线比对（宽 scope：`*Bom*Test,ProductProcessWorkflow*Test,*Readiness*Test`） | 基线 **59** 失败 / 本次 **59** 失败，**逐条同名一致，新增 0**（该 scope 在 origin/main 上本来就有 23 Failures + 36 Errors —— 比计数没有意义，必须比集合） |
| 后端 `ProductProcessWorkflowConfigToolBomFieldsTest` | 17 用例全绿 |
| 前端 `seasoningProcessCategory.spec.ts` | 12 用例全绿 |
| 后端基线比对（`ProductProcessWorkflow*Test,CanvasAI*Test`） | 失败集合**逐条同名一致，新增 0**（3 条在 origin/main 上本来就红） |
| `FlywayVersionUniquenessTest`（合后 main） | 绿（本轮未新增迁移） |

---

## 三、阶段 3（版本合一）的现状与**已探明的关键事实**

### ✅ 最大的风险源已排除

`revisionHash` 由 `WorkflowRevisionSnapshotService#hash` 算，输入是
`factoryId / productTypeId / definitionVersion / schemaVersion / **nodesJson** / edgesJson / viewportJson`。

**`materialBindings` 挂在节点 data 里 ⇒ 落进 `nodesJson` ⇒ 自动进 hash。**

所以设计定稿里的「`materialBindings` 纳入 revisionHash 计算」**不需要改哈希公式**，
既有 revision 的 `nodesJson` 不变 ⇒ 它们的 hash 也不会变 ⇒ 已排产计划钉的
`selected_workflow_revision_hash` 不会失配。

> 旁证：`hash(revision)` 已有**三路回落**（storedOrderHash / legacyCanonicalHash / canonicalHash），
> 说明历史上换过哈希口径且是靠"接受已存的那个"兜住的。

### ✅ 3-1 已做：投入明细进工艺定义（commit `e651628447`）

`ProcessNodeData` 新增 `materialBindings[]` + `injectionAmount`；加载 BOM 时把
**权威数值**（`dosagePerKgG` / `subsequentPotRatio`，不是展示串 `dosageText`）
hydrate 进工序节点 data。改克数 → 改 nodesJson → 换 revisionHash → **新工艺版本**。

⛔ hydration 走的是**加载期幂等投影**，不是 `mutate()`：
后者会置 dirty，用户只是**打开一张图**就变成「有未保存改动」，保存后造出一个与旧版
等价的新版本 —— 版本线会因为「看了一眼」而增长，跟方案 B 正好相反。
真机实测：同一张图连开两次，「保存草稿」两次都是 disabled。

**关于「去掉 stripBomOverlay」这句的判读**（我自己拍的，理由写在 commit 里）：
定稿的两句话可以同时成立 —— strip 原本的**理由**是「改克数只动 BOM 草稿、不产生新版本」，
方案 B 推翻了这条理由，所以它**不再承载任何数据**；但它的**机制**要留，因为辅料/包材 cell
仍是派生展示物，持久化等于往图里塞重复数据（加载时还会重新派生）。数据本身已搬到真实
工序节点上、已进定义、已进 hash。若你的读法不同，改的是这一处判读，不影响 3-1 的其余部分。

### ✅ 3-3 已做：删掉 `WORKFLOW_ACTIVE_BOM_REQUIRED`，改为从画布投影（commit `f4fe42fc35`）

没有生效 BOM 时不再拦发布，改为 `projectActiveBomFromRevision`：把画布上绑了 SKU 的
`RAW_MATERIAL` 节点投影成 BOM 的 RAW 明细行，创建并激活。

**只在「该产品还没有任何 ACTIVE BOM」时触发** ⇒ 没有既有数据可被覆盖 ⇒
没有 BOM 就没有生产计划钉过它 ⇒ 已排产批次的快照不可能受影响。
有 ACTIVE BOM 时走的仍是原来的「克隆 + 重绑」，一个字没改。

⛔ **投影不编主料用量**。2026-08-05 那份「主料用量为空的 ACTIVE BOM」之所以有害，
不是因为空，**是因为它是编的**（有人为了让画布能发布手工凑了一份）。空用量在这条
口径下是合法且诚实的表达 —— 有一条测试专门钉它（`neverInventsAMainMaterialQuantity`）。

同理只投影 RAW：辅料/包材/副产各自已有写入路径，用户在画布上配好后照常落库。
这里只负责把「BOM 存在」这件事补上，不去猜用户还没配的东西。

**改了三处承载点**（一个规则两处承载，只改一处 = 后端通了前端仍被挡）：
`BomRecipeServiceImpl#synchronizeActiveBomToWorkflowRevision`（抛 → 投影）、
`WorkflowBomSynchronizationService#preflight`（USER_INPUT_REQUIRED → AUTO_MIGRATABLE）、
前端 `workflowApi.ts` + `ProductProcessWorkflowEditor.vue` 的已知错误码清单（删死条目）。

**撞到的闸（翻转，不删除）**：`workflowApi.spec.ts` 两处断言列着旧错误码清单。
原意图「发布路径要显式列出它自己处理的失败态」保留 —— 不是删掉那行了事，而是
**加了反向断言钉住「它不许再回到清单里」**，并做了阴性对照（把那行加回去 → 当场变红）。

### ⏳ 3-2 唯一剩项：`stripBomOverlay` 的判读（已判，未再动代码）

见上一节的判读：strip 的**理由**没了（改克数现在就该产生新版本），但**机制**要留
（辅料/包材 cell 仍是派生展示物，持久化 = 往图里塞重复数据）。数据本身已搬到真实
工序节点、已进定义、已进 hash，所以 strip 不再承载任何数据。
**若 owner 的读法是「连机制一起去掉、把浮层节点也持久化进图」**，那是另一种图结构，
需要先决定辅料/包材 cell 在图里的身份（子节点？端口？），再动手。

### 📌 一条值得记的判据（3-3 差点被我自己判死）

我先前在这份文档里写过「3-3 卡在设计缺口：激活 BOM 要求主料用量，而画布上没有主料用量，
所以投影不出可激活的 BOM」，并据此停手。**那是错的**，错在**读了提示语没读代码**——
去查实际判据后：

| 闸 | 提示语 | 实际判什么 |
|---|---|---|
| `requireBomCompleteForActivation` | 「请至少配置一项主原料后再激活」 | `ProductConfigurationReadinessService.java:241` **只数 `rawCount > 0`（行数）**，不看 `standard_quantity` |
| `validateActivatableItems` | 「请至少添加一条原辅料或包材明细」 | `BomRecipeServiceImpl.java:1657-1662` 注释自己写着「**原料与工序辅料的 BOM 行表达资格/关系，固定用量可留空**；包材是确定性消耗，必须有正数用量」 |

也就是说：**主料用量本来就允许为空**，这是既有口径（主料按报工实际重量走）。
投影一份可激活的 BOM 所需的东西画布上全都有：

| BOM 行 | 画布来源 | 用量 |
|---|---|---|
| RAW | `RAW_MATERIAL` 节点的 skuId | 可留空（合法） |
| AUXILIARY | 工序节点的 `materialBindings`（3-1 已进定义） | `dosagePerKgG` |
| PACKAGING | 成品节点的包材 cell | 必须 > 0，画布上本来就要求填 |
| BYPRODUCT | `isByproduct` 产出节点（阶段 2 已是真实节点） | 报工时填 |

📌 判据（值得记）：**判一道闸要不要满足，去 grep 它实际比较的字段，不要读它的提示文案。**
提示语是写给用户的近似说法，和代码里的判据可以差很远 —— 这次就差了「有没有行」vs「有没有值」。

### 硬闸（3-1 已跑，结果在这里）

**3-1 的实测结果**（prod，F006 只读打开两次）：

| 对象 | 改前 vs 改后 |
|---|---|
| 6 个被生产计划钉住的 revision（239/240/242/243/248/250）的 `md5(nodes_json)` | **逐条逐字相同** |
| 同上的 `revision_hash` | **相同** |
| 9 条 `production_plans` 全字段 | **逐字相同**（含 2 条六膳门在产计划） |
| 含 `materialBindings` 的 revision 数 | 0 → 0（开两次图，写入 0） |
| 「保存草稿」按钮（打开后） | 两次都是 disabled（dirty=false） |

⚠️ **已知验证缺口**：调料绑定数据**全在 LIUSHANMEN**（F006 的 BOM 已被
`V20261029_71` 清空，0 个 recipe）。LIUSHANMEN 是真客户只读 —— 拿真客户当
「打开会不会写」的小白鼠正好本末倒置，所以没在其上验。「bindings 非空」那条路径
目前由后端 `MaterialBindingsInRevisionHashTest`（4 用例，含阴性对照）+ 前端
`materialBindingsHydration.spec.ts`（8 用例）覆盖，**未做真机**。
要补真机，得先在 F006 建一份带调料的 BOM。

### 硬闸基线（改前快照；将来再动版本合一时照此逐条比对）

`production_plans` 共 9 条，导出在
`D:\Temp\claude\...\scratchpad\p3-plans-before.txt`，也抄一份在这里防丢：

| plan id | factory | status | bom_recipe | bom_ver | rev_id | rev_hash(前 16) |
|---|---|---|---|---|---|---|
| 225d9a92… | F006 | CANCELLED | 6a71111e… | 1 | 242 | `5af167b43843b355` |
| 2b848faf… | F006 | COMPLETED | 1a743e08… | 2 | 243 | `c8a4c0a58f3cf21f` |
| **2d0910d1…** | **LIUSHANMEN** | **IN_PROGRESS** | 5702398e… | 1 | 250 | `696f0e6000d711d0` |
| 84e842a6… | F006 | PENDING | 7fc19ae6… | 1 | 248 | `df4814137134de51` |
| 96233f1f… | F006 | COMPLETED | 1a743e08… | 2 | 243 | `c8a4c0a58f3cf21f` |
| a875a4c6… | F006 | COMPLETED | 7fc19ae6… | 1 | 248 | `df4814137134de51` |
| d3d4f619… | F006 | IN_PROGRESS | 7fc19ae6… | 1 | 248 | `df4814137134de51` |
| **e8861f79…** | **LIUSHANMEN** | **IN_PROGRESS** | 7f7e5705… | 1 | 239 | `5e2e7a3f60d22e21` |
| fe3548a3… | F006 | COMPLETED | 92280fd9… | 1 | 240 | `b9e79a4af88d331f` |

⛔ 加粗那两条是**真客户在产计划**。定稿的停手条件：快照逐条比对出现**任何**差异 → 立刻停，不要试图解释掉。

---

## 四、本轮查证到的事实（省下次的力气）

| 事实 | 数字 | 为什么重要 |
|---|---|---|
| `bom_recipe_items` 里 `material_category='BYPRODUCT'` | **0 条** | 阶段 2 因此可以直接删浮层，零迁移 |
| 全平台 `bom_recipe_items` / `bom_recipes` | 18 / 9 | BOM 面非常小，阶段 3 的比对成本低 |
| `raw_material_types` 勾 `is_byproduct` | 1 个（F006 `验收-副产-肥油` YL113） | 唯一可用的副产验收样本 |
| `product_process_workflow_revisions` | 28 | — |
| **副产是物料不是产品 SKU** | `bom_recipe_items.material_type_id → raw_material_types(id)` 硬外键 | 我第一版接错了池子，接产品 SKU 会直接违反外键 |

---

## 八、离线部署台账（GitHub 上查不到，必须逐条核销）

按 `.claude/rules/worktree-and-main-only-deploy.md` 的「离线期间的账要写下来」。

| 项 | 值 |
|---|---|
| 部署时间 | 2026-08-08 10:10–10:14 |
| 部署的本地 commit | `22f1abcf2b`（本地 main HEAD，**不在 origin 上**） |
| 部署方式 | detached worktree `../cretas-deploy-0808` + `SKIP_GIT_CHECK=1` |
| web-admin | ✅ 四路哈希一致 `3fd838a6351e93d1…`，756 assets |
| Java 后端 | ✅ 蓝绿 green→blue，版本 `v20260808_101150`，总耗时 225s |
| Python | ❌ 未部署（本轮无 Python 改动） |
| DB 迁移 | ❌ 本轮零新增迁移；部署前 `FlywayVersionUniquenessTest` + `*RepositoryQueryValidationTest` 62 用例全绿 |

**含哪些工作**（本地 main 在 `origin/main` 之上，除本轮画布即 BOM 五个阶段外，
还夹带其它并发 session 已合入 main 的 commit —— 这是「main 是唯一汇合点」的常态，
恢复 GitHub 后各自走 PR 核销）。

**运行中的 jar 已核对确含本次修复**（不只看部署成功）：

```
projectActiveBomFromRevision      1   ← 3-3 投影
WORKFLOW_ACTIVE_BOM_REQUIRED      0   ← 3-3 前置已删
projectRawMaterialItems           1   ← 投影入口
assertSeasoningCategoryAllows     1   ← 阶段 4 类别闸
POT_RATIO_CATEGORIES/INJECTION_   2   ← 类别常量
readableReason                    1   ← 可读拒绝原因
SeasoningProcessCategory.class    1   ← 常量类
```

⚠️ 核对时踩了一次探针坑：先用 `strings | grep '后续锅调料比例'` 得 0，
差点判成「没部署上」。**`strings` 默认只认 ASCII，中文常量当然找不到** ——
换 ASCII 标识符（方法名/常量名）才是有效判据。

**部署前后数据零影响**：6 个被生产计划钉住的 revision + 9 条计划逐字相同。
prod 后端 `HTTP 200`，web-admin 公网 `HTTP 200`。

---

## 五、GitHub 恢复后的推送清单

```bash
# 链式分支，按顺序推（或只推 p3 一个，它含 p1+p5+p2+p4+3-1 全部）
git push origin codex/claude-canvas-bom-p1
git push origin codex/claude-canvas-bom-p5
git push origin codex/claude-canvas-bom-p2
git push origin codex/claude-canvas-bom-p4
git push origin codex/claude-canvas-bom-p3     # 3-1
git push origin codex/claude-bom-canvas-spec   # 设计定稿
# ⛔ 永远不要 git push origin main
```

本地 main 上还有 **56 个未推送的 commit**（`git rev-list --count origin/main..main`），
不止本轮的 —— 恢复后要按分支逐个开 PR，不能拿本地 main 直接推。

---

## 六、未处置项

- **阶段 3-2**：`stripBomOverlay` 的判读我已自行拍板（机制留、理由没了），未再动代码。
  若 owner 的读法是「连机制一起去掉」，那是另一种图结构，需先定辅料/包材 cell 在图里的身份。
- `干式熟成鸡 400g` BOM v3 是 ACTIVE 但主料 `standard_quantity` 为 NULL
  （0 个生产计划用过它）。定稿认为这正是 `WORKFLOW_ACTIVE_BOM_REQUIRED` 逼出来的产物——
  阶段 3 删掉那个前置后应一并清理。**需 owner 确认后再动。**
- `.env.test.example` 指向 F006 账号；LIUSHANMEN 副产 SKU = 0；参考价全 0。
- **未部署 prod。**

---

## 七、本轮踩的坑（判据）

1. **删入口时要连带搜一遍「提到这个入口的文案」** —— 阶段 2 只删了组件，
   三处给用户看的文案还在指已删的副产入口（owner 截图发现）。防呆规则 5 的
   「有帮助的提示变成死路」。已修 + 补闸（剥注释后断言）。
2. **禁某物存在时要剥掉注释再断言，或断言语法形态** —— 本轮踩三次：
   自己写的解释性注释里出现被禁的字符串，把断言打红。
   正确写法见 `byproductOutputNode.spec.ts` 的 `userFacingText()`。
3. **源码字符串闸只能证明「调用存在」，不能证明「行为正确」** ——
   `expect(EDITOR).toContain('selectByproductMaterials(rows)')` 绿了，
   但真机验收才知道筛出来对不对。
4. **探针会自己造出故障** —— 数副产下拉项得 505，以为筛选失效；实际是
   `:teleported="false"` 让全页所有 el-select 的 popper 都在 DOM 里，
   我用全局选择器数了所有下拉。按该 select 自己的 popper 数 = 1，是对的。
5. **heredoc 里的 `\r` `\n` 会被 bash 解释成真的 CR/LF 写进源码** ——
   本轮两次把 `/\r?\n/` 写成了含真实 CR 的坏正则。用 Edit 工具或 `chr(92)` 拼。
6. **Python 逐行过滤重写文件会静默截断** —— 一次把 spec 从 2555 字节写成 1571 字节，
   靠 `git checkout-index` 才捞回来。改文件优先用 Edit 工具。
