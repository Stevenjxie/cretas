package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workprocess.WorkProcessTask;

import java.math.BigDecimal;

/**
 * Authoritative WIP inventory operations shared by reporting entry points.
 */
public interface WipInventoryService {

    SemiFinishedInventory validateSourceWip(String sourceWipNo, BigDecimal inputQuantity, String inputUnit);

    void postApprovedOutput(String factoryId, ProductionReport report, WorkProcessTask task, Long operatorId);
}
