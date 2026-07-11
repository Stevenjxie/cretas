package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsDriver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogisticsDriverRepository extends JpaRepository<LogisticsDriver, String> {

    List<LogisticsDriver> findByFactoryIdAndActiveTrueAndDeletedAtIsNull(String factoryId);
}
