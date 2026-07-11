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
}
