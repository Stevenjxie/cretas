package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.AvailabilityResourceType;
import lombok.Data;

import java.time.LocalDate;

/**
 * {@code PUT /daily-availability} 请求体 — upsert（按 factoryId+resourceType+resourceId+
 * availDate 幂等，见 {@link com.cretas.aims.logistics.service.LogisticsResourceService}）。
 *
 * <p>⚠️ 与 {@code DriverInputRequest}/{@code VehicleProfileUpdateRequest} 不同：本请求体是
 * <b>完整状态替换</b>而非 partial patch —— 每次 PUT 都会用 {@code available}/{@code shiftStart}/
 * {@code shiftEnd} 整体覆盖该 (resourceType, resourceId, availDate) 键下的记录（幂等 PUT 语义，
 * 也让"清除班次覆盖只保留不可用标记"这类场景不需要额外的 patch 语法 — 直接传 null 即可清空）。
 */
@Data
public class DailyAvailabilityUpsertRequest {
    private AvailabilityResourceType resourceType;
    private String resourceId;
    private LocalDate availDate;
    /** null → 默认 true（新建场景等价 "只覆盖班次不标记不可用"）。 */
    private Boolean available;
    private String shiftStart;
    private String shiftEnd;
}
