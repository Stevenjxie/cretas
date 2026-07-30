package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.supplier.SupplierAddressDTO;
import com.cretas.aims.dto.supplier.SupplierBankAccountDTO;
import com.cretas.aims.dto.supplier.SupplierContactDTO;
import com.cretas.aims.service.supplier.SupplierProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 供应商多联系人 / 多地址 / 多银行账户（客户反馈 F006 / LIUSHANMEN）。
 *
 * <p>写接口权限与 {@link SupplierController} 的写接口一致
 * （{@code procurement:read_write} / {@code finance:read_write}）—— 这些子档
 * 与供应商主档是同一份数据的不同视图, 权限口径不一致会出现「能改主档改不了联系人」。
 *
 * <p>所有方法都以 {@code factoryId} 作为租户隔离键, service 层再次校验
 * 子记录归属的 supplierId, 防止同工厂内跨供应商越权。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/suppliers/{supplierId}")
@RequiredArgsConstructor
@Tag(name = "供应商联系人/地址/银行账户", description = "供应商多联系人、多地址、多银行账户维护")
public class SupplierProfileController {

    private final SupplierProfileService supplierProfileService;

    // ──────────────────────────────── 联系人 ────────────────────────────────

    @GetMapping("/contacts")
    @Operation(summary = "查询供应商联系人列表", description = "按 主联系人优先 → 排序号 → 创建时间 返回")
    public ApiResponse<List<SupplierContactDTO>> listContacts(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId) {
        return ApiResponse.success(supplierProfileService.listContacts(factoryId, supplierId));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/contacts")
    @Operation(summary = "新增/更新供应商联系人",
               description = "请求体带 id 即更新, 不带即新增。主联系人会同步回写供应商主档的联系人/电话/邮箱")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "UPDATE",
            entityType = "SupplierContact", summary = "'维护供应商联系人 ' + #request.name")
    public ApiResponse<List<SupplierContactDTO>> saveContact(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @Valid @RequestBody SupplierContactDTO request) {
        log.info("维护供应商联系人: factoryId={}, supplierId={}, contactId={}",
                factoryId, supplierId, request.getId());
        return ApiResponse.success(
                supplierProfileService.saveContact(factoryId, supplierId, request));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @DeleteMapping("/contacts/{contactId}")
    @Operation(summary = "删除供应商联系人", description = "至少保留一条; 删除主联系人会自动顺位提升下一条")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "DELETE",
            entityType = "SupplierContact", summary = "'删除供应商联系人 ' + #contactId")
    public ApiResponse<List<SupplierContactDTO>> deleteContact(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @PathVariable @NotBlank String contactId) {
        return ApiResponse.success(
                supplierProfileService.deleteContact(factoryId, supplierId, contactId));
    }

    // ───────────────────────────────── 地址 ─────────────────────────────────

    @GetMapping("/addresses")
    @Operation(summary = "查询供应商地址列表")
    public ApiResponse<List<SupplierAddressDTO>> listAddresses(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId) {
        return ApiResponse.success(supplierProfileService.listAddresses(factoryId, supplierId));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/addresses")
    @Operation(summary = "新增/更新供应商地址", description = "主地址会同步回写供应商主档的地址字段")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "UPDATE",
            entityType = "SupplierAddress", summary = "'维护供应商地址'")
    public ApiResponse<List<SupplierAddressDTO>> saveAddress(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @Valid @RequestBody SupplierAddressDTO request) {
        log.info("维护供应商地址: factoryId={}, supplierId={}, addressId={}",
                factoryId, supplierId, request.getId());
        return ApiResponse.success(
                supplierProfileService.saveAddress(factoryId, supplierId, request));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @DeleteMapping("/addresses/{addressId}")
    @Operation(summary = "删除供应商地址", description = "至少保留一条; 删除主地址会自动顺位提升下一条")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "DELETE",
            entityType = "SupplierAddress", summary = "'删除供应商地址 ' + #addressId")
    public ApiResponse<List<SupplierAddressDTO>> deleteAddress(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @PathVariable @NotBlank String addressId) {
        return ApiResponse.success(
                supplierProfileService.deleteAddress(factoryId, supplierId, addressId));
    }

    // ─────────────────────────────── 银行账户 ───────────────────────────────

    @GetMapping("/bank-accounts")
    @Operation(summary = "查询供应商银行账户列表")
    public ApiResponse<List<SupplierBankAccountDTO>> listBankAccounts(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId) {
        return ApiResponse.success(supplierProfileService.listBankAccounts(factoryId, supplierId));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/bank-accounts")
    @Operation(summary = "新增/更新供应商银行账户",
               description = "主账户会同步回写供应商主档的开户行/账号 —— 出纳付款单以此为准")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "UPDATE",
            entityType = "SupplierBankAccount", summary = "'维护供应商银行账户 ' + #request.bankName")
    public ApiResponse<List<SupplierBankAccountDTO>> saveBankAccount(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @Valid @RequestBody SupplierBankAccountDTO request) {
        log.info("维护供应商银行账户: factoryId={}, supplierId={}, accountId={}",
                factoryId, supplierId, request.getId());
        return ApiResponse.success(
                supplierProfileService.saveBankAccount(factoryId, supplierId, request));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @DeleteMapping("/bank-accounts/{bankAccountId}")
    @Operation(summary = "删除供应商银行账户",
               description = "删除主账户会自动顺位提升下一条; 删完最后一条会清空主档镜像, 出纳将看不到默认收款账户")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "DELETE",
            entityType = "SupplierBankAccount", summary = "'删除供应商银行账户 ' + #bankAccountId")
    public ApiResponse<List<SupplierBankAccountDTO>> deleteBankAccount(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @PathVariable @NotBlank String bankAccountId) {
        return ApiResponse.success(
                supplierProfileService.deleteBankAccount(factoryId, supplierId, bankAccountId));
    }
}
