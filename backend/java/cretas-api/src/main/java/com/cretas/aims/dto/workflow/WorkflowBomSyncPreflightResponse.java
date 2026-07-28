package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowBomSyncPreflightResponse {

    public enum Classification {
        READY,
        AUTO_MIGRATABLE,
        USER_INPUT_REQUIRED,
        CONFLICT
    }

    private Classification classification;
    private Integer activeBomVersion;
    private Integer syncDraftVersion;
    private Long activeBomWorkflowRevisionId;
    private Long targetWorkflowRevisionId;
    @Builder.Default
    private List<String> preservedItems = new ArrayList<>();
    @Builder.Default
    private List<AutomaticMapping> automaticMappings = new ArrayList<>();
    @Builder.Default
    private List<SyncIssue> missingItems = new ArrayList<>();
    @Builder.Default
    private List<SyncIssue> conflicts = new ArrayList<>();
    private boolean canCompleteAutomatically;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutomaticMapping {
        private String materialTypeId;
        private String materialName;
        private String fromNodeId;
        private String toNodeId;
        private String toProcessNodeId;
        private String toInputPortId;
        private String toEdgeId;
        private String ownerRecipeId;
        private String costScope;
        private String costScopeKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncIssue {
        private String code;
        private String materialTypeId;
        private String materialName;
        private String processNodeId;
        private String field;
        private String message;
        private String action;
    }
}
