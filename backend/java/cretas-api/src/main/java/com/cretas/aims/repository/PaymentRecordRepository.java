package com.cretas.aims.repository;

import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, String> {

    Page<PaymentRecord> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    Page<PaymentRecord> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, PaymentRecordStatus status, Pageable pageable);

    /** Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 16): paginated by customer, newest first, deleted excluded. */
    Page<PaymentRecord> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String customerId, Pageable pageable);

    List<PaymentRecord> findBySalesOrderIdAndDeletedAtIsNull(String salesOrderId);

    /** Factory-scoped lookup for sales order tab. */
    List<PaymentRecord> findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String salesOrderId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecord p WHERE p.salesOrderId = ?1 AND p.status = 'VERIFIED' AND p.deletedAt IS NULL")
    BigDecimal sumVerifiedAmountBySalesOrderId(String salesOrderId);

    /**
     * 防呆 R4 (幂等防双击): 5min 窗口内同 工厂/销售单/金额 的待核 (PENDING) 收款 → 视为重复点击。
     * 镜像 TransferServiceImpl.findRecentDuplicates。金额是最高完整性面 (Rule 4 明列收款),
     * 双击会双计入 SO paidAmount。
     */
    @Query("SELECT p FROM PaymentRecord p WHERE p.factoryId = ?1 AND p.salesOrderId = ?2 "
            + "AND p.amount = ?3 AND p.status = com.cretas.aims.entity.enums.PaymentRecordStatus.PENDING "
            + "AND p.deletedAt IS NULL AND p.createdAt >= ?4 ORDER BY p.createdAt DESC")
    List<PaymentRecord> findRecentDuplicatePayments(String factoryId, String salesOrderId,
            BigDecimal amount, java.time.LocalDateTime cutoff);

    /**
     * 收款列表 动态筛选 (status / paymentMethod 均可选).
     *
     * payment_records 是不断增长的财务台账 (无上限), 沿用 SystemLogRepository.searchLogs
     * 同款 CAST(:param AS text) IS NULL 模式 — 单条原生 SQL 覆盖 "无筛选/单筛选/双筛选" 全组合,
     * 避免为每种筛选组合各写一个 derived-query 方法。ORDER BY 已硬编码在查询里 (对齐旧
     * PageRequest.of(page, size, Sort.by(DESC,"createdAt")) 行为), 调用方须传入 unsorted
     * Pageable, 否则 Spring Data 会尝试对原生查询追加第二个 ORDER BY 导致 SQL 语法错误。
     */
    @Query(value = "SELECT * FROM payment_records p WHERE p.factory_id = :factoryId AND p.deleted_at IS NULL " +
           "AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text)) " +
           "AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text)) " +
           "ORDER BY p.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM payment_records p WHERE p.factory_id = :factoryId AND p.deleted_at IS NULL " +
           "AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text)) " +
           "AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))",
           nativeQuery = true)
    Page<PaymentRecord> searchPayments(@Param("factoryId") String factoryId,
                                        @Param("status") String status,
                                        @Param("paymentMethod") String paymentMethod,
                                        Pageable pageable);
}
