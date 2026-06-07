---
name: field-consistency-check
description: 检查前后端字段名一致性。自动比较后端 Entity 与前端 Interface 的所有字段，发现缺失或不一致的字段。使用此 Skill 确保数据模型统一。
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash
---

# 字段一致性检查 Skill

## Entity/Interface 映射表

### 核心业务实体

| 后端 Entity | 前端文件 | Interface 名 |
|-------------|---------|-------------|
| Customer | customerApiClient.ts | Customer |
| Supplier | supplierApiClient.ts | Supplier |
| User | userApiClient.ts | UserDTO |
| Factory | platformApiClient.ts | FactoryDTO |
| FactoryEquipment | equipmentApiClient.ts | Equipment |
| MaterialBatch | materialBatchApiClient.ts | MaterialBatch |
| RawMaterialType | materialTypeApiClient.ts | MaterialType |
| ProductType | productTypeApiClient.ts | ProductType |
| QualityInspection | qualityInspectionApiClient.ts | QualityInspection |
| ShipmentRecord | shipmentApiClient.ts | ShipmentRecord |
| DisposalRecord | disposalRecordApiClient.ts | DisposalRecord |

### SmartBI 实体 (PostgreSQL)

| 后端 Entity | 数据库表 | 说明 |
|-------------|---------|------|
| SmartBIPgUpload | smart_bi_pg_uploads | 上传记录 |
| SmartBIPgFieldDefinition | smart_bi_pg_field_definitions | 字段定义 |
| SmartBIPgDynamicData | smart_bi_pg_dynamic_data | 动态数据 |

## 快速检查方法

使用 Grep/Glob 工具而非 bash 命令进行检查:

1. **提取后端字段**: 用 Read 工具读取 Entity 文件
   - Entity 路径: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/`
   - 查找 `private` 字段声明

2. **提取前端字段**: 用 Read 工具读取 Interface 文件
   - RN 前端 API: `frontend/CretasFoodTrace/src/services/api/`
   - Vue Web Admin: `web-admin/src/api/`

3. **对比差异**: 比较两侧字段名列表

## 字段命名差异表

| Entity | 电话字段 | 地址字段 |
|--------|---------|---------|
| Factory | contactPhone | address |
| Supplier | contactPhone + phone | address |
| Customer | contactPhone + phone | shippingAddress + billingAddress |
| User | phone | - |

## 命名规范

| 层级 | 命名风格 | 示例 |
|------|----------|------|
| Java Entity | camelCase | `batchNumber` |
| 数据库列 | snake_case | `batch_number` |
| JSON API | camelCase | `"batchNumber"` |
| TypeScript | camelCase | `batchNumber` |

## 修复流程

1. 用 Grep 查找后端字段: `private` in `entity/XXX.java`
2. 用 Read 查看前端 Interface
3. 更新前端 Interface 与后端一致
4. 编译检查: `npx tsc --noEmit --skipLibCheck`

## 相关路径

- 后端 Entity: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/`
- 前端 RN API: `frontend/CretasFoodTrace/src/services/api/`
- 前端 Vue API: `web-admin/src/api/`
