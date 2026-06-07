package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * T126 Phase 1 — 成品库存调整日志 Repository
 */
@Repository
public interface FinishedGoodsAdjustmentLogRepository extends JpaRepository<FinishedGoodsAdjustmentLog, Long> {

    List<FinishedGoodsAdjustmentLog> findByBatchIdOrderByCreatedAtDesc(String batchId);
}
