package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsOrderBatchRepository extends JpaRepository<LogisticsOrderBatch, String> {

    /** 幂等键查询 (spec §2 决策 7) — 重复上传返回既有批次, service 层短路不静默双写。 */
    Optional<LogisticsOrderBatch> findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull(
            String factoryId, LocalDate businessDate, String sourceFingerprint);

    List<LogisticsOrderBatch> findByFactoryIdOrderByBusinessDateDesc(String factoryId);
}
