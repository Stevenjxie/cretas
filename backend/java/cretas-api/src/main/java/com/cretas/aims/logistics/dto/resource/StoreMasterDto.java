package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.LocationStatus;
import com.cretas.aims.logistics.entity.enums.StoreMasterSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * {@code GET /logistics/stores} 列表项 / {@code PUT} 响应体 — 门店主数据
 * (见 {@link com.cretas.aims.logistics.entity.LogisticsStoreMaster})。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreMasterDto {
    private String id;
    private String storeName;
    private String address;
    private String areaCode;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private LocationStatus locationStatus;
    private StoreMasterSource source;
    private Long version;
}
