package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionInputAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ProductionInputAllocationRepository
        extends JpaRepository<ProductionInputAllocation, Long> {

    List<ProductionInputAllocation> findByFactoryIdAndProcessSheetRowIdOrderByAllocationOrderAsc(
            String factoryId, Long processSheetRowId);

    /**
     * 只统计尚未小结的正式报工占用。小结给 ProcessSheetRow.interimSettledAt 打戳后，
     * 该数量自然退出占用，避免与 MaterialBatch.usedQuantity 重复扣减。
     */
    @Query("SELECT SUM(a.quantity) FROM ProductionInputAllocation a, ProcessSheetRow r "
            + "WHERE a.factoryId = :factoryId AND a.materialBatchId = :materialBatchId "
            + "AND a.processSheetRowId = r.id AND a.deletedAt IS NULL AND r.deletedAt IS NULL "
            + "AND r.submissionStatus = 'SUBMITTED' AND r.interimSettledAt IS NULL")
    BigDecimal sumPendingQuantityByMaterialBatchId(
            @Param("factoryId") String factoryId,
            @Param("materialBatchId") String materialBatchId);
}
