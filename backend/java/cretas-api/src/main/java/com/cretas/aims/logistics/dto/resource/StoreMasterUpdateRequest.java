package com.cretas.aims.logistics.dto.resource;

import lombok.Data;

import java.math.BigDecimal;

/**
 * {@code PUT /logistics/stores/{id}} 请求体 — 调度员永久修正门店坐标/地址/区域
 * (客户"改一次以后就不用管了"的价值落点)。
 *
 * <p>Partial update (同 {@code DriverInputRequest}/{@code VehicleProfileUpdateRequest} 风格,
 * 非 {@code DailyAvailabilityUpsertRequest} 的完整替换语义) —— 每个字段 null 表示不修改。
 * 当 {@code longitude}/{@code latitude} 同时提供时, 服务层置
 * {@code source=MANUAL, locationStatus=RESOLVED}(见
 * {@code LogisticsResourceServiceImpl#updateStoreMaster})。乐观锁 {@code version}。
 */
@Data
public class StoreMasterUpdateRequest {
    private String address;
    private String areaCode;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Long version;
}
