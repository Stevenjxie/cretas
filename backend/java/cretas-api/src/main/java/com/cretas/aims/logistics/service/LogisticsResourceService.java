package com.cretas.aims.logistics.service;

import com.cretas.aims.logistics.dto.resource.DailyAvailabilityDto;
import com.cretas.aims.logistics.dto.resource.DailyAvailabilityUpsertRequest;
import com.cretas.aims.logistics.dto.resource.DriverInputRequest;
import com.cretas.aims.logistics.dto.resource.LogisticsDriverDto;
import com.cretas.aims.logistics.dto.resource.LogisticsVehicleDto;
import com.cretas.aims.logistics.dto.resource.StoreMasterDto;
import com.cretas.aims.logistics.dto.resource.StoreMasterUpdateRequest;
import com.cretas.aims.logistics.dto.resource.VehicleDriverBindingDto;
import com.cretas.aims.logistics.dto.resource.VehicleProfileUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * 物流资源管理服务 (handoff §11.2) — 车辆物流 profile / 独立司机资料 / 车辆-司机绑定。
 *
 * <p>不改现有 {@link com.cretas.aims.entity.Vehicle}(kg 载重语义)，物流方数/来源/区域/班次
 * 落 {@link com.cretas.aims.logistics.entity.LogisticsVehicleProfile} 1:1 关联表 (spec §2 决策 5)。
 */
public interface LogisticsResourceService {

    /** GET /vehicles — 车辆基础字段 + 物流 profile 合并读模型 + 司机绑定。 */
    List<LogisticsVehicleDto> listVehicles(String factoryId);

    /** PUT /vehicles/{vehicleId}/profile — upsert 物流 profile（partial update + 乐观锁）。 */
    LogisticsVehicleDto updateVehicleProfile(String factoryId, String vehicleId, VehicleProfileUpdateRequest request);

    /** GET /drivers — 含 inactive（管理员维护列表需要看到全部）。 */
    List<LogisticsDriverDto> listDrivers(String factoryId);

    /** POST /drivers。 */
    LogisticsDriverDto createDriver(String factoryId, DriverInputRequest request);

    /** PUT /drivers/{driverId} — partial update + 乐观锁。 */
    LogisticsDriverDto updateDriver(String factoryId, String driverId, DriverInputRequest request);

    /** PUT /vehicles/{vehicleId}/drivers — 整体替换绑定集合（支持一车 2-3 个司机）。 */
    LogisticsVehicleDto setVehicleDrivers(String factoryId, String vehicleId, List<VehicleDriverBindingDto> bindings);

    /**
     * GET /daily-availability?date=YYYY-MM-DD — 只返回该日期存在覆盖的记录；未出现的司机/车辆
     * 当天按其固定资料默认可用（诚实默认，见 {@link com.cretas.aims.logistics.entity.LogisticsDailyAvailability}）。
     */
    List<DailyAvailabilityDto> listDailyAvailability(String factoryId, LocalDate date);

    /**
     * PUT /daily-availability — upsert（按 resourceType+resourceId+availDate 幂等，完整状态替换
     * 语义见 {@link DailyAvailabilityUpsertRequest}）。
     */
    DailyAvailabilityDto upsertDailyAvailability(String factoryId, DailyAvailabilityUpsertRequest request);

    /** DELETE /daily-availability/{id} — 软删除，恢复该资源当天默认可用。 */
    void deleteDailyAvailability(String factoryId, String id);

    // ==================== 门店主数据 (store master) ====================

    /**
     * GET /logistics/stores?page=&size=&keyword= — 门店主数据管理列表（门店名称升序分页，
     * {@code keyword} 非空时按门店名称模糊搜索）。{@link com.cretas.aims.logistics.entity.LogisticsStoreMaster}
     * 是坐标"解析一次, 逐日复用"的落点 —— 见类注释。
     */
    Page<StoreMasterDto> listStoreMasters(String factoryId, String keyword, Pageable pageable);

    /**
     * PUT /logistics/stores/{id} — 调度员永久修正门店坐标/地址/区域（partial update + 乐观锁）。
     * 坐标同时提供时置 {@code source=MANUAL, locationStatus=RESOLVED}（人工修正是最终事实来源，
     * 后续导入不再覆盖）。
     */
    StoreMasterDto updateStoreMaster(String factoryId, String id, StoreMasterUpdateRequest request);

    /** DELETE /logistics/stores/{id} — 软删除。 */
    void deleteStoreMaster(String factoryId, String id);
}
