package com.cretas.aims.service.workflow;

import java.util.List;

/** Deterministic material-topology classification derived from the canvas graph. */
public record WorkflowTopology(
        Type type,
        List<String> terminalOutputSkuIds,
        List<String> rootInputSkuIds) {

    public enum Type {
        SINGLE_OUTPUT_PRODUCT,
        RAW_MATERIAL_SPLIT,
        JOINT_PRODUCTION,
        INVALID
    }

    public boolean isSingleOutput() {
        return type == Type.SINGLE_OUTPUT_PRODUCT;
    }

    public boolean isMultiOutput() {
        return type == Type.RAW_MATERIAL_SPLIT || type == Type.JOINT_PRODUCTION;
    }
}
