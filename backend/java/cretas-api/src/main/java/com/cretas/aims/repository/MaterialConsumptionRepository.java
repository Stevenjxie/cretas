package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialConsumption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
/**
 * 原材料消耗记录数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Integer> {
    /**
     * 根据生产计划查找消耗记录
     */
    List<MaterialConsumption> findByProductionPlanId(String productionPlanId);

    /**
     * 根据批次查找消耗记录
     */
    List<MaterialConsumption> findByBatchId(String batchId);

    /**
     * 查找工厂的消耗记录
     */
    List<MaterialConsumption> findByFactoryId(String factoryId);

    /**
     * 根据时间范围查找消耗记录
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId " +
           "AND m.consumptionTime BETWEEN :startTime AND :endTime")
    List<MaterialConsumption> findByTimeRange(@Param("factoryId") String factoryId,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);

    /**
     * 计算生产计划的总消耗成本
     */
    @Query("SELECT SUM(m.totalCost) FROM MaterialConsumption m WHERE m.productionPlanId = :planId")
    BigDecimal calculateTotalCostByPlan(@Param("planId") String planId);

    /**
     * 计算批次的总消耗量
     */
    @Query("SELECT SUM(m.quantity) FROM MaterialConsumption m WHERE m.batchId = :batchId")
    BigDecimal calculateTotalQuantityByBatch(@Param("batchId") String batchId);

    /**
     * 获取生产计划的消耗统计
     */
    @Query("SELECT m.batch.materialType.name, SUM(m.quantity), SUM(m.totalCost) " +
           "FROM MaterialConsumption m WHERE m.productionPlanId = :planId " +
           "GROUP BY m.batch.materialType.name")
    List<Object[]> getConsumptionStatsByPlan(@Param("planId") String planId);

    /**
     * 删除生产计划的所有消耗记录
     */
    void deleteByProductionPlanId(String productionPlanId);

    /**
     * 根据生产批次ID查找消耗记录
     */
    List<MaterialConsumption> findByProductionBatchId(Long productionBatchId);

    /**
     * 按工厂ID分页查询消耗记录（排序由Pageable控制）
     */
    Page<MaterialConsumption> findByFactoryIdOrderByConsumptionTimeDesc(
            String factoryId, Pageable pageable);

    /**
     * 按工厂ID + 生产批次ID分页查询
     */
    Page<MaterialConsumption> findByFactoryIdAndProductionBatchIdOrderByConsumptionTimeDesc(
            String factoryId, Long productionBatchId, Pageable pageable);

    /**
     * 按工厂ID + 原材料批次ID分页查询
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId AND m.batchId = :batchId ORDER BY m.consumptionTime DESC")
    Page<MaterialConsumption> findByFactoryIdAndBatchIdPaged(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId,
            Pageable pageable);

    /**
     * 按工厂ID + 生产批次ID + 原材料批次ID分页查询
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId " +
           "AND m.productionBatchId = :productionBatchId AND m.batchId = :batchId " +
           "ORDER BY m.consumptionTime DESC")
    Page<MaterialConsumption> findByFactoryIdAndProductionBatchIdAndBatchIdPaged(
            @Param("factoryId") String factoryId,
            @Param("productionBatchId") Long productionBatchId,
            @Param("batchId") String batchId,
            Pageable pageable);

    /**
     * 按工厂ID和原材料批次ID查询消耗记录
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId AND m.batchId = :batchId")
    List<MaterialConsumption> findByFactoryIdAndBatchId(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId);

    /**
     * 按生产批次ID和物料类型ID查询消耗记录
     */
    List<MaterialConsumption> findByProductionBatchIdAndMaterialTypeId(Long productionBatchId, String materialTypeId);

    /**
     * 按生产批次ID和工厂ID查询消耗记录
     */
    List<MaterialConsumption> findByProductionBatchIdAndFactoryId(Long productionBatchId, String factoryId);

    /**
     * 计算生产批次的总消耗成本
     */
    @Query("SELECT COALESCE(SUM(m.totalCost), 0) FROM MaterialConsumption m WHERE m.productionBatchId = :batchId")
    BigDecimal calculateTotalCostByProductionBatch(@Param("batchId") Long batchId);

    /**
     * 按生产批次ID和来源类型查询
     */
    List<MaterialConsumption> findByProductionBatchIdAndSourceType(Long productionBatchId, String sourceType);

    /**
     * SP-F: 软删除某消耗批次(productionBatchId)的全部消耗边记录。
     * 用于 re-save/delete 时逆向清除已物化的消耗 edges，factory-scoped 防跨租户。
     */
    @Modifying
    @Query("UPDATE MaterialConsumption c SET c.deletedAt = CURRENT_TIMESTAMP " +
           "WHERE c.factoryId = :f AND c.productionBatchId = :pbId AND c.deletedAt IS NULL")
    int softDeleteByFactoryIdAndProductionBatchId(@Param("f") String factoryId,
                                                  @Param("pbId") Long productionBatchId);
}
