package com.cretas.aims.repository.hr;

import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
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
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String> {

    Optional<LeaveRequest> findByIdAndFactoryId(String id, String factoryId);

    Page<LeaveRequest> findByFactoryIdAndUserId(String factoryId, Long userId, Pageable pageable);

    Page<LeaveRequest> findByFactoryIdAndUserIdAndStatus(
            String factoryId, Long userId, HrRequestStatus status, Pageable pageable);

    Page<LeaveRequest> findByFactoryIdAndStatus(
            String factoryId, HrRequestStatus status, Pageable pageable);

    Page<LeaveRequest> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 「全部」列表页 类型/状态 筛选 (2026-07-14, web-admin filter audit) — 两个参数均可空。
     * CAST(:param AS string) IS NULL 类型 hint (PostgreSQL 严格类型推断, 见 database-entity-sync.md)。
     */
    @Query("SELECT r FROM LeaveRequest r WHERE r.factoryId = :factoryId " +
           "AND (CAST(:leaveType AS string) IS NULL OR r.leaveType = :leaveType) " +
           "AND (CAST(:status AS string) IS NULL OR r.status = :status)")
    Page<LeaveRequest> findByFiltersAll(@Param("factoryId") String factoryId,
                                        @Param("leaveType") LeaveType leaveType,
                                        @Param("status") HrRequestStatus status,
                                        Pageable pageable);

    /**
     * R4 防呆: 同 user 同期间已有 DRAFT/SUBMITTED 申请 (避免重复提交).
     * 返回 list, 调用方取 first 显示 existingId.
     */
    @Query("""
            SELECT r FROM LeaveRequest r
            WHERE r.factoryId = :factoryId
              AND r.userId = :userId
              AND r.status IN (
                com.cretas.aims.entity.hr.enums.HrRequestStatus.DRAFT,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.SUBMITTED,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED)
              AND r.startDate <= :endDate
              AND r.endDate >= :startDate
            """)
    List<LeaveRequest> findOverlappingActive(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 已批准 + 类型 + 时间范围 (用于 HR 月报表和余额计算). */
    @Query("""
            SELECT r FROM LeaveRequest r
            WHERE r.factoryId = :factoryId
              AND r.status = com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED
              AND r.leaveType = :leaveType
              AND r.startDate <= :rangeEnd
              AND r.endDate >= :rangeStart
            """)
    List<LeaveRequest> findApprovedByTypeAndRange(
            @Param("factoryId") String factoryId,
            @Param("leaveType") LeaveType leaveType,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd);

    /** 工厂月度汇总: 类型 → 总小时数. */
    @Query("""
            SELECT r.leaveType, SUM(r.durationHours)
            FROM LeaveRequest r
            WHERE r.factoryId = :factoryId
              AND r.status = com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED
              AND r.startDate <= :rangeEnd
              AND r.endDate >= :rangeStart
            GROUP BY r.leaveType
            """)
    List<Object[]> sumApprovedHoursByTypeInRange(
            @Param("factoryId") String factoryId,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd);
}
