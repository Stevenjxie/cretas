package com.cretas.aims.logistics.service;

import com.cretas.aims.logistics.dto.plan.MoveStopRequest;
import com.cretas.aims.logistics.dto.plan.PlanDto;
import com.cretas.aims.logistics.dto.plan.PlanSnapshotDto;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 4 — 计划生命周期 + 人工调整 + 确认状态机 (handoff §10, §11.3; spec §6).
 *
 * <p>本接口下所有写方法（generate/regenerate/reorder/move/setTripVehicle/setTripDriver/
 * confirmTrip/confirmPlan）遵守两条铁律：
 * <ol>
 *   <li><b>乐观锁</b>：并发旧 version 写入 → 409（{@code VERSION_CONFLICT}），绝不
 *       last-write-wins（{@code fool-proof-design.md} Rule 4 / spec §2 决策 8）。</li>
 *   <li><b>确认终态</b>：计划一旦 {@code CONFIRMED}/{@code EXPORTED}，或车次一旦
 *       {@code CONFIRMED}，拒绝对其继续做任何调整（409）。</li>
 * </ol>
 *
 * <p>每次写操作都返回完整最新 {@link PlanSnapshotDto}（plan + trips + stops + 未分配门店 +
 * 新 version），前端不需要再拼装多接口视图（handoff §12.3）。
 */
public interface LogisticsPlanService {

    /**
     * POST /plans/generate — 委托 {@code LogisticsRoutingService.generatePlan}（幂等）。
     *
     * @param optimizeBy 排线优化模式 (TIME=时间最快 / DISTANCE=路程最短); null 按 DISTANCE。
     *                   存于计划上, regenerate 复用 (档1-B 2026-07-11)。
     */
    PlanSnapshotDto generatePlan(String factoryId, String batchId, BigDecimal targetLoadPct,
            RouteOptimizeMode optimizeBy);

    /** GET /plans — 工厂内计划列表，最新业务日期在前；{@code status} 可选过滤。 */
    Page<PlanDto> listPlans(String factoryId, PlanStatus status, Pageable pageable);

    /** GET /plans/{planId} — 完整计划快照。 */
    PlanSnapshotDto getPlan(String factoryId, String planId);

    /** POST /plans/{planId}/regenerate — 丢弃人工调整，重新跑排线算法。 */
    PlanSnapshotDto regeneratePlan(String factoryId, String planId);

    /**
     * PUT /plans/{planId}/trips/{tripId}/stops/reorder — {@code storeIds} 必须是该车次现有
     * 停靠点集合的一个完整排列（{@code deliveryOrderId} 列表，不是 storeCode）。
     */
    PlanSnapshotDto reorderStops(String factoryId, String planId, String tripId, List<String> storeIds, Long version);

    /**
     * POST /plans/{planId}/trips/{tripId}/stops/move — {@code tripId} 是源车次；
     * {@code request.targetTripId=null} 新建待匹配车次。重新校验目标车次容量/重量 +
     * 一订单一车次；源车次为空时软删除。
     */
    PlanSnapshotDto moveStop(String factoryId, String planId, String tripId, MoveStopRequest request);

    /** PUT /plans/{planId}/trips/{tripId}/vehicle — {@code vehicleId=null} 解除分配。 */
    PlanSnapshotDto setTripVehicle(String factoryId, String planId, String tripId, String vehicleId, Long version);

    /** PUT /plans/{planId}/trips/{tripId}/driver — {@code driverId=null} 解除分配。 */
    PlanSnapshotDto setTripDriver(String factoryId, String planId, String tripId, String driverId, Long version);

    /** POST /plans/{planId}/trips/{tripId}/confirm — 车次必须无 NEEDS_* 态才能确认。 */
    PlanSnapshotDto confirmTrip(String factoryId, String planId, String tripId, Long version, Long userId);

    /** POST /plans/{planId}/confirm — 全部车次 CONFIRMED 且零未分配门店才能确认。 */
    PlanSnapshotDto confirmPlan(String factoryId, String planId, Long version, Long userId);
}
