# HANDOFF：一加物流排线 MVP 后端闭环

> 日期：2026-07-11  
> 接手对象：Claude Code  
> 项目：`C:\Users\Steve\my-prototype-logistics`  
> 当前生产入口：`https://admin.cretaceousfuture.com/demo?tenant=logistics`  
> 当前 `origin/main` 基线（编写本文时）：`c9954d194a40c740f8bfc5c9b903b61ef089dce6`

## 0. 交接结论

当前物流模块不是一个后端闭环 MVP，而是一个已部署的高保真前端功能原型：

- 有四步操作流、详细地图、多点线路、容量分车、人工调序、车辆/司机展示、导出预览和 CSV 下载。
- 排线逻辑运行在浏览器内，数据来自前端 fixture。
- 用户上传的 CSV 只做浏览器字段校验，不会替换订单，也不会写数据库。
- 刷新后业务状态丢失，调度记录主要是 mock。
- 物流演示账号使用本地假 token，不能作为真实后端租户使用。
- Java 已有车辆、出货、客户、导入中心和生产调度等基础能力，但没有物流订单、车次、停靠点和线路计划领域。
- Python `scheduling` 当前是 LinUCB 工人推荐，不是车辆路径规划。

本次接手的目标不是继续润色前端，也不是重写整个 TMS，而是复用现有 Cretas 底座，补齐一个可持久化、可回读、可调整、可确认、可导出的物流垂直闭环。

## 1. 客户问题与产品目标

客户不是要实时 GPS 监控，而是要解决每日排线复杂度：每天订单不同，约 200 家门店，需要根据门店货量、车辆容量、服务区域和路线距离快速形成多点配送线路，再由调度员人工修正。

系统应表达的核心价值：

> 调度员把当天订单导入，系统立即把门店点位展示出来，按容量和距离生成可解释的推荐线路；调度员只需处理异常、调整顺序、确认并导出，不需要在传统 TMS 中跨多个复杂菜单维护同一批数据。

这不是“成熟最优算法”的承诺。首期价值是：

1. 数据只录一次。
2. 每家门店不会静默漏排或重复分配。
3. 超载、缺车、缺司机、缺路线数据会明确阻止正式确认。
4. 地图、线路卡、人工调整、调度记录和导出使用同一份后端事实。
5. 刷新、重新登录或换页面后仍能继续处理。

## 2. 会议转录的权威需求

权威来源：

- `tmp/meeting-20260709-logistics/transcript.md`
- `tmp/meeting-20260709-logistics/transcript.json`（结构化时间段和识别结果）
- `tmp/meeting-20260709-logistics/requirements-summary.md`
- `tmp/meeting-20260709-logistics/meeting.m4a` / `meeting.wav`（仅在转录歧义时回听）
- 客户地图参考图仅存在本地临时目录，不得提交到 public GitHub。

接手者必须完整阅读 `transcript.md`，不能只依赖需求摘要或本 Handoff 的摘录。`transcript.json` 用于核对时间段；音频只作为识别歧义的最终证据。以上会议材料均保留在本地 `tmp`，不得加入 Git staging。

关键原话：

- `[00:22-00:56]`：模板导入订单，在地图显示全部点位，设置装载率，自动排线，允许手动调整。
- `[01:56-02:13]`：当天约 200 家门店；门店包含件、箱、重量、体积；车辆按约 10 方规划。
- `[03:05-03:09]`：业务是多点运输。
- `[03:43-03:54]`：司机可预先维护固定区域，系统规划后最好自动匹配司机。
- `[04:12-04:18]`：调度维度包括车辆容量、大小和司机工作时间。
- `[04:26-05:15]`：客户车辆是双温车，首期不需要按冷冻/常温拆车；先按总容量约束。
- `[05:16-05:53]`：车辆和司机有绑定，也可能一车多个司机，白班/晚班可不同。
- `[06:16-06:34]`：客户明确不需要实时位置和实时 GPS。
- `[07:51-08:05]`：点击门店看到货量；一辆车装多个点；输出第一家、第二家、第三家顺序。
- `[08:24-08:31]`：线路每天随订单动态变化，不是固定线路。
- `[08:50-09:07]`：一条线路可装约 5 家门店，输出总公里数并导成表格。
- `[09:38-09:51]`：导出车辆、门店顺序和车辆当天总公里数。
- `[09:51-10:00]`：首期一家门店通常由一辆车送完，不拆单到多辆车。
- `[09:09-09:24]`：司机工资/公里计费公式不放进本模块。
- `[11:52-12:01]`：电脑网页端即可，无手机端要求。

## 3. 当前已经完成的内容

### 3.1 已部署前端入口

物流租户左侧“物流调度”下有四个入口：

- `/scheduling/logistics/workbench`：排线工作台
- `/scheduling/logistics/records`：调度记录
- `/scheduling/logistics/orders`：门店与订单
- `/scheduling/logistics/resources`：车辆与司机

旧地址 `/scheduling/logistics-demo` 会跳到工作台。物流租户不会再误入通用工厂 Dashboard。

### 3.2 可复用前端资产

目录：`web-admin/src/views/scheduling/logistics/`

```text
logistics/
├── types.ts
├── mockData.ts
├── roadSegments.ts
├── routeEngine.ts
├── useLogisticsDemoState.ts
├── components/
│   ├── LogisticsStepBar.vue
│   ├── LogisticsMap.vue
│   ├── RouteCards.vue
│   ├── StoreDetailDrawer.vue
│   ├── OrderImportStep.vue
│   ├── ManualConfirmStep.vue
│   └── ExportConfirmStep.vue
├── workbench/index.vue
├── records/index.vue
├── orders/index.vue
└── resources/index.vue
```

当前前端具备：

- 固定 13 家示例门店及高细节苏州脱敏底图。
- 多条多点配送线路。
- 目标装载率和确定性容量分车。
- 超出目标/硬容量时产生下一车次或待处理门店。
- 路线卡、地图节点、人工顺序和导出共用 `RouteTrip.storeIds`。
- 人工上下移动门店顺序。
- 车辆、司机、替班司机、自有/外协、区域和班次展示。
- CSV 下载。
- Vitest 组件/领域测试和 Playwright medium 流程。

这些 UI 和交互逻辑应保留，但必须从“前端事实源”迁移为“后端 API 事实源”。

### 3.3 相关提交

- `3cf25cd38`：第一版物流前端页面。
- `2464e4a7e`：容量分车和差异化表达。
- `a2d7bfce1`：模块化多点调度工作台，33 个文件，约 4200 行新增。
- `efb140d5c`：通用 scheduling 前端 mock 数据流，不是物流 Java 后端。
- `decaa14ee`：物流 Dashboard 跳转和菜单 hover 修复。

### 3.4 已有设计文档的定位

- `docs/superpowers/specs/2026-07-10-logistics-demo-modular-workbench-design.md`
- `docs/superpowers/plans/2026-07-10-logistics-demo-modular-workbench.md`

这两份文档只描述前端 Demo。计划头部明确写了：

> Pure Vue 3 frontend mock.

并明确排除了 Java/Python API、数据库和后端持久化。它们可以作为 UI/交互依据，不得继续作为本次后端闭环的范围依据。

## 4. 当前没有完成的内容

以下能力目前不存在或没有接入物流页面：

1. 真正把 CSV/Excel 行解析为物流订单并写库。
2. 导入批次、行级错误、重复导入和幂等控制。
3. 门店订单刷新后回读。
4. 门店经纬度/定位状态持久化。
5. 物流车辆方数、自有/外协、区域和班次持久化。
6. 独立司机资料和车辆多司机绑定。
7. 后端物流计划、车次、停靠点和路线段实体。
8. 后端生成线路、重新计算容量和公里数。
9. 跨线路移动门店和后端校验。
10. 正式确认状态机、乐观锁和审计记录。
11. 后端调度记录和再次导出。
12. 后端生成 CSV/Excel。
13. 真实、租户受限的物流 Demo JWT。
14. 刷新/重新登录/换电脑后的持久化读回。

## 5. 现有后端可复用能力

### 5.1 Java 车辆管理

相关文件：

- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Vehicle.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/VehicleController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/VehicleServiceImpl.java`

已有车牌、司机姓名/电话、载重、当前装载、状态和车辆类型。缺少体积容量、车辆来源、服务区域、多司机和班次。

不要直接把 `capacity` 从 kg 改成 m³。保持现有仓库装车语义，新增物流 profile 或明确的新字段。

### 5.2 Java 出货与客户

相关文件：

- `entity/ShipmentRecord.java`
- `controller/ShipmentController.java`
- `entity/Customer.java`

已有客户、配送地址、出货日期、产品、数量、车辆和司机字段。它们可作为来源关联，但不能代替每日门店物流订单：缺少箱数、件数、重量、体积、时间窗、经纬度和导入批次。

### 5.3 Java 导入中心

相关文件：

- `service/datacenter/ImportService.java`
- `entity/datacenter/ImportRule.java`
- `entity/datacenter/ImportJob.java`
- `service/datacenter/impl/ImportServiceImpl.java`

已有 dry-run、行级错误、commit 和导入任务历史。优先复用其模式和公共解析能力；如果通用反射写实体无法安全表达物流聚合，可新增物流专用 import service，但必须保留“预检 → 用户确认 → 提交”的两段式体验。

### 5.4 Java 智能调度

相关文件：

- `entity/SchedulingPlan.java`
- `entity/LineSchedule.java`
- `controller/SchedulingController.java`
- `service/scheduling/**`

这套调度是生产排程，`LineSchedule` 绑定 `productionLineId`、`batchId` 和工人。复用状态机、权限、分页、确认和服务分层模式，不要把物流线路硬塞进生产排程表。

### 5.5 Python scheduling

`backend/python/scheduling/api/routes.py` 当前只提供 LinUCB 工人推荐。首版物流闭环不需要调用它，也不应宣传为现有路径优化算法。

第二阶段若需要成熟 VRP、时间窗、多目标优化，可评估 Python OR-Tools 服务；本轮不阻塞于此。

## 6. 推荐目标架构

### 6.1 高层数据流

```text
CSV / Excel
    ↓
Java 导入预检（字段、类型、重复、地址/坐标）
    ↓
用户确认提交
    ↓
PostgreSQL：订单批次 + 门店订单
    ↓
Java 物流排线服务
    ├── 容量/载重约束
    ├── 区域与班次候选
    ├── 车辆/司机匹配
    ├── 多点顺序生成
    └── 距离提供器
    ↓
PostgreSQL：计划 + 车次 + 停靠点 + 路段/里程
    ↓
Vue 工作台读取同一计划
    ↓
人工调序 / 跨线路移动 / 换车换司机
    ↓
Java 重新校验和计算
    ↓
正式确认 → 调度记录 → 后端 CSV/Excel 导出
```

### 6.2 领域边界

新增 `logistics` bounded context，不污染生产 `SchedulingPlan/LineSchedule`。

建议 Java package：

```text
com.cretas.aims.logistics
├── controller
├── dto
├── entity
├── repository
├── service
│   ├── importjob
│   ├── routing
│   └── export
└── mapper
```

如项目现有约定要求 entity/controller 位于原目录，也应使用 `logistics` 子包保持边界。

## 7. 建议数据模型

最终字段名应结合现有命名规范和数据库迁移规则确定，但业务语义必须覆盖以下内容。

### 7.1 `logistics_order_batches`

- `id`
- `factory_id`
- `business_date`
- `batch_number`
- `source_filename`
- `status`: `PREVIEWED | COMMITTED | PLANNED | CANCELLED`
- `total_rows`, `valid_rows`, `error_rows`
- `created_by`, `created_at`
- `version`

幂等键至少包含 `factory_id + business_date + source fingerprint`，重复上传不能静默生成两批相同订单。

### 7.2 `logistics_delivery_orders`

- `id`, `factory_id`, `batch_id`
- `store_code`, `store_name`
- `address`
- `area_code`
- `pieces`, `boxes`
- `weight_kg`, `volume_cbm`
- `window_start`, `window_end`
- `longitude`, `latitude`
- `location_status`: `RESOLVED | UNRESOLVED | OUT_OF_BOUNDS`
- `status`: `IMPORTED | PLANNED | CONFIRMED | CANCELLED`
- `source_row_number`
- `version`

首期一家门店订单不可拆到多辆车。单家体积/重量超过所有车辆硬容量时进入待处理，不能静默拆单。

### 7.3 `logistics_vehicle_profiles`

建议与现有 `vehicles.id` 一对一关联：

- `vehicle_id`, `factory_id`
- `capacity_cbm`
- `max_weight_kg`（若现有 `Vehicle.capacity` 已覆盖可只引用）
- `source`: `OWNED | OUTSOURCED`
- `body_type`
- `temperature_mode`: 首期可固定 `DUAL_TEMP`
- `service_areas`
- `available_from`, `available_to`
- `active`
- `version`

### 7.4 `logistics_drivers`

- `id`, `factory_id`
- `name`, `phone`
- `employment_type`: `OWNED | OUTSOURCED`
- `service_areas`
- `available_from`, `available_to`
- `active`
- `version`

### 7.5 `logistics_vehicle_drivers`

- `vehicle_id`, `driver_id`
- `role`: `PRIMARY | BACKUP`
- `shift_start`, `shift_end`
- `priority`
- 唯一键和有效期，支持一车 2–3 个司机。

### 7.6 `logistics_plans`

- `id`, `factory_id`, `order_batch_id`, `plan_date`
- `plan_number`
- `target_load_pct`
- `status`: `DRAFT | NEEDS_ACTION | CONFIRMED | EXPORTED | CANCELLED`
- `distance_source`: `MAINTAINED_MATRIX | MAP_PROVIDER`
- `total_stores`, `total_trips`, `total_distance_km`
- `created_by`, `confirmed_by`, `confirmed_at`
- `version`

### 7.7 `logistics_trips`

- `id`, `plan_id`, `trip_no`
- `vehicle_id`, `driver_id`
- `status`: `DRAFT | NEEDS_VEHICLE | NEEDS_DRIVER | NEEDS_ROUTE_DATA | CONFIRMED`
- `total_volume_cbm`, `total_weight_kg`
- `load_rate`, `weight_load_rate`
- `total_distance_km`
- `geometry`（JSONB，可选）
- `version`

### 7.8 `logistics_stops`

- `id`, `trip_id`, `delivery_order_id`
- `sequence_no`
- `leg_distance_km`
- `arrival_window_start`, `arrival_window_end`
- `geometry`（当前段可选 JSONB）
- 唯一键 `trip_id + sequence_no`
- 唯一约束/服务校验确保一个订单只属于一个有效计划车次。

### 7.9 `logistics_distance_edges`

首期不接真实地图 SDK，但公里数不能伪造：

- `factory_id`
- `from_point_id`, `to_point_id`
- `distance_km`
- `geometry`
- `source`: `CUSTOMER_MAINTAINED | MAP_PROVIDER | DEMO_FIXTURE`
- `updated_at`

Demo 租户可把当前 `roadSegments.ts` 的脱敏数据迁移成 `DEMO_FIXTURE`。真实租户若缺边，计划状态必须是 `NEEDS_ROUTE_DATA`，不得用穿地图直线或伪造公里数降级。

经纬度可用于候选排序或地图点位，但直线距离不得冒充最终道路公里数。

## 8. 导入模板与校验

### 8.1 模板字段

客户明确要求的字段：

- 门店名称
- 地址
- 数量
- 重量
- 体积

推荐完整模板：

| 字段 | 必填 | 说明 |
|---|---:|---|
| 业务日期 | 是 | 当天排线日期 |
| 门店编码 | 是 | 幂等与主数据关联 |
| 门店名称 | 是 | 客户明确要求 |
| 配送地址 | 是 | 客户明确要求 |
| 件数 | 是 | 非负整数 |
| 箱数 | 是 | 非负整数 |
| 重量 kg | 是 | 正数 |
| 体积 m³ | 是 | 正数 |
| 配送开始时间 | 否 | 首期可选 |
| 配送结束时间 | 否 | 首期可选 |
| 经度 | 否 | 与纬度同时提供 |
| 纬度 | 否 | 与经度同时提供 |
| 区域 | 否 | 可由门店资料补充 |

### 8.2 两段式导入

1. `preview`：解析文件，返回总行数、有效行、错误行和逐行错误，不写业务订单。
2. `commit`：使用 preview job ID 提交，写入订单批次和有效订单。

要求：

- 缺必填字段、非法数字、负数、重复门店编码、经纬度只填一个、同批重复行必须明确指出行号和列名。
- 提交操作幂等；重复点击不能重复写入。
- 提交后页面立即显示真实导入门店，不再显示固定 13 家 fixture。
- 未定位订单可保留为 `UNRESOLVED`，但生成线路前必须处理，或明确进入待处理集合。
- 下载模板必须与后端实际校验字段一致，不能再由 Vue 单独维护另一份表头。

## 9. MVP 排线算法

首期在 Java 实现确定性、可解释 heuristic，不承诺全局最优，不需要 LLM，也不需要 Python。

### 9.1 输入

- 已提交订单批次。
- 可用车辆和物流 profile。
- 司机、车辆绑定、区域和班次。
- `targetLoadPct`，范围 `(0, 100]`，UI 可限制为 50–100。
- 距离边/geometry provider。

### 9.2 硬约束

1. 单车总体积不得超过 `capacityCbm`。
2. 单车总重量不得超过 `maxWeightKg`。
3. 一家门店只能属于一条车次，不拆单。
4. 一个车辆不能同时分配到重叠车次。
5. 一个司机不能同时分配到重叠车次。
6. 车辆/司机需覆盖区域和班次。
7. 缺地址定位或缺必要距离边时不能正式确认。

### 9.3 软目标

按以下顺序实现并可配置权重，但首期不需要复杂优化器：

1. 优先固定区域和固定司机。
2. 在硬容量内尽量接近目标装载率。
3. 尽量少用车。
4. 在候选方案中减少总公里数。

### 9.4 建议步骤

1. 按区域和时间窗形成候选集合。
2. 按车辆容量和目标装载率进行确定性装箱。
3. 为每个车次选择满足容量/载重/区域/班次的车辆。
4. 优先选择该车主司机，其次可用替班司机。
5. 用已维护距离边进行 nearest-neighbor 顺序生成。
6. 汇总分段公里数、总体积、总重量和装载率。
7. 无车生成 `NEEDS_VEHICLE`；无司机生成 `NEEDS_DRIVER`；缺边生成 `NEEDS_ROUTE_DATA`；单店超硬容量进入 unassigned。

所有 tie-break 必须稳定，例如按区域、门店编码、车辆 ID 排序，保证相同输入产生相同结果。

## 10. 人工调整规则

后端必须提供原子操作，前端不能只改本地数组：

- 同车次上移/下移停靠点。
- 把门店移动到另一车次指定位置。
- 新建待匹配车次。
- 修改车辆。
- 修改司机。
- 重新生成整个草稿。

每次调整后后端重新验证：

- 门店唯一归属。
- 体积和重量硬容量。
- 车辆/司机冲突。
- 区域/班次。
- 路段数据完整性。
- 分段公里数与总公里数。

调整成功返回完整最新 plan snapshot 和新 `version`。并发旧版本返回 409，不允许静默 last-write-wins。

## 11. API 草案

统一前缀：`/api/mobile/{factoryId}/logistics`

### 11.1 订单导入

- `GET /order-import/template`
- `POST /order-import/preview`：multipart 文件
- `POST /order-import/{jobId}/commit`
- `GET /order-batches`
- `GET /order-batches/{batchId}`
- `GET /orders?batchId=...`
- `PUT /orders/{orderId}/location`

### 11.2 资源

- `GET /vehicles`
- `PUT /vehicles/{vehicleId}/profile`
- `GET /drivers`
- `POST /drivers`
- `PUT /drivers/{driverId}`
- `PUT /vehicles/{vehicleId}/drivers`

### 11.3 排线计划

- `POST /plans/generate`
- `GET /plans`
- `GET /plans/{planId}`
- `POST /plans/{planId}/regenerate`
- `PUT /plans/{planId}/trips/{tripId}/stops/reorder`
- `POST /plans/{planId}/trips/{tripId}/stops/move`
- `PUT /plans/{planId}/trips/{tripId}/vehicle`
- `PUT /plans/{planId}/trips/{tripId}/driver`
- `POST /plans/{planId}/trips/{tripId}/confirm`
- `POST /plans/{planId}/confirm`
- `GET /plans/{planId}/export.csv`
- `GET /plans/{planId}/export.xlsx`（若项目已有 Excel 导出工具，建议同时支持）

所有写接口：

- 从认证上下文校验 factory，不信任 body 里的 factoryId。
- 使用 `@RequireModule("scheduling")` 和合适的读写权限。
- 返回统一 `ApiResponse<T>`。
- 对无效状态迁移返回 409 和可操作提示。
- 不返回假成功或前端 fallback 数据。

## 12. 前端改造要求

### 12.1 保留

- 四模块信息架构。
- 四步工作台。
- 详细地图视觉。
- 线路卡和门店详情。
- 人工调整交互。
- 当前可读性、颜色和无 hover 自动展开行为。

### 12.2 替换

- 新建 `web-admin/src/api/logistics.ts`，定义完整请求/响应类型。
- `useLogisticsDemoState.ts` 改为 API-backed composable/store，或者新建 `useLogisticsScheduling.ts` 并逐步替换。
- `OrderImportStep.vue` 上传真实文件，展示 preview 行级错误，commit 后加载真实订单。
- `orders/index.vue` 从后端分页读取订单，显示导入批次和定位状态。
- `resources/index.vue` 从后端读取并维护车辆 profile、司机和绑定。
- `workbench/index.vue` 从后端生成/读取 plan snapshot；所有调整写回后端。
- `records/index.vue` 读取后端计划历史并支持再次导出。
- `ExportConfirmStep.vue` 下载后端文件，不在浏览器重新拼一套 CSV。

### 12.3 状态规则

- 页面刷新后，根据 URL 中 plan ID 或最新草稿恢复。
- 未导入时显示空状态，不自动注入 13 家 mock 门店。
- Demo 租户的示例数据也必须来自数据库 seed/API，而不是组件 fixture。
- API 失败显示错误并保留当前已知状态，不得自动回退 mock。
- 地图、卡片、确认和导出都使用后端返回的同一个 plan snapshot。

### 12.4 地图与坐标

首期继续使用脱敏静态苏州底图，不强制接真实地图 SDK：

- API 返回经纬度和可选 geometry。
- 前端使用集中 projection adapter 把经纬度映射到 `1917 × 1165` 坐标系。
- 超出配置地图边界显示 `OUT_OF_BOUNDS`，不伪造点位。
- Demo fixture 的 geometry 可迁移到数据库。
- 后续真实地图只需替换 map/distance provider，不改变计划 API 和业务表。

## 13. Demo 认证和租户隔离

当前 `web-admin/src/store/modules/auth.ts` 对物流使用：

```ts
localStorage.setItem('cretas_access_token', 'demo-logistics-local-token');
```

这必须删除。

目标：

1. 复用现有后端 demo-login 流程，让 `tenant=logistics` 获取真实 JWT/HttpOnly cookie。
2. 后端返回 `factoryId=DEMO_LOGISTICS`、`factoryType=LOGISTICS`、`businessDomain=LOGISTICS`、`role=dispatcher`。
3. JWT 必须被锁定到 `DEMO_LOGISTICS`，不能通过路径访问其他 factory。
4. Demo 用户只拥有物流所需模块和写权限；不能访问真实工厂、财务或其他租户。
5. Demo 数据可写，但需要可重复 seed/reset 策略，避免公共访客互相永久污染。

可采用：每个 demo session 克隆 snapshot，或定时重置专用租户。首期至少确保 tenant isolation 和可恢复 seed。

## 14. 明确非目标

本轮不做：

- 实时 GPS、车辆轨迹、偏航告警。
- 司机端 App 或手机端操作。
- 司机工资、公里计费、财务结算。
- 复杂冷链/常温分车；首期双温车按总容量处理。
- LLM 生成路线。
- 宣称成熟全局最优 VRP。
- 未确认的外部地图 SDK 或付费地图 Key。
- 将原始客户截图、会议音频、转录文件提交到 public GitHub。

## 15. 推荐实施顺序

### Phase 0：重新审计和规格冻结

- 从最新 `origin/main` 创建干净 worktree。
- 核对上述现有实体、权限、迁移规范和 demo-login。
- 把本 Handoff 转为正式 spec 和 bite-sized plan。
- 不重新设计 UI；前端交互以现有工作台为基线。

### Phase 1：数据库和后端领域骨架

- Migration、entities、repositories。
- Demo seed。
- 后端 repository/service tests。
- 交付标准：可写入并回读订单、车辆 profile、司机、计划、车次、停靠点。

### Phase 2：真实导入闭环

- 模板下载、preview、行级错误、commit、幂等。
- 前端导入页面接 API。
- 交付标准：上传客户格式文件后，门店列表和地图数据来自数据库；刷新仍存在。

### Phase 3：后端确定性排线

- 容量/载重、区域、车辆、司机、距离 provider。
- generate/regenerate 和计划快照。
- 交付标准：相同输入结果稳定，所有门店恰好一次，异常进入明确状态。

### Phase 4：人工调整和确认

- reorder/move/assign/confirm、乐观锁和审计。
- 前端从本地 mutation 改成 API mutation。
- 交付标准：调整后刷新仍保留；非法调整被后端拒绝。

### Phase 5：记录和导出

- 计划分页、详情、再次导出、后端 CSV/Excel。
- 交付标准：确认计划出现在调度记录；下载内容与详情逐字段一致。

### Phase 6：真实 Demo 身份和 E2E

- 真实物流 demo JWT、seed/reset。
- 生产级 E2E，但不自动部署生产。
- 交付标准：匿名入口完整走通闭环，无假 token、无 mock fallback。

## 16. 测试和验收证据

### 16.1 后端单元/集成测试

至少覆盖：

- 导入合法文件成功。
- 缺字段、非法数字、负数、重复行有精确错误。
- 重复 commit 幂等。
- 容量和载重硬约束。
- 一家门店不拆单。
- 每个订单恰好分配一次或明确 unassigned。
- 目标装载率改变能影响车次数量。
- 自有/外协车辆均可匹配。
- 一车多司机、班次和区域校验。
- 缺车辆、司机、距离边时不能确认。
- reorder/move 后公里数和顺序更新。
- 乐观锁冲突返回 409。
- 租户 A 不能访问租户 B 数据。
- 正式确认后不能任意修改。
- 导出顺序、里程、方数、重量与计划详情一致。

### 16.2 前端测试

- API contract tests。
- 上传 preview 和 commit 状态。
- 无 mock fallback。
- 调整失败显示后端 actionable error。
- 刷新后恢复同一计划。
- 记录页读取后端。
- 后端导出下载。

### 16.3 Playwright 深度 E2E

必须达到 L4 工作流，不只是页面文字检查：

1. 进入物流 Demo，确认后端签发真实身份。
2. 下载模板。
3. 上传包含至少 8 家门店的文件。
4. preview 显示有效/错误行。
5. commit 后订单列表和地图出现真实上传门店。
6. 刷新页面，门店仍存在。
7. 维护/读取车辆司机。
8. 生成至少 2 条多点线路。
9. 调整门店顺序，并把一家门店移动到另一条线路。
10. 刷新页面，调整仍存在。
11. 处理待匹配车辆/司机后逐车次确认。
12. 正式确认计划。
13. 在调度记录页读回同一计划。
14. 下载 CSV/Excel，解析文件并核对车辆、司机、门店顺序、方数、装载率和公里数。
15. 浏览器无 401/403/500、无错误 toast、无 console error。

### 16.4 证据要求

- 测试命令和数量。
- API 请求/响应或数据库读回。
- 刷新后的持久化证据。
- 跨模块回读证据。
- 导出文件解析结果。
- 关键截图路径。
- `git diff origin/main...HEAD --stat`。

不接受以下证明：

- 只跑 Vite build。
- 只验证按钮能点。
- 只看成功 toast。
- 前端 state 中出现数据但刷新丢失。
- 仍使用 `MOCK_STORES`/`MOCK_RECORDS` 完成所谓 E2E。

## 17. 工作区、Git 和部署纪律

1. 从最新 `origin/main` 创建独立 worktree；不要在共享主目录直接开发。
2. 推荐分支：`claude/logistics-mvp-closed-loop`。
3. 不得从现有 `codex/restaurant-unified-ai-entry` 开分支；该分支混有大量其他领域提交。
4. 每个 phase 独立、可测试、可审查地提交。
5. 提交前检查 staged scope，不使用无脑 `git add .`。
6. 后端 migration 必须遵守项目当前最高版本和 Flyway 规则；先查 `origin/main`。
7. 不提交任何真实账号、密码、地图 Key、客户截图、音频或转录。
8. 完成后 push feature branch 并开 PR；未经用户明确授权，不 merge、不部署生产。
9. 若获授权部署，必须先 merge 到 main，再从干净 main 部署 Java 和 web-admin，并做线上深度 E2E。

## 18. 重点风险

### P0

- 继续使用假物流 token，导致真实 API 全部 401/403。
- 前端上传成功但仍展示 fixture。
- 生产 `SchedulingPlan` 被错误复用，污染生产排程语义。
- 距离缺失时伪造公里数，影响客户后续计费依据。
- Demo JWT 可越权读取其他 factory。
- 导出与计划详情不是同一后端 snapshot。

### P1

- 车辆 `capacity` kg 与物流方数混用。
- 一车多司机被压回单一字符串字段。
- 调整只在浏览器生效。
- 正式确认没有后端状态机和乐观锁。
- 同一门店被重复分配或静默漏排。
- 公共 Demo 数据被访客永久污染。

### P2

- 200 点地图性能和标签碰撞。
- 后续地图 SDK adapter。
- OR-Tools 多目标优化。
- GPS、司机端和计费扩展。

## 19. Definition of Done

只有同时满足以下条件才可称为“物流 MVP 闭环完成”：

- [ ] 订单模板由后端生成。
- [ ] 文件 preview 有行级错误。
- [ ] commit 写数据库且幂等。
- [ ] 门店、订单、车辆、司机和绑定可后端回读。
- [ ] 计划、车次和停靠点由后端持久化。
- [ ] 后端生成确定性多点线路。
- [ ] 容量、载重、区域、班次和冲突由后端校验。
- [ ] 人工调序、跨线路移动、换车换司机写回后端。
- [ ] 刷新和重新登录后状态保持。
- [ ] 正式确认有后端状态机和并发保护。
- [ ] 调度记录能回读确认计划。
- [ ] CSV/Excel 由后端生成且与计划一致。
- [ ] 物流 Demo 使用真实、租户隔离的后端身份。
- [ ] 深度 Playwright 完整走通并解析下载文件。
- [ ] 没有 mock fallback、假成功或过度承诺。
- [ ] Scoped tests、Java build、web build、integration tests 全通过。

## 20. 接手者最终汇报格式

最终汇报必须包含：

1. 实际完成的闭环，而不是修改文件列表。
2. 数据模型和 migration。
3. 新增 API 及权限。
4. 前端哪些 mock 已被删除/隔离。
5. 算法规则和明确非目标。
6. 单测、集成测试、E2E 数量与结果。
7. 持久化、刷新回读、跨模块回读和导出解析证据。
8. 剩余风险。
9. commit、branch、PR URL。
10. 是否已 merge/deploy；未获授权不得模糊表述。
