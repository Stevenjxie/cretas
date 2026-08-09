package com.cretas.aims.dto.material;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建/更新物料分类节点。节点 ID 由数据库生成，用户不维护编码。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMaterialCodeSegmentRequest {

    @Min(value = 1, message = "层级最小为1")
    @Max(value = 3, message = "层级最大为3")
    private Short level;

    @Size(max = 100)
    private String segmentLabel;

    /** 一级分类为 null；二/三级分类必须传直属上级节点 ID。 */
    private Long parentId;

    private Integer sortOrder;

    private Boolean isActive;
}
