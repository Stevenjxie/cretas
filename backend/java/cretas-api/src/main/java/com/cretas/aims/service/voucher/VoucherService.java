package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 凭证生成 + 生命周期管理. Event listeners 通过 createFromBusiness hook 进来.
 */
public interface VoucherService {

    /**
     * 由业务单生成凭证 (idempotent). 内部:
     * 1. 检查 (businessType, businessId) 是否已有凭证 → 直接返回 existing
     * 2. 加载业务 entity (via type dispatch)
     * 3. registry 找 generator → generate → assignVoucherNumber → save
     *
     * @return 持久化后的 Voucher (含 entries)
     */
    Voucher createFromBusiness(String factoryId, String businessType, String businessId);

    /** 折旧专用 — 输入 Map 而非业务 entity. */
    Voucher createDepreciation(String factoryId, Map<String, Object> input);

    /**
     * 批量补凭证: 扫描 factoryId 下所有 vflag=UNCREATED 的业务单, 逐个 createFromBusiness.
     * @return 生成的 voucher 数量
     */
    int batchCreateForFactory(String factoryId, String businessType);

    /** 过账: DRAFT → POSTED. factoryId 用于跨租户校验 (凭证须属于该工厂)。 */
    Voucher post(String factoryId, String voucherId, Long userId);

    /** 作废: → VOID, 不可逆 (从代码层; DB 仍可改). factoryId 用于跨租户校验。 */
    void voidVoucher(String factoryId, String voucherId, String reason, Long userId);

    /** Idempotent 查询. */
    Optional<Voucher> findBySourceBusiness(String businessType, String businessId);

    /** 按状态查 — controller page 用. */
    List<Voucher> findByStatus(String factoryId, VoucherStatus status);
}
