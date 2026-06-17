package com.cretas.aims.repository;

import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.enums.DefectStatus;
import com.cretas.aims.entity.enums.DefectType;
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
 * Sprint4-H Q-PROCESS-1: 工序质检不良数据访问接口.
 */
@Repository
public interface QualityDefectRepository extends JpaRepository<QualityDefect, String> {

    Optional<QualityDefect> findByIdAndFactoryId(String id, String factoryId);

    Page<QualityDefect> findByFactoryId(String factoryId, Pageable pageable);

    Page<QualityDefect> findByFactoryIdAndQualityInspectionId(
            String factoryId, String qualityInspectionId, Pageable pageable);

    List<QualityDefect> findByFactoryIdAndQualityInspectionId(
            String factoryId, String qualityInspectionId);

    /**
     * 复合过滤查询. CAST(:param AS string) 避免 PG 推不出参数类型 (per
     * database-entity-sync.md JPQL 参数 IS NULL pattern).
     */
    @Query("SELECT d FROM QualityDefect d WHERE d.factoryId = :factoryId " +
            "AND (CAST(:status AS string) IS NULL OR d.status = :status) " +
            "AND (CAST(:defectType AS string) IS NULL OR d.defectType = :defectType) " +
            "AND (CAST(:qualityInspectionId AS string) IS NULL OR d.qualityInspectionId = :qualityInspectionId) " +
            "AND (CAST(:materialId AS string) IS NULL OR d.materialId = :materialId) " +
            "AND (CAST(:fromDate AS string) IS NULL OR d.createdAt >= :fromDate) " +
            "AND (CAST(:toDate AS string) IS NULL OR d.createdAt <= :toDate)")
    Page<QualityDefect> findByFilters(
            @Param("factoryId") String factoryId,
            @Param("status") DefectStatus status,
            @Param("defectType") DefectType defectType,
            @Param("qualityInspectionId") String qualityInspectionId,
            @Param("materialId") String materialId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable);

    long countByFactoryIdAndStatus(String factoryId, DefectStatus status);

    long countByFactoryIdAndDefectType(String factoryId, DefectType defectType);
}
