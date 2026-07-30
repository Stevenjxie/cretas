package com.cretas.aims.dto.supplier;

import com.cretas.aims.entity.enums.SupplierContactType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 供应商联系人 —— 读写共用 DTO (id 为空即新建)。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SupplierContactDTO {

    private String id;
    private String supplierId;

    @NotBlank(message = "联系人姓名不能为空")
    @Size(max = 100, message = "联系人姓名不能超过 100 字")
    private String name;

    /** 为空时后端按 OTHER 处理。 */
    private SupplierContactType contactType;

    /** 主联系人的电话会镜像回 suppliers.phone, 所以格式与主档同一套正则。 */
    @Pattern(regexp = com.cretas.aims.service.supplier.SupplierProfileValidator.PHONE_REGEXP,
             message = "联系电话格式不正确，请填写中国大陆手机号或带区号的固定电话（可含分机）")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 字")
    private String email;

    @Size(max = 100, message = "职务不能超过 100 字")
    private String position;

    private Boolean isPrimary;

    private Integer sortOrder;

    private String notes;

    private Long version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 中文名, 前端下拉直接显示, 免得再维护一份映射表。 */
    public String getContactTypeLabel() {
        return contactType == null ? SupplierContactType.OTHER.getDisplayName()
                : contactType.getDisplayName();
    }
}
