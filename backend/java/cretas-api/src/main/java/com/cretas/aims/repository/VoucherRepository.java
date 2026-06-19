package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    long countByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, VoucherStatus status);

    /** 凭证号生成: factory + year 最大序号. */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = ?1 AND v.voucherNumber LIKE CONCAT('V-', ?2, '-%') AND v.deletedAt IS NULL")
    long countByFactoryIdAndYear(String factoryId, String year);
}
