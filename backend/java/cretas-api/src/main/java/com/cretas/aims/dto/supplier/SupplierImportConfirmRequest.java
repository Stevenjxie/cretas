package com.cretas.aims.dto.supplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class SupplierImportConfirmRequest {
    @NotBlank(message = "fileDigest 不能为空")
    @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "fileDigest 必须是64位SHA-256十六进制摘要")
    private String fileDigest;

    @NotBlank(message = "idempotencyKey 不能为空")
    @Size(max = 100, message = "idempotencyKey 不能超过100个字符")
    private String idempotencyKey;

    @Valid
    @NotEmpty(message = "至少选择一行有效供应商")
    @Size(max = 1000, message = "单次最多导入1000行")
    private List<SupplierImportPreviewDTO.SupplierRowData> rows;
}
