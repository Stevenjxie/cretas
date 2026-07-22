package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.OaActionIdempotencyLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OaActionIdempotencyLedgerRepository
        extends JpaRepository<OaActionIdempotencyLedger, String> {

    Optional<OaActionIdempotencyLedger> findByFactoryIdAndInstanceIdAndIdempotencyKey(
            String factoryId, String instanceId, String idempotencyKey);
}
