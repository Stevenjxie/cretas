package com.cretas.aims.dto.material;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 物料分类节点 DTO（单节点 + 可选子节点列表）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCodeSegmentDTO {

    private Long id;
    private String factoryId;
    private Short level;
    private String segmentLabel;
    private Long parentId;
    private Integer sortOrder;
    private Boolean isActive;

    /** 树形查询时填充的子节点列表 (非持久化). */
    private List<MaterialCodeSegmentDTO> children;
}
