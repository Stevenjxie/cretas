package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BomPriceAdjustmentProposalRepository extends JpaRepository<BomPriceAdjustmentProposal, Long> {

    Optional<BomPriceAdjustmentProposal> findByIdAndFactoryId(Long id, String factoryId);

    Page<BomPriceAdjustmentProposal> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, BomPriceAdjustmentProposal.Status status, Pageable pageable);

    Page<BomPriceAdjustmentProposal> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);
}
