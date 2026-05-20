package com.cretas.aims.repository;

import com.cretas.aims.entity.CallRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通话记录 repository (Sprint 6 W2-C-2).
 *
 * <p>主查询: 按 (factoryId, customerId) 分页 + callTime DESC.
 *
 * <p>防呆 R4 dedup: 5min 内同 (factoryId, customerId, callTime ±5min) 防重复.
 */
@Repository
public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {

    /** 客户详情主查询 — factoryId + customerId 双重 scope. */
    Page<CallRecord> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCallTimeDesc(
            String factoryId, String customerId, Pageable pageable);

    /** factoryId scope (列表页非 customer-scope 时). */
    List<CallRecord> findByFactoryIdAndDeletedAtIsNullOrderByCallTimeDesc(String factoryId);

    /** 单 customer count (header 显 "已有 N 条" 防呆 R2 context). */
    long countByFactoryIdAndCustomerIdAndDeletedAtIsNull(String factoryId, String customerId);

    /**
     * 防呆 R4 dedup: 5min 内同 (factoryId, customerId, callTime within window).
     * 镜像 WechatRecordRepository.findRecentByContent — 这里 dedup 维度是 callTime 而非 messageContent
     * (因为 audio 重复上传可能 size 一致但是不同尝试, 用 callTime 范围更合理).
     */
    @Query("SELECT c FROM CallRecord c " +
            "WHERE c.factoryId = :factoryId AND c.customerId = :customerId " +
            "AND c.callTime BETWEEN :timeFrom AND :timeTo " +
            "AND c.deletedAt IS NULL")
    List<CallRecord> findRecentByTime(
            @Param("factoryId") String factoryId,
            @Param("customerId") String customerId,
            @Param("timeFrom") LocalDateTime timeFrom,
            @Param("timeTo") LocalDateTime timeTo);

    /** ByOss URL — pending transcripts polling / Whisper retry hook. */
    Optional<CallRecord> findByFactoryIdAndIdAndDeletedAtIsNull(String factoryId, Long id);

    /**
     * 日期范围查询 — 按 (factoryId, customerId, callTime range) 分页.
     * 用于报表 / AI Tool 查询场景.
     */
    @Query("SELECT c FROM CallRecord c " +
            "WHERE c.factoryId = :factoryId AND c.customerId = :customerId " +
            "AND c.callTime >= :startTime AND c.callTime <= :endTime " +
            "AND c.deletedAt IS NULL " +
            "ORDER BY c.callTime DESC")
    List<CallRecord> findByCustomerInTimeRange(
            @Param("factoryId") String factoryId,
            @Param("customerId") String customerId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);
}
