package com.cretas.aims.dto.config;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkflowTransitionDTO {
    private String from;
    private String to;
    private String action;
    private String label;
    private String buttonType;
    private boolean enabled;
    private String condition;
    private List<String> allowedRoles;
    /**
     * R40 BUG-5 fix: when false, transition exists in state machine but is auto-triggered
     * by an upstream event (e.g., 创建生产计划 → SO 自动 PROCESSING) — FE should NOT render
     * a manual button. Default true (backward compatible).
     */
    @Builder.Default
    private boolean manualTrigger = true;
}
