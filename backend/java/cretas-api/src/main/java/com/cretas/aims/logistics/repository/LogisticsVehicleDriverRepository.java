package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogisticsVehicleDriverRepository extends JpaRepository<LogisticsVehicleDriver, String> {

    List<LogisticsVehicleDriver> findByVehicleIdAndDeletedAtIsNull(String vehicleId);

    /** Phase 2: GET /vehicles 列表批量拉取所有绑定 (避免逐车 N+1 查询)。 */
    List<LogisticsVehicleDriver> findByFactoryIdAndDeletedAtIsNull(String factoryId);
}
