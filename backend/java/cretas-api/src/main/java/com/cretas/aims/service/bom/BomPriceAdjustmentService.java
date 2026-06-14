package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface BomPriceAdjustmentService {

    List<BomPriceAdjustmentProposal> generateFromReceive(String factoryId, PurchaseReceiveRecord receiveRecord);

    List<BomPriceAdjustmentProposal> generateForMaterial(
            String factoryId,
            String materialTypeId,
            String materialName,
            BigDecimal latestPreTaxPrice,
            BomPriceAdjustmentProposal.SourceType sourceType,
            String sourceReceiveRecordId,
            Long sourceReceiveItemId);

    BomPriceAdjustmentProposal approve(String factoryId, Long proposalId, Long approverId, String comment);

    Page<BomPriceAdjustmentProposal> list(
            String factoryId, BomPriceAdjustmentProposal.Status status, Pageable pageable);
}
