# 交接：画布即 BOM（方案 B）已全部完成并上线，附一入多出/多入一出 E2E 结论

你接手的是白垩纪（Cretas）食品溯源系统。上一轮做完了「画布即 BOM」的全部阶段、修了三个真机才暴露的缺陷、部署到了 prod，并跑通了两种拓扑的完整生产 E2E。**这份交接的重点不是「做了什么」，而是「哪些判据能省你的时间」和「哪些坑是我踩过的」。**

---

## 0. 先读这三条，它们决定你怎么干活

### ⛔ GitHub 账号被停用（`Stevenjxie`，403）

push / fetch / PR / `gh` 全断。协议在常驻规则 `.claude/rules/worktree-and-main-only-deploy.md` 的「🔌 GitHub 不可用时」一节：

- **本地 `main` 是唯一汇合点**，被 worktree `C:/Users/Steve/cretas-rest-ai` 检出
- 动它之前**单独跑一次** `git status --porcelain`，看到结果再决定（别把检查和动作写进同一条命令 —— 那样检查等于没做）
- 部署走 `git worktree add --detach $(git rev-parse main)` + `SKIP_GIT_CHECK=1`
- ⛔ **永远不要 `git push origin main`**。恢复后各 feature 分支分别开 PR
- 本地 main 领先 `origin/main` **83 个 commit**（不止本轮的，含其它并发 session）

### 🔴 prod 上跑的不是当前 main HEAD

| 组件 | 部署自 | 说明 |
|---|---|---|
| web-admin | `59e7a3aa53` | 四路哈希一致 |
| Java 后端 | `d89ebc047a` | 蓝绿，含最后一个 BOM 修复 |

main 之后又被别的 session 推进了。**要判断「某修复在不在 prod」，别看 main，去 unzip jar 里 grep**（方法见下面第 4 条判据）。

### 并发环境

26 个 worktree 同时存在，多个 chat / Cursor 在写同一个 repo。commit 用 `git commit -m "..." -- <显式文件>` 或 `./scripts/safe-commit.sh`，**不要**裸 `git add` 后 `git commit`（husky 会顺走别人的文件）。

---

## 1. 已完成并上线的内容

「画布即 BOM」= 工艺版本与 BOM 版本合一。设计定稿在分支 `codex/claude-bom-canvas-spec` 的 `docs/superpowers/specs/2026-08-07-canvas-is-bom-design.md`。

| 阶段 | commit | 内容 |
|---|---|---|
| 1 出口关死 | `e9b7b867bd` | 删掉画布跳去 BOM 页的最后两个出口 |
| 5 删旧 BOM 页 | `1b29eda181` | 51 文件；老地址 `/production/bom*` 改 redirect 到画布 |
| 2 副产改真实节点 | `58a4f55b8a` | 浮层 → 工序派生的真实产出节点（`MaterialNodeData.isByproduct`） |
| 4 画布 AI 扩能 | `ecbe6ec1b8` | 补丁路 + 编译器路，五条硬约束各有反例单测 |
| 3-1 投入明细进定义 | `e651628447` | `materialBindings` 进 `ProcessNodeData` → 进 nodesJson → 进 revisionHash |
| — 接上断链 | `c6c5d74caf` | 用户改完必须置 dirty（否则版本永远不跳，见判据 ①） |
| 3-3 删前置 + 投影 | `f4fe42fc35` | 删 `WORKFLOW_ACTIVE_BOM_REQUIRED`，改从画布投影 BOM |
| — 修空转 + 包材 | `8931a61025` | 见判据 ②③ |
| — 修联产口径 | `449dc1e4a4` | 见判据 ③ |

历史交接细节在 `HANDOFF-2026-08-07-canvas-is-bom.md`（同目录）。

---

## 2. 唯一剩下的产品决策：3-2

定稿写了「去掉序列化路径上的 `stripBomOverlay`」。**我判读为「理由没了、机制要留」并据此实施**：

- strip 原本的理由是「改辅料克数只动 BOM 草稿、不产生新工艺版本」—— 方案 B 推翻了它
- 但 strip 的**机制**要留：辅料/包材 cell 仍是**派生展示物**，持久化 = 往图里塞重复数据（加载时还会重新派生）
- 数据本身已搬到真实工序/成品节点上（`materialBindings` / `packagingBindings`），已进定义、已进 hash

**owner（Steve）说「3-2 先留着」。** 若他改主意要「连机制一起去掉、浮层节点也持久化进图」，那是另一种图结构，动手前要先定：辅料/包材 cell 在图里算什么身份（子节点？端口？），连线怎么连，图校验怎么算。

---

## 3. 🔴 判据（最该继承的部分）

这些都是真机踩出来的，不是理论。

### ① 「不许发生」的断言要限定条件

3-1 我写了闸断言「hydration 一律不置 dirty」。**闸绿着，而功能是断的**：用户改完辅料 → 写 BOM 表 → 重载 → 灌新值 → 图不 dirty → 「保存草稿」灰的 → **新版本永远产生不了**。那条闸保住了「打开不算改动」（对的），却把「改了要算改动」一起挡死，悄无声息。

无条件的 `not.toMatch` 会连该发生的那一半一起挡掉。

### ② 改一条规则，要问「这条链路上还有谁在判同一件事」

不是「我改了几处」。3-3 我改了两处（synchronize + preflight），jar 也核对过含修复，**部署上 prod 之后仍然是空转的** —— 同一条规则的**第三个承载点**藏在一个名字完全不同的 validator 里（`ProductProcessWorkflowCatalogValidator#validateFinishedOutputBoms`），报的错误码也不一样，而且它跑在 BOM 投影**前面** ⇒ 投影永远跑不到。

**只有真机跑一遍才暴露。**

### ③ 判「该不该满足某条件」，去 grep 它实际比较的字段，不要读提示文案

我曾判定 3-3「卡在设计缺口：激活 BOM 要求主料用量，而画布上没有」，据此停手。**那是错的**：

| 闸 | 提示语 | 实际判什么 |
|---|---|---|
| `requireBomCompleteForActivation` | 「请至少配置一项主原料」 | 只数 `rawCount > 0`（**行数**），不看 `standard_quantity` |
| `validateActivatableItems` | 「请至少添加一条明细」 | 注释自己写着「原料与工序辅料的行表达资格/关系，**固定用量可留空**」 |

同型的还有：联产的第二个产出，家族机制说「BY_PRODUCT 不需要自己的明细」，catalog 闸说「每个成品都必须有明细」—— **两处口径正好相反**，导致联产图永远发布不了。

### ④ 判「某修复在不在 prod」要 unzip jar，且判据必须是 ASCII

```bash
ssh root@47.100.235.168 'J=/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar;
  unzip -p $J "BOOT-INF/classes/com/cretas/aims/.../YourClass.class" | strings | grep -c "yourMethodName"'
```

⚠️ `strings` 默认只认 ASCII，**中文常量搜不到**。我用 `grep '后续锅调料比例'` 得 0，差点判成「没部署上」。用方法名/常量名这类 ASCII 标识符。

⚠️ 而且「代码在 jar 里」≠「它跑得到」（见判据 ②）。

### ⑤ 蓝绿部署会切端口，SSH 隧道要跟着改

每次 `deploy-backend.sh --env prod` 都在 10010 ↔ 10020 之间切。隧道指着旧槽位时，浏览器里会看到**全线 500**，看起来像 prod 挂了。先查：

```bash
ssh root@47.100.235.168 'ss -lntp | grep -E ":(10010|10020) "'
```

本地 `pkill -f ssh` 常常杀不掉，用 `netstat -ano | grep :10010` 拿 PID 再 `Stop-Process`。

### ⑥ 真机验收的两条硬规矩

- **弹窗能开、格子能渲染都不算**，必须真按一次保存并回查数据库
- **toast 3 秒自动消失**，「点了没反应」≠ 没发请求。装 XHR 探针（axios 走 XHR **不走 fetch**）+ MutationObserver 抓 toast；查弹窗别只查 `.el-dialog`，`ElMessageBox` 是 `.el-message-box`

### ⑦ 清理测试数据要还原到「系统自己的一致状态」，不能只删行

我这轮栽了三次：
- 把 revision 改回 DRAFT 但没改 workflow 行 → workflow PUBLISHED + revision DRAFT 的不一致态，画布发布按钮直接灰掉
- 软删了 BOM 但 workflow 还发布着 → `No active BOM family covers the exact Workflow revision`
- 计划钉的 BOM 被删 → `PINNED_BOM_NOT_FOUND`，逐道录入打不开

每次都要返工。**删之前先想「删完之后这套数据还自洽吗」。**

---

## 4. 真机 E2E 结论（一入多出 / 多入一出）

两种拓扑**都完整跑通**，链路是：

```
画布发布(自动投影 BOM) → 期初建账入库 → 建生产计划(钉 BOM+revision)
→ 转批次 → 逐道报工 → 生产小结 → 扣料 + 成品/半成品入库
```

### 一入多出（拓扑成品C：1 原料 → 1 工序 → 2 成品）

- 建计划时系统自报「**1→多 · 单投入多成品**」，联产的成品D 自动纳入，两个产出各钉一份 BOM
- 转批次时为**两个**成品各建一个生产批次
- 报工的产出明细自动开 2 行，各自算出成率
- **投料只扣一次**：R4 扣 100kg（不是按 2 个产出扣 200）✅
- 入库：成品C 12 box + 成品D 6 box，各自独立批次

### 多入一出（黄油鸡：4 原料 → 原料处理 → 半成品 → 定量包装 → 成品）

- 建计划时系统自报「**多→1 · 多投入单产出**」，2 道工序路线自动固定
- 报工时一个报工组里**同时列 4 个原料**，各有选用勾 + 数量框
- 扣料：A/B/C/D **各 50kg** ✅
- 产出：半成品 `CLK-SEMI-...` 40kg AVAILABLE，可供下一道工序

### 几个流程事实（省你摸索）

1. **报工不直接动库存**。报工 → `SUBMITTED` → 「生产小结」(`interim-settle`) 才真正扣料入库。我一开始看到 `used_quantity=0` 以为没生效。
2. **库存只能从收货/退货/调拨/盘点/受控调整写入**，物料批次页没有「直接加批次」按钮（防呆）。要造库存走「盘点 → 期初建账/期初入库 → **预览比对** → 确认导入 → 提交审批 → 应用差异」，五步缺一不可。
3. 「报工审批」队列是空的 —— 本工厂未配审批流，走的是「生产小结」。
4. el-select 的 popper 用 JS `.click()` 常常打不开，要用 Playwright 真实点击；`:teleported="false"` 会让全页所有 popper 都在 DOM 里，**按全局选择器数选项会数到别的下拉**（我为此误判过一次「筛选失效」）。

---

## 5. F006 当前的数据状态（测试租户，可写）

E2E 的样本**留着没清**，下次回归可直接用：

- 5 个原料批次（各 500kg；已用 100 / 50 / 50 / 50 / 50）
- 2 个成品批次（拓扑成品C 12 box、成品D 6 box）
- 1 个半成品批次（40kg）
- 2 条新生产计划、5 个生产批次
- BOM：拓扑成品C/D 家族（ACTIVE，rev 264）、黄油鸡家族

**⚠️ LIUSHANMEN 是真客户，只读。** 全程没碰：9 条 recipe / 2 条计划 / 21 个批次一个没动。两条在产计划（`2d0910d1` / `e8861f79`）是硬闸对照物。

要清 F006 的话，记得判据 ⑦ —— 别只删行。

---

## 6. 已知未处置

| 项 | 说明 |
|---|---|
| **3-2** | 见第 2 节，等 owner 拍板 |
| **test 环境 10011 挂了** | 部署脚本自己报 `HTTP 000`。不是这轮弄的，现在只有 prod 在跑 |
| **「bindings 非空」的 hydration 未做真机** | 3-1 的那条路径只有单测覆盖。F006 现在有调料数据了，可以补 |
| **GitHub 恢复后的收尾** | 各 feature 分支（`codex/claude-canvas-bom-p1/p2/p3/p4/p5/p6`、`codex/claude-bom-canvas-spec`）分别 push → PR → 合完再 `git checkout main && git reset --hard origin/main` |

---

## 7. 环境速查

- 主工作目录 `C:\Users\Steve\my-prototype-logistics`（⛔ 别在这里直接干活，开 worktree）
- main 在 `C:\Users\Steve\cretas-rest-ai`
- prod 服务器 `root@47.100.235.168`，DB 密码在 `/www/wwwroot/cretas/.env.prod`（`set -a; . .env.prod; set +a`）
- web-admin prod：`139.196.165.140:8086`
- 测试账号 `f006_admin / 123456`（F006 测试租户）、`liushanmen_admin / 123456`（真客户）
- 本地开发：`cd web-admin && npm run dev`，配 SSH 隧道 `-L 10010:127.0.0.1:<活跃槽位>` 打 prod 后端
- 验证命令：`npx vue-tsc -b --force`（CI 用这条，比 `--noEmit` 严）、`npx vitest run`
- 后端跑测试前**必须先在干净 `origin/main` 跑同一 scope 取基线**，比对失败集合**逐条同名**，只看「新增是否为 0」—— 本仓这个 scope 本来就有 23 Failures + 36 Errors，比计数没有意义
