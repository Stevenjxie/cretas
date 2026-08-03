# 交接 — 单位口径治理 + F006 双拓扑闭环 + 6 条遗留问题（2026-08-03 整夜，12 个 PR 全部已上线）

**状态**: ✅ 无待合并、无待部署、无阻塞。剩 2 件非紧急，均已写明为什么刻意没做。
**上一份**: `docs/dispatch/handoff-2026-08-03-warehouse-fixes.md`（仓储六处修复）、
`docs/dispatch/handoff-2026-08-03-unit-governance.md`（单位线前半段）

---

## 0. 一句话现状

线上 Java jar = `fb0ca03d0b`（含本轮全部后端改动），**`backend/java` 无未上线差异**；
Web 线上 = `b14e9887d2`，落后的 6 个文件全是别的 session 的餐饮改动（#2255/#2257/#2258），不归本线。
四个服务（backend / python / embedding / logistics）全 active。

---

## 1. 本轮 12 个 PR（全部 MERGED + 已部署 + prod 实测）

| PR | 内容 | prod 判据 |
|---|---|---|
| #2220 | 仓储/结单六处修复 | 结单 409→COMPLETED；过期提示 expired:300 |
| #2222 | 交接文档 | — |
| #2230 | 单位落库口径收敛到 `storageUnit` + 自定义单位纯中文 + **P0-1 写入侧真根因** | jar 字节码核过 5 处 |
| #2232 | 只/个/件 = 三个单位，还原 113 行档案 | 五条判据全中（含反向：box 8 / case 7 中文 0）|
| #2236 | 带鱼 2 行 `箱`→`kg`（羊排刻意不动）| 改 2 跳 0；混写 3→1 |
| #2237 | 包装单位批次不再静默消失（第 1 层栅栏）| 单测绿，**但线上没生效**（见 §3）|
| #2238 | 拆第 2、3 层栅栏 | `expiredElsewhere=[{原料仓,300,kg}]` |
| #2240 | 单位线交接文档 | — |
| #2241 | **凭证号按「已占用最大序号」生成** | 期初建账拿到 `V-2026-0303`（预测值）|
| #2247 | 部署锁探活改 winpid + Get-Process | 上线后**当晚自清 5 把陈旧锁** |
| #2249 | SKU 大类创建期必填 + 收货凭证缺失禁用按钮 | jar 核过 `PRODUCT_CATEGORY_REQUIRED` |
| #2254 | 副产品 BOM 可激活 + 包材必填一次收齐 + 物料节点缺 SKU 不静默 | **副产品 BOM 史上首次 ACTIVE** |

---

## 2. Steve 本轮两条拍板及其**作用域边界**

### 拍板 1：「单位存中文」→ 查存量后改为**分两步**

原话是「存中文」。prod 实测：`raw_material_types.unit` 766 行 **100% 英文码**、
`product_types.unit` 771 行只有 1 行中文、`material_batches.quantity_unit` 885 行中文仅 11 行。
全量中文化要动 **~2400 行**，而它要治的中英混写只有 **11 行**。摆出这个数后改为
**「先自定义单位存中文，内置单位存量不动」**。

落地在 `UnitContractService#storageUnit`（全系统唯一承载点）：
```
1 权威表认不出        → 原样 trim
2 工厂自定义单位      → displayName 中文名
3 内置单位            → code 英文码（存量不动）
```

### 拍板 2：「只 ≠ 件，算两个单位」

⛔ **作用域仅限「数量 / 库存」。** Workflow **槽位匹配**侧
（`BomWorkflowRevisionService#canonicalUnit` → `canonicalCodeOrRaw`）**刻意仍把 件/个/只 折成 pcs**，
因为它判的是「这个投入槽还在不在」，本就要认本地化写法；既有契约
`localizedCountUnitMatchesCanonicalBomUnitDuringStableSlotRekeying` 明确断言 `unitsCompatible("pcs","只")` 为 true。

**这个不对称是设计，不是遗漏。** 已加测试 `ScopeIsInventoryNotSlotMatching` 钉住。

---

## 3. 三条最值得记的技术事实

### 3.1 P0-1 真根因在**写入侧**，不在查询侧

`PurchaseServiceImpl` 建批次时 `batch.setQuantityUnit(inventoryUnit)` **完全没有归一** ——
采购单行写什么就抄什么；而档案侧走权威归一存 `box`。
所以最初那个「放宽调拨查询侧归一」的修法**方向本来就错**，已撤回。

> **判据：两处值对不上时，先看两处分别是谁写进去的，别先改读的那一侧。**

### 3.2 那个缺陷有**三层栅栏**，#2237 只拆了第一层

部署 #2237 后去验，羊排那 100kg **仍然不可见**：

| 层 | 位置 | 挡住的原因 |
|---|---|---|
| 1 | `unitMatches` 不认包装单位 | #2237 拆 |
| 2 | `findAvailableBatchesFEFO` 只取 `status='AVAILABLE'` | 过期的取不出来 |
| 3 | `findExpiredBatchesByWarehouse` 只取传入的那一个仓（调用方传生产仓）| 别的仓取不出来 |

第 2、3 层在**仓储查询**里，**早于任何单位判断执行**——单元测试怎么测都测不到。

> 讽刺：第 3 层那条查询的 Javadoc 写的动机就是「实测 F006 羊排**在原料仓**有 100kg 但全部 EXPIRED」，
> 可它的实现按**生产仓**过滤。**实现与自己写的动机相反。**

### 3.3 「期初建账每天只能一次」是误判

不是每日额度。`generateVoucherNumber` 用「未删条数 + 1」编号，而 repository 那行注释写的是
「factory + year **最大序号**」——**注释说最大号，代码数条数**。
F006 2026 年凭证：总 **302** 条 / 未删 **66** / 最大号 **V-2026-0302** → 生成 `V-2026-0067`，早被占了，
**每次算出同一个号 = 永久撞**。修后立刻拿到 `V-2026-0303`。

影响面不止期初建账 —— `generateVoucherNumber` 有 2 个调用点，该工厂**凭证生成整体已废**过一段时间。

---

## 4. prod 数据现状（可直接复验）

```sql
-- 单位混写: 应为 1 行, 且是羊排那条「合法的包装单位存量」
SELECT b.factory_id, b.batch_number, rt.name, rt.unit AS 档案, b.quantity_unit AS 批次
FROM material_batches b JOIN raw_material_types rt ON rt.id=b.material_type_id
WHERE b.deleted_at IS NULL AND rt.deleted_at IS NULL
  AND b.quantity_unit IS DISTINCT FROM rt.unit
  AND (b.quantity_unit ~ '[一-龥]' OR rt.unit ~ '[一-龥]');

-- 计数单位: 应为 个67 / 只3 / 件2, pcs 为 0
SELECT unit, count(*) FROM raw_material_types
WHERE deleted_at IS NULL AND unit IN ('pcs','个','只','件') GROUP BY 1;

-- 反向: 盒/箱 不得被还原成中文 (应 box 8 / case 7, 中文 0)
SELECT unit, count(*) FROM raw_material_types
WHERE deleted_at IS NULL AND unit IN ('box','case','盒','箱') GROUP BY 1;

-- 副产品 BOM: 应有 1 份 ACTIVE (史上第一份)
SELECT output_role, status, count(*) FROM bom_recipes WHERE deleted_at IS NULL GROUP BY 1,2;
```

**F006 两条产线均已跑完闭环**（两种拓扑都验到了）：

| 计划 | 拓扑 | 终态 |
|---|---|---|
| `PLAN-1785758730933-F5EFE66E`（猪蹄，wf 140） | 拆骨 **1入2出** → 卤制 **2入1出** → 拼装分装（跨单位 kg→盒 + BOM 包材）| 小结后逐层 USED_UP，成品 `FG-…-S1` **10 box AVAILABLE**，已停产关闭 |
| `PLAN-1785684091442-2992A755`（羊排，wf 138） | 撒料 **3入1出**（原料版多入单出，FEFO 拆 7 批）→ 冷冻 | COMPLETED + 仓库确认实收 `POSTED`，差异 0 |

关键实证：**SFI（半成品库）投料按设计在小结才扣** —— 报工时 `已耗 0.00`，小结后变 `2.00`。
（前一轮曾把这误报成「97.78 元凭空消失」，已撤回。）

---

## 5. ⏸ 刻意没做的 2 件（下一个人别当成遗漏）

### 5.1 包装单位批次**进不了可投量**（唯一有实质技术阻塞的）

```java
kgToStorageQuantity(kg, unit) { return "g".equals(unit) ? kg*1000 : kg; }
```

扣减侧只做 g↔kg，对「箱」是**原样返回**。放进可投量 → 100kg 的分配落到只有 10 箱的批次上
= **超扣 10 倍**，比原缺陷严重得多。

**当前边界**（已加测试 `displayIsWiderThanAllocationOnPurpose` 钉住）：

| 用途 | 含「箱」批次 |
|---|---|
| 可投量 / 自动分配 | ❌ |
| 「过期 X，不可投料」 | ✅ |
| 「原料仓另有 X」 | ✅ |

**要放开必须先做**：让 `kgToStorageQuantity` 走包装规格反算（`UnitContractService#convert` 已支持双向），
并连 `ProcessSheetServiceImpl` 的消费路径一起验。**独立一轮的量。**
当前影响面：全库仅 1 条这类批次且 EXPIRED，**不影响任何人**。

另：用户**显式选**一个包装单位批次仍报 `PRODUCTION_INPUT_BATCH_UNIT_MISMATCH` —— 那是**明确报错不是静默丢失**，同一族，等上面做完再放开。

### 5.2 主仓 `web-admin/node_modules/playwright` 被掏空

`playwright` 与 `playwright-core` **只剩 `lib/`，没有 `package.json`**，`require` 直接 MODULE_NOT_FOUND。
像是 `mklink /J` 那次连坐的残留。**不影响部署**（Web 构建在别的 worktree 跑），只影响在主仓跑 Playwright 脚本。

修法一行：`cd web-admin && npm install --prefer-offline --legacy-peer-deps`
**当时没做的原因**：机器上还有 86 个 node 进程在跑，动它会干扰别的 session。等空闲再做。

临时绕法（本轮一直这么用）：`require('C:/Users/Steve/cretas-bom-dup/web-admin/node_modules/playwright')`

---

## 6. 查证结论：P5 不成立（前一轮记错了）

`docs/dispatch/2026-08-02-f006-minloop-issues.md` 的 P5 说「BOM 三个入口互不指路」——**查证不成立**：

- `PUT /bom/recipes/{id}` 的 hint = 「请逐条编辑物料；批量导入需使用支持完整 BOM Family 的专用入口」
- `POST /bom/batch/add` 的 hint = 「请从 BOM 工作区的系统投入槽配置主料或替代料」
- `POST /bom/batch/modify` 返回体里带 `ecnId` / `ecnNumber` —— **走 ECN 是设计，不是静默**

其余 P4 / P6 / P7 / P8 / P10(P11) **五条均复现且已修**。

---

## 7. 🔴 发布流程：本轮踩了 6 次才用对

**每次部署都白编 5 分钟**，因为 `--tests` 点名了自己的测试类，对不上 CI 制品的选择器
（`manifest_target_tests='*RepositoryQueryValidationTest'`）。而那 5 分钟正是**让 main 前进、整轮作废**的原因——今晚因此作废过一轮（严格 git 闸报 `HEAD != origin/main`）。

### 正确三步（照抄）

```bash
# 0) 干净 worktree, HEAD 必须恰好 == origin/main
cd /c/Users/Steve/cretas-deploy-0803 && git fetch origin main -q && git checkout --detach origin/main -q

# 1) 预热(合并后立刻跑; --tests 必须用 CI 的选择器, 否则命不中制品)
./scripts/deploy/prewarm-main-artifact.sh --tests '*RepositoryQueryValidationTest' --wait 420
#    期望末行: PREWARM=done

# 2) 部署
./scripts/deploy/release-cretas.sh --phase deploy \
  --base-sha <上一次已上线的全 SHA> --tests '*RepositoryQueryValidationTest' --confirm-prod YES-PROD
#    期望: RELEASE_FINAL_STATUS=deployed   (实测 55s; 不预热则 412s 且可能被 main 前进作废)
```

### ⚠️ 三个判据陷阱（本轮全踩过）

1. **`--base-sha` 必须是真 SHA**，别拿短 SHA 补零凑 40 位（脚本会 `ERROR: Base SHA cannot be resolved`，干净失败无副作用）。用 `git rev-parse <short>`。
2. **`--tests` 里的类必须真实存在**，否则 preflight 报 `test selector has no source file`。
3. 🔴 **「没有 `RELEASE_FINAL_STATUS`」≠「什么都没做」。** 本轮那次 412s 的运行在 git 闸上中止、没打最终状态行，**但 jar 其实已经装上并重启了**（时间戳为证）。这个判据只能证明「结果不确定」，**必须去核对运行 jar** 才能定论：
   ```bash
   ssh root@47.100.235.168 "cd /www/wwwroot/cretas && ls -la aims-0.0.1-SNAPSHOT.jar; \
     unzip -p aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/com/cretas/aims/<路径>.class' | strings | grep -c '<你的标记>'"
   ```

### 部署锁（#2247 已修）

被杀的进程会留下 `/tmp/cretas-*.lock`。修复后能**自动识别并清理**（Windows 侧 `Get-Process` 交叉验证 winpid）。
若仍被挡，先逐个核实持有者再删：
```bash
for f in /tmp/cretas-*.lock; do pid=$(cat $f|head -1); \
  powershell -NoProfile -Command "if(Get-Process -Id $pid -EA SilentlyContinue){'ALIVE'}else{'DEAD'}"; done
```

---

## 8. 环境与凭证

- prod: `root@47.100.235.168`，库 `cretas_prod_db`，`sudo -u postgres psql`
- Web: `https://admin.cretaceousfuture.com`（解析到网关 `139.196.165.140`，**不是**主服务器）
- F006 账号：`f006_admin` / `123456`
- 主服务器 `10010` 端口**不对外**，探针必须走网关域名
- ⚠️ **同一 session 内密集 ssh/scp 会触发封禁**：现象是「GitHub 通（200）但两台 Cretas 都不通」——
  一测就分得清是**我被封**还是**线上挂了**，别猜。Steve 那边可放行。
- ⚠️ 登录取 token 用带回退的写法：`j?.data?.tokens?.token || j?.data?.token || j?.data?.accessToken`
  （只写第一种会间歇性拿到 undefined → 401）

---

## 9. 本轮反复应验的判据（下一个人直接用）

1. **拿到口径决定先查存量。** 两次因此转向：「存中文」→ 分两步；「kg/箱 都是错的」→ 只有 2 行是错的（羊排有真规格，改了会让 100kg 变 10kg）。
2. **接到口径决定先 `ls db/flyway | tail`** —— `V20261029_48`（前一天刚上线）定的是相反方向，不看就会写出自相矛盾的东西。
3. **mock 掉权威表的测试证明不了任何事。** 撤回的那个修复（自造 catalog，对「只→pcs」塌陷完全无感）、`convert()` 的 `at` 不能为 null（为 null 直接返回 `PRODUCT_CONVERSION_MISSING`，走不到包装规格那段）——都是「用真实实现」才暴露的。
4. **部署完要去验，别信单元测试绿。** #2237 单测全绿但线上没生效（三层栅栏只拆了一层）。
5. **数据迁移必须先在 prod 事务里 `BEGIN…ROLLBACK` 干跑。** `V20261029_50` 干跑当场抓到 `record "r" is not assigned yet`（同一 DO 块里既声明 `RECORD r` 又拿 `r` 当表别名），不干跑就是 Flyway 启动失败。
6. 🔴 **断言/计数要落在「可执行构造」上，不是「字符出现」。** 本轮踩了**三次**：
   - 迁移契约用「全文出现 ≥2 次」判守卫 → 删掉一处 UPDATE 守卫仍绿（诊断 SELECT 里也有一次）
   - `loop.indexOf("BY_PRODUCT")` 与 `indexOf("validateActivatableItems")` → **命中的是我自己写的注释**，位置恒定，变异怎么改都抓不到
   - `grep UNIT_CODE_MAX_LENGTH` 显示「已有」→ 命中的是我刚写的三处引用而非声明
7. **读变异结果前先确认变异真的落地**（`grep -c` 对比基线）。本轮有 3 次「没红」其实是变异没生效或编译失败。
8. **一个规则常有多个承载点，改完要再扫一遍。** 本轮实例：P4 有**两道**激活闸（只豁免一道仍过不去）；P7 的单位校验有**第二个承载点且在聚合之前执行**（不挪走就永远只报那一条）。
9. **`gh pr checks` 把 cancelled 显示成 `fail`** —— 判断 CI 失败要看 `conclusion`。
10. **无条件 `echo` 的自查语句会骗人。** 本轮又踩一次：`grep -c` 实际返回 2，而旁边说明文字写着「0=已不存在」。

---

## 10. 相关文档

- `docs/dispatch/handoff-2026-08-03-warehouse-fixes.md` — 仓储六处修复
- `docs/dispatch/handoff-2026-08-03-unit-governance.md` — 单位线前半段
- `docs/dispatch/2026-08-02-f006-minloop-issues.md` — 12 条问题清单（**P5 已查证不成立**，其余五条已修）
