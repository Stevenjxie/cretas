# 六扇门 ERP-lite — Master 执行计划 & 文档索引

> 12 子项 spec+plan 已全部出齐(2026-06-09)。本文 = 索引 + scope-lock 执行波次 + 跨子项对账 + 红线 gate 协议。
> 配合 organizer 模型: Opus 唯一前门+出货闸; fleet 执行到 PR; 🔒 红线 Opus 终审从 main 部署。

## 文档索引
- **脊梁**: [00-master-blueprint.md](./00-master-blueprint.md) — 数据模型/红线设计/依赖/约定(强制遵循)
- **需求源**(docs/meetings/2026-06-09-liushanmen/): requirements-catalog.md(456需求+行号) / 需求与现状分析.md(103模块gap) / 排期-roadmap.md / 决策选项表.md(5决策) / 2转录
- **12 子项**(本目录, 各 spec+plan):

| SP | spec | plan | 流 | 端 | 🔒 |
|---|---|---|---|---|---|
| SP1 生产闭环-同单双产出 | ✅ | ✅ | C | be+web+RN | 🔒库存事务 |
| SP2 二次加工+整单撤回 | ✅ | ✅ | C | be+web+RN | 🔒事务回滚 |
| SP3 三价成本引擎 | ✅ | ✅ | B | be+web | 🔒成本口径 |
| SP4 一物一码补缺 | ✅ | ✅ | A+B | be+web+RN | — |
| SP5 销售到开票 | ✅ | ✅ | E | be+web | 🔒毛利红线 |
| SP6 采购到付款 | ✅ | ✅ | D | be+web+RN | 🔒审批/科目 |
| SP7 仓库管控 | ✅ | ✅ | F | be+web+RN | 🔒权限/审批 |
| SP8 16位编码 | ✅ | ✅ | A3 | be+web | — |
| SP9 人工人效 | ✅ | ✅ | B4+I | be+web | — |
| SP10 研发报价 | ✅ | ✅ | G | be+web | — |
| SP11 财务凭证导出 | ✅ | ✅ | H | be+web | 🔒会计口径 |
| SP12 审批引擎+权限+打印 | ✅ | ✅ | X | be+web | 🔒权限 |

---

## scope-lock 执行波次 (一口气全做 ≠ 字面并行)

> 每子项独立 worktree off origin/main → PR → 🔒 Opus 终审 → merge main → 从 main 部署 → 核对运行 jar。同波内文件不重叠可并行; 跨波串行。

```
波1 (地基, 并行 2):     SP1(生产闭环)  ‖  SP4(一物一码)
                        └ 两者无文件重叠, 可同时开。SP1 建 SemiFinishedInventoryTransaction; SP4 建 厂号/产地/税率/per_portion。
   ↓ (SP1+SP4 merge main 后)
波2 (并行 4):           SP2(二次加工+撤回)  ‖  SP3(三价成本)  ‖  SP6(采购付款)  ‖  SP7(仓库管控)
                        ⚠️ SP2/SP3 都依赖 SP1 的 Txn 表 → 必须波1 SP1 已 merge。
                        ⚠️ SP6/SP7 都改 MaterialBatch → 依赖 SP4 已 merge; 且 SP6↔SP7 都碰 MaterialBatch/PurchaseReceiveRecord → 二者**串行**(SP6 先, SP7 接), 不在同时。
                        ✅ 安全并行子集: {SP2, SP3, SP6} 一组(SP2/SP3 锁半成品链, SP6 锁采购链, 不重叠); SP7 接 SP6 之后。
   ↓
波3 (并行 3):           SP5(销售开票, 依SP3红线+SP4税率)  ‖  SP9(人工人效, 依SP1+SP3)  ‖  SP8(16位编码, 依SP4)
   ↓
波4 (并行→串行):        SP10(研发报价, 依SP3+SP4)  ‖  SP12(审批引擎+权限)  →  SP11(财务凭证, 依SP6+SP7+SP12)
```

**scope-lock 串行链(同文件不并发)**:
- `YieldReportServiceImpl`: SP1 → SP2 → SP9
- `SemiFinishedInventory(+Txn)`: SP1 → SP2 → SP3
- `MaterialBatch` / `PurchaseReceiveRecord`: SP4 → SP6 → SP7
- `BomRecipeItem`: SP4 → SP8
- `SalesOrder*`: SP5 独占
- `FinishedGoodsBatch.status` 枚举: SP2/SP5/SP6 各加各值(Java 枚举 append 安全, DB VARCHAR 无约束) → 终审 diff 核无覆盖

---

## 跨子项对账 (Opus 在终审/派活时把关)

1. **Flyway 号 = 规划占位, 非最终**: 各 SP 写的号(SP1 V20261010_0x / SP2-7 V20260910_1x-6x / SP8-12 V20260911_x)是规划段。**prod 持续在涨号**, 真实号在每个 SP 的 PR 时按 then-current origin/main **重新分配 + 查重**(`git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`)。不变量: 每 SP 独立段 + merge 前查重(教训复发 3 次)。⚠️ SP1 用了 V20261010 与其余 V20260910 基线不一致 → 执行时统一按查重结果定, 不影响正确性。
2. **共享字段/枚举碰撞(终审 diff 必核)**:
   - `FinishedGoodsBatch.status` 新枚举值(SP2 REVERSED / SP5/SP6 各自) — append 安全, 核无互覆盖。
   - `sourceOrderId`(单值, SP7 读) vs `sourceOrderIds`(list, SP5 加) — SP7 需补兼容读旧字段。
   - `@PriceSensitive` 覆盖: SP3(variancePct) / SP5(commissionPreview/targetGrossMargin) 新成本字段**必须**标注, 否则泄露给 sales。
   - Label 扫码端点: SP4 建 `GET /labels/scan/{code}`, SP7 入库扫码**复用不另建**。
3. **审批: P0 轻量状态机, SP12 后迁引擎**: SP6 付款/SP7 盘点报损/SP2 撤回 各用 P0 内置状态机(PENDING→APPROVED→DONE); SP12 通用引擎上线后写 adapter 迁移(不阻塞 P0)。

---

## 🔒 红线 gate 协议 (执行者只到 PR, Opus 终审)

8 个红线 SP 的执行者**只做到 实现+自测+PR off origin/main**, 收尾由 Opus 终审 + 从 main 部署。终审重点:

| SP | Opus 终审必查点 |
|---|---|
| SP1 | 半成品双产出不破坏现有 FG 链; Txn 流水账 IN/OUT 平衡; 移动均价并发行锁 |
| SP2 | 撤回三层守卫在事务外; 单事务 null-safe 回退不吞 doomed-tx; 移动均价重放正确; 幂等 |
| SP3 | 移动均价 scale/HALF_UP; @Async 回填 REQUIRES_NEW(防 doomed-tx); variancePct @PriceSensitive; 未税换算 |
| SP5 | 毛利红线后端算、不下发成本数值给 sales(脱敏); 不卡死提交 |
| SP6 | markPaid 三写(状态+ArAp+Supplier余额)原子, 失败全回滚; 结算属性→会计科目映射 |
| SP7 | 仓库零自主权(出入库/盘亏盈/报损必单据+审批); 报损分两路审批; 盘点财务批后生效 |
| SP11 | 凭证表科目映射正确; 不自建总账; 进销存金额口径 |
| SP12 | 权限矩阵无越权; 审批引擎迁移不破坏 P0 状态机 |

**通用收尾**: 每 SP PR 前 `git diff origin/main...HEAD --stat` 确认 scope 干净; commit 锁 scope(`git commit -- F1 F2`); worktree 各自 `npm install --prefer-offline`(⛔禁 mklink /J)。RN 部分 OTA 推送; 部署后 live 验 + 核对运行 jar 含修复。

---

## 状态 & 下一步

- ✅ **12 子项 spec+plan 全部出齐 + 红线脊梁核查通过**(SP2 深读确认照 §3.1)。
- ⏳ **下一步 = 执行**(Steve go): 按波1 起步。建议波1 先派 **SP1(我编排, 红线半成品事务) + SP4(一物一码, 非红线可较快)**。
- **周五演示**: 全做完后演示全链路, 客户提最后一波 → 微调。待确认: 编码严格16位? 财务接API?(否则维持小补+导表)
