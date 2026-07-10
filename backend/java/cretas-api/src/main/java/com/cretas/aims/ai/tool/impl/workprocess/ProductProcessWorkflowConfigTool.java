package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Preview-only validator for AI generated product-process Workflow patches. */
@Component
public class ProductProcessWorkflowConfigTool extends AbstractTool {

    private static final String TOOL_NAME = "canvas_product_process_workflow_config";
    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "UPSERT_NODE", "REMOVE_NODE", "UPSERT_EDGE", "REMOVE_EDGE", "SET_NODE_FIELD");
    private static final Set<String> ALLOWED_FIELD_ROOTS = Set.of(
            "name", "skuId", "skuCode", "specification", "ports",
            "conversionRule", "reportingRequired");
    private static final Set<String> NODE_KINDS = Set.of(
            "RAW_MATERIAL", "PROCESS", "SEMI_FINISHED", "FINISHED_GOOD");
    private static final Set<String> MATERIAL_NODE_KINDS = Set.of(
            "RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD");
    private static final Set<String> CONVERSION_MODES = Set.of(
            "ACTUAL_WEIGHT", "FIXED_RATIO", "SUM_OUTPUTS", "FORMULA");
    private static final Set<String> MATERIAL_DATA_KEYS = Set.of(
            "name", "skuId", "skuCode", "specification", "baseUnit", "bound");
    private static final Set<String> PROCESS_DATA_KEYS = Set.of(
            "workProcessId", "processName", "processCategory", "inputUnit", "outputUnit",
            "standardTime", "ports", "conversionRule", "reportingRequired",
            "allowMultipleUpstreamSources", "allowFinishedGoodsSource");
    private static final Set<String> PORT_KEYS = Set.of(
            "id", "direction", "materialNodeId", "materialName", "skuId",
            "materialKind", "unit", "ordinal");
    private static final Pattern SAFE_FIELD_PATH = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)*$");
    private final ProductProcessWorkflowValidator workflowValidator;

    public ProductProcessWorkflowConfigTool(
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator workflowValidator) {
        this.objectMapper = objectMapper;
        this.workflowValidator = workflowValidator;
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Validates and previews local product-process WorkflowPatch objects without business execution";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of("type", "string"),
                        "definition", Map.of("type", "object"),
                        "selectedNodeId", Map.of("type", List.of("string", "null")),
                        "patches", Map.of("type", "array", "items", Map.of("type", "object"))),
                "required", List.of("definition", "patches"));
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.GENERATE;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("product-process", "workflow", "configuration");
    }

    @Override
    public String preview(ToolCall toolCall, Map<String, Object> context) {
        try {
            Map<String, Object> arguments = parseArguments(toolCall);
            if (!(arguments.get("definition") instanceof Map<?, ?> definition)) {
                return buildSemanticError(
                        "WORKFLOW_DEFINITION_REQUIRED", "Workflow definition is required for preview");
            }
            List<Map<String, Object>> patches = sanitizePatches(arguments.get("patches"));
            ProductProcessWorkflowDTO candidate = objectMapper.convertValue(
                    definition, ProductProcessWorkflowDTO.class);
            applyCandidateBatch(candidate, patches);
            workflowValidator.validateForDraft(candidate);
            validateGraphSemantics(candidate);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PREVIEW");
            data.put("applied", false);
            data.put("patches", patches);
            return buildSuccessResult(data);
        } catch (PatchRejectedException | BusinessException | IllegalArgumentException error) {
            return buildSemanticError("WORKFLOW_PATCH_REJECTED", "Workflow patch batch rejected");
        }
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("errorCode", "WORKFLOW_AI_PREVIEW_ONLY");
            error.put("error", "Workflow AI can only generate a local preview patch");
            return objectMapper.writeValueAsString(error);
        } catch (Exception serializationError) {
            return "{\"success\":false,\"errorCode\":\"WORKFLOW_AI_PREVIEW_ONLY\","
                    + "\"error\":\"Workflow AI preview only\"}";
        }
    }

    private List<Map<String, Object>> sanitizePatches(Object rawPatches) {
        if (!(rawPatches instanceof List<?> patches) || patches.isEmpty()) {
            throw new PatchRejectedException();
        }
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object rawPatch : patches) {
            if (!(rawPatch instanceof Map<?, ?> patch)) {
                throw new PatchRejectedException();
            }
            Map<String, Object> sanitized = sanitizePatch(patch);
            if (sanitized == null) throw new PatchRejectedException();
            accepted.add(sanitized);
        }
        return List.copyOf(accepted);
    }

    private Map<String, Object> sanitizePatch(Map<?, ?> patch) {
        String operation = readNonBlankString(patch.get("op"));
        if (operation == null || !ALLOWED_OPERATIONS.contains(operation)) return null;
        return switch (operation) {
            case "UPSERT_NODE" -> sanitizeNodePatch(patch);
            case "REMOVE_NODE" -> sanitizeIdPatch(patch, "nodeId");
            case "UPSERT_EDGE" -> sanitizeEdgePatch(patch);
            case "REMOVE_EDGE" -> sanitizeIdPatch(patch, "edgeId");
            case "SET_NODE_FIELD" -> sanitizeFieldPatch(patch);
            default -> null;
        };
    }

    private Map<String, Object> sanitizeNodePatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "node")) || !(patch.get("node") instanceof Map<?, ?> node)) {
            return null;
        }
        Map<String, Object> sanitizedNode = sanitizeNode(node);
        return sanitizedNode == null ? null : linkedMap("op", "UPSERT_NODE", "node", sanitizedNode);
    }

    private Map<String, Object> sanitizeNode(Map<?, ?> node) {
        if (!hasExactKeys(node, Set.of("id", "kind", "position", "data"))) return null;
        String id = readNonBlankString(node.get("id"));
        String kind = readNonBlankString(node.get("kind"));
        if (id == null || kind == null || !NODE_KINDS.contains(kind)
                || !(node.get("position") instanceof Map<?, ?> position)
                || !(node.get("data") instanceof Map<?, ?> data)) return null;
        Map<String, Object> sanitizedPosition = sanitizePosition(position);
        Map<String, Object> sanitizedData = "PROCESS".equals(kind)
                ? sanitizeProcessData(data) : sanitizeMaterialData(data);
        if (sanitizedPosition == null || sanitizedData == null) return null;
        return linkedMap("id", id, "kind", kind, "position", sanitizedPosition, "data", sanitizedData);
    }

    private Map<String, Object> sanitizePosition(Map<?, ?> position) {
        if (!hasExactKeys(position, Set.of("x", "y"))
                || !isFiniteNumber(position.get("x")) || !isFiniteNumber(position.get("y"))) return null;
        return linkedMap("x", position.get("x"), "y", position.get("y"));
    }

    private Map<String, Object> sanitizeMaterialData(Map<?, ?> data) {
        if (!hasAllowedKeys(data, MATERIAL_DATA_KEYS)
                || readNonBlankString(data.get("name")) == null
                || !(data.get("skuId") instanceof String)
                || !isOptionalNullableString(data, "skuCode")
                || !isOptionalNullableString(data, "specification")
                || !isOptionalString(data, "baseUnit")
                || !isOptionalBoolean(data, "bound")) return null;
        return copyKnownValues(data, MATERIAL_DATA_KEYS);
    }

    private Map<String, Object> sanitizeProcessData(Map<?, ?> data) {
        if (!hasAllowedKeys(data, PROCESS_DATA_KEYS)
                || readNonBlankString(data.get("workProcessId")) == null
                || readNonBlankString(data.get("processName")) == null
                || readNonBlankString(data.get("inputUnit")) == null
                || readNonBlankString(data.get("outputUnit")) == null
                || !(data.get("reportingRequired") instanceof Boolean)
                || !(data.get("ports") instanceof List<?> ports)
                || !(data.get("conversionRule") instanceof Map<?, ?> conversionRule)
                || !isOptionalNullableString(data, "processCategory")
                || !isOptionalFiniteNumber(data, "standardTime")
                || !isOptionalBoolean(data, "allowMultipleUpstreamSources")
                || !isOptionalBoolean(data, "allowFinishedGoodsSource")) return null;
        List<Map<String, Object>> sanitizedPorts = sanitizePorts(ports);
        Map<String, Object> sanitizedRule = sanitizeConversionRule(conversionRule);
        if (sanitizedPorts == null || sanitizedRule == null) return null;
        Map<String, Object> result = copyKnownValues(data, PROCESS_DATA_KEYS);
        result.put("ports", sanitizedPorts);
        result.put("conversionRule", sanitizedRule);
        return result;
    }

    private List<Map<String, Object>> sanitizePorts(List<?> ports) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawPort : ports) {
            if (!(rawPort instanceof Map<?, ?> port) || !hasAllowedKeys(port, PORT_KEYS)) return null;
            String id = readNonBlankString(port.get("id"));
            String direction = readNonBlankString(port.get("direction"));
            String unit = readNonBlankString(port.get("unit"));
            if (id == null || !("INPUT".equals(direction) || "OUTPUT".equals(direction))
                    || unit == null || !(port.get("ordinal") instanceof Number ordinal)
                    || !isFiniteNumber(ordinal)
                    || ordinal.doubleValue() < 0 || ordinal.doubleValue() != Math.rint(ordinal.doubleValue())
                    || !isOptionalString(port, "materialNodeId")
                    || !isOptionalString(port, "materialName")
                    || !isOptionalString(port, "skuId")) return null;
            Object materialKind = port.get("materialKind");
            if (materialKind != null && (!(materialKind instanceof String kind)
                    || !MATERIAL_NODE_KINDS.contains(kind))) return null;
            result.add(copyKnownValues(port, PORT_KEYS));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> sanitizeConversionRule(Map<?, ?> rule) {
        if (!hasAllowedKeys(rule, Set.of("mode", "expression"))) return null;
        String mode = readNonBlankString(rule.get("mode"));
        if (mode == null || !CONVERSION_MODES.contains(mode)) return null;
        Object expression = rule.get("expression");
        if (expression != null && !(expression instanceof String)) return null;
        return copyKnownValues(rule, Set.of("mode", "expression"));
    }

    private Map<String, Object> sanitizeEdgePatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "edge")) || !(patch.get("edge") instanceof Map<?, ?> edge)
                || !hasExactKeys(edge, Set.of("id", "source", "sourceHandle", "target", "targetHandle"))) return null;
        Map<String, Object> sanitizedEdge = new LinkedHashMap<>();
        for (String key : List.of("id", "source", "sourceHandle", "target", "targetHandle")) {
            String value = readNonBlankString(edge.get(key));
            if (value == null) return null;
            sanitizedEdge.put(key, value);
        }
        return linkedMap("op", "UPSERT_EDGE", "edge", sanitizedEdge);
    }

    private Map<String, Object> sanitizeIdPatch(Map<?, ?> patch, String idKey) {
        if (!hasExactKeys(patch, Set.of("op", idKey))) return null;
        String id = readNonBlankString(patch.get(idKey));
        return id == null ? null : linkedMap("op", patch.get("op"), idKey, id);
    }

    private Map<String, Object> sanitizeFieldPatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "nodeId", "path", "value"))) return null;
        String nodeId = readNonBlankString(patch.get("nodeId"));
        String path = readNonBlankString(patch.get("path"));
        if (nodeId == null || !isAllowedFieldPath(path) || !isAllowedFieldValue(path, patch.get("value"))) return null;
        return linkedMap("op", "SET_NODE_FIELD", "nodeId", nodeId, "path", path, "value", patch.get("value"));
    }

    private boolean isAllowedFieldPath(String path) {
        if (path == null || !SAFE_FIELD_PATH.matcher(path).matches()) return false;
        String root = path.split("\\.", 2)[0];
        if (!ALLOWED_FIELD_ROOTS.contains(root)) return false;
        return switch (root) {
            case "conversionRule" -> Set.of("conversionRule", "conversionRule.mode", "conversionRule.expression").contains(path);
            case "ports" -> "ports".equals(path);
            default -> !path.contains(".");
        };
    }

    private boolean isAllowedFieldValue(String path, Object value) {
        return switch (path) {
            case "name" -> readNonBlankString(value) != null;
            case "skuId" -> value instanceof String;
            case "skuCode", "specification", "conversionRule.expression" ->
                    value == null || value instanceof String;
            case "reportingRequired" -> value instanceof Boolean;
            case "conversionRule.mode" -> value instanceof String mode && CONVERSION_MODES.contains(mode);
            case "conversionRule" -> value instanceof Map<?, ?> rule && sanitizeConversionRule(rule) != null;
            case "ports" -> value instanceof List<?> ports && sanitizePorts(ports) != null;
            default -> false;
        };
    }

    private boolean hasExactKeys(Map<?, ?> value, Set<String> keys) {
        return value.size() == keys.size() && hasAllowedKeys(value, keys);
    }

    private boolean hasAllowedKeys(Map<?, ?> value, Set<String> keys) {
        return value.keySet().stream().allMatch(key -> key instanceof String && keys.contains(key));
    }

    private Map<String, Object> copyKnownValues(Map<?, ?> source, Set<String> allowedKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowedKeys) if (source.containsKey(key)) result.put(key, source.get(key));
        return result;
    }

    private String readNonBlankString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private boolean isFiniteNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private boolean isOptionalString(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) instanceof String;
    }

    private boolean isOptionalNullableString(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) == null || value.get(key) instanceof String;
    }

    private boolean isOptionalBoolean(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) instanceof Boolean;
    }

    private boolean isOptionalFiniteNumber(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) == null || isFiniteNumber(value.get(key));
    }

    private void applyCandidateBatch(
            ProductProcessWorkflowDTO candidate,
            List<Map<String, Object>> patches) {
        patches.stream()
                .filter(patch -> Set.of("UPSERT_NODE", "REMOVE_NODE").contains(patch.get("op")))
                .forEach(patch -> applyNodePatch(candidate, patch));
        patches.stream()
                .filter(patch -> "SET_NODE_FIELD".equals(patch.get("op")))
                .forEach(patch -> applyFieldPatch(candidate, patch));
        patches.stream()
                .filter(patch -> Set.of("UPSERT_EDGE", "REMOVE_EDGE").contains(patch.get("op")))
                .forEach(patch -> applyEdgePatch(candidate, patch));
    }

    private void applyNodePatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        if ("REMOVE_NODE".equals(patch.get("op"))) {
            String nodeId = String.valueOf(patch.get("nodeId"));
            boolean removed = candidate.getNodes().removeIf(node -> nodeId.equals(node.getId()));
            if (!removed) throw new PatchRejectedException();
            candidate.getEdges().removeIf(edge ->
                    nodeId.equals(edge.getSource()) || nodeId.equals(edge.getTarget()));
            return;
        }
        ProductProcessWorkflowDTO.Node next = objectMapper.convertValue(
                patch.get("node"), ProductProcessWorkflowDTO.Node.class);
        ProductProcessWorkflowDTO.Node existing = findNode(candidate, next.getId());
        if (existing != null && !Objects.equals(existing.getKind(), next.getKind())) {
            throw new PatchRejectedException();
        }
        if (existing == null) candidate.getNodes().add(next);
        else candidate.getNodes().set(candidate.getNodes().indexOf(existing), next);
    }

    private void applyFieldPatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        ProductProcessWorkflowDTO.Node target = findNode(candidate, String.valueOf(patch.get("nodeId")));
        if (target == null) throw new PatchRejectedException();
        String path = String.valueOf(patch.get("path"));
        if (!isPathCompatibleWithNodeKind(target.getKind(), path)) {
            throw new PatchRejectedException();
        }
        Map<String, Object> data = target.getData();
        if (data == null) {
            data = new LinkedHashMap<>();
            target.setData(data);
        }
        Object value = patch.get("value");
        if (path.startsWith("conversionRule.")) {
            Object existingRule = data.get("conversionRule");
            Map<String, Object> rule = existingRule instanceof Map<?, ?> source
                    ? copyStringObjectMap(source)
                    : new LinkedHashMap<>();
            rule.put(path.substring("conversionRule.".length()), value);
            data.put("conversionRule", rule);
        } else {
            data.put(path, value);
        }
    }

    private void applyEdgePatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        if ("REMOVE_EDGE".equals(patch.get("op"))) {
            String edgeId = String.valueOf(patch.get("edgeId"));
            if (!candidate.getEdges().removeIf(edge -> edgeId.equals(edge.getId()))) {
                throw new PatchRejectedException();
            }
            return;
        }
        ProductProcessWorkflowDTO.Edge next = objectMapper.convertValue(
                patch.get("edge"), ProductProcessWorkflowDTO.Edge.class);
        ProductProcessWorkflowDTO.Edge existing = candidate.getEdges().stream()
                .filter(edge -> next.getId().equals(edge.getId()))
                .findFirst()
                .orElse(null);
        if (existing == null) candidate.getEdges().add(next);
        else candidate.getEdges().set(candidate.getEdges().indexOf(existing), next);
    }

    private boolean isPathCompatibleWithNodeKind(String kind, String path) {
        if ("PROCESS".equals(kind)) {
            return path.equals("ports")
                    || path.equals("conversionRule")
                    || path.startsWith("conversionRule.")
                    || path.equals("reportingRequired");
        }
        return MATERIAL_NODE_KINDS.contains(kind)
                && Set.of("name", "skuId", "skuCode", "specification").contains(path);
    }

    private void validateGraphSemantics(ProductProcessWorkflowDTO definition) {
        Map<String, ProductProcessWorkflowDTO.Node> nodes = new HashMap<>();
        definition.getNodes().forEach(node -> nodes.put(node.getId(), node));
        Map<String, Map<String, Map<?, ?>>> portsByProcess = new HashMap<>();

        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!"PROCESS".equals(node.getKind())) continue;
            Object rawPorts = node.getData() == null ? null : node.getData().get("ports");
            if (!(rawPorts instanceof List<?> ports)) throw new PatchRejectedException();
            Map<String, Map<?, ?>> portById = new HashMap<>();
            for (Object rawPort : ports) {
                if (!(rawPort instanceof Map<?, ?> port)) throw new PatchRejectedException();
                String portId = readNonBlankString(port.get("id"));
                String direction = readNonBlankString(port.get("direction"));
                String materialNodeId = readNonBlankString(port.get("materialNodeId"));
                ProductProcessWorkflowDTO.Node material = nodes.get(materialNodeId);
                if (portId == null || !("INPUT".equals(direction) || "OUTPUT".equals(direction))
                        || material == null || "PROCESS".equals(material.getKind())
                        || portById.put(portId, port) != null) {
                    throw new PatchRejectedException();
                }
                Object materialKind = port.get("materialKind");
                if (materialKind != null && !material.getKind().equals(materialKind)) {
                    throw new PatchRejectedException();
                }
            }
            portsByProcess.put(node.getId(), portById);
        }

        Set<String> matchedPorts = new HashSet<>();
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            ProductProcessWorkflowDTO.Node source = nodes.get(edge.getSource());
            ProductProcessWorkflowDTO.Node target = nodes.get(edge.getTarget());
            if (source == null || target == null || source.getId().equals(target.getId())) {
                throw new PatchRejectedException();
            }
            if ("PROCESS".equals(source.getKind()) && !"PROCESS".equals(target.getKind())) {
                Map<?, ?> port = portsByProcess.getOrDefault(source.getId(), Map.of())
                        .get(edge.getSourceHandle());
                if (port == null || !"OUTPUT".equals(port.get("direction"))
                        || !target.getId().equals(port.get("materialNodeId"))
                        || !"input".equals(edge.getTargetHandle())) {
                    throw new PatchRejectedException();
                }
                matchedPorts.add(source.getId() + "::" + edge.getSourceHandle());
            } else if (!"PROCESS".equals(source.getKind()) && "PROCESS".equals(target.getKind())) {
                Map<?, ?> port = portsByProcess.getOrDefault(target.getId(), Map.of())
                        .get(edge.getTargetHandle());
                if (port == null || !"INPUT".equals(port.get("direction"))
                        || !source.getId().equals(port.get("materialNodeId"))
                        || !"output".equals(edge.getSourceHandle())) {
                    throw new PatchRejectedException();
                }
                matchedPorts.add(target.getId() + "::" + edge.getTargetHandle());
            } else {
                throw new PatchRejectedException();
            }
        }

        portsByProcess.forEach((processId, ports) -> ports.forEach((portId, port) -> {
            if (port.get("materialNodeId") != null
                    && !matchedPorts.contains(processId + "::" + portId)) {
                throw new PatchRejectedException();
            }
        }));
    }

    private ProductProcessWorkflowDTO.Node findNode(
            ProductProcessWorkflowDTO definition,
            String nodeId) {
        return definition.getNodes().stream()
                .filter(node -> nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> copyStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) result.put(stringKey, value);
        });
        return result;
    }

    private String buildSemanticError(String errorCode, String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("errorCode", errorCode);
            error.put("error", message);
            return objectMapper.writeValueAsString(error);
        } catch (Exception serializationError) {
            return "{\"success\":false,\"errorCode\":\"WORKFLOW_PATCH_REJECTED\","
                    + "\"error\":\"Workflow patch batch rejected\"}";
        }
    }

    private static final class PatchRejectedException extends RuntimeException {
    }

    private Map<String, Object> linkedMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return result;
    }
}
