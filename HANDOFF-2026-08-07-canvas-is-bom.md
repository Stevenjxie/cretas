# 画布即 BOM（方案 B）—— 阶段 1/5/2/4 + 3-1 完成，阶段 3 剩两项

**日期**: 2026-08-07
**本地 main**: `d4ac283780 merge: 画布即 BOM 阶段 3-1(投入明细进工艺定义)`
**设计定稿**: `docs/superpowers/specs/2026-08-07-canvas-is-bom-design.md`（分支 `codex/claude-bom-canvas-spec`）
**GitHub 仍不可用** → 全部走本地 main 汇合（见 `.claude/rules/worktree-and-main-only-deploy.md` 的「🔌 GitHub 不可用时」）
**未部署 prod**（owner 未下指令）

---

## 一、各阶段 commit

| 阶段 | commit | 分支 | 一句话 |
|---|---|---|---|
| 1 出口关死 | `e9b7b867bd` | `codex/claude-canvas-bom-p1` | 删掉画布跳去 BOM 页的最后两个出口 + `goToBomManagement` |
| 5 删旧 BOM 页 | `1b29eda181` | `codex/claude-canvas-bom-p5` | 51 文件；老地址改 redirect 到画布 |
| 2 副产改真实节点 | `58a4f55b8a` | `codex/claude-canvas-bom-p2` | 浮层 → 工序派生的真实产出节点 |
| 4 画布 AI 扩能 | `ecbe6ec1b8` | `codex/claude-canvas-bom-p4` | 两条 AI 路径 + 五条硬约束 |
| **3-1** 投入明细进定义 | `e651628447` | `codex/claude-canvas-bom-p3` | 改克数 → 换 revisionHash → 新工艺版本 |
| **合并** | `077f049ae7` + `d4ac283780` | `main`（worktree `cretas-rest-ai`） | — |

分支是**链式**的（p1 ← p5 ← p2 ← p4），合 p4 一次即全部合入。

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
| `vitest` 全量（合后 main） | 350 files / 2675 passed / 5 skipped / **0 failed** |
| 后端 `MaterialBindingsInRevisionHashTest`（3-1 机制证明） | 4 用例全绿（含阴性对照：hash 必须覆盖整个 data，而不是只算 id/kind） |
| 前端 `materialBindingsHydration.spec.ts` | 8 用例全绿 |
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

### 🔴 3-2 / 3-3 未做，且**卡在一个设计缺口上**（不是实现难度）

- **删除 `WORKFLOW_ACTIVE_BOM_REQUIRED` 前置**
  消费点：`service/bom/WorkflowBomSynchronizationService.java:54`（preflight）、
  `BomRecipeServiceImpl.java:682-686`（同步时直接抛）、
  `workflow/workflowApi.ts:22`、`ProductProcessWorkflowEditor.vue:3971`。
- **`synchronizeActiveBomToWorkflowRevision` 降级为「从画布投影出 BOM 快照」**
  （`BomRecipeService.java:54` / `BomRecipeServiceImpl.java:663` / `WorkflowBomSynchronizationService.java:323`）

**卡点（查证过，不是猜的）**：要让「没有 ACTIVE BOM 也能发布」成立，就得从画布投影出一份
**能激活**的 BOM。而激活要过两道闸（`BomRecipeServiceImpl.java:403-411`，注释里写着
「两道闸都得豁免……一个规则两处承载」）：

```
validateActivatableItems(member);                        // 至少 1 条明细
readinessService.requireBomCompleteForActivation(...);   // 至少配置一项主原料
```

⛔ **「主料用量」这块数据画布上根本没有** —— 画布只声明「可投入哪些物料」，
用量由生产计划固定的 BOM 限定（`WorkflowProcessNode.vue` 的说明文字就是这么写的：
「Workflow 只声明可投入物料；主料和替代料由生产计划固定的 BOM 自动限定」）。

所以「画布是权威、BOM 是投影」这句话在**主料用量**这一维上目前不成立：投影不出来的东西，
删掉前置也变不出来。这是设计缺口，要么
 (a) 主料用量也搬进画布（那是另一次口径变更，得你拍板），要么
 (b) 为「投影出来的 BOM」放宽这两道激活闸（但那会把 2026-08-05 那份
     「主料用量为空的 ACTIVE BOM」合法化 —— 定稿正是要消灭它）。

**我没有替你选**，因为两条路都改变已拍板的口径（属于定稿 §5 的停手条件）。

### 硬闸（3-1 已跑，结果在这里；做 3-2/3-3 时照样再跑一次）

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

### 硬闸基线（改前快照，做 3-2/3-3 时逐条比对）

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

- **阶段 3-2 / 3-3**（见 §三的「卡点」）—— 需要 owner 在两条路里选一条，
  两条都改变已拍板口径，属定稿 §5 的停手条件，未替 owner 选。
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
