package com.cretas.aims.dto.producttype.importing;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SkuImportConfirmRequest {
    @NotBlank(message = "previewToken不能为空")
    private String previewToken;
}
