package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.WastageReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 报损单 Repository (SP7 T3).
 */
@Repository
public interface WastageReportRepository extends JpaRepository<WastageReport, String> {

    Page<WastageReport> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    @Query("SELECT w FROM WastageReport w WHERE w.factoryId = :factoryId " +
           "AND (CAST(:trackType AS string) IS NULL OR w.trackType = :trackType) " +
           "AND (CAST(:status AS string) IS NULL OR w.status = :status) " +
           "ORDER BY w.createdAt DESC")
    Page<WastageReport> findByFactoryIdWithFilters(
            @Param("factoryId") String factoryId,
            @Param("trackType") WastageReport.TrackType trackType,
            @Param("status") WastageReport.Status status,
            Pageable pageable);

    /**
     * 按审批角色查询待审批的报损单:
     * - FINANCE → WAREHOUSE 轨 PENDING_APPROVAL
     * - FACTORY_MANAGER → FACTORY 轨 PENDING_APPROVAL
     */
    @Query("SELECT w FROM WastageReport w WHERE w.factoryId = :factoryId " +
           "AND w.status = 'PENDING_APPROVAL' " +
           "AND ((:role = 'FINANCE' AND w.trackType = 'WAREHOUSE') " +
           "  OR (:role = 'FACTORY_MANAGER' AND w.trackType = 'FACTORY')) " +
           "ORDER BY w.submittedAt ASC")
    Page<WastageReport> findPendingByApproverRole(
            @Param("factoryId") String factoryId,
            @Param("role") String role,
            Pageable pageable);
}
