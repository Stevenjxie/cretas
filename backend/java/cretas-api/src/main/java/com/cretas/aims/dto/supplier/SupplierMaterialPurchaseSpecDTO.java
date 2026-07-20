package com.cretas.aims.dto.supplier;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplierMaterialPurchaseSpecDTO {
    private String id;
    private String supplierMaterialId;
    private String materialTypeId;
    private String name;
    private String purchasePackageUnit;
    private String inventoryBaseUnit;
    private BigDecimal factor;
    @PriceSensitive private BigDecimal quotedPrice;
    private String currency;
    private BigDecimal minOrderQuantity;
    private Integer leadTimeDays;
    private Boolean defaultSpec;
    private Boolean active;
    private Long version;
}
