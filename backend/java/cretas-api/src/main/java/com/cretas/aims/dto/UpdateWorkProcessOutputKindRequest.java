package com.cretas.aims.dto;

import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 仅修改工序主产出类型，避免 Workflow 快捷修正提交整份过期工序数据。 */
@Data
public class UpdateWorkProcessOutputKindRequest {

    @NotNull(message = "默认产出类型不能为空")
    private WorkProcessOutputMaterialKind defaultOutputMaterialKind;
}
