package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Shared read/write truth for the Workflow-first SKU → BOM → production gate. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductConfigurationCompletenessReport {

    private String factoryId;
    private String productTypeId;
    private String stage;
    private String bomState;
    private Long workflowDraftId;
    private Integer workflowDraftVersion;
    private String bomRecipeId;
    private Integer bomVersion;
    private boolean workflowDraftComplete;
    private boolean bomConfigurable;
    private boolean bomComplete;
    private boolean bomActive;
    private boolean workflowEnabled;
    private boolean workflowPublishAllowed;
    private boolean workflowEnableAllowed;
    private boolean productionPlanAllowed;
    @Builder.Default
    private List<Issue> issues = new ArrayList<>();
    @Builder.Default
    private List<ProcessAuxiliaryStatus> processAuxiliaryStatuses = new ArrayList<>();
    @Builder.Default
    private List<PackagingLevelStatus> packagingLevels = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        private String code;
        private String message;
        private String target;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessAuxiliaryStatus {
        private String workflowProcessNodeId;
        private String workProcessId;
        private String processName;
        private String auxiliaryPolicy;
        private long bindingCount;
        private boolean complete;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackagingLevelStatus {
        private String packagingSpecId;
        private String name;
        private String packageUnit;
        private String baseUnit;
        private long materialCount;
        private boolean complete;
    }
}
