package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductWorkProcess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductWorkProcessRepository extends JpaRepository<ProductWorkProcess, Long> {

    List<ProductWorkProcess> findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
            String factoryId, String productTypeId);

    Optional<ProductWorkProcess> findByFactoryIdAndProductTypeIdAndProcessOrder(
            String factoryId, String productTypeId, Integer processOrder);

    Optional<ProductWorkProcess> findByFactoryIdAndId(String factoryId, Long id);

    boolean existsByFactoryIdAndProductTypeIdAndWorkProcessId(
            String factoryId, String productTypeId, String workProcessId);

    /**
     * 2B Task B2: workflow 过程单投影用 — 按 (factoryId, productTypeId, workProcessId) 取
     * 该产品对这道工序的成本类别等旧配置 (若存在)。workflow 批次的 {@code WorkProcessTask}
     * 不绑定 {@code productWorkProcessId}, 但同一 (factoryId, productTypeId, workProcessId)
     * 组合可能仍留有历史 {@link ProductWorkProcess} 行 (旧 legacy 配置), 若无则调用方回落 null。
     */
    Optional<ProductWorkProcess> findByFactoryIdAndProductTypeIdAndWorkProcessId(
            String factoryId, String productTypeId, String workProcessId);

    void deleteByFactoryIdAndProductTypeIdAndWorkProcessId(
            String factoryId, String productTypeId, String workProcessId);

    List<ProductWorkProcess> findByFactoryIdAndWorkProcessId(String factoryId, String workProcessId);
}
