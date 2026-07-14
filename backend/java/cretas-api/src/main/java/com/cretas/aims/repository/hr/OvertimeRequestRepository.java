package com.cretas.aims.repository.hr;

import com.cretas.aims.entity.hr.OvertimeRequest;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.OvertimeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OvertimeRequestRepository extends JpaRepository<OvertimeRequest, String> {

    Optional<OvertimeRequest> findByIdAndFactoryId(String id, String factoryId);

    Page<OvertimeRequest> findByFactoryIdAndUserId(String factoryId, Long userId, Pageable pageable);

    Page<OvertimeRequest> findByFactoryIdAndUserIdAndStatus(
            String factoryId, Long userId, HrRequestStatus status, Pageable pageable);

    Page<OvertimeRequest> findByFactoryIdAndStatus(
            String factoryId, HrRequestStatus status, Pageable pageable);

    Page<OvertimeRequest> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 「全部」列表页 类型/补偿方式/状态 筛选 (2026-07-14, web-admin filter audit) — 三个参数均可空。
     * CAST(:param AS string) IS NULL 类型 hint (PostgreSQL 严格类型推断, 见 database-entity-sync.md)。
     */
    @Query("SELECT r FROM OvertimeRequest r WHERE r.factoryId = :factoryId " +
           "AND (CAST(:overtimeType AS string) IS NULL OR r.overtimeType = :overtimeType) " +
           "AND (CAST(:compensationType AS string) IS NULL OR r.compensationType = :compensationType) " +
           "AND (CAST(:status AS string) IS NULL OR r.status = :status)")
    Page<OvertimeRequest> findByFiltersAll(@Param("factoryId") String factoryId,
                                           @Param("overtimeType") OvertimeType overtimeType,
                                           @Param("compensationType") CompensationType compensationType,
                                           @Param("status") HrRequestStatus status,
                                           Pageable pageable);

    /**
     * R4 防呆: 同 user 同期间已有 DRAFT/SUBMITTED/APPROVED 加班申请 (避免重复提交).
     */
    @Query("""
            SELECT r FROM OvertimeRequest r
            WHERE r.factoryId = :factoryId
              AND r.userId = :userId
              AND r.status IN (
                com.cretas.aims.entity.hr.enums.HrRequestStatus.DRAFT,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.SUBMITTED,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED)
              AND r.startTime < :endTime
              AND r.endTime > :startTime
            """)
    List<OvertimeRequest> findOverlappingActive(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 已批准 + 补偿方式 + 时间范围. */
    @Query("""
            SELECT r FROM OvertimeRequest r
            WHERE r.factoryId = :factoryId
              AND r.status = com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED
              AND r.compensationType = :compensationType
              AND r.startTime >= :rangeStart
              AND r.startTime < :rangeEnd
            """)
    List<OvertimeRequest> findApprovedByCompTypeAndRange(
            @Param("factoryId") String factoryId,
            @Param("compensationType") CompensationType compensationType,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    /** 月度汇总: 补偿方式 → 总小时数. */
    @Query("""
            SELECT r.compensationType, SUM(r.hours)
            FROM OvertimeRequest r
            WHERE r.factoryId = :factoryId
              AND r.status = com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED
              AND r.startTime >= :rangeStart
              AND r.startTime < :rangeEnd
            GROUP BY r.compensationType
            """)
    List<Object[]> sumApprovedHoursByCompTypeInRange(
            @Param("factoryId") String factoryId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);
}
