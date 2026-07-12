# Phase 3 排线算法精确规格 (确定性 heuristic — 算法精确性 gate 参考)

> 依据：Handoff §9 + 前端参考实现 `web-admin/src/views/scheduling/logistics/routeEngine.ts`。
> 用途：Java `logistics.service.routing` 实现的**逐条精确契约**；也是我（Opus）gate 算法精确性的对照表。
> 铁律：**相同输入 → 完全相同输出**（确定性）；缺距离边 → `NEEDS_ROUTE_DATA`，**绝不**伪造/直线降级公里数。

---

## 1. 输入

- `orders`: 已 COMMITTED 批次的 `LogisticsDeliveryOrder`（storeCode/areaCode/volumeCbm/weightKg + longitude/latitude/locationStatus）。
- `vehicleProfiles`: `LogisticsVehicleProfile`（capacityCbm/maxWeightKg/serviceAreas/source/availableFrom/availableTo/active）。
- `drivers` + `vehicleDrivers`: 绑定（PRIMARY/BACKUP、shift、priority、区域）。
- `targetLoadPct` ∈ (0,100]（UI 限 50–100）。
- `distanceEdges`: `LogisticsDistanceEdge`（from_point_id/to_point_id/distance_km/source）；`DEPOT` 为起点哨兵。

## 2. 硬约束（全 7 条，违反即不可正式确认）

1. 单车次总体积 ≤ 该车 `capacityCbm`。
2. 单车次总重量 ≤ 该车 `maxWeightKg`。
3. 一家门店不拆到多车次（一 order 恰属一个有效 stop）。
4. 每个 order 只属于一条有效车次（DB `uq_ls_order_single_trip` + service 双重保障）。
5. 同一车辆不同时分配到重叠车次（一个 plan 内一车最多一活跃车次；跨车次复用需班次不重叠）。
6. 同一司机不同时分配到重叠车次（按 shift 窗判重叠）。
7. 车辆/司机必须覆盖门店区域和所需班次。

## 3. 软目标（顺序即优先级；首期不做复杂优化器）

1. 优先固定区域 + 固定（主）司机。
2. 硬容量内尽量接近 `targetLoadPct`。
3. 尽量少用车。
4. 候选中减少总公里数（用已维护距离边，不猜）。

## 4. 精确步骤（Java port，带 routeEngine.ts 行号对照 + 必须的增强）

### Step A — 门店归组到车辆（对照 routeEngine.ts:92-101）
- 对每个 order（按 **stable 排序：areaCode ASC, storeCode ASC**）：
  - 找 `serviceAreas` 含 `order.areaCode` 的**第一个** active vehicleProfile（vehicle 候选按 **vehicleId ASC** stable 排序后取首个匹配）。
  - 若无匹配车 **或** `order.volumeCbm > vehicle.capacityCbm` **或** `order.weightKg > vehicle.maxWeightKg` → 加入 `unassigned`（Step G）。
  - 否则并入该 vehicle 的组。
- ⚠️ 增强 vs 前端：前端只判 volume+area；Java **必须**同时判 `weightKg > maxWeightKg`（硬约束 2）。

### Step B — 组内稳定装箱（对照 routeEngine.ts:108-126）
- `targetCap = capacityCbm * targetLoadPct / 100`。
- 组内 order 保持 Step A 的 stable 顺序，顺序累加：
  - 若 `current 非空 且 (cum + vol) > capacityCbm` → 封箱开新箱（硬容量优先）。
  - 否则若 `current 非空 且 (cum + vol) > targetCap` → 封箱开新箱（软目标）。
  - 加入 current；累加 volume + weight。
  - ⚠️ 增强：装箱时同时累加 `weightKg`，若 `cum_weight + weight > maxWeightKg` 也触发封箱（硬约束 2；前端无此判）。
- 末箱非空则收尾。

### Step C — 每车次分配车辆（对照 routeEngine.ts:128-134）
- 第 1 箱用该组 primaryVehicle。
- 后续箱：从**未使用**车辆中，按 vehicleId ASC 找第一个满足 `matchingVehicle`（区域覆盖 + 容量 ≥ 箱体积 + maxWeight ≥ 箱重量 + 班次可用）的车；找到则标记已用。
- 无可用车 → 该车次 `vehicleId=null`，状态 `NEEDS_VEHICLE`（Step F）。

### Step D — 每车次分配司机（增强，前端仅带 primary driver）
- 车辆已定后：取该 vehicle 的 vehicleDrivers，按 `role=PRIMARY 优先, 再 priority ASC` 排序。
- 选第一个：班次覆盖车次所需时间窗 + 区域覆盖 + 未在本 plan 其它重叠车次占用的司机。
- 无主司机可用则退 BACKUP（同规则）。都无 → `driverId=null`，状态 `NEEDS_DRIVER`。

### Step E — 组装几何 + 公里数（对照 routeEngine.ts:28-45, 47-76）
- 车次 storeIds = 箱内 order 的顺序（首期即装箱顺序；Step H 可优化）。
- 边序列：`DEPOT->s1, s1->s2, …, s_{n-1}->s_n`，查 `distanceEdges`（factory+from+to）。
- **任一边缺失** → 该车次 `segmentDistances=[]`, `totalDistanceKm=0`, 状态 `NEEDS_ROUTE_DATA`，**不累加任何伪造值**。
- 全部命中 → `segmentDistances = 各边 distance_km`；`totalDistanceKm = Σ`（保留 DB scale，`setScale(2, HALF_UP)`，对齐 `python-java-port` Rule 10/12 的中间步舍入，避免 BigDecimal 尾差）。

### Step F/G — 异常态
- `NEEDS_VEHICLE` / `NEEDS_DRIVER` / `NEEDS_ROUTE_DATA`：车次保留、明确标态，阻止 plan 正式确认（硬约束 8）。
- 单店超所有车硬容量 → `unassigned`，`delivery_order.status` 不变、不静默拆单。

### Step H — 顺序优化（首期最小）
- 首期 stop 顺序 = 装箱顺序（稳定）。可选：用已维护距离边做 nearest-neighbor（对照 handoff §9.4.5）；**仅当**边齐全，且 tie-break 稳定（下一跳按 distance ASC, storeCode ASC）。缺边不做 NN，直接 `NEEDS_ROUTE_DATA`。

## 5. 汇总字段（对照 routeEngine.ts:56-74）

每车次：`totalVolumeCbm=Σvol`、`totalWeightKg=Σweight`、`loadRate = capacityCbm>0 ? totalVolumeCbm/capacityCbm : 0`（NUMERIC(6,4)）、`weightLoadRate = maxWeightKg>0 ? totalWeightKg/maxWeightKg : 0`。
计划：`totalStores=Σstops`、`totalTrips=车次数`、`totalDistanceKm=Σ车次距离`（缺边车次记 0，且 plan 落 `NEEDS_ACTION`）。

## 6. 确定性 & tie-break（算法精确性核心）

所有排序显式且稳定：
- order 处理序：`areaCode ASC, storeCode ASC`。
- vehicle 候选序：`vehicleId ASC`。
- driver 候选序：`role(PRIMARY先), priority ASC, driverId ASC`。
- NN 下一跳：`distance ASC, storeCode ASC`。

**同一输入两次运行必须逐字段 bit-一致**（含 trip_no、stop sequence_no、公里数 scale）。这是 gate 断言点。

## 7. 算法精确性 gate（我 personally 验）

1. **对照测试**：构造与 `routeEngine.ts` 现有 Vitest 用例等价的 Java 用例，输入相同 → Java 装箱/分组/unassigned/needs_route_data 结果与 TS 参考一致（差异只允许在 Java 新增的 weight/driver 硬约束上，且方向正确）。
2. **确定性测试**：同输入跑 2 次，`assertEquals` 完整 plan snapshot（含顺序、trip_no、距离 scale）。
3. **硬约束穷举**：每条硬约束一个失败用例（超体积/超重量/拆单/车冲突/司机冲突/区域不覆盖/缺边）→ 明确异常态，非静默。
4. **targetLoad 差异**：70/88/98 三档产生**可验证不同**的车次数（handoff §16 要求）。
5. **诚实降级**：缺一条边 → 该车次 `NEEDS_ROUTE_DATA` 且 `totalDistanceKm=0`，plan `NEEDS_ACTION`；**断言没有任何非零伪造公里数**。
