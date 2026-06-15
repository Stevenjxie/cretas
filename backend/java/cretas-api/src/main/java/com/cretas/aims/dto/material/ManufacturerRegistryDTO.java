package com.cretas.aims.dto.material;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManufacturerRegistryDTO {
    private String id;
    private String factoryId;
    private String code;
    private String name;
    private String originPlace;
    private Boolean isActive;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
