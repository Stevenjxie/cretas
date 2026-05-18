package com.cretas.aims.repository.sales;

import com.cretas.aims.entity.sales.OperationalQuote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 销售运营报价单 Repository — V3 P0-4.
 * 全部 finder 都强制 factoryId 隔离 (避免 Round 1+2 同类漏洞).
 */
@Repository
public interface OperationalQuoteRepository extends JpaRepository<OperationalQuote, String> {

    /** Factory-scoped lookup */
    Optional<OperationalQuote> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** 按工厂 + 状态分页 */
    Page<OperationalQuote> findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String status, Pageable pageable);

    /** Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 9): paginated by customer, newest first, deleted excluded. */
    Page<OperationalQuote> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String customerId, Pageable pageable);

    /** 按工厂分页 (不限状态) */
    Page<OperationalQuote> findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Pageable pageable);

    /** 待我处理 — 销售运营待办列表 */
    Page<OperationalQuote> findByFactoryIdAndStatusAndQuotedByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String status, Long quotedByUserId, Pageable pageable);

    /** 销售下单时拉取有效报价 (APPROVED + 未过期 + 该客户该产品) */
    @Query("SELECT q FROM OperationalQuote q WHERE q.factoryId = :factoryId " +
           "AND q.customerId = :customerId AND q.productTypeId = :productTypeId " +
           "AND q.status = 'APPROVED' " +
           "AND (q.validUntil IS NULL OR q.validUntil >= :today) " +
           "AND q.deletedAt IS NULL " +
           "ORDER BY q.approvedAt DESC")
    List<OperationalQuote> findActiveForOrder(@Param("factoryId") String factoryId,
                                                @Param("customerId") String customerId,
                                                @Param("productTypeId") String productTypeId,
                                                @Param("today") LocalDate today);

    /** 检查报价单号是否已存在 */
    boolean existsByFactoryIdAndQuoteNo(String factoryId, String quoteNo);

    /** 当日报价单计数 (用于生成单号) */
    @Query("SELECT COUNT(q) FROM OperationalQuote q WHERE q.factoryId = :factoryId " +
           "AND q.quoteNo LIKE CONCAT('QT-', :datePrefix, '-%')")
    long countByFactoryIdAndDatePrefix(@Param("factoryId") String factoryId,
                                        @Param("datePrefix") String datePrefix);
}
