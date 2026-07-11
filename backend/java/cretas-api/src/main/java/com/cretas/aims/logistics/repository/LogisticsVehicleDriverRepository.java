package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogisticsVehicleDriverRepository extends JpaRepository<LogisticsVehicleDriver, String> {

    List<LogisticsVehicleDriver> findByVehicleIdAndDeletedAtIsNull(String vehicleId);
}
