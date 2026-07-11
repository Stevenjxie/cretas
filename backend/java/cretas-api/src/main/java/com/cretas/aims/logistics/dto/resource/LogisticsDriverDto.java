package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.OwnershipType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 镜像前端 {@code web-admin/src/api/logistics.ts} {@code LogisticsDriver}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDriverDto {
    private String id;
    private String factoryId;
    private String name;
    private String phone;
    private OwnershipType employmentType;
    private List<String> serviceAreas;
    private String availableFrom;
    private String availableTo;
    private boolean active;
    private Long version;
}
