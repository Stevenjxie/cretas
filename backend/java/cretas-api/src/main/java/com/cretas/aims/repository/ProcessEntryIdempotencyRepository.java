package com.cretas.aims.repository;

import com.cretas.aims.entity.ProcessEntryIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * SP-B1 幂等表 repository.
 */
public interface ProcessEntryIdempotencyRepository extends JpaRepository<ProcessEntryIdempotency, Long> {

    Optional<ProcessEntryIdempotency> findByFactoryIdAndPlanIdAndIdempotencyKey(
            String factoryId, String planId, String idempotencyKey);
}
