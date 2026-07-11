package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogisticsDeliveryOrderRepository extends JpaRepository<LogisticsDeliveryOrder, String> {

    List<LogisticsDeliveryOrder> findByBatchIdAndDeletedAtIsNull(String batchId);

    List<LogisticsDeliveryOrder> findByFactoryIdAndBatchId(String factoryId, String batchId);
}
