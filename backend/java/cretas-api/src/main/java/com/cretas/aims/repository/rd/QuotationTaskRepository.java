package com.cretas.aims.repository.rd;

import com.cretas.aims.entity.rd.QuotationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuotationTaskRepository extends JpaRepository<QuotationTask, String> {
    Page<QuotationTask> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);
    Page<QuotationTask> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, String status, Pageable pageable);
    QuotationTask findBySampleIdAndDeletedAtIsNull(String sampleId);

    /**
     * P1 #33: 取产品最新的研发报价任务 (销售价 ≥ 研发预估价 跨流校验用).
     *
     * <p>同一产品可能有多个报价任务 (多轮试制), 取最新一条 (createdAt DESC) 作为现行预估价来源。
     * Spring Data 派生: {@code findFirst...OrderByCreatedAtDesc} 自动 LIMIT 1。
     * {@code @Where(deleted_at IS NULL)} 已在实体上, 派生查询自动带软删除过滤。
     */
    Optional<QuotationTask> findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
            String factoryId, String productTypeId);
}
