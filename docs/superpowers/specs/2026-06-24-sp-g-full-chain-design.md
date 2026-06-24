# SP-G 逐工序电子表格全链扩展 — 设计 (Design Spec)

**日期**: 2026-06-24
**作者**: Opus organizer (audit-driven, 续 SP-F)
**状态**: Draft → 待 Steve review
**前置**: SP-A 配方(#1066) / SP-B1 物化(#1067) / SP-C 核算(#1069) / SP-F 3 道切片(#1083+) / 跨天(#1089) 全已上线 prod。SP-B2 抽屉已删(#1091, 死代码)。
**客户 mockup**: `production-cost-app(1).html`(张权「生产成本核算系统 V3.0」, Steve 本地持有, 未入 repo)。

---

## 1. 背景：为什么有这个 spec

SP-F 是 Steve 当时拍板的**垂直切片 = 修油+焯水+熟制 3 道**(设计文档 `2026-06-23-sp-f-process-sheet-design.md` §2.2)。真实链是 **修油→滚揉→焯水→去舌苔→熟制→气调**(6 道),切片用「焯水←修油、熟制←焯水」折叠接线,显式省略 滚揉/去舌苔/气调,并把以下整体 defer(§2.3):

| Deferred 项 | 当时原因 |
|---|---|
| 滚揉 / 去舌苔 / 气调 三道工序 | 切片只验前 3 道的领料+成本基准路径 |
| 气调装盒 → **成品批**(finished=true) | 切片所有批 finished=false(未到气调) |
| 单盒成本 / 留样 / 料头 / 副产 | DTO 不带 byproduct/sampleRetain(避免捕获却不计的语义漂移) |
| 张权「成本分析页」(调料表+包装表+单盒测算) | standalone per-box 计算器, 单盒需气调装盒步 |
| 汇总页(出成率链 + per-process 成本追溯) | 需确认 `computeByBatch` 是否产出 per-process labor |
| Q1(注射成本基重) / Q6(副产·肥油回收) 成本引擎 | 触 traceCost 正确性 → 独立 🔒 PR |

**2026-06-24 审计触发**:Steve 问「流程完整吗」→ 确认全链 UI 录入是计划内 defer,SP-B 抽屉是被有意替换的死代码(已删)。Steve: go,写 SP-G spec。

---

## 2. 关键洞察(收窄 scope)

**「成本分析页 / 单盒成本」其实已基本建好** —— `M67YieldCost.vue`(成品出厂核算页, `/production-analytics/yield-cost`)已经展示:
- 单盒成本 + 成本四拆(原料/人工/调料/包装)
- 包装明细(膜/气体/标签/其他)
- 副产·肥油回收 / 留样(不可售)
- 多批混锅溯源(Excel 做不到的多对多)

→ 张权 mockup 的「成本分析页」**大部分是现成的**。SP-G **不重建这页**,真正缺的是**录入侧**:把后 3 道工序(滚揉/去舌苔/气调装盒)录进来,让一个真实产品能**纯 UI 从领料走到成品批+盒数**,喂给已有的成本页。

**后端成本引擎已完整实现**(2026-06-24 核实 `OrderCostBreakdownService.computeByBatch`):
- 单盒 perBox(L191) / 四拆 原料·人工·调料·包装(L190) / traceCost 谱系回溯(L161)
- **副产回收** byproductCredit + netTotal(L193-195) ← Q6 早已实现(#1027/1028), **非 deferred**
- **留样** sellableBoxCount + 可售单盒(L200-202) / **包装明细** packagingAcc(L100)
- recordChain 已支持 finished + PACKAGING(本 session API 实跑:完整链 → 单盒¥2.18)

→ **成本引擎不缺**。缺口纯在**录入侧**:① 前端 config + AutoCalc 公式(后 3 道)② **process-sheet row DTO 加 byproducts/sampleRetain/packagingDetail**(让 SP-F 录入能喂**已就绪**的引擎 —— 切片故意没带, §2.3)③ 气调装盒成品批语义。**SP-G 不改成本引擎**。

---

## 3. Scope

### 3.1 In scope(SP-G)

| 子项 | 内容 |
|---|---|
| **G1 滚揉**(纯配置) | PROCESS_SHEET_CONFIG 加 `gunrou`(单上游 WIP, 无特殊公式), 恢复接线 焯水←滚揉←修油 |
| **G2 去舌苔**(新 AutoCalc) | 加 `qushetai`, 新增 `AutoCalc='reverseInput'`(投入=碎肉+产出反推), 恢复接线 熟制←去舌苔←焯水 |
| **G3 气调装盒 → 成品批**(核心) | 加 `qitiao`, finished=true 成品批; 单盒克重 → 盒数; 新 AutoCalc(单盒克重/每盒人工/料头/留样); process-sheet row DTO 加 byproduct/sampleRetain 字段 |
| **G4 单盒成本页接通** | 复用现有 `M67YieldCost.vue`, 去 M67 硬编码 → 通用(任意产品/批次号); 气调成品批驱动 |
| **G5 Q1 注射基重 verify**(可选 🔒) | 仅 **verify** RecipeCostCalculator 注射基重口径; 偏差才独立 🔒 PR。**Q6/留样/副产 已实现, 不在此** |

### 3.2 Out of scope(继续 defer / 单独)
- **汇总页**(出成率链 + per-process 成本追溯):⚠️ 先 verify `computeByBatch` 是否产出 per-process labor(当前疑为单一人工桶)再决定,**不假设**。
- **操作记录 / 行-字段级 diff 审计**:现有 audit 多为 entity 级, mockup 要 row-field diff, 建之前先验证能否产出。
- 真客户(F006/LIUSHANMEN)铺全 6 道 —— 先 DEMO 验证, GO 后再说。

---

## 4. 技术难点(review 重点)

### 4.1 新 AutoCalc 类型(口径已从 mockup 锁定 ✅ D1 resolved)

mockup `production-cost-app(1).html` JS(line 2620-2665)给了精确公式,**直接照实现**:

现 `AutoCalc = 'yield'|'remaining'|'totalHours'`。需扩:

**① 滚揉(gunrou)** — 纯配置, 沿用现有 `yield` pattern(单上游→产出, 出成率=产出/投入), 无新公式。

**② 去舌苔(qushetou)** — 新 `reverseInput`:
```
录入: 碎肉(scrap) + 产出(output)
算:   投入 input = scrap + output        // 反推!
      出成率 = output / input
      剩余 remain = output − 下游领用
```

**③ 气调装盒(qidiao)** — 新一组(成品批):
```
录入: 入库 storage / 留样 sample / 剩余 remain / 领用 claim / 成品重 productWeight / 料头 trimmings / 单盒克重 boxWeight / 工时段
算:   实际生产(盒) actualProd = storage + sample + remain + claim
      总重量 totalWeight = productWeight + trimmings
      气调出成率 = productWeight / usedWeight   (usedWeight=上游投料合计)
      每盒人工费 laborPerBox = (员工单价 × 总工时) / actualProd
```

→ 新 AutoCalc: `reverseInput`(去舌苔) + `sumBoxes`(实际生产) + `sumWeight`(总重量) + `yieldByProductWeight`(气调出成率) + `laborPerBox`。`AutoCalc` union 已设计为可扩展。

> **D1 已解决**:口径来自 mockup JS, 不需再对。注: mockup 的「单盒成本测算」只算 **调料+包装(不含人工/原料)** —— 我们 `computeByBatch` 的全四拆 + 副产/留样 **比 mockup 更全**, G4 直接用更全的, 不退回 mockup 的简版。

### 4.2 气调装盒 = 成品批(finished=true)的语义
- 切片所有批 finished=false。气调要产 finished=true 成品批 → 触发 `createWipMaterialBatch` 不同分支(成品批不建 WIP, 直接是成品)。
- 盒数 = 成品重 / 单盒克重 → 喂 yield-cost 页的「产出盒数」。
- 后端 recordChain 已支持 finished; 但 **process-sheet 增量端点 saveRow 是否支持 finished 行 + 装盒语义**需 verify(SP-F slice 写死 finished=false)。

### 4.3 byproduct / sampleRetain DTO 字段
- SP-F 切片**故意不带** byproducts/sampleRetainQuantity(§2.3, 避免「捕获却不计」语义漂移)。
- G3 要真计留样/料头/副产 → process-sheet row DTO + materializeBatch 要接这些字段, **且成本引擎要真的算**(否则又是语义漂移)。这与 Q6(副产回收)耦合。

> **决策点 D2**:留样/料头/副产的成本处理口径 = M67YieldCost 已展示的那套(副产按回收价抵扣 / 留样成本由售出盒承担)? 确认现有 `OrderCostBreakdownService` 已实现这套, G3 只是把录入接上, 还是引擎也要改?

### 4.4 成本引擎现状(⚠️ 纠正:Q6 已实现, 非 deferred)
- **Q6 副产回收 + 留样 已上线**(#1027/1028, `computeByBatch` L193-202)。SP-G **不重做**, 只把 SP-F 录入的 byproducts/sampleRetain **接进** DTO 喂它(§2.3 切片故意没带)。
- **Q1 注射成本基重** = 唯一可能仍开的小引擎项, **先 verify**(看 RecipeCostCalculator 的 injectionRawKg 基重口径是否=mockup)。若需改 → 独立 🔒 PR + DEMO 0-diff; 若已对 → 无引擎工作。
- 结论:**SP-G 主体是录入 + DTO wiring, 不碰成本引擎**(除非 Q1 verify 发现偏差)。

---

## 5. 分期(建议 PR 切分)

| PR | 内容 | 风险 | 模型 |
|---|---|---|---|
| **G1** | 滚揉纯配置 + 接线恢复 | 低 | Sonnet(规则轻) |
| **G2** | 去舌苔 + reverseInput AutoCalc | 中(新公式) | Opus 定公式 + Sonnet 实现 |
| **G3** | 气调装盒 + 成品批 + byproduct/留样 DTO + 新 AutoCalc | **高**(成品批语义 + 成本正确性) | Opus 主导 |
| **G4** | M67YieldCost 去硬编码 → 通用单盒页 | 中 | Sonnet |
| **G5** | Q1 注射基重 **verify**(偏差才修) | 低(只读核对) | Opus verify |

> G3 是 🔒 红线(成品批语义 / 成本正确性), 执行者只到 PR, Opus 终审 + 从 main 部署。**成本引擎已就绪, G3 只是把录入字段接进去**。先对完 D1 气调口径再开 G3。

---

## 6. 开工前必 verify(不假设, 审计教训)

1. **process-sheet saveRow 支持 finished 行吗**(SP-F 写死 false)? 看 ProcessSheetServiceImpl + materializeBatch finished 分支。
2. **computeByBatch 产 per-process labor 吗**(汇总页前提)? 当前疑单一人工桶 —— 决定汇总页是否进 scope。
3. ~~OrderCostBreakdownService 已算 留样/料头/副产?~~ **已确认实现**(computeByBatch L193-202, #1027/1028)→ G3 只「接录入」, 不改引擎。
4. **张权 mockup 气调页的列口径**(D1) —— 让 Steve 给 mockup 或对一遍。
5. **Q1 注射基重**:RecipeCostCalculator 的 injectionRawKg 基重 = mockup 口径? (G5 verify)

---

## 7. 开放问题(待 Steve 拍板)

- ~~D1:气调口径~~ **已解决**(§4.1, mockup JS line 2620-2665 锁定全部公式)。
- ~~D2:留样/副产引擎已实现?~~ **已确认实现**(verify #3), G3 只接录入。
- **D3**:汇总页是否进 SP-G?(取决于 verify #2 — computeByBatch 是否产 per-process labor)
- **D4**:先全程 DEMO 验证 + GO 后才碰真客户铺全 6 道?(建议 yes, 同 BOM 合并的 hard-gate)
- **D5**:分期顺序 —— G1/G2/G4 低中风险可先并行起, G3 等 D1 口径对完?

---

## 8. 验证(同既往纪律)

- DEMO_FACTORY(df_admin)全程, 绝不碰 F006/LIUSHANMEN(同 BOM 合并 hard-gate)。
- 每道新工序: 录一行 → 物化 → 桶分类正确 → 喂 yield-cost 页单盒对得上手算。
- 气调成品批: finished=true + 盒数 + 留样/料头 → M67YieldCost 单盒成本端到端。
- headed E2E(headless:false / zh-CN / 1920×1080)截图存档。
- Q1/Q6: DEMO 0-diff(改前后 computeByBatch 对比)。
