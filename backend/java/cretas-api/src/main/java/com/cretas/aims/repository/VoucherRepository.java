package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, String> {

    Page<Voucher> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    Page<Voucher> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, VoucherStatus status, Pageable pageable);

    Page<Voucher> findByFactoryIdAndVoucherTypeAndDeletedAtIsNull(String factoryId, VoucherType type, Pageable pageable);

    /** 同时按 status + type 过滤 (修复 status/type 同传时 type 被静默忽略). */
    Page<Voucher> findByFactoryIdAndStatusAndVoucherTypeAndDeletedAtIsNull(
            String factoryId, VoucherStatus status, VoucherType type, Pageable pageable);

    /**
     * 凭证列表动态筛选 (status / voucherType / sourceBusinessType 均可选, 任意子集组合).
     * 凭证表是只增不减的财务台账 (unbounded), 与 SystemLogRepository.searchLogs 同一 CAST(:param AS text)
     * IS NULL 模式 — 未传的参数条件恒真, 避免为每种筛选组合各写一个 derived-query 方法
     * (原有 status/type 组合已经需要 4 个方法, 加一维 sourceBusinessType 会翻倍到 8 个)。
     */
    @Query(value = "SELECT * FROM vouchers v WHERE v.factory_id = :factoryId AND v.deleted_at IS NULL " +
           "AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text)) " +
           "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type = CAST(:voucherType AS text)) " +
           "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type = CAST(:sourceBusinessType AS text)) " +
           "ORDER BY v.voucher_date DESC, v.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM vouchers v WHERE v.factory_id = :factoryId AND v.deleted_at IS NULL " +
           "AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text)) " +
           "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type = CAST(:voucherType AS text)) " +
           "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type = CAST(:sourceBusinessType AS text))",
           nativeQuery = true)
    Page<Voucher> searchVouchers(@Param("factoryId") String factoryId,
                                  @Param("status") String status,
                                  @Param("voucherType") String voucherType,
                                  @Param("sourceBusinessType") String sourceBusinessType,
                                  Pageable pageable);

    /** Factory-scoped lookup — enforces tenant isolation. */
    Optional<Voucher> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /**
     * Idempotent lookup: 同一业务单 ({businessType, businessId}) 只能有一张凭证.
     * VoucherService.createFromBusiness 先调此方法; 非 null 直接返回 existing,
     * 防止 event 重发导致重复生成.
     */
    Optional<Voucher> findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
            String sourceBusinessType, String sourceBusinessId);

    /** factory + voucher_number 唯一. */
    Optional<Voucher> findByFactoryIdAndVoucherNumberAndDeletedAtIsNull(String factoryId, String voucherNumber);

    @Query("SELECT v FROM Voucher v WHERE v.factoryId = ?1 AND v.voucherDate BETWEEN ?2 AND ?3 AND v.deletedAt IS NULL")
    List<Voucher> findByFactoryIdAndDateRange(String factoryId, LocalDate from, LocalDate to);

    /**
     * F006 财务审计 Bug 6 (2026-07-04): 统计期间内待过账 (DRAFT) 凭证数 — 官方报表切到
     * POSTED-only 口径后, 用于给财务人员一个"为什么报表数字看着少"的可见提示
     * (pendingDraftVoucherCount), 而不是让报表悄悄变空/变小却不说明原因。
     */
    long countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
            String factoryId, VoucherStatus status, LocalDate from, LocalDate to);

    long countByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, VoucherStatus status);

    /** 凭证号生成: factory + year 最大序号. */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = ?1 AND v.voucherNumber LIKE CONCAT('V-', ?2, '-%') AND v.deletedAt IS NULL")
    long countByFactoryIdAndYear(String factoryId, String year);

    /** 结转损益: 某期 active 结转凭证 (POSTED + 非红冲镜像), 反结账时红冲它们。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT v FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'PL_CLOSING' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    List<Voucher> findActiveClosingVouchers(String factoryId, String sourceIdPrefix);

    /** 结转损益: 某期历史结转批次数 (含 REVERSED, 排红冲镜像) — 算 revision。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'PL_CLOSING' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    long countClosingBatches(String factoryId, String sourceIdPrefix);

    /** 结转成本: 某期 active 结转成本凭证 (POSTED + 非红冲镜像), 反结账时红冲它们。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT v FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'COST_CARRYOVER' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    List<Voucher> findActiveCostCarryoverVouchers(String factoryId, String sourceIdPrefix);

    /** 结转成本: 某期历史结转批次数 (含 REVERSED, 排红冲镜像) — 算 revision。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'COST_CARRYOVER' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    long countCostCarryoverBatches(String factoryId, String sourceIdPrefix);
}
