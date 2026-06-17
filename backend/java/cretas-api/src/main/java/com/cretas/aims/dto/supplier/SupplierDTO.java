package com.cretas.aims.dto.supplier;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 供应商数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SupplierDTO {
    private String id;
    private String factoryId;
    private String supplierCode;
    private String name;
    private String contactPerson;
    private String phone;
    private String email;
    private String address;
    private String businessLicense;
    private String taxNumber;
    private String bankName;
    private String bankAccount;
    // 业务信息
    private String suppliedMaterials;
    private String paymentTerms;
    private Integer deliveryDays;
    private BigDecimal creditLimit;
    @PriceSensitive
    private BigDecimal currentBalance;
    // 评级信息
    private Integer rating;
    private String ratingNotes;
    private String qualityCertificates;
    // 状态信息
    private Boolean isActive;
    private String notes;
    // 统计信息
    private Integer totalOrders;
    @PriceSensitive
    private BigDecimal totalAmount;
    private LocalDateTime lastOrderDate;
    // 审计信息
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private String createdByName;

    /** Optimistic lock version — FE must send back on PUT to detect concurrent edits (409 Conflict on mismatch) */
    private Long version;

    // ===================================================================
    // 前端字段别名（兼容前端）
    // ===================================================================

    /**
     * code 别名（兼容前端）
     * 前端使用 code，后端使用 supplierCode
     */
    @JsonProperty("code")
    public String getCode() {
        return supplierCode;
    }
}
