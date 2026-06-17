package com.cretas.aims.repository;

import com.cretas.aims.entity.QualityReturnOrder;
import com.cretas.aims.entity.enums.QualityReturnStatus;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Sprint4-H Q-RETURN-1: 质检退回单数据访问.
 */
@Repository
public interface QualityReturnOrderRepository extends JpaRepository<QualityReturnOrder, String> {

    Optional<QualityReturnOrder> findByIdAndFactoryId(String id, String factoryId);

    Page<QualityReturnOrder> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 复合过滤. CAST(:param AS string) IS NULL pattern (per database-entity-sync.md
     * PG parameter type inference workaround).
     */
    @Query("SELECT r FROM QualityReturnOrder r WHERE r.factoryId = :factoryId " +
            "AND (CAST(:status AS string) IS NULL OR r.status = :status) " +
            "AND (CAST(:targetType AS string) IS NULL OR r.targetType = :targetType) " +
            "AND (CAST(:qualityInspectionId AS string) IS NULL OR r.qualityInspectionId = :qualityInspectionId) " +
            "AND (CAST(:targetId AS string) IS NULL OR r.targetId = :targetId) " +
            "AND (CAST(:fromDate AS string) IS NULL OR r.createdAt >= :fromDate) " +
            "AND (CAST(:toDate AS string) IS NULL OR r.createdAt <= :toDate)")
    Page<QualityReturnOrder> findByFilters(
            @Param("factoryId") String factoryId,
            @Param("status") QualityReturnStatus status,
            @Param("targetType") QualityReturnTargetType targetType,
            @Param("qualityInspectionId") String qualityInspectionId,
            @Param("targetId") String targetId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope) — {@link #findByFilters} + createdBy IN list.
     * Mirrors filters; adds {@code AND r.createdBy IN :createdByList}.
     * Caller resolves chain (single user for SELF, chain for DEPT/SELF_AND_BELOW).
     */
    @Query("SELECT r FROM QualityReturnOrder r WHERE r.factoryId = :factoryId " +
            "AND (CAST(:status AS string) IS NULL OR r.status = :status) " +
            "AND (CAST(:targetType AS string) IS NULL OR r.targetType = :targetType) " +
            "AND (CAST(:qualityInspectionId AS string) IS NULL OR r.qualityInspectionId = :qualityInspectionId) " +
            "AND (CAST(:targetId AS string) IS NULL OR r.targetId = :targetId) " +
            "AND (CAST(:fromDate AS string) IS NULL OR r.createdAt >= :fromDate) " +
            "AND (CAST(:toDate AS string) IS NULL OR r.createdAt <= :toDate) " +
            "AND r.createdBy IN :createdByList")
    Page<QualityReturnOrder> findByFiltersAndCreatedByIn(
            @Param("factoryId") String factoryId,
            @Param("status") QualityReturnStatus status,
            @Param("targetType") QualityReturnTargetType targetType,
            @Param("qualityInspectionId") String qualityInspectionId,
            @Param("targetId") String targetId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("createdByList") java.util.Collection<Long> createdByList,
            Pageable pageable);

    /**
     * 计算当日 return_number 序号 (用于自动生成 QR-YYYYMMDD-NNN).
     * 返回当日已生成的退回单数, 调用方 +1 即可得到下一个序号.
     */
    @Query("SELECT COUNT(r) FROM QualityReturnOrder r WHERE r.factoryId = :factoryId " +
            "AND r.createdAt >= :dayStart AND r.createdAt < :nextDay")
    long countByFactoryAndDay(@Param("factoryId") String factoryId,
                              @Param("dayStart") LocalDateTime dayStart,
                              @Param("nextDay") LocalDateTime nextDay);
}
