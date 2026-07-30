package com.cretas.aims.logistics.dto.importjob;

import com.cretas.aims.logistics.entity.enums.DeliveryExecutionStatus;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 镜像前端 {@code web-admin/src/api/logistics.ts} {@code LogisticsDeliveryOrder}.
 * JSON 字段用 {@code windowStart}/{@code windowEnd}（不是 entity 的
 * {@code deliveryWindowStart}/{@code deliveryWindowEnd}）以对齐前端契约。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryOrderDto {
    private String id;
    private String factoryId;
    private String batchId;
    private String storeCode;
    private String storeName;
    private String address;
    private String areaCode;
    private Integer pieces;
    private Integer boxes;
    private BigDecimal weightKg;
    private BigDecimal volumeCbm;
    private String windowStart;
    private String windowEnd;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private LocationStatus locationStatus;
    private DeliveryOrderStatus status;
    // 执行态(送达跟踪)
    private DeliveryExecutionStatus deliveryStatus;
    private String deliveredAt;
    private String exceptionReason;
    private String exceptionDisposition;
    private String exceptionNote;
    private Integer sourceRowNumber;
    private Long version;
}
