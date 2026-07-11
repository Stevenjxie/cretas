package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.OwnershipType;
import lombok.Data;

import java.util.List;

/**
 * {@code POST /drivers} 及 {@code PUT /drivers/{driverId}} 请求体 —
 * 镜像前端 {@code DriverInput}（update 场景为 partial：null 字段保持原值）。
 */
@Data
public class DriverInputRequest {
    private String name;
    private String phone;
    private OwnershipType employmentType;
    private List<String> serviceAreas;
    private String availableFrom;
    private String availableTo;
    private Boolean active;
    /** 乐观锁：仅 update 场景使用，省略=不校验。 */
    private Long version;
}
