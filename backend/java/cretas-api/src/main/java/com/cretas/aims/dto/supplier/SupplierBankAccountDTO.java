package com.cretas.aims.dto.supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 供应商银行账户 —— 读写共用 DTO (id 为空即新建)。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SupplierBankAccountDTO {

    private String id;
    private String supplierId;

    @NotBlank(message = "户名不能为空")
    @Size(max = 200, message = "户名不能超过 200 字")
    private String accountName;

    @NotBlank(message = "开户行不能为空")
    @Size(max = 100, message = "开户行不能超过 100 字")
    private String bankName;

    @Size(max = 200, message = "支行/网点不能超过 200 字")
    private String branchName;

    /**
     * 账号。只允许数字 (国内对公/对私账号一律纯数字), 防呆挡住把"中国工商银行 6222…"
     * 整串粘进来 —— 这类错误出纳打款时才发现就晚了。
     */
    @NotBlank(message = "银行账号不能为空")
    @Pattern(regexp = "^[0-9]{8,32}$", message = "银行账号只能是 8-32 位数字，请勿包含空格、开户行名称或其他符号")
    private String accountNumber;

    /** 为空时后端按 CNY 处理。 */
    @Pattern(regexp = "^(CNY|USD|EUR|JPY|HKD)$", message = "币种只支持 CNY / USD / EUR / JPY / HKD")
    private String currency;

    private Boolean isPrimary;

    private Integer sortOrder;

    private String notes;

    private Long version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
