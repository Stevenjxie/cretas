# 一加物流排线 MVP 后端闭环 — Grounded Spec & Decisions (Phase 0)

> 日期：2026-07-11
> 分支：`claude/logistics-mvp-closed-loop`（off `origin/main` @ `c9954d194`）
> 批准基线：`docs/dispatch/2026-07-11-logistics-mvp-closed-loop-handoff.md`（视为已批准的产品/架构基线）
> 本文职责：把 Handoff 的推荐方案**落到已核实的代码事实上**，锁定 Handoff 留白的实现决策。Handoff 覆盖 what/why；本文覆盖 how（grounded）。

---

## 0. 范围与执行纪律

- 目标闭环：`模板下载 → 上传 → 后端预检 → 行级错误 → 确认提交写库 → 地图读真实点位 → 后端生成多点线路 → 车辆/司机匹配 → 人工调序/跨线路移动/换车换司机 → 后端重算容量与公里数 → 逐车次确认 → 正式确认 → 调度记录回读 → 后端 CSV/Excel 导出 → 刷新仍在`。
- 隔离：全部工作在 worktree `C:\Users\Steve\cretas-logistics-mvp`，分支 `claude/logistics-mvp-closed-loop`。**绝不**从 `codex/restaurant-unified-ai-entry` 开分支。
- TDD：每条业务规则先写失败测试再实现。分阶段独立 commit，scoped diff（`git commit -- <files>`，禁 `git add .`）。
- **停在 PR**：未经 Steve 明确授权不 merge、不部署生产。
- 只有三类真外部阻塞才停下问：地图供应商凭证 / 客户最终模板 / 生产授权。

---

## 1. 已核实的代码事实（file:line 证据）

| 事实 | 证据 | 对方案的影响 |
|---|---|---|
| `ApiResponse<T>` = `{success, message, data, code}`，有 `success()/success(data)` 静态工厂 | `backend/java/cretas-api/.../entity`(通用) `ApiResponse.java:22,28,31,37,56,60` | 所有 logistics controller 返回 `ApiResponse<T>` |
| `@RequireModule` 注解存在且已在 controller 使用 | `annotation/RequireModule.java`；`controller/APSAdaptiveController.java` 等 | logistics controller 用 `@RequireModule("scheduling")` |
| Flyway 迁移目录 `db/migration`，命名 `V<yyyyMMdd>_<NN>__desc.sql` | `backend/java/cretas-api/src/main/resources/db/migration/` | Phase 1 写迁移前先 `ls \| sort` 取真实最高版本号，避免撞号（本项目多次踩 Flyway 撞号挂 prod）|
| `Vehicle.java`：有 `plateNumber/driverName/driverPhone/capacity(BigDecimal)/currentLoad/status/vehicleType`，**无** volume/source/serviceArea/多司机/shift | `entity/Vehicle.java:29-70` | 不改 `Vehicle.capacity`（kg 载重语义）；新增 `logistics_vehicle_profiles` 1:1 关联，物流方数/来源/区域/班次全落 profile |
| `LineSchedule` 绑 `productionLineId/batchId/assigned_workers/workerAssignments` = 生产排程 | `entity/LineSchedule.java:31,34,51,108` | **不**复用生产 `SchedulingPlan/LineSchedule` 存物流线路；只借鉴其状态机/分页/权限/分层 pattern |
| Python `scheduling` = LinUCB 工人推荐 | `backend/python/scheduling/api/routes.py:3-5` | 首期排线**纯 Java 确定性 heuristic**，不调 Python、不用 LLM |
| `ImportService`/`ImportServiceImpl` 存在（dry-run/preview/commit/ImportJob） | `service/datacenter/ImportService.java` + `impl/ImportServiceImpl.java` | 借鉴两段式（preview→confirm→commit）pattern；若通用反射写实体不能安全表达物流聚合，建物流专用 import service |
| 物流 demo 假 token | `web-admin/src/store/modules/auth.ts:211` `'demo-logistics-local-token'` | P0：删除，接后端 demo-login 取真实 JWT，锁定 `DEMO_LOGISTICS` |
| 前端已有 4 模块 workbench + 确定性算法 + `needs_route_data` 诚实降级 | `web-admin/src/views/scheduling/logistics/{types,routeEngine,useLogisticsDemoState}.ts` | 算法 1:1 port 到 Java；API JSON 契约对齐 `types.ts` 的 camelCase 形状 |

---

## 2. 锁定的实现决策（Handoff 留白项）

1. **包/目录**：新增 `com.cretas.aims.logistics` bounded context（controller/dto/entity/repository/service{importjob,routing,export}/mapper）。遵循项目"entity 继承 `BaseEntity`"约定（`created_at/updated_at/deleted_at` + 触发器）。
2. **字段命名**（`field-naming-convention.md`）：Java entity camelCase、DB 列 snake_case、JSON camelCase、TS camelCase。JSON 契约字段名**对齐前端 `types.ts`** 现有形状（`storeIds/segmentKeys/segmentDistances/totalDistanceKm/totalVolumeCbm/loadRate/status`），减少前端改动面。
3. **状态枚举加 DB CHECK**：本项目多次踩"加 Java 枚举值但 PG CHECK 没放宽 → @Transactional 撞 CHECK 回滚 → 静默零变化"。所有 status 列的 CHECK 约束必须含全部枚举值；加值时 additive 迁移 `DROP … IF EXISTS; ADD … 含全值`。
4. **PG 类型推断**（`database-entity-sync.md`）：JPQL 里 parameter-side `(:param IS NULL OR …)` 且 param 可能传 null → 用 `CAST(:param AS string)`。H2 mock 漏报，PG 才炸。
5. **vehicle profile 1:1**：`logistics_vehicle_profiles.vehicle_id` 引用现有 `vehicles.id`；物流车方数/来源/区域/班次落 profile，不动 `Vehicle`。
6. **距离诚实**（Handoff §7.9）：`logistics_distance_edges`；缺边 → 计划 `NEEDS_ROUTE_DATA`，**禁止**直线/伪造 km。Demo 租户把 `roadSegments.ts` 脱敏数据迁成 `source=DEMO_FIXTURE`。
7. **幂等键**：`logistics_order_batches` = `factory_id + business_date + source_fingerprint` 唯一；重复上传返回既有批次不静默双写（`fool-proof-design.md` Rule 4）。
8. **乐观锁**：所有可变实体带 `version`（JPA `@Version`）；并发旧版本写 → 409 + 可操作提示，禁 last-write-wins。
9. **租户隔离**：写接口 factoryId 一律从**认证上下文**取，不信任 body（`concurrent-edit`/RLS 教训）。
10. **demo-login**：复用后端 demo-login（DEMO_REST 已有先例，见 `project_2026_07_09_mobile_rest_ai_qa_url`）签发 `factoryId=DEMO_LOGISTICS / factoryType=LOGISTICS / role=dispatcher` 的真实 JWT；Phase 6 定位精确端点。

---

## 3. 数据模型（9 表，语义见 Handoff §7；此处只记决策差异）

采用 Handoff §7 的 9 表：`logistics_order_batches / logistics_delivery_orders / logistics_vehicle_profiles / logistics_drivers / logistics_vehicle_drivers / logistics_plans / logistics_trips / logistics_stops / logistics_distance_edges`。

关键约束：
- 一家门店只属于一条有效计划车次（`logistics_stops` 唯一约束 + service 校验）。
- 单店超所有车硬容量 → `delivery_orders.status` 保留 + 进 unassigned，不静默拆单。
- 所有表继承 `BaseEntity` 审计字段 + `version`。
- 所有 status/enum 列 DB CHECK 含全值（见决策 3）。

---

## 4. 排线算法（Java port of `routeEngine.ts`，Handoff §9）

参考实现 `web-admin/src/views/scheduling/logistics/routeEngine.ts`：
- 按 `areaCodes` 把 store 分到 vehicle；无匹配车或单店超容量 → unassigned。
- 组内按 `targetLoadPct` 软目标 + 硬 `capacityCbm` 稳定装箱 → 多车次。
- `assembleTripGeometry`：`DEPOT->s1->s2…` 查 distance edges；任一段缺失 → `needs_route_data`，**不累加伪造 km**。
- tie-break 稳定排序（区域、门店编码、车辆 ID）→ 相同输入相同结果。

Java 侧硬约束（Handoff §9.2 全 7 条）+ 软目标顺序（固定区域/司机 → 接近目标装载 → 少车 → 少公里）。

---

## 5. API（Handoff §11，前缀 `/api/mobile/{factoryId}/logistics`）

全部端点见 Handoff §11.1-11.3。所有写接口：认证上下文取 factory / `@RequireModule("scheduling")` / `ApiResponse<T>` / 非法状态迁移 409 / 无假成功无 mock fallback。

---

## 6. 分阶段（Handoff §15；每阶段独立 PR-able commit 段 + 我 gate）

- **Phase 1**：migration + entities + repositories + demo seed + repository/service tests。交付：订单/车辆profile/司机/计划/车次/停靠点可写可回读。
- **Phase 2**：真实导入（模板/preview/行级错误/commit/幂等）+ 前端导入页接 API。交付：上传后门店来自 DB，刷新仍在。
- **Phase 3**：Java 确定性排线 + 车辆/司机匹配 + DistanceProvider。交付：相同输入稳定，全门店恰好一次或明确异常态。🔒 **我亲自 gate（算法正确性 + 最贵部分）**。
- **Phase 4**：人工调整（reorder/move/换车换司机）+ 乐观锁 + 正式确认状态机。🔒 **我亲自 gate（状态机 + 并发）**。
- **Phase 5**：调度记录 + 后端 CSV/Excel 导出。交付：导出逐字段等于计划详情。
- **Phase 6**：真实 demo JWT + seed/reset + L4 深度 E2E。🔒 **我亲自 gate（租户隔离）**。

执行层路由：机械量（entity/repository/DTO/前端接线/测试样板）派 **in-harness Sonnet subagent**；🔒 keystone（数据模型/算法/状态机/租户隔离）我自做或亲 gate。

---

## 7. 验收（Handoff §16 / §19 DoD）

不接受：只跑 build / 只按钮能点 / 只看成功 toast / 前端有 state 但刷新丢 / 仍用 MOCK_* 做 E2E / 浏览器拼 CSV / Python LinUCB 冒充路径算法。
必须：后端单测+集成测试、迁移验证、Java build、web Vitest、web build:check、Playwright L4 深度闭环（解析下载文件逐字段核对）、`git diff origin/main...HEAD --stat`、持久化刷新回读证据。
