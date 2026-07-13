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
     * 调料配方按工序 (2026-07-13): 报工时按工序名跨模式 (legacy 线性链 / 图 workflow) 定位工序,
     * 取其 processCategory (熟制/注射) + workProcessId。工序名在工厂内通常唯一
     * (existsByFactoryIdAndProcessName 已假设唯一性); >1 时调用方按 processOrder 消歧。
     */
    List<WorkProcess> findByFactoryIdAndProcessName(String factoryId, String processName);

    /**
     * C5: near-dup detection — find processes with the same name + category + unit
     * (excluding the caller's own ID on update; pass null for create).
     */
    List<WorkProcess> findByFactoryIdAndProcessNameAndProcessCategoryAndUnit(
            String factoryId, String processName, String processCategory, String unit);

    List<WorkProcess> findByFactoryIdAndIdIn(String factoryId, List<String> ids);
}
