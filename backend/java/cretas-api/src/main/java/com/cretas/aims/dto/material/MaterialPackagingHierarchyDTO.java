package com.cretas.aims.dto.material;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 原料包装换算 DTO.
 * 库存基本单位必填；动态包装规则优先，旧二/三级字段仅保留兼容。
 *
 * @since 2026-05-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialPackagingHierarchyDTO {
    private String id;
    private String factoryId;
    private String materialTypeId;

    @NotBlank(message = "一级单位不能为空")
    private String level1Unit;

    private BigDecimal level1PerLevel2;
    private String level2Unit;

    private BigDecimal level2PerLevel3;
    private String level3Unit;

    private String notes;

    /** 每条采购包装单位直接换算到库存基本单位。 */
    @Valid
    private List<MaterialPackagingSpecDTO> packagingSpecs;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
