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
    private String quotedPriceUnit;
    /**
     * 该规格没有自己的报价时, 由**供应关系上配的采购价**推导出来的、按本规格包装单位计价的参考价。
     *
     * <p>客户 2026-07-30 表格第 38 行:「在成品SKU中设定完换算规格后, 生成采购订单的时候将不再
     * 自动填入供应商中设定好的采购单价。」—— 配了规格后计价单位变成「箱」, 而供应关系价是
     * 「元/kg」, 前端单位不等就把价清空了。
     *
     * <p>推导只用**本规格行自己声明的** {@code conversionFactor} 走一次换算, 不跨任何未声明的
     * 单位猜 (跨不过去就留 null, 与「不伪造 0」同一条口径)。有 {@code quotedPrice} 时不推导 ——
     * 免得界面上出现两个都自称是价的数。
     */
    @PriceSensitive private BigDecimal derivedPrice;
    private String derivedPriceUnit;
    /** {@code SUPPLIER_RELATION_SAME_UNIT} / {@code SUPPLIER_RELATION_CONVERTED}; 未推导时为 null。 */
    private String derivedPriceSource;
    private String currency;
    private BigDecimal minOrderQuantity;
    private Integer leadTimeDays;
    private Boolean defaultSpec;
    private Boolean active;
    private Long version;
}
