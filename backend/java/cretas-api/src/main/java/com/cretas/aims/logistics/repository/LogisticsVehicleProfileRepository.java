package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsVehicleProfileRepository extends JpaRepository<LogisticsVehicleProfile, String> {

    List<LogisticsVehicleProfile> findByFactoryIdAndActiveTrueAndDeletedAtIsNull(String factoryId);

    Optional<LogisticsVehicleProfile> findByVehicleIdAndDeletedAtIsNull(String vehicleId);

    /** Phase 2: GET /vehicles 列表批量拉取 (避免逐车 N+1 查询)。 */
    List<LogisticsVehicleProfile> findByFactoryId(String factoryId);
}
