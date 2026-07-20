package com.cretas.aims.dto.supplier;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplierMaterialDTO {
    private String id;
    private String factoryId;
    private String supplierId;
    private String supplierName;
    private String materialTypeId;
    private String materialCode;
    private String materialName;
    private String baseUnit;
    private String supplierMaterialCode;
    @PriceSensitive private BigDecimal defaultPurchasePrice;
    private String currency;
    private String purchaseUnit;
    private BigDecimal minOrderQuantity;
    private Integer leadTimeDays;
    private Boolean preferred;
    private Boolean active;
    private Long version;
}
