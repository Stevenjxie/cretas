package com.cretas.aims.repository.workreport;

import com.cretas.aims.entity.workreport.EmployeeProcessSegment;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.SegmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * P1-1 员工工序片段 Repository.
 */
@Repository
public interface EmployeeProcessSegmentRepository
        extends JpaRepository<EmployeeProcessSegment, String> {

    /** 查某员工的所有 ACTIVE 片段 (应 <= 1). */
    List<EmployeeProcessSegment> findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(
            String factoryId, Long employeeId, SegmentStatus status);

    Optional<EmployeeProcessSegment> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** 查某员工在时间范围内的所有 segment (按 startAt 升序). */
    List<EmployeeProcessSegment> findByFactoryIdAndEmployeeIdAndStartAtBetweenAndDeletedAtIsNullOrderByStartAtAsc(
            String factoryId, Long employeeId, LocalDateTime start, LocalDateTime end);

    /** 查所有 ACTIVE 的 segment (用于每天 20:00 扫描仍在线员工). */
    List<EmployeeProcessSegment> findByFactoryIdAndStatusAndDeletedAtIsNull(
            String factoryId, SegmentStatus status);

    /**
     * 单元C (F006 REQ-14): 某员工在某工序上所有已结束片段的总工时 (分钟).
     *
     * <p>客户原话: 员工一个工序段内可能多次扫码上班/下班, 客户要"所有上班下班时间"汇总.
     * 只统计 end_at 已填 (已结束) 的片段, 防止把进行中的片段算进去.
     *
     * <p>用 native query: PostgreSQL EXTRACT(EPOCH FROM interval) 在 JPQL 里不稳定,
     * native SQL 直接算 (end_at - start_at) 间隔秒数 / 60 = 分钟.
     * COALESCE 保证无片段时返 0 而非 null.
     */
    @Query(value = "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (end_at - start_at)) / 60.0), 0) " +
            "FROM employee_process_segments " +
            "WHERE factory_id = :factoryId AND employee_id = :employeeId AND process_id = :processId " +
            "AND end_at IS NOT NULL AND deleted_at IS NULL", nativeQuery = true)
    Double sumMinutesByEmployeeAndProcess(@Param("factoryId") String factoryId,
            @Param("employeeId") Long employeeId, @Param("processId") String processId);
}
