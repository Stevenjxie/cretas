package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryLedgerSnapshotRepository extends JpaRepository<InventoryLedgerSnapshot, String> {

    List<InventoryLedgerSnapshot> findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
            String factoryId, String accountingPeriodId, SnapshotType snapshotType);

    Optional<InventoryLedgerSnapshot> findByFactoryIdAndAccountingPeriodIdAndMaterialTypeIdAndSnapshotTypeAndDeletedAtIsNull(
            String factoryId, String accountingPeriodId, String materialTypeId, SnapshotType snapshotType);

    /**
     * 查找最近一期期末快照 (period 的 year/month 小于等于给定日期的最近记录).
     * 用于"期初 = 上期期末" 逻辑.
     */
    @Query("SELECT s FROM InventoryLedgerSnapshot s " +
           "JOIN com.cretas.aims.entity.finance.AccountingPeriod p " +
           "  ON s.accountingPeriodId = p.id " +
           "WHERE s.factoryId = :factoryId " +
           "  AND s.materialTypeId = :materialTypeId " +
           "  AND s.snapshotType = :snapshotType " +
           "  AND s.deletedAt IS NULL " +
           "  AND (p.year * 100 + p.month) < :yearMonth " +
           "ORDER BY (p.year * 100 + p.month) DESC")
    List<InventoryLedgerSnapshot> findLatestBeforePeriod(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId,
            @Param("snapshotType") SnapshotType snapshotType,
            @Param("yearMonth") int yearMonth);

    boolean existsByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
            String factoryId, String accountingPeriodId, SnapshotType snapshotType);
}
