package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workprocess.WorkProcessTask;

import java.math.BigDecimal;

/**
 * Authoritative WIP inventory operations shared by reporting entry points.
 */
public interface WipInventoryService {

    default SemiFinishedInventory validateSourceWip(String sourceWipNo, BigDecimal inputQuantity, String inputUnit) {
        return validateSourceWip(null, sourceWipNo, inputQuantity, inputUnit, null);
    }

    SemiFinishedInventory validateSourceWip(
            String factoryId, String sourceWipNo, BigDecimal inputQuantity, String inputUnit, Long excludeReportId);

    void postApprovedOutput(String factoryId, ProductionReport report, WorkProcessTask task, Long operatorId);

    /**
     * SP1 T4 — Returns all WorkProcessTasks for {@code batchId} whose parent
     * WorkProcess has {@code semiFinishedOutputCode} configured.
     *
     * <p>Used by the RN report screen to populate the "semi output code" dropdown.
     *
     * @param factoryId factory scope
     * @param batchId   production batch ID
     * @return response object containing the list of output options (may be empty)
     */
    OutputOptionsResponse getOutputOptions(String factoryId, Long batchId);
}
