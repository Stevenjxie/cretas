package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.AvailabilityResourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * {@code GET /daily-availability} 列表项 / {@code PUT} 响应体 — 镜像前端
 * {@code web-admin/src/api/logistics.ts} {@code DailyAvailability}。
 *
 * <p>只有存在覆盖的 (resourceType, resourceId, availDate) 组合才会出现在列表里；
 * 未出现的资源当天按其固定资料 (see {@code LogisticsDriverDto}/{@code LogisticsVehicleDto})
 * 默认可用 (诚实默认, 不列全量资源逐个标注)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyAvailabilityDto {
    private String id;
    private AvailabilityResourceType resourceType;
    private String resourceId;
    private LocalDate availDate;
    private boolean available;
    private String shiftStart;
    private String shiftEnd;
    private Long version;
}
