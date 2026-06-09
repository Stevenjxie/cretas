package com.cretas.aims.dto.material;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SP8: 物料分段编码字典 DTO (单节点 + 可选子节点列表用于树形返回).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCodeSegmentDTO {

    private Long id;
    private String factoryId;
    private Short level;
    private String segmentCode;
    private String segmentLabel;
    private String parentCode;
    private Integer sortOrder;
    private Boolean isActive;

    /** 树形查询时填充的子节点列表 (非持久化). */
    private List<MaterialCodeSegmentDTO> children;
}
