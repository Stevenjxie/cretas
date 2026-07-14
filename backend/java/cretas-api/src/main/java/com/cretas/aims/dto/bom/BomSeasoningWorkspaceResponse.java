package com.cretas.aims.dto.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class BomSeasoningWorkspaceResponse {
    private String recipeId;
    private String productTypeId;
    private String productName;
    private BomRecipe.Status status;
    private boolean editable;
    private Long seasoningRevision;
    private List<ProcessView> processes = new ArrayList<>();
    private List<MaterialSummary> materialSummaries = new ArrayList<>();
    private List<Anomaly> anomalies = new ArrayList<>();

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ProcessView {
        private String workProcessId;
        private String processName;
        private String processCategory;
        private Integer processOrder;
        private List<BomSeasoningItem> bindings;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MaterialSummary {
        private String materialTypeId;
        private String materialCode;
        private String materialName;
        private String category;
        private String unit;
        private BigDecimal priceSnapshot;
        private List<ProcessUsage> processUsages;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ProcessUsage {
        private String workProcessId;
        private String processName;
        private BigDecimal dosagePerKgG;
        private BigDecimal subsequentPotRatio;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Anomaly {
        private String code;
        private String message;
        private Long bindingId;
    }
}
