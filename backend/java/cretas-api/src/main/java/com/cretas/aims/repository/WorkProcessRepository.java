package com.cretas.aims.repository;

import com.cretas.aims.entity.WorkProcess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkProcessRepository extends JpaRepository<WorkProcess, String> {

    Page<WorkProcess> findByFactoryId(String factoryId, Pageable pageable);

    List<WorkProcess> findByFactoryId(String factoryId);

    List<WorkProcess> findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(String factoryId);

    Optional<WorkProcess> findByFactoryIdAndId(String factoryId, String id);

    boolean existsByFactoryIdAndProcessName(String factoryId, String processName);

    /**
     * C5: near-dup detection — find processes with the same name + category + unit
     * (excluding the caller's own ID on update; pass null for create).
     */
    List<WorkProcess> findByFactoryIdAndProcessNameAndProcessCategoryAndUnit(
            String factoryId, String processName, String processCategory, String unit);

    List<WorkProcess> findByFactoryIdAndIdIn(String factoryId, List<String> ids);
}
