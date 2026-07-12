package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductProcessWorkflowActivationRepository
        extends JpaRepository<ProductProcessWorkflowActivation, Long> {

    Optional<ProductProcessWorkflowActivation> findByFactoryIdAndProductTypeId(
            String factoryId,
            String productTypeId);
}
