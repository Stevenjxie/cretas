# 六扇门 — 金蝶云星空凭证导入模板 设计 spec

**日期**: 2026-06-15
**来源**: 六扇门 ERP「主动建版本」剩余项 ③（handoff `docs/dispatch/2026-06-15-handoff-liushanmen-remaining-proactive-build.md`）
**转录依据**: `docs/meetings/2026-06-09-liushanmen/transcript-2b.txt` [26:20–29:35]；周五确认单 `docs/meetings/2026-06-09-liushanmen/周五确认单-2026-06-12.md` #4
**scope 决策**: Steve 2026-06-15 拍板「主动建云星空默认版」（不等客户给版本；config-driven，真版本后续改 config）

---

## 1. 背景与问题

财务把数据导进他们自己的金蝶（总账模块），现在手工录入。客户诉求 [27:28]「按照他们的表头记录数据」，Steve 方案 [28:32-28:46]「系统按你们金蝶需要的表头打印出来，你可以把表头信息告诉我，我在系统里建一个」。

**数据流向 = Cretas → 金蝶**（财务把 Cretas 产出的文件导进金蝶），**不是**反向解析外部文件进 Cretas。所以本质是**新增一种导出格式**，列序/表头精确匹配金蝶凭证 import 向导期望，而非做文件 parser。

### verify-first 现状（已对 origin/main `cacd518e7` 核实）

| 事实 | 证据 |
|---|---|
| 财务导出**已全建**（8 张：凭证序时账/科目余额/序时账/总账/明细账/试算平衡/利润表/数量金额账） | `service/finance/impl/VoucherExportServiceImpl.java`（EasyExcel `.xlsx`） |
| 导出 **config-driven**（凭证序时账列名取 `VoucherExportConfig`） | `VoucherExportServiceImpl` line ~106-116；配置实体 `entity/finance/VoucherExportConfig.java` |
| `VoucherTargetSystem` 枚举 = KINGDEE / YONYOU / CUSTOM | `entity/enums/VoucherTargetSystem.java` |
| 配置表有 9 个可覆盖列名（凭证字号/日期/摘要/科目编码/科目名称/借方金额/贷方金额/辅助核算/币别），**无凭证字、汇率列** | `VoucherExportConfig.java`（col_voucher_no…col_currency） |
| `resolveConfig` 无配置行时返内存默认对象（targetSystem=KINGDEE + 默认列名），**无 NPE** | `VoucherExportServiceImpl` line ~965-971 |
| **凭证导入模板（金蝶可直接 import 的格式）缺** | grep 全仓 0 个 finance/voucher import；现有「导入模板」均为 customer/equipment/supplier 等无关实体 |
| 金蝶版本仍 customer-blocked（6.14 ship 状态 line 53/69） | 演示未定下版本 → 按 handoff 主动建云星空默认版 |

### 云星空 import 与现有凭证序时账导出的**真差异**

| 维度 | 现有凭证序时账导出 | 云星空 import 需要 |
|---|---|---|
| 凭证字 | 合并在「凭证字号」一列 | **独立「凭证字」列**（默认「记」）+ 独立「凭证号」 |
| 借/贷金额 | 两列都写 `0.00` | **未用的一侧留空**（非 0.00，否则金蝶可能拒行） |
| 汇率 | 无 | **独立「汇率」列**（CNY = 1） |
| 币别 | 有 | 有（默认「人民币」/CNY） |

---

## 2. 架构

> 复用现有导出基础设施，**新增一个独立导出端点 + service 方法**产云星空-import-shaped xlsx。**不做 parser，不做 API 对接。**

### 2.1 枚举扩展（无迁移）

- `VoucherTargetSystem` 增加 `KINGDEE_YXSKY("金蝶云星空")`。
- `target_system` 是 VARCHAR(32) 字符串列 → **无需 Flyway 迁移**（新枚举值天然兼容）。

### 2.2 新 service 方法 + 端点

- `VoucherExportService.exportKingdeeImportTemplate(factoryId, period/dateRange)` → `byte[]` xlsx。
- Controller 新端点（镜像现有 voucher-export）：`GET /api/mobile/{factoryId}/finance/voucher-import-template`（参数对齐现有凭证导出：起止期间）。权限对齐现有凭证导出端点（Codex grep `VoucherExport` controller 的 `@RequirePermission` + 脱敏 advice，金额是 `@PriceSensitive` 受众 —— 财务角色可导，sales 等脱敏/403，对齐现有 KINGDEE 导出行为）。
- 复用现有凭证数据查询（同凭证序时账的取数逻辑），只换**输出列布局**。

### 2.3 云星空列布局（默认版）

每行一条分录（金蝶 import 一借一贷各占一行）：

| 列 | 表头 | 取值 |
|---|---|---|
| 1 | 凭证字 | 常量「记」（config 可覆盖） |
| 2 | 凭证号 | 凭证序号 |
| 3 | 日期 | 记账日期 `yyyy-MM-dd` |
| 4 | 摘要 | 分录摘要 |
| 5 | 科目编码 | 科目编码 |
| 6 | 科目名称 | 科目名称（云星空可选，留着不破 import） |
| 7 | 借方金额 | 借方分录填金额，贷方分录**留空** |
| 8 | 贷方金额 | 贷方分录填金额，借方分录**留空** |
| 9 | 币别 | 「人民币」（config 可覆盖） |
| 10 | 汇率 | 「1」 |
| 11 | 辅助核算 | 可选（有则填，无留空） |

- **金额序列化**：用现有金额处理（BigDecimal `setScale(2, HALF_UP)`，per `python-java-port` 同源精度纪律 —— 本卡纯 Java，遵 Java BigDecimal HALF_UP）。借/贷未用侧写**空字符串/null**，不写 0.00。
- **文件头备注**（首行注释或 sheet 名）：「云星空默认版；如使用精斗云/KIS 请联系管理员调整列序」。

### 2.4 config 复用（post-launch 客户给版本时）

- 复用 `VoucherExportConfig`（按 `factory_id + target_system=KINGDEE_YXSKY` 取行）。
- 重叠列名（凭证号/日期/摘要/科目编码/科目名称/借方/贷方/币别/辅助核算）走 config 覆盖；**云星空特有列（凭证字、汇率）当前为常量**，客户给精确版本后再决定是否提升为 config 列（届时一个加列迁移，**本卡不做**，避免 over-engineer）。
- 无配置行 → 沿用 `resolveConfig` 默认（云星空默认列名），无 NPE。

---

## 3. 数据流

```
财务点「导出金蝶导入模板」(web-admin / 端点)
        │  GET /{factoryId}/finance/voucher-import-template?start&end
        ▼
VoucherExportService.exportKingdeeImportTemplate
   ├─ 复用现有凭证取数 (period 内分录)
   ├─ resolveConfig(factoryId, KINGDEE_YXSKY)  ← 默认或客户覆盖
   └─ 按云星空列布局写 xlsx (借/贷分列留空, 凭证字「记」, 汇率 1)
        ▼
.xlsx 下载  ──►  财务拖进金蝶云星空 import 向导
```

---

## 4. 错误处理 / 测试

- **错误处理**（`api-response-handling` + 禁降级）：
  - 期间无凭证数据 → 返回**仅表头**的空 xlsx（诚实空，非假数据；可加一行提示「所选期间无凭证」）。
  - 权限不足（非财务受众）→ 对齐现有 KINGDEE 导出（脱敏或 403），不静默返全量。
- **测试**（后端 JUnit，Codex 跑**全量** `./mvnw.cmd test`）：
  - 一借一贷两行：借方行贷方列空、贷方行借方列空（**断言空非 0.00**）。
  - 凭证字常量「记」、汇率「1」、币别「人民币」。
  - 金额 HALF_UP scale 2。
  - 多分录凭证列序正确。
  - config 覆盖：插入 KINGDEE_YXSKY config 行改列名 → 导出表头随之变。
  - 无 config → 默认列名，无 NPE。
  - 空期间 → 仅表头 xlsx。
- web-admin（若加导出按钮）：build + type 绿。

---

## 5. 开放问题（非阻塞）

- **O1 web-admin 入口**：财务导出页加一个「金蝶导入模板」导出按钮（与现有凭证导出并列）。**取做**（否则端点无 UI 触点）；Codex 按现有财务导出页结构加。
- **O2 凭证字/汇率 是否即提升为 config 列**：**否**（常量先上，客户给版本再加列迁移）。
- **O3 是否同时产「科目余额表」import 版**：**否**（转录核心是凭证表 import；周五确认单 #4「除凭证表外还要哪几张」是 customer-blocked，留 P2）。

---

## 6. 关键文件指针（Codex 自包含用）

| 用途 | 路径（origin/main） |
|---|---|
| 凭证导出 service（复用取数 + resolveConfig + EasyExcel 写法） | `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/VoucherExportServiceImpl.java` |
| 导出配置实体 | `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/VoucherExportConfig.java` |
| 目标系统枚举（加 KINGDEE_YXSKY） | `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/VoucherTargetSystem.java` |
| 凭证导出 controller（镜像端点 + 权限/脱敏） | grep `VoucherExport` 的 `@*Mapping` / `@RequirePermission`（Codex 定位） |
| Flyway 目录（**本卡无迁移**，仅供查号纪律） | `backend/java/cretas-api/src/main/resources/db/flyway/`（最大 V20261024_15） |

---

## 7. 验收

- 财务导出「金蝶导入模板」xlsx：借/贷分列正确留空、凭证字「记」、汇率「1」、金额 HALF_UP。
- 后端全量测试绿。
- config 行覆盖列名生效；无 config 走默认无 NPE。
- 文件可被金蝶云星空 import 向导识别（演示/客户验证）；客户给精确版本后仅改 config（无改码）。
- **诚实空**：无凭证期间返仅表头 xlsx，不编造行。
