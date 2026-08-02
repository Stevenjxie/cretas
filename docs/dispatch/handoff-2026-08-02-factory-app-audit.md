# 交接：工厂 App 全角色审计整改 + 两个未查完的线索

**日期**: 2026-08-02
**分支**: `codex/claude-app-audit-fix`（worktree 在 `C:\Users\Steve\cretas-app-audit`）
**PR**: #2191（已开，**未合并、未部署**）
**上游审计文档**: `docs/audits/factory-app-all-role-readonly-audit-2026-08-01.md`

---

## 0. 先看这一条

审计文档**不在 main 上**。它在从未合并的分支 `codex/factory-app-role-audit-20260801`，commit `3206e9ef52`。
按文件名在仓库里搜是搜不到的，我一开始就是这样扑空的。取法：

```bash
git log --all --oneline --diff-filter=A -- '*factory-app-all-role*'
git show 3206e9ef52:docs/audits/factory-app-all-role-readonly-audit-2026-08-01.md
```

**建议顺手把它合进 main**，否则下一个人还要再扑一次。

---

## 1. 已做（PR #2191，9 条里的 7 条）

APP-RBAC-001 / 002 / 003、APP-CONTRACT-004、APP-DATA-005、APP-UX-006、APP-WEB-007、APP-I18N-008。
逐条根因与修法写在 PR 正文里，不重复。这里只记**接手需要知道的判断**：

### 1.1 一条贯穿的形状（值得记住）

7 条里有 5 条是同一件事：**前端存了一份契约副本，副本和权威那份反了或旧了**。

| 问题 | 权威 | 前端副本 |
|---|---|---|
| RBAC-001/002 | Controller 的 `@RequirePermission`（只在写接口上） | 页面级角色白名单，读也拦 |
| RBAC-003 | `WhitelistController` 每个接口的 `@RequireRole` | 只看工厂功能开关，不看角色 |
| CONTRACT-004 | 后端实际返回的扁平结构 | 凭空声明的嵌套 DTO |
| UX-006 | `ROLE_METADATA`（19 个角色） | `HomeScreen` 私有 `roleMap`（6 个） |

修法统一是**让副本指向权威**，不是把副本补齐。仓里还有同类没收敛的副本，见 §3.3。

### 1.2 一个我刻意没做的决定 —— 需要你或 Steve 拍板

**`hr_admin` 到底该不该有白名单权限？**

事实：`WhitelistController` 的**每一个**接口（含 `GET /whitelist` 和 `/stats`）都挂
`@RequireRole({"factory_super_admin","permission_admin"})`，而 `HRTabNavigator` **只有 `hr_admin` 会进**。
即：这个 Tab 对所有能看见它的人都必然 403，**从来没有人用成功过**。

但它明显是**为 HR 建的**（住在 HR 导航里、HR 首页有"待激活白名单"卡片）—— 也就是后端注解和产品意图是矛盾的。

我选了 fail-closed 那一侧（UI 对齐后端当前契约：无权就不显示入口），因为**放开注册白名单是扩权，不该由我单方面做**。
若业务确认 HR 应当能管：改后端注解，然后给 `permissionHelper.ts` 的 `WHITELIST_ACCESS_ROLES` 加一项即可 —— 该常量上方已写明"改这里之前先改后端注解"。
另：工厂超管在 web-admin 上仍能管白名单，能力没丢。

### 1.3 测试里踩到的一件事

`hrApiClient.test.ts` 原有两条断言是 `expect(data.whitelistPending).toBe(0)` ——
**旧期望写的正是缺陷本身**（"白名单统计失败时回落 0"）。已改成断言 `null` 并在测试里注明原因。
接手时若再看到类似"改了实现就挂"的测试，先问一句：**旧期望是不是在编码缺陷本身**。

### 1.4 提交时踩到的坑（会再犯，记一下）

我用 `json.load` → `json.dumps` 往返改 locale 文件，结果 `processing.json` diff 变成 **310 行**（我只加了 2 个 key）。
根因不是格式化，而是**这些 locale 文件里有重复 JSON key**（`processing.json` 的 `aiAnalysisDetail` 出现 **3 次**），
`json.load` 按 last-wins 合并，写回时就把前面几份静默删了。

判据还是那条：**diff 远大于预期就停手**。最终改成"按行定点插入 + 复用该行原有行尾"，
并用"逐 key 路径解析验证"证明 14 个 key 都能取到。同理，RN 源码用 Python 改写时也会整档 LF→CRLF，
本轮靠 `difflib` 对齐把未改动行的行尾还原回去（`WhitelistListScreen.tsx` 一度 50/31，还原后 21/2）。

---

## 2. 明确没做的（不是漏，是取舍）

### 2.1 APP-TECH-009（P3/P4）

`expo-av` → `expo-audio`/`expo-video`、`shadow*`/`textShadow*`/`pointerEvents` 弃用告警、Web `useNativeDriver`。

**理由**：审计自己的验收标准就是"目标 Expo SDK 升级前完成兼容替换"——这是依赖升级批次的事。
没有真机回归就换掉音视频依赖，风险和收益不对等（这些是告警，不是故障）。

### 2.2 12 角色矩阵没有重跑 ⚠️ 这条最重要

**PR #2191 的全部改动只过了 `tsc --noEmit` + `jest`，没有任何一处经过人眼或真机确认。**

审计文档"后续建议顺序"第 4 条明确要求：修复后重新执行同一 12 角色只读矩阵。**这一步没做。**

而且这批改动恰好落在**只有打开页面才看得见**的那一类上（页面级权限、空状态、卡片渲染、日志噪声）——
`memory/feedback_only_visible_when_you_open_the_page.md` 记的就是这个教训：单测绿 / 类型绿 / CI 绿，
四个前端缺陷照样全在线上。

**接手第一件事应该是补这一步**，而不是接着写代码。原 harness 的证据目录：
`C:\Users\Steve\cretas-factory-app-sheet-0801\.codex\tmp\factory-app-role-audit\evidence\2026-08-01T15-24-34-769Z\`
（61 张截图 + `role-audit.json`，SHA-256 `1d6d54c8…`）。

至少要覆盖：
1. 销售主管点"客户"、采购主管点"供应商" → 能看到真实列表，不再整页拒绝，也不再泄漏 `common.adminOnly`
2. 只读角色进这两个页 → 看得到列表，但新增/编辑/停用/删除入口**全部不可见**（后端仍是最终真值）
3. `hr_admin` 登录 → 白名单 Tab 与首页卡片**不出现**，且首页其它数字正常
4. 质检员首页三个数字有值、分析页能渲染（无数据时是"暂无"而不是"数据格式异常"）
5. 生产经理"今日概览"满足 `todayInProgress + todayCompleted <= todayTotal`
6. 五个角色首页无英文角色码、无空白卡
7. Web 控制台推送相关 error/warning = 0

**验证 4 之前要先部署后端** —— `breakdown` / `gradeDistribution` / `todayInProgressBatches`
都是新加的键，不部署的话前端走的是回落分支（数字会是 0，看起来像没修好）。

---

## 3. 未查完的线索

### 3.1 🔴 BOM 配方管理页不支持鼠标滚轮（Steve 8/2 当面报的，**没修完**）

页面：web-admin `/production/bom`，标题「BOM / 配方管理」，文件 `web-admin/src/views/production/bom-unified/index.vue`。

查到哪一步了：

- 外层 `AppLayout.vue` 的 `.app-content` 是 `overflow-y: auto` + `min-height: 100vh`，**看起来正常**
- `bom-unified/index.vue` 只有 `.bom-unified { height: 100% }`
- 真正可疑的在内层 `web-admin/src/views/production/bom/index.vue`（4411 行）：

```scss
.bom-page          { height: 100%; display: flex; flex-direction: column;
                     min-height: 0; overflow: hidden; }   // ← 外层锁死
.bom-page__scroll  { flex: 1 1 auto; min-height: 0; overflow: auto;
                     overscroll-behavior: contain; }      // ← 内层才滚
```

**推测**（未验证）：`.bom-page` 靠 `height: 100%` 拿高度，但它的祖先链
（`.app-content > * { min-height: 100% }` → `el-card` → `el-tabs[type=border-card]` → `el-tab-pane`）
中间没有一个有**确定高度**，`height:100%` 会退化成 `auto` → `.bom-page` 被内容撑开 → `.bom-page__scroll` 的
`flex:1` 算不出可滚高度 → 内层不产生滚动条，而外层又被 `.bom-page` 的 `overflow: hidden` 挡住 → 滚轮两边都不响应。
`overscroll-behavior: contain` 会进一步阻止滚动链冒泡到外层。

**下一步**：直接开浏览器验，别继续读代码猜。
`document.querySelector('.bom-page').getBoundingClientRect().height` 与 `.bom-page__scroll` 的
`scrollHeight` / `clientHeight` 对比一眼就知道是"没高度"还是"被 hidden 挡住"。
若确认是上面那条链，最省的修法是给 `.bom-page` 改成 `min-height: 100%` 或让它不再 `overflow: hidden`，
但**要同时确认 el-tabs 里另一个 tab（转换率）没被带坏**。

⚠️ 注意这个页面在 `keep-alive` 里（`AppLayout.vue` 的 `<keep-alive :include="keepAliveViews" :max="5">`），
改布局后要切走再切回来验一次，别只验首次进入。

### 3.2 `半只` 这个单位（延续上一轮）

F006「干式熟成鸡—前处理」，是**规格不是单位**，零批次引用。V48 迁移刻意没动它。需要人决定：改成 `piece` 还是把它从单位字段挪到规格字段。

### 3.3 还没收敛的"契约副本"

按 §1.1 的形状扫出来但本轮没动的：

- **权限矩阵有 3 份**：`permissionHelper.ts` 的 `PERMISSION_MATRIX`、`types/auth.ts` 的
  `getDefaultPermissionsForRole()`、后端 `PermissionServiceImpl`。前两份都在前端，且**互相不完全一致**
  （`PERMISSION_MATRIX` 没有 `dispatcher` / `yield_operator`，`getDefaultPermissionsForRole` 有）。
- **HR 首页 DTO 有 3 份**：`hrApiClient.ts` 的 `HRDashboardData`、`types/hr.ts`、`types/hrNavigation.ts`。
  本轮改 `whitelistPending: number | null` 时三处都要改 —— 这本身就是味道。
- **`QualityStatistics` 有 2 份且都不对**：`types/qualityInspector.ts`（本轮已修）和
  `services/api/qualityInspectionApiClient.ts`（**没修**）。后者声明
  `passedInspections/totalSampleSize/totalPassCount/conditionalInspections`，
  而后端产出的是 `passedBatches` 且根本没有样本量字段 →
  **`screens/factory-admin/ai-analysis/QualityAnalysisScreen.tsx` 很可能整屏显示 0**。
  这个屏不在 12 角色只读矩阵覆盖范围内（它是 factory-admin 的），所以审计没抓到。**值得单独验一下。**

### 3.4 27 → 18 条 BLOCKING 缺 actionHint

`BlockingErrorsCarryActionHintTest.KNOWN_DEBT` 里还剩 18 个码，需要业务侧告诉"该把用户引导到哪"才能写。

---

## 4. 环境与纪律速查

- worktree：`C:\Users\Steve\cretas-app-audit`（off `origin/main`，用完 `git worktree remove`）
- ⛔ **不要** `mklink /J` 共享 `node_modules`（Windows 上删 worktree 会把主仓的一起掏空）。本轮是
  `npm install --prefer-offline --legacy-peer-deps` 独立装的。
- 本 PR 碰了 backend + frontend → **必须走 PR，不能 fastlane**。
- 部署只从 main：`./scripts/deploy/release-cretas.sh --phase deploy --base-sha <SHA> --tests '<类名>' --confirm-prod YES-PROD`
  - `--tests` **只收显式类名，不收通配符**
  - 唯一可信的成功信号：`DEPLOY_EXIT=0` **且** `RELEASE_FINAL_STATUS` 出现恰好一次。
    后台任务的 exit code 不可信；`main_guard: passed` 也不可信（构建窗口里 main 前进会被静默拒绝）。
- prod 库是 `cretas_prod_db`，**不是** `cretas_db`（上一轮在这上面整轮结论都错过一次）。
