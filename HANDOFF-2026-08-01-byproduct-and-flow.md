# 交接：副产链路已通 + 正常报工流程走查（卡在一个 a11y 属性）

**日期**: 2026-08-01
**上一份**: `HANDOFF-2026-07-31-byproduct.md`（副产项目 4/7），本文接它

---

## ⚠️ 先读这条

**上一份交接有两处硬伤，害我踩空。这份你同样不要全信 —— 先跑文末「接手先跑这四条」。**

上一份错在哪：
1. 说「分支未推送（6 个 commit 领先 origin）」—— 实际 `HEAD == origin/同名分支`，早推了。
2. spec §1 与交接表格都写「BOM 侧 NRV 抵扣**在用**」—— **prod 实测 0 行有值**。Task 7 就是被这句话派去追一个不存在的「两套单价合并」。（已在 #2105 更正文档。）

**教训不变：交接写的是「我知道的剩余项」，不是「仓库/线上的真实状态」。**

**我自己这一轮也犯过同类错，写在第 6 节，别重蹈。**

---

## 1. 现在到哪了（一句话）

副产**后端整条链已通并上线**；今天转去走「正常原料→半成品→成品」流程时，
在 prod 撞出 3 个 UI 缺陷，**2 个已修上线**，**第 3 个未修且正卡着流程走不完**。

---

## 2. 今天合入并部署的 PR（都已 prod 验证）

| PR | 内容 |
|---|---|
| #2096 | 副产 Task 1–7 + 修 #2080 只修一半的「缺 NRV 清空整个 family 成本」 |
| #2100 | 盘点副产价值确认后端 + 两个副产声明位收敛（`ByproductDeclarationResolver`） |
| #2105 | **副产从 `category` 取值改为物料标记 `is_byproduct`**（`V20261029_38`）+ 修两处「产出被当投入」+ 修好 main 上坏掉的 vue-tsc |
| #2106 | 请求 DTO `@Pattern` 补 BYPRODUCT（第三个承载点） |
| #2107 | 空替代料列表不该拦住副产明细保存（第四个承载点） |
| #2109 | **报工副产真正落生产仓** —— 接上一直零调用方的 `buildByproductBatch` |
| #2111 | 通用占位「副产」在唯一声明时归属该 SKU，否则不猜 |
| #2113 | **补上表格模式缺失的「生产日期」格** —— 逐道报工被它堵死 |

当前 `origin/main = cc976327d3`。最后一次部署 `RELEASE_FINAL_STATUS=deployed`。

---

## 3. 🔴 立刻要接着做的：`aria-disabled` 陈旧（未修）

**症状**：逐道录入里勾「选用」后，投料数量框的 DOM `disabled` 已正确变 `false`，
但 `aria-disabled` 仍停在 `"true"`。

**已核实它只是属性陈旧、不是真禁用**（这几条都验过，别再重复验）：
- 外层 `.el-input-number` **没有** `is-disabled` 类
- `pointerEvents: auto` / `tabIndex: 0` / `readOnly: false`
- **Tab 键能真实聚焦进去**

**影响**：
- 鼠标用户无感
- **读屏用户被告知「已禁用」** —— 真实无障碍缺陷
- **Playwright 据 `aria-disabled` 一律拒绝 click/fill** → 自动化走不完流程

**位置**：`web-admin/src/views/production/components/processSheet/ProcessDataTable.vue:3282`
```vue
<el-input-number v-model="item.quantity" :disabled="!item.selected" ... />
```
`aria-disabled` 由 Element Plus `el-input-number` 内部渲染，我们只传了 `:disabled`。
修法要绕过组件内部（换控件 / 显式覆盖属性 / 升级 EP），**风险与收益需先问 Steve**——他还没拍板。

**这条不修，「报工 → 生产小结」这条流程就用自动化走不完。**
（替代路径：让 Steve 自己用鼠标填数，我们在旁边盯数据与后续环节。）

---

## 4. 副产链路的真实状态（别再重查）

| 环节 | 状态 |
|---|---|
| 建副产 SKU | ✅ prod 实测跑通（`category=原料` + `is_byproduct=t`，材质分类保留） |
| BOM 第四类声明副产 | ✅ prod 实测跑通（保存成功，页签显示 副产(1)） |
| 副产不进成本池 / 不变采购需求 / 不变领料需求 | ✅ 单测 + 变异 |
| 报工 → 落生产仓（后端） | ✅ 已接线（#2109），单测 + **接线测试** + 变异 |
| 盘点确认单价 + 抵扣额 | ⚠️ 建好了，**等第一条真批次**（prod 副产批次仍为 0） |
| 报工按 BOM 预填副产（RN） | ❌ 后端已给，RN 报工屏零引用。**Steve 说别管 RN** |
| 成本报表单列抵扣行（spec §8.2/8.3） | ❌ 没做 |
| Workflow 产出 Cell 标「是否副产」（spec §4.1） | ❌ 没做 |

**prod 现状快照**（2026-08-01 实测）：
```
byproduct SKU 1 / BOM副产行 1 / 副产批次 0 / SKU与BOM单位不一致 15
```

---

## 5. 🔴 单位问题（Steve 明确说「最多问题就是这个」）

**根因已定位**：`V20261029_32__unit_codes_to_chinese.sql` 只中文化了四张表 ——
`raw_material_types` / `product_types` / `material_batches` / `workflow_task_ports`，
**唯独没有 `bom_recipes.output_unit`**。

于是它把 SKU 侧 `bag→袋`、`box→盒` 改成中文，BOM 侧留在英文码 ——
**「报工单位 袋 / BOM 单位 bag」这个不一致就是这条 migration 自己造出来的**。

**影响面（F006 实测）**：35 个配方里 **15 条**字面不一致（其中生效版 4 条）：
- `盒 vs box` 11 条（4 条生效）—— 别名表能识别，**不拦人**
- `盒 vs 克` 4 条（0 条生效）—— **量纲不同**，别名表救不了

**代码侧已安全**（#2077/#2079 让比较都走权威别名表），**数据侧仍是脏的**。
建议补一条 migration 把 `bom_recipes.output_unit` 也中文化对齐 —— 🔒 动 BOM 数据，
Steve 还没拍板（我提过，他没回）。

---

## 6. 🔴 我这一轮搞错过的（照着别再犯）

### 1. 用 `browser_evaluate` 里的 `element.click()` 冒充真实 UI 操作
Steve 一句「注意 ui 的操作」点破。注入式点击/原生 setter 塞值会**绕过 hover/focus/pointer 事件**
和依赖真实事件的校验 —— 「我点通了」≠「用户点得通」。
**判据**：一律用 `browser_click` / `browser_type` / `browser_press_key`；
结果另外用数据库独立核对。（我前面的结论因为都查了库所以站得住，但过程不足以证明。）

### 2. 判据选错对象，两次
- `category='副产'` vs **标记** —— 前者让副产在 BOM 原料页签选不到，堵死「副产能当原料再投入」这个初衷
- **按名称匹配** BOM 声明 —— 而 web 报工把副产名写死成「副产」，永远匹配不上
**判据**：定判据前先问「执行时真正决定这件事的是哪一行代码/哪个字段」。

### 3. 只测实现，没测「谁调它」
`ByproductBatchMaterializer` 我先只写了 6 条单测，**把接线短路掉后 6 条全绿** ——
而「没人调」正是当时在修的缺陷。补了接线测试才成立。
**判据**：修「建好了没人调」类缺陷时，必须有一条盯**调用点**的用例。

### 4. 拿「日志 grep 到 0 次」当证据（两次，两次都不成立）
- 业务错误码**根本不写进日志**（同类阳性对照 0 命中）
- 未鉴权探测端点：**不存在的端点也返回 401**，401 区分不了存在与否
**判据**：阳性对照必须与被查对象**同类**，不能只证明"grep 在工作"。

### 5. 归因下早了
把某 family 成本为 NULL 归因于 NRV，查明细才发现是**未定价 RAW 行更早短路**。
另：说过「web-admin 没有任何工序能录副产」，实际**那个 workflow 的产出明细区有副产控件**，
是我在另一个计划上搜完就下了结论。

### 6. python 文本模式改文件把 LF 写成 CRLF
`FactoryStocktakeServiceImpl` 一度显示 2549 行改动（真实 +91）。
**判据**：commit 后看 `--stat`，行数远超预期就 `file <path>` 查行尾。
后来改用 `io.open(..., newline='')` 读写并按行保留原样，且注意**有些文件本来就是混合行尾**。

### 7. 测试正则匹配到了错的那一处
- `row.unit` 会匹配到 `row.unitPrice`（踩两次，要 `(?![A-Za-z])`）
- 生产日期那条匹配到了**已小结只读行**（它在文件里更靠前），要用 `placeholder` 精确锁定

---

## 7. prod 上留下的东西（都可删，Steve 已知）

| 对象 | 标识 |
|---|---|
| 副产 SKU | `验收-副产-肥油`（`RMT_1785513705730`，业务编码 `M5ZU29000005`） |
| BOM 副产明细 | 配方 `f5985654`（SOP-20260729-01-黄油鸡-成品800g v2）上 1 条 |
| 验收生产计划 | `PLAN-1785522839462-CB00FF18`（SOP-20260730-01，进行中，1 行草稿） |
| BOM 版本切换 | **SOP-20260729-01 v1→v2 已生效**；**SOP-20260730-01 v1→v2 已生效**（v2 含我加的 1 条辅料）。系统提示「仅之后新建的生产计划采用此版本」，对已有计划无影响 |
| 凭证文件 | `C:\Users\Steve\cretas-bom-unit\.env.test`（gitignored，密码是 Steve 给的测试账号 `f006_admin`） |

---

## 8. 环境要点（都验证过）

- **prod 库名** `cretas_prod_db`（不是 `cretas_db`）。连法：
  `ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -c \\\"SQL\\\"\""`
- **本机跑 Java**：`export JAVA_HOME="C:/Program Files/Zulu/zulu-21"`
- **部署**（⚠️ 单独捕获退出码 + 必须核 `RELEASE_FINAL_STATUS`）：
  ```bash
  cd /c/Users/Steve/cretas-deploy-0730 && git fetch origin --quiet && git checkout --detach origin/main
  export LC_ALL=C JAVA_HOME="C:/Program Files/Zulu/zulu-21"
  ./scripts/deploy/release-cretas.sh --phase deploy --base-sha <上次部署的40位SHA> \
    --tests '<真实测试类名>' --confirm-prod YES-PROD > /tmp/deploy.log 2>&1
  echo "EXIT=$?" > /tmp/deploy.exit    # ← 不要在同一条命令里接别的, 会吞掉退出码
  ```
- **web-admin 三样验证缺一不可**（web-admin 的 PR 上 CI 不跑前端）：
  `npx vue-tsc -b --force && npx vitest run && npm run build`
  **当前基线：全绿**（vue-tsc 0 / vitest 301 files 2299 tests / build 绿）。
  ⚠️ 早先那 4 条既有红已被别的 session 在 main 上修掉，**别再照抄旧交接里的「4 红基线」**。
- **Java 测试基线是红的**（既有）：`Bom*Test` 等约 23 failures / 42 errors。
  **必须 `git stash push -u` 取基线逐个对比**，只看总数会误判。
- **浏览器**：Playwright MCP，`https://admin.cretaceousfuture.com`，`f006_admin`。
  登录态可能是别的租户（`demo_rest`），**先看右上角是不是 `f006_admin / 工厂总监`**。

---

## 9. 接手先跑这四条（不管这份交接怎么说）

```bash
# 1. main 有没有再前进 / CI 红没红
cd /c/Users/Steve/cretas-bom-unit && git fetch origin --quiet && git log --oneline origin/main -3
gh run list --branch main --limit 5

# 2. 前端基线（本文说全绿, 自己确认）
cd web-admin && npx vue-tsc -b --force && npx vitest run 2>&1 | tail -3

# 3. Java 基线（红的, 记下数字再改代码）
cd ../backend/java/cretas-api && export JAVA_HOME="C:/Program Files/Zulu/zulu-21"
mvn clean test -Dtest='Bom*Test' 2>&1 | grep -E "Tests run:.*Failures|BUILD"

# 4. prod 副产链路现状（阳性对照: 总数应非 0）
ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -c \\\"SELECT count(*) total, count(*) FILTER (WHERE is_byproduct) bp FROM raw_material_types;\\\"\""
```

`-`（skipped）≠ `✓`（passed），看 CI 要看清**绿的是哪几个 job**
（`vue-build-check` 长期 skipped，别当它过了）。

---

## 10. 建议的下一步顺序

1. **问 Steve `aria-disabled` 修不修** → 修完把「报工 → 生产小结」一次走完（这是他当前最想要的）
2. 走通后顺带验证盘点副产区（届时会有第一条真副产批次）
3. `bom_recipes.output_unit` 中文化 migration（🔒 待 Steve 拍板）
4. 成本报表单列抵扣行（spec §8.2/8.3）
5. 清理第 7 节的验收数据

---

## 11. 红线（不变）

DB migration / 权限 RLS 多租户 / **成本财务口径** / 资金路径 / 撤回冲销 → **默认只记录不修**，报告里标 🔒。

⚠️ Steve 本 session 逐次授权过：merge + 部署 prod + prod 写入（建 SKU / 建计划 / 激活 BOM 版本）+
Playwright 写操作。**那是逐次授权，不自动延续。** 默认是「实现 + 自测 + 出报告为止」。
但他明确说过**不要每个 PR 都停下来等人工 review**（PR + 实测证据 + 失败模式安全，三条齐了可以自己合）。
