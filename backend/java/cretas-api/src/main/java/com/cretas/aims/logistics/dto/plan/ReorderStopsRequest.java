package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

import java.util.List;

/**
 * {@code PUT /plans/{planId}/trips/{tripId}/stops/reorder} 请求体。
 *
 * <p>{@code storeIds} 是该车次**完整**的新顺序（每项为 {@code deliveryOrderId}，
 * 与 {@link TripDto#getStoreIds()} 的元素语义一致 — 不是门店编码 {@code storeCode}）。
 * 必须恰好是该车次现有停靠点集合的一个排列（不可增删门店，增删走 move）。
 */
@Data
public class ReorderStopsRequest {
    private List<String> storeIds;
    private Long version;
}
