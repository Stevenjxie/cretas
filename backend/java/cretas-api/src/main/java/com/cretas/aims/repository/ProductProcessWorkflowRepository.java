package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductProcessWorkflowRepository extends JpaRepository<ProductProcessWorkflow, Long> {

    Optional<ProductProcessWorkflow> findByIdAndFactoryId(Long id, String factoryId);

    Optional<ProductProcessWorkflow> findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflow.Status status);
}
