package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.WastageReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
     * 按可见轨道集合查询待审批报损单 (W2 修, 2026-06-10)。
     *
     * <p>service 层按角色推导 visibleTracks 后调用此方法，
     * 避免在 SQL 中硬编码大写虚构角色码。
     * trackType IN + status = PENDING_APPROVAL + deletedAt IS NULL（@Where 已处理）。
     */
    @Query("SELECT w FROM WastageReport w WHERE w.factoryId = :factoryId " +
           "AND w.status = com.cretas.aims.entity.inventory.WastageReport.Status.PENDING_APPROVAL " +
           "AND w.trackType IN :trackTypes " +
           "ORDER BY w.submittedAt ASC")
    Page<WastageReport> findPendingByTrackTypes(
            @Param("factoryId") String factoryId,
            @Param("trackTypes") List<WastageReport.TrackType> trackTypes,
            Pageable pageable);
}
