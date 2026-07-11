package com.cretas.aims.logistics.dto.plan;

import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单个停靠点（车次内一个门店订单的排位）。
 *
 * <p>镜像前端 {@code web-admin/src/api/logistics.ts} {@code LogisticsStop}。
 * {@code order} 是可选的内嵌展开（复用 Phase 2 的 {@link DeliveryOrderDto}），
 * 避免前端为了拿门店名/地址再单独查一次订单。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StopDto {
    private String id;
    private String tripId;
    private String deliveryOrderId;
    private Integer sequenceNo;
    private BigDecimal legDistanceKm;
    private String arrivalWindowStart;
    private String arrivalWindowEnd;
    private DeliveryOrderDto order;
}
