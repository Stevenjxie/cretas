package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository — Sprint 8 P3 Phase A {@link HaccpMonitoringRecord}.
 *
 * <p>主要供 Skill `food-safety-recall` step 3 (haccp_checkpoint_review) 召回追溯使用.
 */
@Repository
public interface HaccpMonitoringRecordRepository extends JpaRepository<HaccpMonitoringRecord, Long> {

    /** 查 batch 全部监控记录, 倒序 (最新优先). */
    List<HaccpMonitoringRecord> findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
            String factoryId, String batchNumber);

    /** 查 factory 全部偏离记录 (is_deviation=true), 用于异常告警 list. */
    List<HaccpMonitoringRecord> findByFactoryIdAndIsDeviationTrue(String factoryId);
}
