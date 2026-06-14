# 六扇门 2026-06-14 Session Ship 状态 + 验证过 Backlog

> **用途**: 权威记录本 session 已 ship 到 prod 的功能 + 验证过的剩余 backlog。
> ⚠️ **给未来 scoping 用**: 6.09 requirements-catalog / 排期 / 6.14 使用逻辑清单 反映的是**规划/部署前**状态,
> 把很多本 session 已 ship 的项标成"缺/部分"。**做 backlog scoping 必须读当前 main 代码, 不能信那些规划文档**
> (2026-06-14 一个 Explore 就因读规划文档把已 ship 的当成缺 + 幻觉出 `com/example/cretas` 假包路径)。
> 真实包是 `com.cretas.aims`。

---

## 一、本 session 已 ship 到 prod (全部 deployed + 验证)

### 9 红线 backlog (两批 blue-green 部署)
| 项 | PR | prod 验证 |
|---|---|---|
| B8 未税价口径 | #849 | StandardCostServiceImpl caliberHint/PRE_TAX |
| F4 入库 sourceDoc 堵口 | #851 | MaterialBatchServiceImpl validateSourceDoc/LEGACY_IMPORT |
| 出成率自学习**建议** | #853 | BomYieldSuggestion (⚠️ 只写 PENDING 建议, **不自动应用** — 见 backlog) |
| B9 BOM 价随采购浮动 | #854 | BomPriceAdjustmentProposal + approve 应用 |
| F9 库存不足报警 | #856 | InventoryLowStockEventPublisher (fail-open 终审修复) |
| 进项票催票 | #857 | PurchaseInvoiceChaseService + V20261024_09 |
| 科目余额表期初结转 | #858 | VoucherExport 期初 CLOSED |
| N10 结算自动建 FG 守卫 | #859 | SupplyChainOrchestrator requiresProductionSettlement |
| B6 半成品跨类加权 | #861 | YieldReport/WipInventory honest-null |

### 4 决策卡 (4 决策已拍板全做)
| 决策 | 卡 | PR | prod |
|---|---|---|---|
| ① 编码**严格 16 位** | A | #869 | BomRecipeItem.primaryCode + V20261024_10 + 16位强制(工厂启用字典时) |
| ② 权限模块访问控制 | B | #870 | UserModuleAccess + ModuleEnabledInterceptor(后端enforcement) + V20261024_11 |
| ③ G5 付款给客户 | — | — | **关闭**(转录确认无付客户款场景) |
| ④ 财务表头 | C | #871 | VoucherExport: 序时账/总账/明细账/试算平衡(三组校验不平抛错) |

### 其它
| 项 | PR | 说明 |
|---|---|---|
| 撤回 deprecate | #866 | 计划级 PRODUCTION_REVERSAL 死路 @Deprecated → 导向批次级整单撤回(ReportReversalService) |
| 生产退货/退料回仓 | #872 | 退回=发出−实用−损耗; 关单事务内真反冲 MaterialBatch.usedQuantity(fail-closed) + V20261024_12 |
| H2 flaky 根治 | #868 | test H2 URL 加 NON_KEYWORDS=MONTH,YEAR,VALUE(test-only, prod 不受影响) |
| smartbi 解锁 | #864 | SmartBiQueryTemplateController 改 @Autowired(required=false) (修别 session 强 merge 的红) |

### In-flight (本 session 派出, 未 merge)
- Card E 利润表+数量金额明细账 (feat/liushanmen-finance-statements)
- Card F B9 BomPriceAdjustment 健壮性 polish (PENDING 去重 + approve 锁)
- Card G 生产+BOM E2E 测试 (Playwright headed + 防呆)

---

## 二、验证过的剩余 Backlog (对当前 main 代码核实, 非规划文档)

### 财务域
- **资产负债表 + 现金流量表** — Card E 做利润表+数量账, 这两张剩 (P1/P2)
- **金蝶导入模板** — 待客户给版本 (KIS/云星空/精斗云), 表头列序不同 (客户-blocked)
- Card C 试算平衡 `IllegalStateException`→`BusinessException(400)` refinement (Card E 同文件, 并进去做)

### 生产域
- **出成率自动应用** — #853 只写 PENDING 建议; "报工后自动回写出成率作下批领料默认" 真缺 (工厂核心诉求, M)
- N10 普通报工中转 / 原料厂号方向 — F006 走 settlement guard 已覆盖主路径; 厂号选择是客户向 (转录 [110:59], 待澄清"厂号=编码段 vs 独立属性")

### 销售域
- **销售报表含税单价列** — N6 只做一半, 补"未税/含税/税额"三列并排 (S)
- 底价/毛利率红线**前端弹窗** — 后端#804 + grossMarginConfig 前端基础有, "下单低于最低售价红色弹窗"待补 (M)
- 多 SO→单生产工单合并 — 倾向"直接在销售单做不单独合并", 待客户拍板 (L, customer-blocked)

### 库存/RN 域
- **一物一码 RN 扫码 + 热敏/二维码打印模板** — web 扫码有, RN 领料扫码 + 打印缺 (M)

### 客户-blocked (需见客户确认)
- 金蝶版本 (④ 表头) · 原料厂号方向 (N10) · 多 SO 合并

---

## 三、Codex 卡候选 (独立可并行, 已核实真缺)
按优先级 (排除已 ship):
1. **出成率自动应用** (P0, M) — BomYieldSuggestionService 完工事件→自动回写 ProductType yieldRate 作下批默认 (无迁移)
2. **销售报表含税单价列** (P1, S) — 三列并排, web-admin 报表 (无迁移)
3. **毛利红线下单弹窗** (P1, M) — 下单页低于最低售价红色弹窗 (复用 grossMarginConfig)
4. **一物一码 RN 扫码 + 打印模板** (P1, M) — RN 端
5. **财务资产负债表 + 现金流量表** (P1/P2) — 待 Card E merge 后 (同 VoucherExport 文件, 避冲突)

> 派卡前对当前 main 代码确认现状, **别信规划文档的"缺/部分"标记**。
