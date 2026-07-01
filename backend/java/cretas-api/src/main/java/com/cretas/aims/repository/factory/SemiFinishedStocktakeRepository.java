package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 半成品盘点任务 Repository (镜像 SP7 {@link FactoryStocktakeRepository})。
 */
@Repository
public interface SemiFinishedStocktakeRepository extends JpaRepository<SemiFinishedStocktake, String> {

    @Query("SELECT s FROM SemiFinishedStocktake s WHERE s.factoryId = :factoryId " +
           "AND (CAST(:status AS string) IS NULL OR s.status = :status) " +
           "ORDER BY s.createdAt DESC")
    Page<SemiFinishedStocktake> findByFactoryIdAndOptionalStatus(
            @Param("factoryId") String factoryId,
            @Param("status") SemiFinishedStocktake.Status status,
            Pageable pageable);

    Optional<SemiFinishedStocktake> findByFactoryIdAndStocktakeNo(String factoryId, String stocktakeNo);

    /** 查询同工厂同月份未完成的半成品盘点 (防重复发起) */
    @Query("SELECT COUNT(s) FROM SemiFinishedStocktake s WHERE s.factoryId = :factoryId " +
           "AND s.periodMonth = :periodMonth " +
           "AND s.status NOT IN ('APPLIED', 'REJECTED')")
    long countActiveStocktakeForMonth(
            @Param("factoryId") String factoryId,
            @Param("periodMonth") String periodMonth);
}
