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

    /**
     * 批量过账 (follow-up to #1228 finding): 逐张校验+过账 (与 {@link #post} 同一套规则:
     * DRAFT 状态 + 期间结账 gate), 每张独立事务 — 一张失败 (不平/期间已锁/凭证不存在) 不影响
     * 其余凭证的过账结果。幂等: 已 POSTED 的凭证跳过 (不视为失败)。
     *
     * @return 每个 voucherId 对应的过账结果 (成功/跳过/失败+原因), 与入参顺序一致
     */
    List<com.cretas.aims.dto.finance.VoucherBatchPostResultDTO> batchPost(
            String factoryId, List<String> voucherIds, Long userId);

    /** 作废: → VOID, 不可逆 (从代码层; DB 仍可改). factoryId 用于跨租户校验。 */
    void voidVoucher(String factoryId, String voucherId, String reason, Long userId);

    /** Idempotent 查询. */
    Optional<Voucher> findBySourceBusiness(String businessType, String businessId);

    /** 按状态查 — controller page 用. */
    List<Voucher> findByStatus(String factoryId, VoucherStatus status);

    /**
     * 直建已过账凭证 (手工指定分录)。用于系统结转损益/红冲等无业务单来源的凭证。
     * ⚠️ 蓄意绕过期间结账 gate (assertPeriodOpen) — 仅限系统结转 (锁定期间须能过结转凭证)。
     * 借贷必平 (validateBalanced); 直接 status=POSTED。
     */
    com.cretas.aims.entity.finance.Voucher createManual(
            String factoryId, com.cretas.aims.entity.enums.VoucherType type,
            java.time.LocalDate voucherDate, java.util.List<com.cretas.aims.dto.finance.VoucherEntrySpec> entries,
            String sourceBusinessType, String sourceBusinessId, String description, Long userId);

    /**
     * 资金段现金流水凭证 (finance audit Bug 5): 收款 (借 1002/贷 1122) / 付款 (借 2202/贷 1002)。
     *
     * <p>与 {@link #createManual} 的区别:
     * <ul>
     *   <li>status = <b>DRAFT</b> (业务凭证惯例, 财务手工过账; #1225 的 POSTED-only 金蝶导出闸兜住泄漏),
     *       createManual 是 POSTED (期末系统结转)。</li>
     *   <li>接收已构建好的 {@link com.cretas.aims.entity.finance.VoucherEntry} (含辅助核算),
     *       createManual 的 {@link com.cretas.aims.dto.finance.VoucherEntrySpec} 不带 aux。</li>
     *   <li><b>幂等</b>: 按 (sourceBusinessType, sourceBusinessId) 查已有凭证直接返回, 不重复生成
     *       (uk_voucher_source_business; 防监听器重投 / 重复确认)。</li>
     *   <li>走期间结账 gate (assertPeriodOpen) — 落在 CLOSED 期间抛异常, 由 AFTER_COMMIT 监听器 fail-soft 兜住。</li>
     * </ul>
     */
    com.cretas.aims.entity.finance.Voucher createCashMovementVoucher(
            String factoryId, com.cretas.aims.entity.enums.VoucherType type,
            java.time.LocalDate voucherDate,
            java.util.List<com.cretas.aims.entity.finance.VoucherEntry> entries,
            String sourceBusinessType, String sourceBusinessId, String description, Long userId);
}
