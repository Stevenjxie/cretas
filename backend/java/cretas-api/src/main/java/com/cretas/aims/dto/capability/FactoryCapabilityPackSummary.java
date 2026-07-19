package com.cretas.aims.dto.capability;

import com.cretas.aims.ai.capability.FactoryCapabilityPack;

import java.util.List;

/** Safe API projection: no prompt, tool list, tenant, user, examples or eval cases. */
public record FactoryCapabilityPackSummary(
        String packId,
        String version,
        String status,
        String digest,
        OutputSchemaSummary outputSchema,
        List<WorkflowReferenceSummary> workflowReferences) {

    public static FactoryCapabilityPackSummary from(FactoryCapabilityPack pack) {
        return new FactoryCapabilityPackSummary(
                pack.packId(),
                pack.version(),
                pack.status().name(),
                pack.digest(),
                new OutputSchemaSummary(
                        pack.outputSchema().schemaId(),
                        pack.outputSchema().fields().stream()
                                .map(field -> new OutputFieldSummary(
                                        field.name(), field.type().name(), field.required()))
                                .toList()),
                pack.workflowReferences().stream()
                        .map(reference -> new WorkflowReferenceSummary(
                                reference.referenceId(), reference.type().name(),
                                reference.mutation(), reference.approvalRequired()))
                        .toList());
    }

    public record OutputSchemaSummary(String schemaId, List<OutputFieldSummary> fields) {
        public OutputSchemaSummary {
            fields = List.copyOf(fields);
        }
    }

    public record OutputFieldSummary(String name, String type, boolean required) {}

    public record WorkflowReferenceSummary(
            String referenceId, String type, boolean mutation, boolean approvalRequired) {}
}
