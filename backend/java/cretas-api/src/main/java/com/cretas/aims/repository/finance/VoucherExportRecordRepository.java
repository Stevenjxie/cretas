package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.finance.VoucherExportRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface VoucherExportRecordRepository extends JpaRepository<VoucherExportRecord, String> {

    /**
     * Rule-4 幂等 dedup: 同一工厂同一 exportType+period 在 createdAfter 之后是否有导出记录.
     * 5 分钟内重复提交 → 返回已有记录.
     */
    @Query("SELECT r FROM VoucherExportRecord r " +
           "WHERE r.factoryId = :factoryId " +
           "  AND r.exportType = :exportType " +
           "  AND r.periodStart = :periodStart " +
           "  AND r.periodEnd = :periodEnd " +
           "  AND r.createdAt >= :createdAfter " +
           "  AND r.deletedAt IS NULL " +
           "ORDER BY r.createdAt DESC")
    Optional<VoucherExportRecord> findRecentExport(
            @Param("factoryId") String factoryId,
            @Param("exportType") String exportType,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd,
            @Param("createdAfter") LocalDateTime createdAfter);
}
