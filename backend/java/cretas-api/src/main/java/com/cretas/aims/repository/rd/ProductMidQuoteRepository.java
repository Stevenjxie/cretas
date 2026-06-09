package com.cretas.aims.repository.rd;

import com.cretas.aims.entity.rd.ProductMidQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductMidQuoteRepository extends JpaRepository<ProductMidQuote, String> {

    /**
     * 根据试制批次查询中报价 (幂等检查用).
     */
    Optional<ProductMidQuote> findByFactoryIdAndTrialBatchId(String factoryId, Long trialBatchId);

    /**
     * 查询指定试样最新的中报价 (三价对比用).
     * Spring Data 派生 — findFirst 自动取 ORDER BY createdAt DESC 第一条.
     */
    Optional<ProductMidQuote> findFirstByFactoryIdAndSampleIdOrderByCreatedAtDesc(
            String factoryId, String sampleId);
}
