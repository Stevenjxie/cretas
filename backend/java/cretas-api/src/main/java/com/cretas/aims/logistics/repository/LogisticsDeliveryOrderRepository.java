package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsDeliveryOrderRepository extends JpaRepository<LogisticsDeliveryOrder, String> {

    List<LogisticsDeliveryOrder> findByBatchIdAndDeletedAtIsNull(String batchId);

    List<LogisticsDeliveryOrder> findByFactoryIdAndBatchId(String factoryId, String batchId);

    /** Phase 2: GET /orders?batchId=... 分页列表 (租户隔离)。 */
    Page<LogisticsDeliveryOrder> findByFactoryIdAndBatchId(String factoryId, String batchId, Pageable pageable);

    /** Phase 2: PUT /orders/{orderId}/location 租户隔离查询。 */
    Optional<LogisticsDeliveryOrder> findByIdAndFactoryId(String id, String factoryId);
}
