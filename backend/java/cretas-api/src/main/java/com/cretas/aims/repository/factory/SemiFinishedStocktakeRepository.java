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

    /**
     * 查询同工厂进行中(非终态)的半成品盘点数 (防重复发起 —— 张权可任意时间发起周/月盘点,
     * 去月底限制后, 唯一去重规则是"同工厂同时只允许一个未完成盘点")。
     *
     * <p>非终态 = INITIATED / COUNTING / PENDING_APPROVAL / APPROVED (已审批未生效);
     * 终态 APPLIED / REJECTED 排除 —— 上一个盘点生效或驳回后即可再发起 (支持周复盘节奏)。
     * 不限 periodMonth: 允许同月多次盘点 (周盘)。
     */
    @Query("SELECT COUNT(s) FROM SemiFinishedStocktake s WHERE s.factoryId = :factoryId " +
           "AND s.status NOT IN ('APPLIED', 'REJECTED')")
    long countActiveStocktake(@Param("factoryId") String factoryId);
}
