package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.TransferDiffRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 调拨差异记录 Repository
 *
 * @since 2026-06-11
 */
@Repository
public interface TransferDiffRecordRepository extends JpaRepository<TransferDiffRecord, String> {

    /** 查调出方工厂的全部差异单（调出方维度） */
    List<TransferDiffRecord> findBySourceFactoryIdOrderByCreatedAtDesc(String sourceFactoryId);

    /** 查调入方工厂的全部差异单（调入方维度） */
    List<TransferDiffRecord> findByTargetFactoryIdOrderByCreatedAtDesc(String targetFactoryId);

    /** 双向：source 或 target 都可查 */
    @Query("""
            SELECT d FROM TransferDiffRecord d
            WHERE (d.sourceFactoryId = :factoryId OR d.targetFactoryId = :factoryId)
            AND (:status IS NULL OR CAST(:status AS string) IS NULL OR d.status = :status)
            ORDER BY d.createdAt DESC
            """)
    List<TransferDiffRecord> findByEitherFactoryAndStatus(
            @Param("factoryId") String factoryId,
            @Param("status") String status);

    /** 查指定调拨单关联的所有差异单 */
    List<TransferDiffRecord> findByTransferIdOrderByCreatedAtDesc(String transferId);

    /** 唯一查询（source factory + diff number） */
    Optional<TransferDiffRecord> findBySourceFactoryIdAndDiffNumber(String sourceFactoryId, String diffNumber);

    /** 调出方工厂待处理差异单数（工作台角标） */
    @Query("""
            SELECT COUNT(d) FROM TransferDiffRecord d
            WHERE d.sourceFactoryId = :factoryId AND d.status = 'PENDING' AND d.deletedAt IS NULL
            """)
    long countPendingBySourceFactory(@Param("factoryId") String factoryId);
}
