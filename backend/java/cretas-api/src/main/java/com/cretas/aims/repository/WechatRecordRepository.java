package com.cretas.aims.repository;

import com.cretas.aims.entity.WechatRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 微信记录 repository (Sprint 6 W2-C-1).
 *
 * 主查询: 按 (factoryId, customerId) 分页 + recordTime DESC.
 * 防呆 R4 dedup: 5min 内同 (factoryId, customerId, messageContent) 防重复.
 */
@Repository
public interface WechatRecordRepository extends JpaRepository<WechatRecord, Long> {

    /** 客户详情主查询 — factoryId + customerId 双重 scope, 防租户串数据. */
    Page<WechatRecord> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByRecordTimeDesc(
            String factoryId, String customerId, Pageable pageable);

    /** factoryId scope (列表页非 customer-scope 时). */
    List<WechatRecord> findByFactoryIdAndDeletedAtIsNullOrderByRecordTimeDesc(String factoryId);

    /** 单 customer count (header 显 "已有 N 条" 防呆 R2 context). */
    long countByFactoryIdAndCustomerIdAndDeletedAtIsNull(String factoryId, String customerId);

    /**
     * 防呆 R4 dedup: 5min 内同 (factoryId, customerId, messageContent) 重复创建检查.
     * 镜像 CustomerTrackingRecordRepository.findRecentByContent.
     */
    @Query("SELECT w FROM WechatRecord w " +
            "WHERE w.factoryId = :factoryId AND w.customerId = :customerId " +
            "AND w.messageContent = :messageContent AND w.recordTime > :since " +
            "AND w.deletedAt IS NULL")
    List<WechatRecord> findRecentByContent(
            @Param("factoryId") String factoryId,
            @Param("customerId") String customerId,
            @Param("messageContent") String messageContent,
            @Param("since") LocalDateTime since);

    /**
     * 日期范围查询 — 按 (factoryId, customerId, recordTime range) 分页.
     * 用于客户详情筛选 / 报表导出 / AI Tool 查询场景.
     */
    @Query("SELECT w FROM WechatRecord w " +
            "WHERE w.factoryId = :factoryId AND w.customerId = :customerId " +
            "AND w.recordTime >= :startTime AND w.recordTime <= :endTime " +
            "AND w.deletedAt IS NULL " +
            "ORDER BY w.recordTime DESC")
    List<WechatRecord> findByCustomerInTimeRange(
            @Param("factoryId") String factoryId,
            @Param("customerId") String customerId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);
}
