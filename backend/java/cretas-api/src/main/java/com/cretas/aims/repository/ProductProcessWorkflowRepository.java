package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowRepository extends JpaRepository<ProductProcessWorkflow, Long> {

    Optional<ProductProcessWorkflow> findByIdAndFactoryId(Long id, String factoryId);

    /** raw-centric 多成品解析: 批量取 workflow (多租户防御, @Where 软删过滤自动生效)。 */
    List<ProductProcessWorkflow> findByIdInAndFactoryId(Collection<Long> ids, String factoryId);

    Optional<ProductProcessWorkflow> findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflow.Status status);

    List<ProductProcessWorkflow> findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(
            String factoryId,
            String productTypeId);

    Optional<ProductProcessWorkflow> findFirstByFactoryIdAndProductTypeIdAndDefinitionVersion(
            String factoryId,
            String productTypeId,
            Integer definitionVersion);
}
