package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionInterimSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ProductionInterimSettlement}.
 *
 * <p>Used by Task 3 (小结 Service) to:
 * <ol>
 *   <li>Determine next {@code session_seq} = last seq + 1 (first = 1).</li>
 *   <li>Insert new settlement row (UNIQUE constraint on factory/plan/seq prevents doubles).</li>
 * </ol>
 */
@Repository
public interface ProductionInterimSettlementRepository
        extends JpaRepository<ProductionInterimSettlement, String> {

    /**
     * Returns the latest settlement for a plan (highest session_seq) so Task 3 can compute
     * {@code nextSeq = lastSeq + 1}. Returns empty if no settlement exists yet (first = 1).
     */
    Optional<ProductionInterimSettlement> findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(
            String factoryId, String productionPlanId);

    /**
     * 所有未删的小结记录 (按 session_seq 升序)。Task 3 用于重建"前序小结已入库半成品 batchNumber 集合"
     * (从各次 summary.semiInBatchNumbers 并集), 以精确判定 SFI OUT 只对真正入过库的半成品扣减。
     */
    List<ProductionInterimSettlement> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(
            String factoryId, String productionPlanId);

    /**
     * 撤销小结: 按 (factory, plan, sessionSeq) 精确定位某次小结。已撤销 (硬删) → empty (双撤幂等拒绝)。
     */
    Optional<ProductionInterimSettlement> findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(
            String factoryId, String productionPlanId, Integer sessionSeq);

    /**
     * 撤销小结: 物理删除某次小结记录 (绕过 BaseEntity 的 {@code @SQLDelete} 软删)。
     *
     * <p>为何硬删而非软删: {@code uk_interim_plan_seq (factory, plan, session_seq)} 唯一约束对软删行仍生效,
     * 软删后再小结会撞已删行的 seq → 唯一冲突 500。硬删物理释放 seq, 使撤销后重新小结干净可行;
     * 审计留痕由 SFI REVERSE 流水 + 成品调整日志承载 (流水账不可变设计)。
     */
    @Modifying
    @Query("DELETE FROM ProductionInterimSettlement s WHERE s.id = :id")
    void hardDeleteById(@Param("id") String id);
}
