package com.cretas.aims.dto.material;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Explicit confirmation boundary for the mutating business-code backfill. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialBusinessCodeBackfillRequest {

    @NotBlank(message = "幂等键不能为空")
    @Size(max = 100, message = "幂等键长度不能超过100位")
    private String idempotencyKey;

    private Boolean confirm;

    @AssertTrue(message = "必须明确确认后才能回填历史业务编码")
    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirm);
    }
}
