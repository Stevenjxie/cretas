package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.OwnershipType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code PUT /vehicles/{vehicleId}/profile} 请求体 — 只含物流 profile 字段，
 * 不改 {@code Vehicle.capacity}(kg 载重语义)。所有字段 optional（partial update，
 * 未提供的字段保持原值/首建时用 entity 默认值）。
 */
@Data
public class VehicleProfileUpdateRequest {
    private BigDecimal capacityCbm;
    private BigDecimal maxWeightKg;
    private OwnershipType source;
    private String bodyType;
    private List<String> serviceAreas;
    private String availableFrom;
    private String availableTo;
    private Boolean active;
    /** 乐观锁：省略=不校验（首次创建场景）；提供且与当前 DB 版本不符 → 409。 */
    private Long version;
}
