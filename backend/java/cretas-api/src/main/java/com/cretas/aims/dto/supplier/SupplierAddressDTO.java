package com.cretas.aims.dto.supplier;

import com.cretas.aims.entity.enums.SupplierAddressType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 供应商地址 —— 读写共用 DTO (id 为空即新建)。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SupplierAddressDTO {

    private String id;
    private String supplierId;

    @Size(max = 60, message = "地址标签不能超过 60 字")
    private String label;

    /** 为空时后端按 BUSINESS 处理。 */
    private SupplierAddressType addressType;

    @NotBlank(message = "地址不能为空")
    @Size(max = 500, message = "地址不能超过 500 字")
    @Pattern(regexp = com.cretas.aims.service.supplier.SupplierProfileValidator.READABLE_ADDRESS_REGEXP,
             message = "地址必须包含可识别的文字或数字，不能仅填写符号")
    private String address;

    @Size(max = 100, message = "收货联系人不能超过 100 字")
    private String contactName;

    @Pattern(regexp = com.cretas.aims.service.supplier.SupplierProfileValidator.PHONE_REGEXP,
             message = "联系电话格式不正确，请填写中国大陆手机号或带区号的固定电话（可含分机）")
    private String contactPhone;

    private Boolean isPrimary;

    private Integer sortOrder;

    private String notes;

    private Long version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getAddressTypeLabel() {
        return addressType == null ? SupplierAddressType.BUSINESS.getDisplayName()
                : addressType.getDisplayName();
    }
}
