package com.cretas.aims.repository;

import com.cretas.aims.entity.SupplierImportReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierImportReceiptRepository extends JpaRepository<SupplierImportReceipt, String> {
    Optional<SupplierImportReceipt> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
}
