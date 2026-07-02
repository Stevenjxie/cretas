package com.cretas.aims.dto.supplier;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 创建供应商请求
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Schema(description = "创建供应商请求")
public class CreateSupplierRequest {

    @Schema(description = "供应商名称", required = true)
    @NotBlank(message = "供应商名称不能为空")
    @Size(max = 200, message = "供应商名称长度不能超过200个字符")
    private String name;

    // 客户张权原话 (2026-07-02): "这个不要强制 一般不会放系统里面的 都是一脉单传的"
    // 联系人/联系电话/地址 改为选填 (去掉 @NotBlank), 手机号格式仍校验但允许空字符串
    // (pattern 加 ^$| 分支, 同 UpdateSupplierRequest.phone 既有 pattern). Entity 层
    // contact_person/phone/address 列本就 nullable (无 nullable=false), 不需要迁移.
    @Schema(description = "联系人")
    @Size(max = 100, message = "联系人长度不能超过100个字符")
    private String contactPerson;

    @Schema(description = "联系电话")
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100个字符")
    private String email;

    @Schema(description = "地址")
    @Size(max = 500, message = "地址长度不能超过500个字符")
    private String address;

    @Schema(description = "营业执照号")
    @Size(max = 100, message = "营业执照号长度不能超过100个字符")
    private String businessLicense;

    @Schema(description = "税号")
    @Size(max = 50, message = "税号长度不能超过50个字符")
    private String taxNumber;

    @Schema(description = "开户银行")
    @Size(max = 200, message = "开户银行长度不能超过200个字符")
    private String bankName;

    @Schema(description = "银行账号")
    @Size(max = 100, message = "银行账号长度不能超过100个字符")
    private String bankAccount;

    @Schema(description = "供应材料类型")
    @Size(max = 500, message = "供应材料类型长度不能超过500个字符")
    private String suppliedMaterials;

    @Schema(description = "付款条款")
    @Size(max = 200, message = "付款条款长度不能超过200个字符")
    private String paymentTerms;

    @Schema(description = "交货天数")
    @PositiveOrZero(message = "交货天数必须大于等于0")
    private Integer deliveryDays;

    @Schema(description = "信用额度")
    @PositiveOrZero(message = "信用额度必须大于等于0")
    @DecimalMax(value = "9999999999.99", message = "信用额度不能超过 9,999,999,999.99 (DB 精度限制)")
    private BigDecimal creditLimit;

    @Schema(description = "供应商评级 (1-5)")
    private Integer rating;

    @Schema(description = "评级说明")
    @Size(max = 5000, message = "评级说明长度不能超过5000个字符")
    private String ratingNotes;

    @Schema(description = "质量认证证书")
    @Size(max = 1000, message = "质量认证证书长度不能超过1000个字符")
    private String qualityCertificates;

    @Schema(description = "备注")
    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String notes;

    /**
     * Optimistic lock version — echoed from GET response.
     * On UPDATE: if server detects version mismatch → 409 Conflict.
     * On CREATE: ignored (entity starts at 0).
     */
    @Schema(description = "乐观锁版本号 (编辑时必传, 来自 GET 响应)")
    private Long version;
}