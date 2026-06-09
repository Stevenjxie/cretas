package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 半成品库存流水账 Repository (SP1)。
 *
 * <p>流水账不可修改: 无软删, 冲销走 REVERSE 行。
 * 查询方法按 createdAt DESC 保证时序一致。
 */
@Repository
public interface SemiFinishedInventoryTransactionRepository
        extends JpaRepository<SemiFinishedInventoryTransaction, Long> {

    /**
     * 按半成品库存 ID 查询全部流水 (时序正序 — 移动均价回放用)。
     */
    List<SemiFinishedInventoryTransaction> findBySemiFinishedIdOrderByCreatedAtAsc(Long semiFinishedId);

    /**
     * 按半成品库存 ID 查询全部流水 (时序倒序 — 前端展示用)。
     */
    List<SemiFinishedInventoryTransaction> findBySemiFinishedIdOrderByCreatedAtDesc(Long semiFinishedId);

    /**
     * 按工厂 + source_ref + txnType 查找已有流水 (幂等守卫)。
     * 用于检查同一 intermediateBatchNo 的 IN 行是否已存在, 防止重复写入。
     */
    Optional<SemiFinishedInventoryTransaction> findByFactoryIdAndSourceRefAndTxnType(
            String factoryId, String sourceRef, String txnType);

    /**
     * 按工厂 + 报工 ID 查找流水 (撤回用: 找到要冲销的 IN 行)。
     */
    List<SemiFinishedInventoryTransaction> findByFactoryIdAndReportId(String factoryId, Long reportId);

    /**
     * 按工厂 + txnType 查找流水。
     */
    @Query("SELECT t FROM SemiFinishedInventoryTransaction t "
            + "WHERE t.factoryId = :factoryId AND t.txnType = :txnType "
            + "ORDER BY t.createdAt DESC")
    List<SemiFinishedInventoryTransaction> findByFactoryIdAndTxnType(
            @Param("factoryId") String factoryId,
            @Param("txnType") String txnType);
}
