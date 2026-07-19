package com.cretas.aims.ai.capability;

import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, config-only description of one factory AI capability pack. */
public record FactoryCapabilityPack(
        int schemaVersion,
        String packId,
        String version,
        PackStatus status,
        Set<FactoryType> businessTypes,
        Set<FactoryUserRole> roles,
        String instructions,
        Set<String> readToolAllowlist,
        List<WorkflowReference> workflowReferences,
        OutputSchema outputSchema,
        List<String> rules,
        List<String> forbiddenActions,
        List<String> matchTerms,
        List<FewShot> fewShots,
        List<EvalCase> evalCases,
        String digest,
        String resourcePath) {

    public FactoryCapabilityPack {
        businessTypes = Set.copyOf(Objects.requireNonNull(businessTypes, "businessTypes"));
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        readToolAllowlist = Set.copyOf(Objects.requireNonNull(readToolAllowlist, "readToolAllowlist"));
        workflowReferences = List.copyOf(Objects.requireNonNull(workflowReferences, "workflowReferences"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        forbiddenActions = List.copyOf(Objects.requireNonNull(forbiddenActions, "forbiddenActions"));
        matchTerms = List.copyOf(Objects.requireNonNull(matchTerms, "matchTerms"));
        fewShots = List.copyOf(Objects.requireNonNull(fewShots, "fewShots"));
        evalCases = List.copyOf(Objects.requireNonNull(evalCases, "evalCases"));
    }

    public enum PackStatus { DRAFT, PUBLISHED, RETIRED }

    public enum WorkflowReferenceType { FORM, INTENT, NAVIGATION }

    public enum OutputFieldType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY }

    public enum ResponseMode { READ_SUMMARY, WORKFLOW_GUIDANCE, NAVIGATION, NO_MATCH }

    public enum EvalOutcome { MATCH, NO_MATCH }

    public record WorkflowReference(
            String referenceId,
            WorkflowReferenceType type,
            boolean mutation,
            boolean approvalRequired) {}

    public record OutputSchema(String schemaId, List<OutputField> fields) {
        public OutputSchema {
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        }
    }

    public record OutputField(
            String name,
            OutputFieldType type,
            boolean required,
            String description) {}

    public record FewShot(
            String userQuery,
            ResponseMode expectedMode,
            List<String> expectedReadTools,
            String expectedWorkflowReference,
            String assistantResponse) {
        public FewShot {
            expectedReadTools = List.copyOf(
                    Objects.requireNonNull(expectedReadTools, "expectedReadTools"));
        }
    }

    public record EvalCase(
            String caseId,
            String query,
            ResponseMode expectedMode,
            List<String> expectedReadTools,
            String expectedWorkflowReference,
            EvalOutcome expectedOutcome) {
        public EvalCase {
            expectedReadTools = List.copyOf(
                    Objects.requireNonNull(expectedReadTools, "expectedReadTools"));
        }
    }
}
