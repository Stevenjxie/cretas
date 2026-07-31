# 交接：副产品 SKU 化与盘点抵扣（执行中，4/7）

## ⚠️ 先读这条

**上一份交接（`HANDOFF-2026-07-31-walkthrough.md`）有两处硬伤，害我差点踩空。这份交接你同样不要全信 —— 接手先跑「三条自检」（见文末）。**

上一份错在哪：
1. 写「唯一挡住它的是一个过期契约测试」—— 实际上**整个 Java 构建是挂的**（`ProcessSheetController` 新注入第三个构造参数，两个既有测试仍按两参构造 → testCompile 失败 → 那个分支根本产不出 jar）。作者不粗心，那次 CI 红发生在他写交接**之后**的 push 上。
2. 给的测试基线里有一条「documentTrace 是既有红测」—— 那条我复核了确实是（`command="trace"` 在 origin/main 的 list.vue 里同样不存在），但**它涉及的文件正是那个分支改的**，如果不复核就采信，会把自己引入的回归当成基线。

**教训：交接写的是「我知道的剩余项」，不是「仓库当前的真实状态」。**

---

## 你的身份与环境

- **主工作目录** `C:\Users\Steve\my-prototype-logistics` —— ⛔ **不要在这儿改代码**（多 session 共享）
- **本任务工作区**：`C:\Users\Steve\cretas-bom-unit`，分支 `codex/claude-byproduct-spec`，**node_modules 已装好**
- **部署检出点**：`C:\Users\Steve\cretas-deploy-0730`（detached，专用于部署）
- ⚠️ 这个 harness **每次 Bash 调用后 cwd 重置回主目录** → 用绝对路径，或在同一条命令里 `cd`

---

## 当前进度：SDD 执行中，4/7

用的是 `superpowers:subagent-driven-development`。**Steve 选的执行方式是 A（子代理逐任务）**，我中途调整为「实现 inline + 复审派子代理」，理由记在 ledger 里（工作面=1 时项目规则要求 inline，但复审的异视角有独立价值 —— Task 1 就是复审抓到的真回归）。

- **计划**：`docs/superpowers/plans/2026-07-31-byproduct-sku-and-stocktake-credit.md`（7 任务 / 40 步）
- **设计**：`docs/superpowers/specs/2026-07-31-byproduct-output-design.md`
- **ledger（你的恢复地图）**：`.superpowers/sdd/2026-07-31-byproduct-sku-and-stocktake-credit/progress.md`
  —— 有 `Task N: complete` 的别重做

| 任务 | commit | 状态 |
|---|---|---|
| 1 副产大类 + 采购隔离 | `d10683232b` | ✅ 4/4，复审后修过一轮 |
| 2 migration `V20261029_36` | `f3b3912c42` | ✅ 2/2，变异红 |
| 3 抵扣额唯一入口 | `92b5e7bdce` | ✅ 5/5，变异红 |
| 4 副产落生产仓 | `609b35aa4d` | ✅ 2/2，变异红 |
| **5 BOM 配方第四类「副产」** | — | ⬜ **从这里继续** |
| 6 盘点单价确认与展示 | — | ⬜ |
| 7 单价来源收敛存量对比 | — | ⬜ **只出报告，必须停下来问 Steve** |

**分支未推送**（6 个 commit 领先 origin）。origin/main 今天前进很快（已到 `7c2a0d44c3`），随时可能再动。

---

## 🔴 这个项目最重要的一件事：起点不是从零

**我写 spec 初稿时说「没有任何地方能声明副产，也无处记录副产的产出量与价值」—— 这是错的，我没验就写了。** 线上实际：

| 已有能力 | 载体 | 线上数据 |
|---|---|---|
| 工序**预先声明**预期副产 | `work_processes.expected_byproducts` | **4 个工序已声明** |
| 报工**录副产**（名/量/单位/单价） | `production_reports.byproducts` | **15 条已录**，如 `{"name":"肥油","unit":"kg","quantity":36,"unitPrice":8}` |
| 副产成本冲减（含上游 WIP 链传播） | `OrderCostBreakdownService.upstreamByproductCredit` | 在用 |
| BOM 侧 NRV 抵扣 | `bom_recipes.byproduct_nrv_unit_price` + `recomputeFamilyCosts` | 在用 |

**真正缺的只有四样**：① 副产是自由文本 name 不是 SKU → 不能被当原料再投入 ② 不落 `material_batches` → 盘点盘不到 ③ 单价在报工时填（要挪到盘点）④ 单价有两个权威来源。

**两套机制不是重复计算**：`recomputeFamilyCosts` 算的是 **BOM 标准成本**，`OrderCostBreakdownService` 算的是**订单级实际成本** —— 不同的成本对象。问题只在于同一个副产的单价维护在两处。

---

## Steve 定下的设计（四轮问答定稿，别再重问）

1. **副产 SKU 放原料字典** —— 依据是实证不是拍脑袋：prod 里 `material_batches` 中 `source_doc_type='PRODUCTION_BATCH'`（WIP 半成品，同样是「产出物+可再投入」）255 条里 **249 条 `material_type_id` 指向原料字典，0 条指向成品字典**。
2. **落库在「生产仓」不是原料仓** —— Steve 明确纠正过我：「这个 sku 是原料的字典内容，然后肯定生产出来以后是放在生产仓的」。
3. **抵扣挪到盘点做**：生产/报工时只登记（标注/落仓/记重量，**不问价值不拦人**）；**盘点时**①关联系统已知②确认**单价**（系统 ×重量）③算抵扣后成本与利润。
4. **录入两处**：Workflow 产出 Cell 声明结构 + BOM 配方内容第四类填 SKU 和预计产出（**「添加副产」按钮与「添加原料」并排**）。
5. **成本报表单列一行抵扣**（标准成本计算单格式），**一主多副汇总一行 + 明细可展开**，**抵扣按盘点实际重量**，**采购下拉排除 `category=副产`**。

**界面效果 artifact**（Steve 已确认）：https://claude.ai/code/artifact/080a838a-62f6-4dbd-b51f-0e64b83bfe21

---

## 🔴 我在这个 session 里搞错过的逻辑（照着别再犯）

### 1. 「#1976 一只≠一件」不是全局规则 —— 我按全局改，被既有测试证伪

我发现自己在 #2077/#2079 用了会把 件/个/只 全并成 `pcs` 的归一函数，等于悄悄放宽了 #1976。于是新增 `crossLanguageCode`（只折「同一单位的中英写法」）并全局替换。

**结果既有测试当场证伪**：`BomWorkflowRevisionServiceTest#localizedCountUnitMatchesCanonicalBomUnitDuringStableSlotRekeying` 断言 `unitsCompatible("pcs","只") == true` —— **Workflow 快照的 slot re-keying 刻意认本地化写法**。

现在的正确分工（**用错就会踩 #1976**）：
- `UnitContractServiceImpl.crossLanguageCode` —— 做**相等判定/去重**时用（袋≡bag 折，只≠件 保）
- `UnitContractServiceImpl.canonicalCodeOrRaw` —— 归一成**展示/存储码**时用（会合并 只/件）
- 唯一例外：`BomWorkflowRevisionService.canonicalUnit` 刻意用宽的那个，代码里写了原因

### 2. 我一开始把「副产 NRV 拦截」当成需要 Steve 定成本口径的业务决策上报了

**错在只看了报错那一处就下结论。** 追下去发现真正的成本分摊在**报工时**按「比例/重量/数量」算，那段**从头到尾没读过 `outputRole`**；`getOutputRole()` 的消费者也只在 BOM 域内部。所以那个自动标的 BY_PRODUCT 标签不影响任何成本计算，**根本不需要任何决策**，直接豁免即可。

### 3. 「基线 0 失败」的假阴性 —— `git stash` 不带走未跟踪文件

Task 4 取基线时，基线轮报「0 失败」而我这边 1 失败。差点当成自己引入的回归。真相：**新测试文件是未跟踪的，`git stash push`（无 `-u`）不会带走它**，基线轮因它引用被 stash 掉的方法而**编译失败**，我的 grep 只匹配 `[ERROR]   Class.method` 格式，压根没匹配到编译错误 → 显示 0。

**判据**：取基线后先确认那一轮**真的编译并跑起来了**（grep `Tests run` 和 `BUILD`），别只看失败数。

### 4. maven 增量编译的假失败

变异脚本还原源码后 `.class` 仍是变异版，导致一条本该绿的用例显示红，我差点当真缺陷去改。**凡是「刚改完源码结果匪夷所思」→ 先 `mvn clean test` 再下结论。**

### 5. 我把方法插到了别人的 `@Transactional` 下面 → 407 条编译错误

在 `BomWorkflowRevisionService` 加方法时，锚点只匹配了方法签名行，插入点落在了上一个方法的注解**下面** → 我的方法叠了两个 `@Transactional`（不可重复注解），**且原方法丢了注解**。报错 407 条全是 Lombok 级联噪音，真凶只有一条。

**判据**：往 Java 类里插方法前，先看锚点**上一行是不是注解**。

### 6. 部署失败被 `echo` 吞掉退出码

我写的是 `... > log 2>&1; echo "DEPLOY_EXIT=$?"; tail ...` —— 后台通知报的 **exit 0 是整条复合命令的**，而 release 脚本实际 `status: failed`。**差点当成部署成功了。**

**判据**：部署命令**单独捕获退出码**，并且**必须 grep `RELEASE_FINAL_STATUS`**，不能只看通知。

### 7. 「加了显示的一半，没加承载它的另一半」—— 今天撞了四次

- 「生产仓可用」列：加了 `<td>` 没加 `<th>`、CSS grid 还是 3 列
- 前端展示映射表：加了调用点但表本身缺 pack/can/crate/pail/roll/item
- Task 1：加了「副产」大类选项，但 `bigCategoryOf` 没分支 → **选它永远空列表**
- `PRICE_VIEW_ROLES`：后端 12 个角色前端 10 个

**这是本仓最高频的 bug 形状。加任何「新的一类」时，把承载它的所有地方列出来再动。**

---

## 剩余任务的具体注意事项

### Task 5（BOM 配方第四类）
- `activeCategoryTab` 现在是 `'RAW' | 'AUXILIARY' | 'PACKAGING'`，加 `'BYPRODUCT'`
- 按钮文案**本来就是按 tab 切的**（`PACKAGING ? '添加包材' : '添加原料'`），沿用同一规则
- ⚠️ 单位**一律经 `displayProcessUnit` / `displayUnit`** —— 有个契约测试会扫全部 `.vue`，裸露单位插值直接红

### Task 6（盘点确认）
- 前端**只做格式化，不算钱** —— 金额由后端 `ByproductCreditService.creditOf` 算
- `null`（未确认）与 `0`（确认为 0）**必须分得开**，显示「未抵扣」vs「0.00」

### Task 7（⛔ 只出报告，必须停下来问 Steve）
- 会动 **BOM 标准成本**（成本口径红线）
- 报告要回答三件：①两侧单价有没有同一副产取值不同 ②迁移后哪些 BOM 标准成本会变、变多少 ③要不要保留每配方的覆盖位
- **一并调研** `work_processes.expected_byproducts`（4 个工序在用）与本设计的关系 —— 这是 spec 里唯一没排进任务的一项
- 顺带看一眼 `db/flyway/V20261029_32__unit_codes_to_chinese.sql`（预检时发现，与单位中文化相关，没展开）
- **查 prod 时必须做阳性对照**：查询返回 0 行先用不带过滤的同一 join 确认能查出行

---

## 环境要点（都验证过）

**prod 数据库**：库名 **`cretas_prod_db`**（不是 `cretas_db`，同机两个库都在，查错会得出「列不存在」这种误导结论）。
最省事的连法（peer auth，不用密码）：
```bash
ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -c \\\"SELECT ...\\\"\""
```

**本机跑 Java**：`export JAVA_HOME="C:/Program Files/Zulu/zulu-21"`

**部署**（⚠️ 单独捕获退出码 + 必须核 `RELEASE_FINAL_STATUS`）：
```bash
cd /c/Users/Steve/cretas-deploy-0730 && git fetch origin --quiet && git checkout --detach origin/main
export LC_ALL=C JAVA_HOME="C:/Program Files/Zulu/zulu-21"
./scripts/deploy/release-cretas.sh --phase deploy --base-sha <上次部署的40位SHA> \
  --tests '<真实测试类名>' --confirm-prod YES-PROD > /tmp/deploy.log 2>&1
echo "EXIT=$?"                      # ← 后面不要再接别的命令, 会吞掉它
grep -E "RELEASE_FINAL_STATUS|BACKEND_HEALTH" /tmp/deploy.log
```
- ⚠️ origin/main 前进很快，脚本 git 闸会 ABORT → 重新 `checkout --detach origin/main` 再跑
- 部署后**核对运行中的 jar 含你的修复**（带阳性对照）：
```bash
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/<路径>.class'" > /tmp/x.class
javap -p -c /tmp/x.class | grep -c "<你的方法名>"   # 阳性对照: 再 grep 一个你确定存在的符号
```
- 当前 prod 活跃槽位 **10010（blue）**

**web-admin 三样验证缺一不可**（纯前端 PR 上 CI 不跑前端）：
```bash
cd web-admin && npx vue-tsc -b --force && npx vitest run && npm run build
```
`vitest` 的基线是 **4 红**：`productSpecification` / `purchaseOrderTaxRateCreate` / `documentTrace` / `workflowPortGroups`。

---

## 今天已上 prod 的（都验证过，别重做）

| PR | 内容 |
|---|---|
| #2071 | 生产仓可用改后端权威值 + 补漏改的两处列 + 契约改写 |
| #2077 | 成品报工单位改用权威别名表（客户「报工袋/BOM bag」被拦） |
| #2079 | 五处私有单位别名表收敛到唯一入口 |
| #2080 | 计数/包装单位不以英文码示人 + 三处漏网私有表 + 副产 NRV 假拦截 |

**prod 数据改动**（Steve 逐次授权）：`bom_recipes` 两行（SHH0713香辣孜然羊排 v5 ACTIVE / v6 DRAFT）`克/200` → `box/1`，备份表 **`bak_bom_recipes_20260731`**，回滚 SQL 在 PR #2079 评论里。

---

## 红线

DB migration / 权限 RLS 多租户 / **成本财务口径** / 资金路径 / 撤回冲销 → **默认只记录不修**，报告里标 🔒。

⚠️ Steve 本 session 逐次授权过 merge + 部署 prod + 改 2 行 BOM 数据。**那是逐次授权，不自动延续到你** —— 默认是「实现+自测+出报告为止」。但他明确说过**不要每个 PR 都停下来等人工 review**（PR + 实测证据 + 失败模式安全，三条齐了可以自己合）。

---

## 硬规则

- 响应格式 `{success, data, message}`；**禁降级处理**（不返假数据、不臆造默认值）；禁 `as any`
- 错误 toast 必须 sticky（`duration:0 + showClose`）且**原样展示后端 message**
- commit 用 `git commit -m "..." -- <具体文件>`（`--` 限定范围，防并发 session 文件被吞）
- **变异检验是必须的**，且要变**调用点**不只是函数体
- ⚠️ `ProcessDataTable.vue` 有卡片/表格两套模板，改一处必 grep 第二处

---

## 接手先跑这三条（不管这份交接怎么说）

```bash
# 1. CI 历史有没有红的 (尤其是交接时间点之后的 push)
cd /c/Users/Steve/cretas-bom-unit && gh run list --branch codex/claude-byproduct-spec --limit 5

# 2. scope 有没有夹带别的 session 的文件
git fetch origin --quiet && git diff origin/main...HEAD --stat

# 3. 自己取基线, 别采信交接给的数字 (并确认基线轮真的编译跑起来了)
cd backend/java/cretas-api && mvn clean test -Dtest='ProcessSheet*Test' 2>&1 | grep -E "Tests run|BUILD"
```

`-`（skipped）≠ `✓`（passed），看 CI 要看清**绿的是哪几个 job**。
