package com.cretas.aims.dto.supplier;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class SupplierMaterialPurchaseSpecRequest {
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Size(max = 20) private String purchasePackageUnit;
    @NotBlank @Size(max = 20) private String inventoryBaseUnit;
    @NotNull @DecimalMin(value = "0.000000000001") private BigDecimal factor;
    @Positive(message = "采购规格报价必须大于0") private BigDecimal quotedPrice;
    @Pattern(regexp = "^[A-Z]{3}$") private String currency;
    @Positive private BigDecimal minOrderQuantity;
    @PositiveOrZero private Integer leadTimeDays;
    private Boolean defaultSpec;
    private Boolean active;
    private Long version;
}
