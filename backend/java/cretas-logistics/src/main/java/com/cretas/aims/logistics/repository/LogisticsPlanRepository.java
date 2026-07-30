package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LogisticsPlanRepository extends JpaRepository<LogisticsPlan, String> {

    List<LogisticsPlan> findByFactoryIdOrderByPlanDateDesc(String factoryId);

    Optional<LogisticsPlan> findByIdAndFactoryId(String id, String factoryId);
}
