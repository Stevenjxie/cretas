# 交接 — 客户表格 36 条复验 + 生产链修复上线（2026-08-04 晚 ~ 08-05 凌晨）

**状态**：**6 个 PR 全部已合并并上线**（Web/Java 一次发布 + RN 一次 OTA，均已核对运行制品）；
**App 17 条已全部定案**；**Web 19 条未开始**。

## 收尾追加（08-05 凌晨）

| PR | 内容 | 上线核对 |
|---|---|---|
| #2283 | 修 main 上红了 8 天的断言（反射 `finishedOutputQuantity`，#1898 已删） | 无需部署 |
| #2285 | **App 建采购单必带 `orderDate`** —— 客户「不能新建采购订单 400」的唯一原因 | OTA `ts=1785865172419`，manifest 指向它、launchAsset 实拉 200/6.95MB |

### #2285 的判据

后端 `@NotNull("下单日期不能为空")`，而采购建单**整条链都没这字段**（界面不收集/RN 类型没声明/payload 不送）。
prod 实测：旧形状 → `400 hintTarget:orderDate`（与客户报告逐字一致）；只补一个字段 → 穿过校验进业务层。
把该字段**声明为必填**后，tsc **立刻抓到第二个漏送点** `PurchaseOrderDetailScreen`（改备注也走整单更新契约）——
这是"设成必填而非可选"的直接收益。顺带收掉销售那份私有 `todayIso` 用 `toISOString()` 的隐患
（按 UTC 截断，东八区 08:00 前把下单日期记成前一天）。

### App 17 条最终账

**13 条已修**：#1998 修 3 条（AI入库白屏=effect 依赖死循环 / 盘点记录 / 物料需求单入口）、
#2169 修 8 条（消耗记录闪退=`Long` 上调 `.toLowerCase` / 时间冒号 / 工时400 / 批次编辑403 /
生产计划新建入口 / 记录出货假成功 / 销售订单搜索+单位选择器 / App建单四字段）、
`createBatchFromPlan` 放开 IN_PROGRESS 补建批次修掉「完成生产 409」、#2285 修采购 400。

**4 条非缺陷或待办**：
- **#9 入库 409 = 设计如此**：`MaterialBatchServiceImpl:1119`「普通批次页面已关闭无来源入库与续入」，
  入库必须走采购收货。**但入口还摆着让人点了才被拒 —— 防呆待办**（应隐藏或改文案指向采购收货）。
- **#18 仍然坏着**：`ProcurementDeliveryConfirmScreen` 里供应商名称/ID/日期/食材全是裸 `TextInput` 手填。
- **#16 下拉宽度截断**：纯 UI 打磨，未修。
- **#3** 已修，但入口现按 `canManageInventory` 门控，原报告人（`production:*`）已看不到。

### 剩余四块（建议新 session 接手）

1. **Web「main」19 条**未开始，含 8-03 最新 4 条（BOM 1→多无法新建 / 物料需求单无处配置配方用量 /
   合并订单不合并数量且关联 SO 状态未进生产中 / 少收关闭原因应支持「原因+数量」多行）
2. **#18 / #16** 两条 App UI
3. **Java 144 条红 + 上闸**（基线见 §2）
4. 页面文案承诺「锁定」但功能不存在；行级撤销小结标着"功能开发中"却被错误提示引用

---

## 1. 本轮已上线（prod 已核对）

发布：`release-cretas.sh --phase deploy --base-sha 3b8e212ffd`，`DEPLOY_EXIT=0`、`RELEASE_FINAL_STATUS=deployed` 恰 1 次、蓝绿切 10010、Web 四方哈希一致。

| PR | 业务变化 | prod 核对判据 |
|---|---|---|
| #2282 | **库存生产一张计划能一直做下去** —— 成品道不再「一辈子只能报一行」 | jar 里 `WORKFLOW_RUNTIME_BATCH_ALREADY_REPORTED` 残留 **0** |
| #2279 | 已开工计划可改**排产四软字段**（预计完成日/工人数/主管/备注），计划日期与数量仍锁死 | jar 里 `schedule-meta` 端点 **1** 命中；bundle `list-CHs7BdaW.js` 含「编辑排产信息」 |
| #2281 | 补回**中转挂账清账**入口（结算链最后一段） | bundle `list-Bgy_nHiw.js` 含「中转挂账清账」 |
| #2277 | 修两处会随机判红任何 PR 的 CI 抖动 | — |

（更早：#2275「更多」空白框 + 接回编辑/复制，已单独部署过。）

### #2282 的判据（下次别再重复查）

- 一张计划只有一个 WORKFLOW 运行批次；成品道第一行占它，**第二行起开自己的批次**（走副产品分支同一通路 `clerkService.materializeBatch`）
- 安全性来源：`findWorkflowRuntime` 只数 `workflowSelectionMode == WORKFLOW` 的批次，文员通路建的批次不带该模式。**prod 上 PLAN-1785831853929 已长期并存 1 个 WORKFLOW + 7 个 CLK-W 批次，报工页正常**
- 「多个批次一次小结结掉」**本来就支持**：`InterimSettleServiceImpl` 按 `sessionSeq` 分场次，一次小结把所有未结成品行按产品聚合成一个 FG 批次入库（含单位一致性守卫 + 加权成本），撤销按聚合量精确逆转
- 客户口径（张权 8-04）：「本来小结前都是类似草稿的，小结了库存才入库的」「多个批次就小结多次呗」

---

## 2. 🔴 Java 侧没有测试闸（本轮最大的系统性发现）

CI `java-build-test` 只有两步：

```yaml
- run: mvn -B test -Dtest='*RepositoryQueryValidationTest'   # JPA 启动闸
- run: mvn -B package -Dmaven.test.skip=true                 # 无选择器时跳过全部测试
```

**Java 单测套件从来不整体跑。** web-admin 有全量 vitest 闸（332 files / 2474 tests，push+PR 都跑），Java 没有对等物。

后果实例：`ProcessSheetServiceImplTest#pinnedBomPackagingRequirementsScaleInNativeUnits` 反射的 `finishedOutputQuantity` 在 **#1898（7-27）** 就被 `finishedBomOutputs` 取代删除，测试**红了 8 天没人知道**。→ PR **#2283** 已修该断言。

**待办**：正在实测 main 全量 Java 测试的健康度（后台 `mvn -o test -Dmaven.test.failure.ignore=true`）。**先拿到基线红数再决定闸怎么加** —— 直接加闸可能把一堆既有红变成"挡所有人的红"（同 web-admin-gate.yml 上闸前的做法：先确认目标环境是绿的）。

---

## 3. 客户表格复验（36 条未解决）

表格：`docs.google.com/spreadsheets/d/1TyIqNP_z8bBiPyLeXbr3YYvwE1WCuHFhgYAHiJC4w4M`（公开可读，`export?format=xlsx` 拿全部 5 个 tab）

- **「工厂App」17 条**（7-29~7-31，`解决情况` 整栏空白）
- **「main」19 条未解决**（46 条中 15 已解决 / 10 已修复待验证 / 1 暂缓 / 1 正在解决），最新一批是 **8-03 的 4 条**
- 「餐饮」97 条全部已解决（停在 7-27）

⚠️ **`解决情况` 空 ≠ 现在还坏**：#2169（8-01「修复工厂 App 表格反馈的报工与销售建单」）改的文件与表格条目一一对应，OTA 8-02/8-04 也已送达设备，只是表格没回填。

### App 17 条 — 已查证 5 条

| # | 条目 | 结论 | 判据 |
|---|---|---|---|
| 5 | 消耗记录**打开即闪退** | ✅ 已修（#2169） | 根因 `item.productionBatchId?.toLowerCase()` —— 该字段后端是 **Long**，数字上无 `.toLowerCase`，`useMemo` 过滤即抛。已换 `normalizedSearchValue()` |
| 11 | 时间冒号可删/全角 | ✅ 已修（#2169） | 新增 `utils/timeInput.ts`，冒号由程序生成 |
| 12 | 工时上报 **400** | ✅ 已修（同上） | prod 实测：全角 `08：30` → 400「请求格式不正确」；两个对照证明是时间字段（半角+非法 reportType 能进业务层） |
| 13 | 批次编辑 **403** | ✅ 已修（#2169，防呆） | prod 实测报文：`您的角色 [调度] 缺少 仓储管理 或 库存管理 [读写]`；该账号权限仅 `production:*`。#2169 改为无权限时隐藏按钮 + 「原材料库存为只读」提示 |
| 6 | 生产计划**无新建入口** | ✅ 已修（#2169） | `canCreateProductionPlan(roleCode, isReadOnly)` + 「创建计划」按钮 |
| 3 | AI智能入库**白屏** | ⚠️ 部分 | FAB 现按 `canManageInventory` 门控（报告人已看不到入口）。白屏本身**未复现**；我怀疑的 `response.map` 路径已排除（`getActiveSuppliers` 返回 `response.data \|\| []` 恒为数组） |

### App 剩余 11 条待验证

#2 物料需求单模块缺失 / #4 盘点无法在 App 提交审核 / #7 记录出货提示成功但没登记 / #8 完成生产 409 / #9 原材料入库 409 / #10 产量上报 400 / #14 销售订单选产品不能搜索+单位可手填 / #15 App 建单在 Web 缺规格·箱数·税率·业务员 / #16 下拉宽度截断 / #17 采购订单新建 400 / #18 采购确认送货三字段只能手填

**验证方法（已跑通，照做即可）**：
```bash
# 1. 登录拿 token（测试账号密码 123456，见 tests/qa-issue-575/evidence.md）
curl -s -X POST "http://139.196.165.140:8086/api/mobile/auth/unified-login" \
  -H "Content-Type: application/json" \
  -d '{"username":"f006_production_mgr","password":"123456","deviceInfo":{...}}'
# 2. 看 data.permissions —— 很多 4xx 是 RBAC 而非缺陷（production_mgr 只有 production:*）
# 3. 写端点先读后端 @RequirePermission + DTO 校验预测原因，能静态定案就别对真租户写数据
```
⚠️ F006 是**真实客户租户**（六膳门），写操作探针要克制。

### Web「main」19 条待验证

最新 4 条（8-03）：BOM 配方 1→多仍无法新建 / 物料需求单无处配置配方用量 / 合并订单不合并数量且关联 SO 状态未进生产中 / 少收关闭原因应支持「原因+数量」多行。
其余 15 条日期 7-20~7-31，含：采购「编辑」点击无响应、任务框鼠标对比度、SOP 措辞、盘点提交审批需刷新页面、物料需求单生成失败（追踪码 2CC05928）、成品SKU换算后采购单价不再自动带入、发货入口位置、分配批次仓库显示英文代号、AI「什么指令都操作成功」等。

---

## 4. 其他待处理

- **OTA 通道**：8-04 16:59 一次发布被隔离（`oss-userdisable`）。当晚查证：**账号余额 ¥47.77、欠费 ¥0，不是欠费**；数据面随后自行恢复。已从干净 main 重推成功（ts=1785842597806），用 manifest 里的真实 URL 实拉 launchAsset 200/6.95MB。
  判据：**自己拼的 CDN 路径 404 ≠ 发布失败** —— `dl.cretaceousfuture.com` 挂在 bucket 根，以 manifest 给出的 URL 为准。
- **WAF 3.0 已释放**（省 ¥31.67/期）：释放前确认它只配了 `cretaceousfuture.com` 且回源写的是占位符 `1.1.1.1`，而 DNS 直指 139，**流量从未经过它**。教训：配了 WAF ≠ 流量走 WAF。
- **页面文案与实现打架**：生产计划页顶部说明写「进行中的计划支持锁定」，但界面无锁定入口、后端 API 从未实装（#747）。要么删该句，要么实现 —— 产品决定。
- **行级撤销小结**：错误提示引导用户走「撤销小结」，而代码里标着「功能开发中」。列表页的「申请撤销小结」是另一条（申请→审批）流程。

---

**Session**: https://claude.ai/code/session_0148FDWgRjpo5vkkNg3GYsmi
