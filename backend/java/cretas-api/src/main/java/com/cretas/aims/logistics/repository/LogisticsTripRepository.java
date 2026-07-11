package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsTrip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogisticsTripRepository extends JpaRepository<LogisticsTrip, String> {

    List<LogisticsTrip> findByPlanIdAndDeletedAtIsNull(String planId);
}
