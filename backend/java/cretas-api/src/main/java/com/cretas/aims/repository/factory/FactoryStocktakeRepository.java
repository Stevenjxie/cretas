package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.FactoryStocktake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工厂盘点任务 Repository (SP7 T2).
 */
@Repository
public interface FactoryStocktakeRepository extends JpaRepository<FactoryStocktake, String> {

    Page<FactoryStocktake> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    @Query("SELECT s FROM FactoryStocktake s WHERE s.factoryId = :factoryId " +
           "AND (CAST(:status AS string) IS NULL OR s.status = :status) " +
           "ORDER BY s.createdAt DESC")
    Page<FactoryStocktake> findByFactoryIdAndOptionalStatus(
            @Param("factoryId") String factoryId,
            @Param("status") FactoryStocktake.Status status,
            Pageable pageable);

    List<FactoryStocktake> findByFactoryIdAndPeriodMonth(String factoryId, String periodMonth);

    Optional<FactoryStocktake> findByFactoryIdAndStocktakeNo(String factoryId, String stocktakeNo);

    /** 查询同仓库同月份未完成的盘点（防重复发起）*/
    @Query("SELECT COUNT(s) FROM FactoryStocktake s WHERE s.factoryId = :factoryId " +
           "AND s.warehouseId = :warehouseId AND s.periodMonth = :periodMonth " +
           "AND s.status NOT IN ('APPLIED', 'REJECTED')")
    long countActiveStocktakeForWarehouseAndMonth(
            @Param("factoryId") String factoryId,
            @Param("warehouseId") String warehouseId,
            @Param("periodMonth") String periodMonth);
}
