# F006 报工三阶段时序流程重设计

> 来源: 2026-06-03 headed E2E 中 Steve 发现 —— 现有单页逐道报工把"投入+产出"放同页一次提交, 不符真实场景(投入在工序开始, 产出在数小时后, 见六扇门 6.1 群内报工照片时间戳: 聂云 15:08 报"滚揉前540kg", 腌制596.5kg 是更晚)。
> 决策已定 (4 问): 阶段自动识别(防呆) · 生产中两独立动作(提交本段累加 + 完工出成) · 副产物/损耗/留样在完工阶段录 · 后端加 report_kind 字段(INPUT/SEGMENT/OUTPUT)。
> 日期: 2026-06-03 · worktree cretas-phased (off origin/main) · 分支 feat/f006-phased-yield-reporting
> 迁移号: V20260910_03 起 (最新已占 V20260910_02; PR 前必 fetch 复核)

---

## 目标

把单页逐道报工(YieldStepReportScreen 一次填投入+产出+工时段+副产物+留样+照片提交)重构为**三阶段时序流程**, 每阶段只填操作工当下知道的, 出成率完工时自动算。复用现有累加后端(单元A/B/G6-8/适配), 后端只加 report_kind 阶段标记。

## 架构

**复用现有累加模型 + 加阶段标记 + RN 重构为状态机。** 同 work_process_task 多条 ProductionReport 已被 calculateSteps 自动 SUM(投入/产出/工时段/成本/photos/byproducts, 单元3)。三阶段 = 三类 report:
- **INPUT**: inputQuantity + 投入照片 + 领料(materialBatchRefs/sourceWipNo) → materialCost
- **SEGMENT**: laborSegments(本段一条)+ 可选照片 → laborCost(本段)
- **OUTPUT**: outputQuantity + 产出照片 + byproducts + wasteQuantity + sampleRetainQuantity

calculateSteps SUM 跨 report 得整道: totalInput/totalOutput/出成率/总成本/全工时段/全照片。

---

## 单元 1 — 后端 report_kind 字段 + 阶段推断

### 1.1 实体 + 迁移
- `ProductionReport` 加 `report_kind` 列 (VARCHAR(10), nullable; null 视为旧式整合报工兼容)。
- 迁移 `V20260910_03__production_report_kind.sql` (to_regclass 守卫 + ADD COLUMN IF NOT EXISTS + COMMENT 在守卫内):
  ```sql
  DO $$ BEGIN
    IF to_regclass('public.production_reports') IS NOT NULL THEN
      ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS report_kind VARCHAR(10);
      COMMENT ON COLUMN production_reports.report_kind IS '报工阶段 INPUT/SEGMENT/OUTPUT; null=旧式整合报工';
    END IF;
  END $$;
  ```

### 1.2 Request + submitReport
- `YieldReportRequest` 加 `String reportKind` (可选; null/缺省 = 旧式整合, 向后兼容)。
- `submitReport`: `.reportKind(req.getReportKind())` 存入。**按 reportKind 决定哪些字段生效**(防御: INPUT 阶段忽略 outputQuantity, OUTPUT 阶段忽略 inputQuantity——避免误填污染累加):
  - INPUT: 只用 inputQuantity/inputUnit/materialBatchRefs/sourceWipNo/evidenceImages; outputQuantity 强制 null。
  - SEGMENT: 只用 laborSegments/evidenceImages; input/output 强制 null。
  - OUTPUT: 只用 outputQuantity/outputUnit/byproducts/wasteQuantity/sampleRetainQuantity/evidenceImages; inputQuantity 强制 null。
  - null(旧式): 现有全字段行为不变。
- 成本: INPUT 算 materialCost, SEGMENT 算 laborCost(本段), OUTPUT 不算(纯产出)。WIP 产出 upsert 仅在 OUTPUT 阶段(产出锁定时), 成本滚动用整道汇总(materialCost from INPUT + laborCost Σ SEGMENT)。

### 1.3 阶段推断 + DTO
- `BatchYieldDTO.StepYieldDTO` 加 `phase` (String: AWAITING_INPUT / IN_PRODUCTION / COMPLETED) + `inputPhotos`/`outputPhotos` (List<String>, 按 reportKind 分组)。
- calculateSteps 推断: 该 task 无 INPUT report → AWAITING_INPUT; 有 INPUT 无 OUTPUT → IN_PRODUCTION; 有 OUTPUT → COMPLETED。photos 按 report.reportKind 分到 inputPhotos(INPUT/SEGMENT)/outputPhotos(OUTPUT)。
- 旧式报工(reportKind null): phase 按 input/output 有无推断(有output→COMPLETED, 有input无output→IN_PRODUCTION, 否则AWAITING_INPUT), photos 归 inputPhotos(兼容)。

---

## 单元 2 — RN 重构 YieldStepReportScreen 为三阶段状态机

打开道 → getYield 取该道 step.phase → 渲染对应阶段。共享头(产品/批次/工序/计划数量/标准出成率)。

### 2.1 投入阶段 (phase=AWAITING_INPUT)
- 投入量(按托称重)+ 领料批次/WIP选择(现有 MaterialBatchPicker/WipBatchPicker)+ 投入照片(拍照留证/相册)
- 防呆: 投入超收边界预显(现有 getLimits)
- 「提交投入」→ submitReport(reportKind=INPUT) → 刷新 → 转生产阶段

### 2.2 生产阶段 (phase=IN_PRODUCTION)
- 顶部投入摘要(只读: 投入量+投入照片缩略图)
- **时段报工块**: 加一段(开始/结束/人数)+ 可选照片 → 「提交本段」→ submitReport(reportKind=SEGMENT) → 累加(留本阶段, 清空段输入可再加)。已报时段列表显示。
- **完工出成块**: 产出量(按托称重)+ 产出照片 + 副产物(加行)+ 损耗 + 留样 → 「完工出成」→ submitReport(reportKind=OUTPUT) → 出成率算 → 转完成阶段
- 防呆: 产出超收边界预显; 完工二次确认("完工后本道出成率锁定")

### 2.3 完成阶段 (phase=COMPLETED)
- 只读摘要: 投入/产出/出成率/人工+材料成本/全工时段/投入照片+产出照片(分组)
- 「下一道 ▶」继续

### 2.4 防呆沿用
上传中阻提交; null 诚实显示; 跨单位 cumulative null。

---

## 单元 3 — web-admin batches/detail.vue 阶段增强

逐道展示已有(成本/工时段/副产物/证据)。增: 证据按 reportKind 分**投入照片 / 产出照片**两组展示 + 若有报工时间显示"几点投料 / 几点出成"(report createdAt by kind)。低改动, 复用现有 expand-row。

---

## 数据流 (单道猪舌, 贴 6.1)

1. 聂云 15:08 投入报工: 滚揉前 540kg(294.5+245.5 称重计算器)+ 秤照 → INPUT report → 道转 IN_PRODUCTION
2. 生产中多次提交本段(累加工时段)→ SEGMENT reports
3. 完工: 产出 596.5kg(621-24.5)+ 产出照 + 副产物 → OUTPUT report → calculateSteps: 出成率 596.5/540=1.104 保水, 成本汇总(materialCost INPUT + laborCost Σ SEGMENT)

## 测试
- 后端 TDD: submitReport 按 reportKind 字段隔离(INPUT 忽略 output 等); calculateSteps phase 推断 + photos 分组; 三 report 累加得整道出成率/成本; 旧式 null 兼容。
- RN: tsc 无新错; 三阶段状态机渲染(mock getYield 各 phase)。
- prod E2E(Expo web): 批次1924 道1 走 投入→时段→完工 三阶段真实 UI + web-admin 抽查实时。

## 不破坏已上线
- reportKind null = 旧式整合报工行为完全不变(现有 ScanReport 简单报工 + 任何老 client)。
- 现有 getLimits/WIP/成本/适配字段全复用。

## 不做 (YAGNI)
- 投入/产出 跨设备协作(多人接力同道)—— 现累加已支持, 不加锁。
- 时段报工独立审批 —— 沿用现状。
- 微信群自动抓取 —— 范围外。
