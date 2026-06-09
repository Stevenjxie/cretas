package com.cretas.aims.dto.material;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SP8: 创建/更新物料分段编码节点请求 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMaterialCodeSegmentRequest {

    @NotNull(message = "层级不能为空")
    @Min(value = 1, message = "层级最小为1")
    @Max(value = 3, message = "层级最大为3")
    private Short level;

    @NotBlank(message = "编码段不能为空")
    @Size(max = 10, message = "编码段最长10位")
    @Pattern(regexp = "^[0-9]+$", message = "编码段只能包含数字")
    private String segmentCode;

    @NotBlank(message = "编码标签不能为空")
    @Size(max = 100)
    private String segmentLabel;

    /** L1 时为 null; L2/L3 时必填上级 segmentCode. */
    @Size(max = 10)
    private String parentCode;

    private Integer sortOrder;

    private Boolean isActive;
}
