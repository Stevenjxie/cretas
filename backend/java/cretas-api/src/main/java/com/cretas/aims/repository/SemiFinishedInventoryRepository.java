package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 半成品库存 (WIP) Repository — G6 地基。
 *
 * <p>支撑 Wave 2/3: 部分领用扣减 ({@code findByIntermediateBatchNo} 拿源 WIP 行)、
 * 整批出成率 WIP 在制量汇总 ({@code findByFactoryIdAndBatchId})、防呆 :max。
 *
 * <p>软删除约定与 production_reports 一致 (deleted_at IS NULL 过滤); 查询方法显式带
 * {@code DeletedAtIsNull} 后缀 (实体未继承 BaseEntity 的 @Where 自动过滤)。
 */
@Repository
public interface SemiFinishedInventoryRepository extends JpaRepository<SemiFinishedInventory, Long> {

    /**
     * 按工序批次号查 WIP 行 (幂等 upsert 锚点)。
     * intermediate_batch_no 工厂内唯一 (partial unique uq_sfi_intermediate_batch_no), 故返 Optional。
     */
    Optional<SemiFinishedInventory> findByIntermediateBatchNoAndDeletedAtIsNull(String intermediateBatchNo);

    /** 某生产批次的全部 WIP 行 (整批出成率在制量汇总用)。 */
    List<SemiFinishedInventory> findByFactoryIdAndBatchIdAndDeletedAtIsNull(String factoryId, Long batchId);

    /** 某生产批次某状态的 WIP 行 (如 AVAILABLE 可领用 / DEPLETED 已清零)。 */
    List<SemiFinishedInventory> findByFactoryIdAndBatchIdAndStatusAndDeletedAtIsNull(
            String factoryId, Long batchId, String status);

    /** 某工序任务产出的 WIP 行 (一道任务一笔 WIP 行)。 */
    List<SemiFinishedInventory> findByFactoryIdAndSourceWorkProcessTaskIdAndDeletedAtIsNull(
            String factoryId, Long sourceWorkProcessTaskId);

    /**
     * 某生产批次有结余 (available_quantity > 0) 且未软删的 AVAILABLE WIP 行。
     * D3 余料退回 (Wave 4): 完工时若有结余 → 提示退回总仓。
     */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId "
            + "AND w.batchId = :batchId AND w.status = 'AVAILABLE' "
            + "AND w.availableQuantity > 0 AND w.deletedAt IS NULL "
            + "ORDER BY w.processOrder ASC")
    List<SemiFinishedInventory> findRemainingWip(@Param("factoryId") String factoryId,
                                                 @Param("batchId") Long batchId);
}
