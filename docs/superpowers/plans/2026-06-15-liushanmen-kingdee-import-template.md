# 金蝶云星空凭证导入模板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **配套 spec**（必读，含完整列布局/差异/验收）: `docs/superpowers/specs/2026-06-15-liushanmen-kingdee-import-template-design.md`

**Goal:** 新增一个导出端点，从已有凭证数据产出金蝶云星空 import 向导可直接导入的 xlsx（借/贷分列留空、凭证字「记」、汇率「1」），config-driven，客户给精确版本后只改 config 不改码。

**Architecture:** 复用现有 `VoucherExportServiceImpl` 取数 + `VoucherExportConfig` 列名覆盖 + EasyExcel 写法。`VoucherTargetSystem` 加 `KINGDEE_YXSKY`（target_system 是 VARCHAR 字符串列 → **无 Flyway 迁移**）。新 service 方法 + Controller 端点产云星空列布局。**不做 parser，不做 API 对接。**

**Tech Stack:** Java 21 / Spring Boot 3.2 / EasyExcel / BigDecimal(HALF_UP)。

---

## ⚠️ 开工前（必做）

- [ ] **worktree off origin/main**：`git worktree add -b feat/liushanmen-kingdee-template ../cretas-kingdee origin/main`
- [ ] **读 spec** + 读 `VoucherExportServiceImpl.java`（重点：现有凭证序时账导出方法 `exportSequentialLedger` 的取数 + 列写法 + `resolveConfig` 兜底 line ~965）。
- [ ] 本计划**无 Flyway 迁移**（仅枚举值，字符串列兼容）。

## 文件结构

| 动作 | 文件 | 职责 |
|---|---|---|
| Modify | `entity/enums/VoucherTargetSystem.java` | 加 `KINGDEE_YXSKY("金蝶云星空")` |
| Modify | `service/finance/VoucherExportService.java`（接口）+ `impl/VoucherExportServiceImpl.java` | 新方法 `exportKingdeeImportTemplate(factoryId, start, end)` → byte[] |
| Modify | 凭证导出 Controller（grep `VoucherExport` 的 `@*Mapping`） | 新端点 `GET .../finance/voucher-import-template` |
| Modify (可选 O1) | web-admin 财务导出页 | 加「金蝶导入模板」导出按钮 |
| Test | `service/finance/VoucherExportKingdeeTemplateTest.java` | 列布局/借贷留空/常量/HALF_UP |

---

## Task 1: 枚举扩展

**Files:** Modify `entity/enums/VoucherTargetSystem.java`

- [ ] **Step 1:** 加枚举值 `KINGDEE_YXSKY("金蝶云星空")`（现有 KINGDEE/YONYOU/CUSTOM 之后）。
- [ ] **Step 2: 编译** `./mvnw.cmd -q -o compile` 绿。
- [ ] **Step 3: Commit** `git commit -m "feat(finance): VoucherTargetSystem 加 KINGDEE_YXSKY" -- <enum>`

## Task 2: 云星空导出方法（TDD）

**Files:** Modify `VoucherExportService.java` + `VoucherExportServiceImpl.java`；Test `VoucherExportKingdeeTemplateTest.java`

云星空列布局（spec §2.3，11 列）：`凭证字 | 凭证号 | 日期 | 摘要 | 科目编码 | 科目名称 | 借方金额 | 贷方金额 | 币别 | 汇率 | 辅助核算`

- [ ] **Step 1: 写失败测试**（grep 现有 `VoucherExport*Test` 抄 mock 取数 + 解析 xlsx 断言风格；构造一借一贷两条分录）：
  - `kingdeeTemplate_debitRow_creditColumnBlank`：借方分录行 → 借方列有金额、**贷方列空字符串/null（断言非 "0.00"）**。
  - `kingdeeTemplate_creditRow_debitColumnBlank`：贷方分录行 → 反之。
  - `kingdeeTemplate_constants`：凭证字列 == "记"、汇率列 == "1"、币别列 == "人民币"。
  - `kingdeeTemplate_amountHalfUp`：金额 scale 2 HALF_UP（如 12.345 → 12.35）。
  - `kingdeeTemplate_emptyPeriod_headerOnly`：无凭证 → 仅表头 xlsx（诚实空）。
  - `kingdeeTemplate_configOverride`：插入 KINGDEE_YXSKY config 行改某列名 → 表头随之变；无 config → 默认列名无 NPE。
- [ ] **Step 2: 跑测试确认失败** `./mvnw.cmd -o -Dtest=VoucherExportKingdeeTemplateTest test`（FAIL：方法不存在）。
- [ ] **Step 3: 实现** `exportKingdeeImportTemplate`：
  - 复用现有凭证取数（同 `exportSequentialLedger` 的数据源，按 period 拉分录）。
  - `resolveConfig(factoryId, KINGDEE_YXSKY)` 取列名（兜底默认，无 NPE）。
  - 每条分录一行；借/贷按方向**只填一侧，另一侧写空**（`""` 或不写单元格，**不写 0.00**）。
  - 凭证字常量 "记"、汇率 "1"、币别默认 "人民币"（config 可覆盖凭证字/币别；汇率本卡为常量 per spec O2）。
  - 金额 `setScale(2, RoundingMode.HALF_UP)`。
  - EasyExcel 写 xlsx，sheet 首行/文件名含备注「云星空默认版；精斗云/KIS 请联系管理员调列序」。
  - 空期间 → 仅表头。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**。

## Task 3: Controller 端点

**Files:** Modify 凭证导出 Controller

- [ ] **Step 1:** grep `VoucherExport` 现有凭证导出端点，抄其 `@GetMapping` 路径模式 + `@RequirePermission` 权限码 + 脱敏/受众（金额 `@PriceSensitive` —— 财务可导、sales 等脱敏/403，**对齐现有 KINGDEE 导出行为不另发明**）+ xlsx response 头（Content-Disposition / content-type）。
- [ ] **Step 2:** 加 `GET /api/mobile/{factoryId}/finance/voucher-import-template?start=&end=` 调用新 service 方法返 xlsx。
- [ ] **Step 3:** Controller 测试：200 + 正确 content-type；非财务受众脱敏/403（抄现有凭证导出 controller test）。
- [ ] **Step 4: 全量测试** `./mvnw.cmd -o test`（**全量非 -Dtest**）绿。
- [ ] **Step 5: Commit**。

## Task 4: web-admin 导出按钮（spec O1）

**Files:** Modify web-admin 财务导出页（grep 现有凭证导出按钮所在页）

- [ ] **Step 1:** 在现有凭证导出按钮旁加「金蝶导入模板」按钮，调新端点下载 xlsx（对齐现有导出按钮的 blob 下载封装）。
- [ ] **Step 2: build** 绿 + 手测下载（截图）。
- [ ] **Step 3: Commit**。

## Task 5: 收尾验收

- [ ] **Step 1: 全量后端测试**绿 + web-admin build 绿。
- [ ] **Step 2: PR**：`gh pr create --base main --head feat/liushanmen-kingdee-template`。`git diff origin/main...HEAD --stat` 确认 scope 干净。
- [ ] **Step 3: 🔒 停在 PR**：不自部署、不自 merge。回 Opus organizer 终审 + 从 main 部署。

---

## Self-Review（已核 spec 覆盖）

- KINGDEE_YXSKY 枚举（无迁移）→ Task 1 ✓
- 云星空列布局 + 借贷留空 + 凭证字/汇率/币别常量 + HALF_UP + 空期间诚实空 + config 覆盖 → Task 2 测试逐条覆盖 ✓
- 端点 + 权限脱敏对齐现有 → Task 3 ✓
- web-admin 入口 → Task 4 ✓
- 不做 parser / 不接 API / 凭证字汇率先常量 → 范围内贯彻 ✓
- 全量测试 / 禁降级（诚实空非假行）→ 已嵌 ✓
