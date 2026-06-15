package com.cretas.aims.dto.material;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateManufacturerRequest {

    @Size(max = 64, message = "厂号编码长度不能超过64个字符")
    private String code;

    @Size(max = 200, message = "厂商名称长度不能超过200个字符")
    private String name;

    @Size(max = 200, message = "产地长度不能超过200个字符")
    private String originPlace;

    private Boolean isActive;

    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;
}
