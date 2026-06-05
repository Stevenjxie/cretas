package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.SupplierMonthlyReconciliation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface SupplierMonthlyReconciliationRepository
        extends JpaRepository<SupplierMonthlyReconciliation, String> {

    Optional<SupplierMonthlyReconciliation> findByIdAndFactoryId(String id, String factoryId);

    Optional<SupplierMonthlyReconciliation>
    findByFactoryIdAndSupplierIdAndReconciliationMonthAndDeletedAtIsNull(
            String factoryId, String supplierId, LocalDate reconciliationMonth);

    Page<SupplierMonthlyReconciliation> findByFactoryIdOrderByReconciliationMonthDesc(
            String factoryId, Pageable pageable);

    Page<SupplierMonthlyReconciliation> findByFactoryIdAndSupplierIdOrderByReconciliationMonthDesc(
            String factoryId, String supplierId, Pageable pageable);
}
