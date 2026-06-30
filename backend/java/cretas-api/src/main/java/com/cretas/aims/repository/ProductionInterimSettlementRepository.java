package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionInterimSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
