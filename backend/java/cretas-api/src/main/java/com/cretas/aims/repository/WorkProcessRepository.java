package com.cretas.aims.repository;

import com.cretas.aims.entity.WorkProcess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkProcessRepository extends JpaRepository<WorkProcess, String> {

    Page<WorkProcess> findByFactoryId(String factoryId, Pageable pageable);

    List<WorkProcess> findByFactoryId(String factoryId);

    List<WorkProcess> findByFactoryIdAndIsActiveTrueAndMergedIntoIdIsNullOrderByProcessNameAsc(String factoryId);

    Optional<WorkProcess> findByFactoryIdAndId(String factoryId, String id);

    boolean existsByFactoryIdAndProcessName(String factoryId, String processName);

    /**
     * 调料配方按工序 (2026-07-13): 报工时按工序名跨模式 (legacy 线性链 / 图 workflow) 定位工序,
     * 取其 processCategory (熟制/注射) + workProcessId。工序名在工厂内通常唯一
     * (existsByFactoryIdAndProcessName 已假设唯一性); >1 时调用方按 processOrder 消歧。
     */
    List<WorkProcess> findByFactoryIdAndProcessName(String factoryId, String processName);

    List<WorkProcess> findByFactoryIdAndIdIn(String factoryId, List<String> ids);

    @Query("select distinct trim(wp.processCategory) from WorkProcess wp "
            + "where wp.factoryId = :factoryId and wp.processCategory is not null "
            + "and trim(wp.processCategory) <> '' order by trim(wp.processCategory)")
    List<String> findDistinctProcessCategories(@Param("factoryId") String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wp from WorkProcess wp where wp.factoryId = :factoryId and wp.id in :ids order by wp.id")
    List<WorkProcess> lockByFactoryIdAndIdIn(
            @Param("factoryId") String factoryId,
            @Param("ids") List<String> ids);

    boolean existsByFactoryIdAndMergedIntoId(String factoryId, String mergedIntoId);
}
