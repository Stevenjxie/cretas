package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InternalTransferRepository extends JpaRepository<InternalTransfer, String> {

    /**
     * 防呆 R4 (幂等防双击): 5 分钟窗口内、同 (源厂 + 目标厂 + 请求人 + 调拨日期) 且仍处于
     * 未完成状态 (DRAFT / REQUESTED) 的调拨。非空即视为重复创建 → 调用方返 409 + 已有单号。
     * 全部用等值比较 (无 nullable IS NULL 参数), 规避 PG "could not determine data type" 陷阱。
     */
    @Query("""
            SELECT t FROM InternalTransfer t
            WHERE t.sourceFactoryId = :factoryId
              AND t.targetFactoryId = :targetFactoryId
              AND t.requestedBy = :requestedBy
              AND t.transferDate = :transferDate
              AND t.status IN (com.cretas.aims.entity.enums.TransferStatus.DRAFT,
                               com.cretas.aims.entity.enums.TransferStatus.REQUESTED)
              AND t.createdAt >= :since
            ORDER BY t.createdAt DESC
            """)
    List<InternalTransfer> findRecentDuplicates(
            @Param("factoryId") String factoryId,
            @Param("targetFactoryId") String targetFactoryId,
            @Param("requestedBy") Long requestedBy,
            @Param("transferDate") LocalDate transferDate,
            @Param("since") LocalDateTime since);

    /** 调出方视角（我发出的调拨） */
    Page<InternalTransfer> findBySourceFactoryIdOrderByCreatedAtDesc(String sourceFactoryId, Pageable pageable);

    /** 调入方视角（我收到的调拨） */
    Page<InternalTransfer> findByTargetFactoryIdOrderByCreatedAtDesc(String targetFactoryId, Pageable pageable);

    /** 双向视角：调出或调入 */
    @Query("SELECT DISTINCT t FROM InternalTransfer t WHERE t.sourceFactoryId = :factoryId OR t.targetFactoryId = :factoryId ORDER BY t.createdAt DESC")
    Page<InternalTransfer> findByFactoryId(@Param("factoryId") String factoryId, Pageable pageable);

    Page<InternalTransfer> findBySourceFactoryIdAndStatusOrderByCreatedAtDesc(String sourceFactoryId, TransferStatus status, Pageable pageable);

    Optional<InternalTransfer> findBySourceFactoryIdAndTransferNumber(String sourceFactoryId, String transferNumber);

    @Query("SELECT COUNT(t) FROM InternalTransfer t WHERE t.sourceFactoryId = :factoryId AND FUNCTION('DATE', t.createdAt) = CURRENT_DATE")
    long countTodayBySourceFactory(@Param("factoryId") String factoryId);

    /**
     * Factory-scoped lookup — enforces tenant isolation.
     * Returns the transfer only if the given factoryId is either the source or the target,
     * preventing cross-tenant data leakage when a user knows a transferId.
     */
    @Query("SELECT t FROM InternalTransfer t WHERE t.id = :id AND (t.sourceFactoryId = :factoryId OR t.targetFactoryId = :factoryId)")
    Optional<InternalTransfer> findByIdAndEitherFactoryId(@Param("id") String id, @Param("factoryId") String factoryId);
}
