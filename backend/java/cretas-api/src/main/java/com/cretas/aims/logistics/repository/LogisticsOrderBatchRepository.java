package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Phase 2: GET /order-batches 分页列表 (租户隔离, 最新业务日期在前)。 */
    Page<LogisticsOrderBatch> findByFactoryIdOrderByBusinessDateDesc(String factoryId, Pageable pageable);

    /** Phase 2: 租户隔离的详情查询 (GET /order-batches/{batchId})。 */
    Optional<LogisticsOrderBatch> findByIdAndFactoryId(String id, String factoryId);

    /** Phase 2: 批次编号生成 (LOG-yyyyMMdd-NNN) 用来计算当日序号。 */
    long countByFactoryIdAndBusinessDate(String factoryId, LocalDate businessDate);
}
