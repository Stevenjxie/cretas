package com.cretas.aims.dto.production;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductionTransitClearingRequest {

    @NotBlank(message = "清账原因不能为空")
    @Size(max = 64, message = "清账原因不能超过64字符")
    private String clearingReason;

    @Size(max = 1000, message = "清账说明不能超过1000字符")
    private String clearingNote;
}
