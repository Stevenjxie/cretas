# 交接：六膳门清场（第二步未部署）+ OA 两个问题

**日期**: 2026-08-01
**上一份**: `HANDOFF-2026-08-01-byproduct-and-flow.md`（副产链路），本文接它

---

## ⚠️ 先读这条

**这份也不要全信 —— 先跑文末「接手先跑这五条」。**

今天最贵的教训是交接和通知都会骗人：

1. **部署的后台通知报 `exit code 0`，实际 `DEPLOY_EXIT=1`** —— 通知给的是整条命令链的退出码，不是闸门的。今天因此**两次**差点报告「已部署」而实际根本没部署（origin/main 在部署途中被并发 session 推进，strict git gate 正确中止）。
   → **必须**像下面这样单独抓，并核 `RELEASE_FINAL_STATUS`。
2. 上一份交接把「副产声明」和「验收计划」并列写得像同一个计划，实际是**两个不同 SOP** —— 照着走会得出「副产没落库=有 bug」的错误结论（真相是那个 SKU 的 BOM 本来就没声明副产，materializer 正确跳过）。

---

## 1. 六膳门现在是什么状态

**已清空**（`V20261029_43`，已上 prod 并核实）：

| 对象 | 清理前 | 现在 |
|---|---|---|
| production_plans | 8 | **0** |
| bom_recipes | 13 | **0** |
| product_process_workflows | 18 | **0** |

**完好保留**（重建的基础）：原料 229 / 成品 152 / 库存批次 27 / 仓库 7。
**批次单位零不一致**（清理时顺带对齐了 6 条 `YL-元益漫-黄油鸡` 的批次）。

**全部软删，可精确回滚**：台账表 `backup_lsm_cleanup_20260801` 记了本次删的每个 id（10 类共 231 条），
回滚脚本 `db/manual-rollback/V20261029_43__..._rollback.sql` 按台账还原 ——
**只还原这次删的**，不碰库里原有的历史软删行（这正是当初记台账的原因）。

---

## 2. 🔴 清场第二步已写好但**刻意没部署**（最重要）

Steve 最终口径不是重建，是**再删干净**：
> 「就留单位逻辑 sku 原料字典，其他安排的订单和工序workflow全部删除」

`V20261029_44`（PR #2135 分支上，**未合并未部署**）会删：
sales_orders(2+items) / purchase_orders(6+items) / material_batches(27) /
finished_goods_batches(2) / factory_stocktakes(2+items)。发货单类实测已是 0。
保留：raw_material_types(229) / product_types(152) / unit_of_measurements / factory_warehouses(7)。

### ⛔ 为什么没部署 —— 部署前必须确认这条

写完时发现 **Steve 正在实时使用六膳门**：
`SO-20260801-0001 ¥2015.00（胖东来）` 是 2026-08-01 10:25 新建、**正在走 OA 审批**的订单。

迁移按 `factory_id='LIUSHANMEN' AND deleted_at IS NULL` **动态选行**（没有写死 id），
所以**会把这张在办的订单一起删掉**。

→ **部署前必须先问 Steve：当前在办的单据处理完了没有。**

---

## 2b. 🔴 OA 两个问题（Steve 截图，交代「下一个 chat 一起处理」）

截图：六膳门 → 个人 OA → 待我审批

**问题 1：发起人不能审批自己的单**
报错「发起人不能审批自己的销售订单，请由当前 OA 节点授权的其他审批人处理」。
`SO-20260801-0001` 的申请人是 `liushanmen_admin`，当前节点授权角色是「工厂总管理员」——
**同一个人**。Steve 明确说：**admin 应该可以给自己审批**。
（他已让另一个 chat 先处理这次审批，修复留给下一个 chat。）

**问题 2：审批内容看不懂**
第二行显示：
| 业务类型 | 业务单据 | 申请人 |
|---|---|---|
| **未知状态（BUDGET）** | `BUDGET b67922a2-e4b9-4143-bd6e-33d42ed98ae0` | *(空)* |

—— 业务类型是「未知状态」、单据只有一个 **UUID**、申请人空白，
只有一个禁用的「只读」按钮，悬浮提示「该业务域正在接入统一 OA，当前仅可查看审批进度」。

这与今天已修的那类问题同源（见第 4 节 #2130 / #2135）：**报错/展示甩数据库标识给用户**。
修的方向也一样 —— 业务类型要有中文名，单据要显示可读编号，申请人不该空。

---

## 3. 原本要重建什么（Steve 后来改口径了，仅存档）

覆盖三种形态，用于跑纯 headed E2E：

- **多原料 → 一成品**
- **一原料 → 多成品**
- **多工序**

⛔ **不测副产**（Steve 明确说不用）。
📌 **不用照真实配方**（Steve：「没关系，反正先让业务流程统一」）—— 可以自己设计样例。
🔴 **但重点是**：缺东西时报错要**精准指到模块**，让用户知道去哪改，而不是一串看不懂的提示。

### 建议顺序（有依赖关系）

1. **先建 workflow**（工序链 + 端口），因为 BOM 要挂 workflow
2. **再建 BOM**，激活生效版
3. **最后建生产计划**，走报工

### ⚠️ 建的时候盯这几点

- **端口单位**：清理前六膳门的 `workflow_task_ports` 是**中英混写**（`box/pcs/bag` 与 `盒/袋/只` 并存）。重建时统一，别再混。
- **BOM 产出单位必须等于产出 SKU 的单位** —— 今天两条迁移（`_39` / `_41`）修的就是这个不一致。
- 有 `workflow_templates` 表和 `WorkflowTemplateService`，**可能**能省掉手搓（我没验证过能不能用来复制 F006 的 workflow）。

---

## 3. 🔴 一个还没解决的业务问题（会挡住发货）

**Steve 今天截图**：六膳门 `SO-20260709-0001`「干式熟成鸡（半只）」建发货单失败。

**根因（已查清）**：

```
删 SKU（硬删，deleteProductType 那里只有一条从没实现的 TODO）
  ↓ sales_order_items / bom_recipes 没有外键 → 删除不被拦
销售订单明细留着死 product_type_id，页面看着一切正常
  ↓ 建发货单时 sales_delivery_items 有外键 → 插入被拒
报错只给数据库表名「product_types」，用户无从知道是哪个产品、去哪修
```

**代码侧已修，在 PR #2135（未合并）**：删除侧补引用检查 + 报错接上本来就有的 `FK_MODULE_MAP`。

**数据侧没动，需要业务决定**：全租户 **29 条**悬空引用
（F001: sales_order_items 18 / finished_goods_batches 9 / bom_recipes 1；LIUSHANMEN: sales_order_items 1）。
六膳门那条是 400袋、已发 0、**订单已财务审批**。
→ 要么补建「干式熟成鸡（半只）」这个 SKU 让订单走完，要么作废该行。
原料侧 `material_batches` **零悬空**，不用担心。

---

## 4. 今天上了 prod 的（都逐条核过，不是看回执）

| PR | 内容 |
|---|---|
| #2115 | `aria-disabled` 陈旧 —— EP 只在 `onMounted` 写一次；**升级 EP 修不了**（2.14.3 同样） |
| #2123 | BOM 单位对齐 SKU（17 行）+ 缺料 409 文案不再漏 `7box` 给客户 |
| #2125 | 补齐名录缺的 6 个计数单位（`roll/slice/portion/crate/pail/item`） |
| #2127 | 叮咚好食光 5 条 BOM「份」→「盒」（Steve 拍板以 SKU 为准） |
| #2130 | BOM 报错指到模块 + 删悬空 BOM + 补「张」+ **六膳门清理** |

**未合并**：#2135（发货单根因 + 单位口径三条，按交接约束等终审）。

---

## 5. 🔴 单位这条线的架构结论（重要，别再走弯路）

**单位有两个来源，运行时是两层的**：

```java
CanonicalUnit catalogUnit = catalog.units().get(key);   // ① 先查 DB 名录 unit_of_measurements
if (catalogUnit != null) return ...;
String code = SYSTEM_ALIASES.get(key);                  // ② 查不到才落 Java 硬编码兜底
```

catalog 来自 `factoryId IN (:factoryId, '*')` → **DB 名录优先，硬编码只是兜底**。
业务加单位**只改 DB 表，不用发版**；工厂还能建私有单位（F006 的「半只」）。

**🔴 英文码持续重新流入的真根因（今天查明）**：

| 出处 | 方向 |
|---|---|
| `V20261029_32` migration | 把数据改成**中文** |
| `RawMaterialTypeServiceImpl#normalizeInventoryUnit` → `normalized.code()` | 把输入归一成**英文码** |

**两者方向相反**，所以数据永远收敛不了。`box` 躺在库里**不是脏数据，是写入路径主动写的**。
→ 看到「migration 修过又漂回去」，先去看**写入侧归一成什么**，别急着再写一条 migration。

**两个不对称**（未修）：
- `ProductTypeServiceImpl` 的 unit **没有任何归一**（裸传），与 raw_material_types 有闸不对称
- 名录 `ton` vs 权威表 `t` 是**同一物理单位两个码**；名录另缺 `km`

**⏸ 未决**：存储以英文码为准（写入侧现状，展示层翻中文）还是以中文为准（要改 `normalizeInventoryUnit` + 回退他人 migration）。
**任一方向都要先定，再动数据**，否则又是一轮自我撤销。我的建议是前者。

---

## 6. 环境要点

- **prod 库名** `cretas_prod_db`。连法（**别用嵌套转义，把 SQL 写文件再 scp**，今天多次栽在转义上）：
  ```bash
  scp probe.sql root@47.100.235.168:/tmp/probe.sql
  ssh root@47.100.235.168 "chmod 644 /tmp/probe.sql && su - postgres -c 'psql -d cretas_prod_db -f /tmp/probe.sql'"
  ```
- **写 migration 前必做**：把真 SQL 放进 `BEGIN; … ROLLBACK;` 在 prod 跑一遍干跑。
  今天靠它抓到一个反直觉陷阱：按字面「中文化 output_unit」会**修好 10 行同时弄坏 6 行**。
- **本机跑 Java**：`export JAVA_HOME="C:/Program Files/Zulu/zulu-21"`；发布还要 `LC_ALL=C`
- **部署**（⚠️ 单独抓退出码 + 必须核 `RELEASE_FINAL_STATUS`）：
  ```bash
  cd /c/Users/Steve/cretas-deploy-0730 && git fetch origin --quiet && git checkout --detach origin/main
  export LC_ALL=C JAVA_HOME="C:/Program Files/Zulu/zulu-21"
  ./scripts/deploy/release-cretas.sh --phase deploy --base-sha "$(git rev-parse <上次部署的commit>)" \
    --tests '<真实测试类名>' --confirm-prod YES-PROD > /tmp/deploy.log 2>&1
  echo "EXIT=$?" > /tmp/deploy.exit    # ← 不要在同一条命令里接别的
  ```
  🔴 `--base-sha` **必须 40 位全长且真实存在**（我今天拼了个假 SHA，脚本报 `Base SHA cannot be resolved` 挡住了）。
  🔴 **并发极密**：今天 origin/main 被推进 **6 次**，部署被 git 闸拦下 **2 次**。部署前一定重新 fetch + re-detach。
- **浏览器**：Playwright MCP，`https://admin.cretaceousfuture.com`，`f006_admin`（六膳门是 `liushanmen_admin`，见截图）。
  **先看右上角租户对不对**。

---

## 7. 接手先跑这五条（不管这份交接怎么说）

```bash
# 1. main 到哪了 / CI 红没红
cd /c/Users/Steve/cretas-bom-unit && git fetch origin --quiet && git log --oneline origin/main -3
gh run list --branch main --limit 5

# 2. 六膳门是不是真的空了 (应 0/0/0)
#    以及主数据是不是真的还在 (应 229/152/27)  —— 用第 6 节的 scp 方式跑

# 3. #2135 还开着没 (发货单根因修复, 未合并)
gh pr view 2135 --json state,title

# 4. 前端基线 (今天全绿)
cd web-admin && npx vue-tsc -b --force && npx vitest run 2>&1 | tail -3

# 5. Java 基线是红的 (既有 Bom*Test 等), 改代码前先取基线数字
cd ../backend/java/cretas-api && export JAVA_HOME="C:/Program Files/Zulu/zulu-21"
mvn clean test -Dtest='Bom*Test' 2>&1 | grep -E "Tests run:.*Failures|BUILD"
```

`-`(skipped) ≠ `✓`(passed)。`vue-build-check` 在非 full_audit 时长期 skipped，别当它过了。

---

## 8. 红线

DB migration / 权限 RLS 多租户 / **成本财务口径** / 资金路径 / 撤回冲销 → 默认只记录不修，报告里标 🔒。

⚠️ Steve 本 session 逐次授权过：merge + 部署 prod + prod 写入（建 SKU/计划/盘点、激活 BOM 版本、清理六膳门配置）。
**那是逐次授权，不自动延续。** 但他明确说过**不要每个 PR 都停下来等人工 review**
（PR + 实测证据 + 失败模式安全，三条齐了可以自己合）。
#2135 是例外 —— 它来自另一 session 的交接卡，卡上写明「不许自 merge、不许自部署」。
