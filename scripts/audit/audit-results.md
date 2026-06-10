# factoryId 隔离审计报告

**生成时间**: 2026-06-10T13:06:24.220Z
**审计脚本**: scripts/audit/tool-factory-isolation-audit.mjs
**扫描目录**: backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/

## 总览

- **Total Tools 扫描数**: 604
- 🔴 **HIGH 风险**: 0
- 🟡 **MEDIUM 风险**: 29
- 🟢 **LOW 风险**: 484
- ⚪ **EXEMPT（白名单豁免）**: 27
- ⚪ **NON_BUSINESS（非业务 Tool）**: 64

## 按 Domain 分组

| Domain | Total | HIGH | MEDIUM | LOW | EXEMPT | NON_BIZ |
|--------|-------|------|--------|-----|--------|---------|
| foodsafety | 30 | 0 | 14 | 14 | 2 | 0 |
| crm | 27 | 0 | 1 | 26 | 0 | 0 |
| workdesk | 38 | 0 | 6 | 32 | 0 | 0 |
| system | 27 | 0 | 1 | 23 | 3 | 0 |
| pricing | 5 | 0 | 2 | 3 | 0 | 0 |
| purchase | 8 | 0 | 1 | 7 | 0 | 0 |
| cron | 5 | 0 | 4 | 1 | 0 | 0 |
| material | 31 | 0 | 0 | 31 | 0 | 0 |
| restaurant | 107 | 0 | 0 | 41 | 12 | 54 |
| alert | 9 | 0 | 0 | 9 | 0 | 0 |
| alerts | 6 | 0 | 0 | 6 | 0 | 0 |
| dataop | 19 | 0 | 0 | 19 | 0 | 0 |
| hr | 20 | 0 | 0 | 20 | 0 | 0 |
| bom | 13 | 0 | 0 | 13 | 0 | 0 |
| common | 1 | 0 | 0 | 1 | 0 | 0 |
| camera | 11 | 0 | 0 | 9 | 2 | 0 |
| canvas | 15 | 0 | 0 | 14 | 1 | 0 |
| quality | 16 | 0 | 0 | 16 | 0 | 0 |
| config | 3 | 0 | 0 | 3 | 0 | 0 |
| dahua | 3 | 0 | 0 | 3 | 0 | 0 |
| report | 34 | 0 | 0 | 34 | 0 | 0 |
| equipment | 15 | 0 | 0 | 15 | 0 | 0 |
| finance | 16 | 0 | 0 | 16 | 0 | 0 |
| foodknowledge | 1 | 0 | 0 | 1 | 0 | 0 |
| form | 1 | 0 | 0 | 1 | 0 | 0 |
| decoration | 4 | 0 | 0 | 3 | 0 | 1 |
| indicator | 4 | 0 | 0 | 3 | 0 | 1 |
| isapi | 3 | 0 | 0 | 3 | 0 | 0 |
| lineage | 1 | 0 | 0 | 1 | 0 | 0 |
| factory | 3 | 0 | 0 | 3 | 0 | 0 |
| notify | 5 | 0 | 0 | 5 | 0 | 0 |
| pagedesign | 4 | 0 | 0 | 4 | 0 | 0 |
| sales | 8 | 0 | 0 | 8 | 0 | 0 |
| print | 2 | 0 | 0 | 2 | 0 | 0 |
| processing | 17 | 0 | 0 | 17 | 0 | 0 |
| production | 4 | 0 | 0 | 4 | 0 | 0 |
| workprocess | 8 | 0 | 0 | 8 | 0 | 0 |
| shipment | 16 | 0 | 0 | 15 | 1 | 0 |
| rd | 3 | 0 | 0 | 3 | 0 | 0 |
| returnorder | 5 | 0 | 0 | 5 | 0 | 0 |
| warehouse | 4 | 0 | 0 | 4 | 0 | 0 |
| rules | 5 | 0 | 0 | 5 | 0 | 0 |
| sample | 1 | 0 | 0 | 1 | 0 | 0 |
| scale | 13 | 0 | 0 | 11 | 2 | 0 |
| scheduling | 3 | 0 | 0 | 3 | 0 | 0 |
| shortage | 1 | 0 | 0 | 1 | 0 | 0 |
| sop | 3 | 0 | 0 | 3 | 0 | 0 |
| governance | 3 | 0 | 0 | 1 | 2 | 0 |
| transfer | 5 | 0 | 0 | 5 | 0 | 0 |
| user | 3 | 0 | 0 | 3 | 0 | 0 |
| workflow | 3 | 0 | 0 | 1 | 2 | 0 |
| workreport | 4 | 0 | 0 | 4 | 0 | 0 |
| root | 5 | 0 | 0 | 0 | 0 | 5 |
| dictionary | 3 | 0 | 0 | 0 | 0 | 3 |

## 🔴 HIGH 风险清单（立即修复）

_无 HIGH 风险 Tool。_

## 🟡 MEDIUM 风险清单（人工复核）

| # | Domain | File | 可疑模式数 | 示例 |
|---|--------|------|-----------|------|
| 1 | foodsafety | `AdditiveBomComplianceCheckTool.java` | 2 | findById() without factoryId guard: .findById(bomRecipeId) |
| 2 | foodsafety | `ColdChainAlertAcknowledgeTool.java` | 2 | findById() without factoryId guard: .findById(id) |
| 3 | foodsafety | `ColdChainAlertActiveTool.java` | 1 | findById() without factoryId guard: .findById(a.getEquipmentId() |
| 4 | foodsafety | `ColdChainTempQueryTool.java` | 2 | findById() without factoryId guard: .findById(equipmentId) |
| 5 | foodsafety | `CustomerNotifyBatchTool.java` | 2 | findById() without factoryId guard: .findById(recallEventId) |
| 6 | crm | `CustomerPriceMemoryQueryTool.java` | 2 | findById() without factoryId guard: .findById(productTypeId) |
| 7 | foodsafety | `FoodSampleCreateTool.java` | 2 | findById() without factoryId guard: .findById(materialBatchId) |
| 8 | foodsafety | `FoodSampleDisposeTool.java` | 2 | findById() without factoryId guard: .findById(id) |
| 9 | foodsafety | `HaccpCheckpointReviewTool.java` | 2 | findById() without factoryId guard: .findById(id) |
| 10 | workdesk | `HaccpStatusQueryTool.java` | 2 | findById() without factoryId guard: .findById(id) |
| 11 | foodsafety | `NutritionLabelLookupTool.java` | 1 | nutritionLabelRepository.findAll() — no factoryId arg |
| 12 | workdesk | `PdaScanTaskGenerateTool.java` | 1 | purchaseOrderRepository.findById(poId) — no factoryId arg |
| 13 | system | `PermissionAuditTool.java` | 2 | registryService.listModules() — no factoryId arg |
| 14 | workdesk | `PriceHistoryQueryTool.java` | 1 | findById() without factoryId guard: .findById(item.getPurchaseOrderId() |
| 15 | pricing | `PricingStrategyToggleTool.java` | 1 | findById() without factoryId guard: .findById(strategyId) |
| 16 | pricing | `PricingStrategyUpdateTool.java` | 1 | findById() without factoryId guard: .findById(strategyId) |
| 17 | purchase | `ProcurementOrderCreateTool.java` | 2 | findById() without factoryId guard: .findById(created.getId() |
| 18 | foodsafety | `RecallLossEstimateTool.java` | 2 | findById() without factoryId guard: .findById(recallEventId) |
| 19 | foodsafety | `RegulatoryReportGenerateTool.java` | 2 | findById() without factoryId guard: .findById(recallEventId) |
| 20 | workdesk | `RequisitionCreateTool.java` | 1 | rawMaterialTypeRepository.findById(materialId) — no factoryId arg |
| 21 | workdesk | `SalesForecast7DayTool.java` | 1 | salesOrderItemRepository.findBySalesOrderId(soId) — no factoryId arg |
| 22 | cron | `ScheduledTaskDeleteTool.java` | 2 | findById() without factoryId guard: .findById(taskId) |
| 23 | cron | `ScheduledTaskRunNowTool.java` | 2 | findById() without factoryId guard: .findById(taskId) |
| 24 | cron | `ScheduledTaskToggleTool.java` | 2 | findById() without factoryId guard: .findById(taskId) |
| 25 | cron | `ScheduledTaskUpdateTool.java` | 2 | findById() without factoryId guard: .findById(taskId) |
| 26 | foodsafety | `SsopExecuteCompleteTool.java` | 2 | findById() without factoryId guard: .findById(rec.getProcedureId() |
| 27 | foodsafety | `SsopMonthlyAuditReportTool.java` | 2 | findById() without factoryId guard: .findById(procId) |
| 28 | foodsafety | `SsopProductionGateCheckTool.java` | 1 | findById() without factoryId guard: .findById(r.getProcedureId() |
| 29 | workdesk | `SupplierDeliveryEtaTool.java` | 1 | supplierRepository.findById(filterSupplierId) — no factoryId arg |

## 详情（HIGH + MEDIUM）

### [MEDIUM] AdditiveBomComplianceCheckTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/AdditiveBomComplianceCheckTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 3
- **可疑模式**:
  - findById() without factoryId guard: .findById(bomRecipeId)
  - bomRecipeRepository.findById(bomRecipeId) — no factoryId arg

### [MEDIUM] ColdChainAlertAcknowledgeTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/ColdChainAlertAcknowledgeTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 3
- **可疑模式**:
  - findById() without factoryId guard: .findById(id)
  - alertRepository.findById(id) — no factoryId arg

### [MEDIUM] ColdChainAlertActiveTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/ColdChainAlertActiveTool.java`
- **Domain**: foodsafety
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 4
- **可疑模式**:
  - findById() without factoryId guard: .findById(a.getEquipmentId()

### [MEDIUM] ColdChainTempQueryTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/ColdChainTempQueryTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 5
- **可疑模式**:
  - findById() without factoryId guard: .findById(equipmentId)
  - equipmentRepository.findById(equipmentId) — no factoryId arg

### [MEDIUM] CustomerNotifyBatchTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/CustomerNotifyBatchTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 6
- **可疑模式**:
  - findById() without factoryId guard: .findById(recallEventId)
  - recallEventRepository.findById(recallEventId) — no factoryId arg

### [MEDIUM] CustomerPriceMemoryQueryTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/crm/CustomerPriceMemoryQueryTool.java`
- **Domain**: crm
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 4
- **可疑模式**:
  - findById() without factoryId guard: .findById(productTypeId)
  - productTypeRepository.findById(productTypeId) — no factoryId arg

### [MEDIUM] FoodSampleCreateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/FoodSampleCreateTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 4
- **可疑模式**:
  - findById() without factoryId guard: .findById(materialBatchId)
  - materialBatchRepository.findById(materialBatchId) — no factoryId arg

### [MEDIUM] FoodSampleDisposeTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/FoodSampleDisposeTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 3
- **可疑模式**:
  - findById() without factoryId guard: .findById(id)
  - foodSampleRepository.findById(id) — no factoryId arg

### [MEDIUM] HaccpCheckpointReviewTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/HaccpCheckpointReviewTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 2
- **可疑模式**:
  - findById() without factoryId guard: .findById(id)
  - checkpointRepository.findById(id) — no factoryId arg

### [MEDIUM] HaccpStatusQueryTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workdesk/HaccpStatusQueryTool.java`
- **Domain**: workdesk
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 2
- **可疑模式**:
  - findById() without factoryId guard: .findById(id)
  - checkpointRepository.findById(id) — no factoryId arg

### [MEDIUM] NutritionLabelLookupTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/NutritionLabelLookupTool.java`
- **Domain**: foodsafety
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {`
- **factoryId 引用次数**: 4
- **可疑模式**:
  - nutritionLabelRepository.findAll() — no factoryId arg

### [MEDIUM] PdaScanTaskGenerateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workdesk/PdaScanTaskGenerateTool.java`
- **Domain**: workdesk
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 5
- **可疑模式**:
  - purchaseOrderRepository.findById(poId) — no factoryId arg

### [MEDIUM] PermissionAuditTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/system/PermissionAuditTool.java`
- **Domain**: system
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 2
- **可疑模式**:
  - registryService.listModules() — no factoryId arg
  - registryService.listBySource(sourceEnum) — no factoryId arg

### [MEDIUM] PriceHistoryQueryTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workdesk/PriceHistoryQueryTool.java`
- **Domain**: workdesk
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 2
- **可疑模式**:
  - findById() without factoryId guard: .findById(item.getPurchaseOrderId()

### [MEDIUM] PricingStrategyToggleTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/pricing/PricingStrategyToggleTool.java`
- **Domain**: pricing
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 13
- **可疑模式**:
  - findById() without factoryId guard: .findById(strategyId)

### [MEDIUM] PricingStrategyUpdateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/pricing/PricingStrategyUpdateTool.java`
- **Domain**: pricing
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 13
- **可疑模式**:
  - findById() without factoryId guard: .findById(strategyId)

### [MEDIUM] ProcurementOrderCreateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/purchase/ProcurementOrderCreateTool.java`
- **Domain**: purchase
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 6
- **可疑模式**:
  - findById() without factoryId guard: .findById(created.getId()
  - rawMaterialTypeRepository.findById(materialId) — no factoryId arg

### [MEDIUM] RecallLossEstimateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/RecallLossEstimateTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 5
- **可疑模式**:
  - findById() without factoryId guard: .findById(recallEventId)
  - recallEventRepository.findById(recallEventId) — no factoryId arg

### [MEDIUM] RegulatoryReportGenerateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/RegulatoryReportGenerateTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 3
- **可疑模式**:
  - findById() without factoryId guard: .findById(recallEventId)
  - recallEventRepository.findById(recallEventId) — no factoryId arg

### [MEDIUM] RequisitionCreateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workdesk/RequisitionCreateTool.java`
- **Domain**: workdesk
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 3
- **可疑模式**:
  - rawMaterialTypeRepository.findById(materialId) — no factoryId arg

### [MEDIUM] SalesForecast7DayTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workdesk/SalesForecast7DayTool.java`
- **Domain**: workdesk
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 2
- **可疑模式**:
  - salesOrderItemRepository.findBySalesOrderId(soId) — no factoryId arg

### [MEDIUM] ScheduledTaskDeleteTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/cron/ScheduledTaskDeleteTool.java`
- **Domain**: cron
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 1
- **可疑模式**:
  - findById() without factoryId guard: .findById(taskId)
  - taskRepository.findById(taskId) — no factoryId arg

### [MEDIUM] ScheduledTaskRunNowTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/cron/ScheduledTaskRunNowTool.java`
- **Domain**: cron
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 1
- **可疑模式**:
  - findById() without factoryId guard: .findById(taskId)
  - taskRepository.findById(taskId) — no factoryId arg

### [MEDIUM] ScheduledTaskToggleTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/cron/ScheduledTaskToggleTool.java`
- **Domain**: cron
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 1
- **可疑模式**:
  - findById() without factoryId guard: .findById(taskId)
  - taskRepository.findById(taskId) — no factoryId arg

### [MEDIUM] ScheduledTaskUpdateTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/cron/ScheduledTaskUpdateTool.java`
- **Domain**: cron
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 2
- **可疑模式**:
  - findById() without factoryId guard: .findById(taskId)
  - taskRepository.findById(taskId) — no factoryId arg

### [MEDIUM] SsopExecuteCompleteTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/SsopExecuteCompleteTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 3
- **可疑模式**:
  - findById() without factoryId guard: .findById(rec.getProcedureId()
  - recordRepository.findById(recordId) — no factoryId arg

### [MEDIUM] SsopMonthlyAuditReportTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/SsopMonthlyAuditReportTool.java`
- **Domain**: foodsafety
- **原因**: 存在 2 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 8
- **可疑模式**:
  - findById() without factoryId guard: .findById(procId)
  - procedureRepository.findById(procId) — no factoryId arg

### [MEDIUM] SsopProductionGateCheckTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/SsopProductionGateCheckTool.java`
- **Domain**: foodsafety
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 5
- **可疑模式**:
  - findById() without factoryId guard: .findById(r.getProcedureId()

### [MEDIUM] SupplierDeliveryEtaTool.java
- **路径**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workdesk/SupplierDeliveryEtaTool.java`
- **Domain**: workdesk
- **原因**: 存在 1 条可疑模式需人工复核
- **签名**: `protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {`
- **factoryId 引用次数**: 5
- **可疑模式**:
  - supplierRepository.findById(filterSupplierId) — no factoryId arg

## ⚠️ 非 BusinessTool 但引用了 factoryId（建议人工 review）

| # | Domain | File | factoryId 引用次数 |
|---|--------|------|-------------------|
| 1 | root | `CreateIntentTool.java` | 2 |
| 2 | dictionary | `DictionaryAddTool.java` | 7 |
| 3 | dictionary | `DictionaryBatchImportTool.java` | 6 |
| 4 | dictionary | `DictionaryListTool.java` | 5 |
| 5 | decoration | `FactoryHomeLayoutToolStore.java` | 10 |
| 6 | restaurant | `RestaurantFinancialMetricsFetcher.java` | 8 |
| 7 | restaurant | `RestaurantReviewCityTool.java` | 2 |
| 8 | restaurant | `RestaurantReviewComplaintTool.java` | 2 |
| 9 | restaurant | `RestaurantReviewDishTool.java` | 2 |
| 10 | restaurant | `RestaurantReviewEnvScoreTool.java` | 2 |
| 11 | restaurant | `RestaurantReviewGoodTagsTool.java` | 2 |
| 12 | restaurant | `RestaurantReviewPlatformTool.java` | 2 |
| 13 | restaurant | `RestaurantReviewReplyRateTool.java` | 2 |
| 14 | restaurant | `RestaurantReviewScoreTagsTool.java` | 2 |
| 15 | restaurant | `RestaurantReviewServiceScoreTool.java` | 2 |
| 16 | restaurant | `RestaurantReviewStoreRankTool.java` | 2 |
| 17 | restaurant | `RestaurantReviewSummaryTool.java` | 2 |
| 18 | restaurant | `RestaurantReviewTimePeriodTool.java` | 2 |
| 19 | restaurant | `RestaurantReviewTrendTool.java` | 2 |
| 20 | restaurant | `RestaurantReviewVipTagsTool.java` | 2 |
| 21 | restaurant | `RestaurantReviewVipTool.java` | 2 |
| 22 | restaurant | `RestaurantShrinkageAnalysisTool.java` | 5 |
| 23 | restaurant | `RestaurantShrinkageDataFetcher.java` | 6 |
| 24 | restaurant | `RestaurantStorePnlOnePagerTool.java` | 5 |
| 25 | restaurant | `StoreKpiDashboardTool.java` | 2 |
| 26 | restaurant | `StoreReviewRevenueTool.java` | 2 |
| 27 | root | `TestIntentMatchingTool.java` | 7 |
| 28 | root | `UpdateIntentTool.java` | 2 |

---

## 建议后续动作

1. **HIGH 风险**: 立即逐项修复并提交 PR，建议双人 review。
2. **MEDIUM 风险**: 人工复核每一条可疑模式，修复确认的真实漏洞。
3. **跨工厂 E2E 回归测试**: 建议每个 Repository 的 by-factory finder 加单元测试，确保跨工厂查询返回空。
4. **定期审计**: 建议 CI 增加 `node scripts/audit/tool-factory-isolation-audit.mjs` 作为门禁，发现 HIGH 立即阻塞合并。
