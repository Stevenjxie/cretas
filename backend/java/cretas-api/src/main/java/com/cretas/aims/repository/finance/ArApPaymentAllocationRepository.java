package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.finance.ArApPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArApPaymentAllocationRepository extends JpaRepository<ArApPaymentAllocation, String> {

    boolean existsByFactoryIdAndPaymentTransactionId(String factoryId, String paymentTransactionId);

    Optional<ArApPaymentAllocation> findByFactoryIdAndPaymentTransactionIdAndPayableTransactionId(
            String factoryId, String paymentTransactionId, String payableTransactionId);

    List<ArApPaymentAllocation> findByFactoryIdAndPayableTransactionIdOrderByCreatedAtAsc(
            String factoryId, String payableTransactionId);
}
