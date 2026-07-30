package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

/**
 * {@code POST /plans/{planId}/trips/{tripId}/stops/move} 请求体 — 镜像前端
 * {@code MoveStopRequest}。路径里的 {@code tripId} 是**源车次**（当前持有该订单的车次，
 * 用于校验 + 乐观锁）；{@code targetTripId} 是目的车次。
 *
 * <p>{@code targetTripId=null} 时新建一个待匹配车辆的车次（对齐前端 api 客户端注释
 * "targetTripId=null 时新建待匹配车次"），{@code targetIndex} 此时无意义（新车次只有这一单）。
 */
@Data
public class MoveStopRequest {
    private String deliveryOrderId;
    private String targetTripId;
    private Integer targetIndex;
    private Long version;
}
