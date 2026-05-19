# G12-10 采购需求总表 entry verify (Sprint 5 Track Z-2)

**日期**: 2026-05-19
**Owner**: Sprint 5 Track Z agent
**Status**: ⚠️ GAP CONFIRMED — backend + frontend 缺 aggregate "采购需求总表" 入口

---

## TL;DR

**HJ "采购需求总表" entry 在 Cretas 部分实装 (Round 11 N31 S-MRP-1 PR #682) 但 仅 per-order RN screen + AI Tool, web-admin 完全无入口, 也无 aggregate list 后端 endpoint.** Track C-4 (1d) 仍需做.

---

## Verify 详情

### HJ baseline (Round 12 §A.3)

HJ 采购模块有 "采购需求总表" sub-menu — 汇总所有销售订单的物料缺口分析, 并支持按物料/供应商汇总 + 一键转 PO. 是 MRP 中央仪表盘.

### Cretas 现状 (audit 2026-05-19)

#### Backend (PR #682 Sprint 2-E shipped)

| 文件 | 用途 | 覆盖度 |
|---|---|---|
| `SalesOrderShortageReportListener.java` | SO 财务审核通过后异步生成 per-order shortage 快照 | ✅ |
| `SalesOrderShortageReport.java` | 实体 (per-order, factoryId + salesOrderId 唯一) | ✅ |
| `ShortageAnalysisServiceImpl.java` | 算 shortage 逻辑 | ✅ |
| `ShortageAnalysisTool.java` (AI tool) | AI 意图调用 (单订单 live 分析) | ✅ |
| `SalesOrderShortageController.java` | REST `GET /api/mobile/{factoryId}/sales/orders/{orderId}/shortage-report` | ✅ **但仅 per-order** |
| **`SalesOrderShortageReportRepository.findAll() / findByFactoryId(...)`** | **aggregate list 接口** | ❌ **缺** |

Repository 当前只有:
```java
Optional<SalesOrderShortageReport> findByFactoryIdAndSalesOrderId(...);
```

无 `List<SalesOrderShortageReport> findByFactoryIdAndDateRange(...)` 等聚合查询.

#### Frontend (RN ✅ / web-admin ❌)

| Layer | Shortage entry | 路径 |
|---|---|---|
| **RN** | ✅ `SalesOrderShortageReviewScreen.tsx` | `frontend/CretasFoodTrace/src/screens/factory-admin/inventory/` |
| **web-admin** | ❌ NO entry | (procurement router 无 shortage / requirement / MRP 路由) |

`web-admin/src/router/index.ts` procurement 子树仅含:
- orders (采购订单)
- orders/:id
- suppliers (供应商管理)
- price-lists (价格表管理)
- receives (采购入库)
- finance-review (财务待审采购单)
- finance-review/:id
- inquiry-quotes (核价单)
- inquiry-quotes/:id

**无 "采购需求" / "缺料分析" / "MRP 仪表盘" 路由.**

---

## Z-2 决策

### 不能 close — 仅 verify (per dispatch §Z constraints)

Z-2 scope 是 verify-only (0.5d), 但发现 gap 不只是 frontend route. backend 也缺 aggregate endpoint. 完整实装需要:

1. **Backend** (1d):
   - `SalesOrderShortageReportRepository.findByFactoryIdAndStatus(...)` (Page<>)
   - `SalesOrderShortageController.listReports(factoryId, params)` GET endpoint
   - DTO: SummaryRow (按物料聚合: materialId / requiredQty 汇总 / availableQty / shortage / 关联 SO 列表)
   - 服务层 aggregate: `getProcurementRequirementSummary(factoryId, dateRange)` 返按物料聚合
2. **Frontend** (1d):
   - Vue `web-admin/src/views/procurement/requirement-summary/list.vue`
   - 路由 `/procurement/requirement-summary` (Sprint 5 加)
   - 元素: el-table 按物料聚合 + "一键转 PO" 按钮 (跳到 PO create 预填) + 按销售订单展开行
3. **Test** (0.5d):
   - 集成测试: 创建 3 SO 都审核通过 → list endpoint 返 3 SO + 物料汇总

**总工时**: ~2.5d (跟 Track C-4 重合)

### 推荐 Sprint 5 Track C-4 完整做

Z-2 verify 输出 → Track C-4 brief 应明确包含:
- "不仅加 frontend route — 需先加 backend list endpoint + aggregate DTO"
- 估时从 1d 调到 **2.5d** (更准确)
- 列依赖: 复用 Sprint2-E ShortageAnalysisService (per-order 算法已有, 加 aggregate wrapper)

---

## File:line evidence

| 主题 | 文件 | 行 | 现状 |
|---|---|---|---|
| Backend endpoint | `SalesOrderShortageController.java` | 36-53 | 仅 per-order |
| Repository | `SalesOrderShortageReportRepository.java` | 12-16 | 仅 findByFactoryIdAndSalesOrderId |
| Frontend route | `web-admin/src/router/index.ts` | 280-360 | 无 shortage/requirement entry |
| RN screen | `SalesOrderShortageReviewScreen.tsx` | 1-159 | ✅ 存在 (RN-only) |
| PR #682 commit | `b936d19e3` | — | merged Sprint 2-E |

---

## Decision summary

- **Z-2 → ⚠️ GAP FOUND, deferred to Track C-4**
- Track C-4 brief 必须扩到 2.5d 包 backend + frontend + test
- 不动 code 在 Z-2 phase (per dispatch "0.5d verify only")
- 加 frontend route 单独 1-2 行 PR 不可行 — backend endpoint 缺, 加 route 也无数据可显

---

**Sign-off**: Track Z agent, 2026-05-19
