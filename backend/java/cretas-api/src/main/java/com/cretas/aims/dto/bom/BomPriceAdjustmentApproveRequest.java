package com.cretas.aims.dto.bom;

import lombok.Data;

@Data
public class BomPriceAdjustmentApproveRequest {
    private Long approverId;
    private String comment;
}
