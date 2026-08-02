# 交接：六膳门清场第二步已上 prod + OA 两问题根因已定位（未修）

**日期**: 2026-08-01 下午
**上一份**: `HANDOFF-2026-08-01-liushanmen-rebuild.md`（本文接它，并**回答了它的阻断问题**）

---

## ⚠️ 先读这条

上一份交接说「清场第二步刻意没部署，部署前必须先问 Steve 当前在办的单据处理完了没有」。

**Steve 已答复**：「审批的内容留一下，其他的全部清楚然后部署」。**已照此执行并上 prod。**

同样别全信本文，先跑文末「接手先跑这四条」。

---

## 1. 已完成：六膳门清场第二步（V20261029_44）

**PR #2135 已合并**（squash = `f72ae6f315`），Java + Web 均已上 prod。
Flyway `20261029.44` 于 **2026-08-01 11:05:52** 执行成功。

### 现在六膳门是什么状态（逐条实测，不是看回执）

| 对象 | 清理前 | 现在 |
|---|---|---|
| sales_orders | 2 | **1**（只剩保留单） |
| sales_order_items | 2 | **1**（item 733） |
| purchase_orders (+items) | 6 (+6) | **0 (0)** |
| material_batches | 27 | **0** |
| finished_goods_batches | 2 | **0** |
| factory_stocktakes (+items) | 2 (+3) | **0 (0)** |
| production_plans / bom_recipes / workflows | 0/0/0（第一步已清） | **0/0/0** |

**主数据完好**：原料 229 / SKU 152 / 仓库 7 / `unit_of_measurements` 未动。
**他厂未受影响**：其余工厂 478 张活销售订单原样。

### ⛔ 刻意保留的那张单

    SO-20260801-0001   id=1e3c67f0-5692-420d-a7ff-458cc5be7b48
    酱鸭腿 130 × ¥15.50 = ¥2015.00（胖东来）
    status = FINANCE_APPROVED（Steve 于 10:41:03 手工放行）
    OA 实例 6a6374ed-… = APPROVED
    sales_order_items id=733 一并保留

已核过它的 **OA 审批实例**与**凭证关联**（`business_links` → 凭证 V-2026-0011）都指向活单，**不悬空**。

🔴 **迁移里是写死 id 排除，不是按审批状态动态判定** —— 这个选择被现实当场验证：
写交接时它还是 `PENDING_FINANCE_REVIEW` / OA `RUNNING`，部署时已变成 `FINANCE_APPROVED` / `APPROVED`。
若当初写「保留有 RUNNING 审批实例的单」，**部署那一刻谓词会翻面把它删掉**。

### 顺带解决的

被删的 `SO-20260709-0001` 正是上一份交接 §3 那张**挂着死 `product_type_id`、建发货单必炸**的订单
（探针实测其 item 711 的 `product_name` 为空）。六膳门那 1 条悬空引用随之清掉。
**F001 的 28 条悬空引用仍在**（sales_order_items 18 / finished_goods_batches 9 / bom_recipes 1），未处理。

### 回滚

台账 `backup_lsm_cleanup_20260801`，`v44:` 前缀 **8 类共 48 行**，与第一步（裸表名 object_type）互不干扰。
回滚脚本：`backend/java/cretas-api/src/main/resources/db/manual-rollback/V20261029_44__cleanup_liushanmen_orders_and_stock_rollback.sql`
已在 prod 单事务内跑过 **[迁移 → 回滚 → ROLLBACK]** 往返，计数精确回到 2/2/6/6/27/2/2/3。

---

## 2. ⚠️ 一个业务后果，Steve 需要知道

六膳门现在**整体零库存**，且酱鸭腿这个 SKU **本来就 0 个成品批次**。
所以保留的那张单 **审批走得完，但走不到发货**。若要让它跑完整链路，得先重建 workflow → BOM → 生产计划 → 报工出成品。

---

## 3. ✅ OA 两个问题：已修（PR-1 已上 prod / PR-2 待终审）

> 本节最初写的是「根因已定位，代码一行没改」。后续 Steve 说「都修复吧」，已按 spec
> `docs/superpowers/specs/2026-08-01-oa-self-approval-and-budget-design.md` 实现，拆两个 PR。

### PR #2141 —— 自审例外统一 ✅ 已合并（`2db96d3ea1`）并上 prod

已核过运行 jar 字节码：`SelfApprovalPolicy.class` 在、`allowsSelfApproval` 在（阳性对照）、
旧私有方法 `isExplicitCurrentNodeApprover` 已从 `PurchaseServiceImpl` 消失（反向对照）。

🔴 **实现时发现事实与本文初稿不同**：不是「一处修过两处没修」，而是**三处各自长出了半个例外**：

| | 原有例外 |
|---|---|
| 采购 | 只认「节点显式点名」 |
| 调拨 | 只认「工厂超管」 |
| 销售 | **两个都没有** |

两个方法都是 private 且各在各家，谁也复用不了谁。Steve 拍的「点名 **或** admin」恰好是这两半的并集。
已提取到 `service/workflow/SelfApprovalPolicy.java`，三处统一委托。

🔴 **承载点是 6 处不是 3 处**。按中文措辞 grep 会漏 —— 盘点那两处文案是
「发起人**、盘点录入人或提交人**不能审批自己」，中间插了字。**要按 error code 后缀
`SELF_APPROVAL_FORBIDDEN` 找**。盘点 2 处（409、仅盘盈亏时拦、判据含录入人与提交人）
与撤回冲销 1 处是**有意差异化，刻意未动**，已由 `SelfApprovalCarrierContractTest` 锁住 ——
新增第 7 处时会强制先做「统一 or 独立」的归类。

### PR #2144 —— 展示可读 + BUDGET 接入关账 🔒 **未合并，等终审**

🔴 **合并前必读**：接上之后，**在待办点一下「通过」= 执行月度关账**（期间转 CLOSED、
生成库存台账快照、凭证进入 20 天调整窗口、逾期硬锁）。影响**所有工厂**，
而六膳门与 F006 正在走客户测试。已加 warning 类型的特化确认文案。
驳回语义 = 回 `OPEN`；已 CLOSED 时拒绝（反结账走 `reopenPeriod`，不能被一次驳回掀翻）。
无 migration，revert 即可回滚。

另三个成因也都在这个 PR 里修了，且**每个都和截图看起来的不一样**：
- 「未知状态（BUDGET）」**不是漏了一个码** —— 权威表 30+ 个 moduleCode 各带中文名，
  前端手抄了 4 个，**另外 20 多个码同样会显示「未知状态（X）」**，只是还没人点到。
- 「申请人空白」**不是 bug** —— 定时任务发起，`initiated_by` 本就是 NULL。
- 「只读」标签**不是纯 UI 缺陷** —— BUDGET 的 OA 实例是**孤儿**，`requestClose` 启动它
  但批不批都不影响期间，这是 fail-open 的合规设计。

---

## 3b. 📌 原始定位记录（供对照，其中「调拨无例外」一条已被实现推翻）

（上一份交接 §2b。Steve 说「下一个 chat 一起处理」，本轮只做到定位。）

### 问题 1：发起人不能审批自己的单

`SalesServiceImpl.java:1125`：

```java
if (actorId != null && actorId.equals(instance.getInitiatedBy())) {
    throw new BusinessException(403, "发起人不能审批自己的销售订单")
        .withCode("SALES_SELF_APPROVAL_FORBIDDEN")
```

**无条件拒绝，没有任何 admin 例外。**

🔴 **这是「一个闸多处承载」的典型，同形代码有三处**，要放开必须一起看，否则会变成销售单能自批、采购单还是不能：

| 文件 | 行 | 单据 |
|---|---|---|
| `SalesServiceImpl.java` | 1126 | 销售订单 |
| `PurchaseServiceImpl.java` | 682 | 采购单 |
| `TransferServiceImpl.java` | 712 | 调拨单 |

⚠️ **另一份卡（fan-out）里给出了更深一层的结构性成因**，与本条是同一件事的两面：
节点 `admin_approval` 的 `approverRoles=[factory_super_admin]`，而 LIUSHANMEN **只有一个** super_admin（id 1638，就是发起人）
→ 结构性无法审批。**Steve 明确说那一轮不治**，且改 `approval_workflows.nodes_json` 会让在飞实例撞 definition-digest 校验 409。
→ 所以修法要选：放开 admin 自审（改上面三处）**还是**改 OA 节点角色配置。**两条路别同时走。**

### 问题 2：审批列表「未知状态（BUDGET）」+ 申请人空白

截图那条是 `153f4e39-…`，`module_code=BUDGET`，`business_entity_id=b67922a2-…`。

- **「未知状态（BUDGET）」**：`web-admin/src/utils/enumDisplay.ts:299` 的兜底 `未知状态（${normalized}）`。
  `BUDGET` 这个 module_code 没进分域表。注意**已有契约测试** `enumDisplayCoverage.spec.ts` 专门防这类漏网，补的时候连测试一起补。
- 🔴 **「申请人空白」不是 bug，别去追一个不存在的申请人**：实测该实例 `initiated_by` 就是 **NULL**，
  `initiated_at = 2026-08-01 02:00:00`，context = `{"year":2026,"month":7,"entityType":"ACCOUNTING_PERIOD"}`
  —— 它是**凌晨 2 点定时任务自动生成的月度会计期间审批**，本来就没有人类发起人。
  正确修法是显示「系统自动发起」之类，而不是补数据。

---

## 4. 🔴 发布链路的三个坑（本轮实测，别重复踩）

1. **`--tests` 只收显式测试类名，不收通配符**。`ProductType*Test` 被 preflight 直接拒：
   `ERROR: --tests only accepts explicit test classes for release preflight`。
   取真实类名最稳的办法：从本地 `mvn` 日志抓 `in com.cretas.aims.X`（顺便证明它们是绿的）。
   本轮用的 21 个类 = `ProductType*` 10 + `GlobalExceptionHandler*` 9 + `UnitContract*` 2，共 176 个测试全绿。

2. 🔴 **exact-main 闸拦截时是「静默」的，且回执会误导你**。
   并发 session 的 #2136 于 `10:57:24 CST` 合入 main，正落在我 3.5 分钟的构建窗口里
   （10:54:50 起 → 11:01:27 构建完）。构建后的闸正确拒绝用陈旧制品部署，但是：
   - 日志里**没有任何 ERROR**（`origin/main moved` 那句没打印出来）
   - 回执 JSON 里 `main_guard: "passed"`、`drift_recoveries: 0` —— **看起来反而是过的**
   - 真信号只有 `deploy_mode: "none"` + 两边 `deploy: "not-selected"` + **收尾的 `RELEASE_*` printf 一行都没出现**
     （脚本在 `run_deploy_phase` 内部被 `set -e` 掐掉，回执 JSON 是 EXIT trap 写的）

   → **只认 `DEPLOY_EXIT` 和 `RELEASE_FINAL_STATUS` 两个**。
   `RELEASE_FINAL_STATUS` **不出现**本身就是失败信号，别去回执里找 `status` 字段当结论。
   → 后台任务通知的 exit code **三次全是 0**，而真实 `DEPLOY_EXIT` 有两次是 1。

3. **CI 制品覆盖不了你点名的测试类就会回退本地构建**（`ci_selector_does_not_cover_requested`，
   因为制品的 `manifest_target_tests` 是 `'*RepositoryQueryValidationTest'`）。
   **这是正常回退不是失败**，但它把构建窗口拉到 3.5 分钟，正是撞上 main 漂移的原因。
   重跑时若 backend tree 未变（对方只改 docs），制品全复用，**159s 一次过**。

---

## 5. 本轮上 prod 的

| PR | 内容 |
|---|---|
| #2135 | 删产品前查引用 + 外键报错指到模块 + 单位口径三处收敛 + **V20261029_44 六膳门清场第二步**（含回滚脚本） |
| （顺带）#2133 / #2136 | 并发 session 的餐饮遗留四条卡，按 prod=main 铁律一并上线 |

---

## 6. 进行中：销售财审 fan-out 丢写（另一 chat / subagent）

Steve 另开了一张卡查「销售订单财务审核通过」的事件 fan-out 为什么丢写
（`sales_order_shortage_report` 建表至今 0 行、`PP-AUTO-*` 最后一行停在 2026-04-15）。
本轮实测可作为该卡的旁证：**六膳门 `production_plans = 0`、`sales_order_shortage_report = 0`**，
且 SO-20260801-0001 于 10:41:03 财审通过后 `business_links` **只多了 VOUCHER 一条**（凭证 V-2026-0011），
PRODUCTION_PLAN 那条确实没有 → 与「orchestrator 整个事务回滚」一致。

🔒 该卡的收尾约束：**只做到实现 + 自测 + PR，不许自 merge、不许自部署、不许跑数据回填。**

---

## 7. 接手先跑这四条（不管本文怎么说）

```bash
# 1. main 到哪了 / CI 红没红
cd /c/Users/Steve/my-prototype-logistics && git fetch origin --quiet && git log --oneline origin/main -3
gh run list --branch main --limit 5

# 2. 六膳门是不是真的清了 —— 期望 sales_orders=1(只剩 SO-20260801-0001), 其余 0, 主数据 229/152/7
#    用下面的 scp 方式跑, 别用嵌套转义
#    scp probe.sql root@47.100.235.168:/tmp/probe.sql
#    ssh root@47.100.235.168 "chmod 644 /tmp/probe.sql && su - postgres -c 'psql -d cretas_prod_db -f /tmp/probe.sql'"

# 3. 活跃槽位现在是 blue(10010) —— 蓝绿会交替, 别假设
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "systemctl is-active cretas-backend cretas-backend-green"

# 4. 前端基线
cd web-admin && npx vue-tsc -b --force && npx vitest run 2>&1 | tail -3
```

`-`(skipped) ≠ `✓`(passed)。`vue-build-check` / `python-lint-test` / `rn-test` 在非 full_audit 时长期 skipped，别当它们过了。

⚠️ **应用日志在 `/www/wwwroot/cretas/logs/cretas-backend.log`**；`cretas-prod.log` 是死文件，
**grep 不存在的文件永远返 0** —— grep 前先 `ls -lh` 确认存在且新鲜，或先 grep 一个你确定有的串当阳性对照。

---

## 8. 红线

DB migration / 权限 RLS 多租户 / **成本财务口径** / 资金路径 / 撤回冲销 → 默认只记录不修，报告里标 🔒。

⚠️ Steve 本 session 授权过：merge #2135 + 部署 prod（他在被问「整个 #2135 一起上 还是 只上清场迁移」时选了前者）。
**逐次授权，不自动延续。** 但他明确说过不要每个 PR 都停下来等人工 review
（PR + 实测证据 + 失败模式安全，三条齐了可以自己合）。

---

## 9. 还没做的（按优先级）

1. 🔒 **终审 PR #2144**（BUDGET 关账，见 §3 —— 会让所有工厂的期间审批变成「可一键关账」）
2. 🔴 **fan-out 的 Bug 2 没人修**（PR #2140 只修了 2/77 那条）——
   `com.cretas.aims.dto.orchestration.LineItemMatch.isFullySatisfied()` 是无 backing field
   的 computed getter，Jackson 序列化进 JSON 而 Hibernate 的 JSONB deep-copy 反序列化不回来，
   抛 `UnrecognizedPropertyException`，**SQL 根本没发出去**。这条占 77 次里的 **76 次**，
   是 `sales_order_shortage_report` 建表至今 0 行的主因。
   **只合 #2140 解决不了主要问题**；69 单回填也被它阻塞（回填会撞同一个失败）。
3. **六膳门重建**：workflow → BOM → 生产计划 → 报工。上一份交接 §3 存档了原计划
   （多原料→一成品 / 一原料→多成品 / 多工序；⛔ 不测副产；不用照真实配方）。
   ⚠️ 端口单位重建时统一，别再中英混写；BOM 产出单位必须等于产出 SKU 的单位。
3. **F001 的 28 条悬空引用**（业务决定：补建 SKU 还是作废行）
4. **单位存储口径未决**：以英文码为准（写入侧现状）还是以中文为准。
   **任一方向都要先定再动数据**，否则又是一轮自我撤销。上一份交接 §5 的建议是前者。
