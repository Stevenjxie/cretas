package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.SupplierQualification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository — Sprint 9 P2.C {@link SupplierQualification}.
 *
 * <p>BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 *
 * <p>核心查询模式:
 * <ul>
 *   <li>{@link #findByFactoryIdAndSupplierIdAndStatusOrderByExpiryDate} — 查指定供应商的有效资质</li>
 *   <li>{@link #findExpiringWithin} — 主动预警 scheduler 用 (查 30/7 天内将过期)</li>
 *   <li>{@link #findByFactoryIdAndSupplierIdAndStatusIn} — PO BLOCKING gate 用</li>
 * </ul>
 */
@Repository
public interface SupplierQualificationRepository extends JpaRepository<SupplierQualification, Long> {

    /** 查指定供应商指定状态的全部资质 (按过期日升序). */
    List<SupplierQualification> findByFactoryIdAndSupplierIdAndStatusOrderByExpiryDate(
            String factoryId, String supplierId, String status);

    /** 查指定供应商多状态的资质 (PO BLOCKING gate 用). */
    List<SupplierQualification> findByFactoryIdAndSupplierIdAndStatusIn(
            String factoryId, String supplierId, List<String> statuses);

    /** 全工厂查 expiry 在 [before, ...] 之前且 status 在 list 中的资质. */
    List<SupplierQualification> findByFactoryIdAndExpiryDateBeforeAndStatusIn(
            String factoryId, LocalDate before, List<String> statuses);

    /** 按 factory + status 计数 (Workdesk widget 用). */
    long countByFactoryIdAndStatus(String factoryId, String status);

    /** 查 factory 全工厂指定 status 在 expiry 日期窗口内的资质 (按过期日升序). */
    @Query("SELECT s FROM SupplierQualification s WHERE s.factoryId = :factoryId " +
            "AND s.status = 'VALID' AND s.expiryDate BETWEEN :startDate AND :endDate " +
            "ORDER BY s.expiryDate ASC")
    List<SupplierQualification> findExpiringWithin(@Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Scheduler 用: 跨 factory 查 status 在 list 中且已过 expiry 的资质. */
    @Query("SELECT s FROM SupplierQualification s WHERE s.status IN :statuses " +
            "AND s.expiryDate < :now ORDER BY s.expiryDate ASC")
    List<SupplierQualification> findAllExpiredByStatusIn(@Param("statuses") List<String> statuses,
            @Param("now") LocalDate now);

    /** Scheduler 用: 跨 factory 查 VALID 状态且 expiry 在 [now, threshold] 区间 (即将过期). */
    @Query("SELECT s FROM SupplierQualification s WHERE s.status = 'VALID' " +
            "AND s.expiryDate >= :startDate AND s.expiryDate <= :endDate " +
            "ORDER BY s.expiryDate ASC")
    List<SupplierQualification> findAllValidExpiringBetween(@Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 重复证书 dedup. */
    long countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
            String factoryId, String supplierId, String qualificationType, String certificateNumber);
}
