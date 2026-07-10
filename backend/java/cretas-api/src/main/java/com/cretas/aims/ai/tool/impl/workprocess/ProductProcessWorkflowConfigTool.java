package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public ProductProcessWorkflowConfigTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
                "required", List.of("patches"));
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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PREVIEW");
            data.put("applied", false);
            data.put("patches", sanitizePatches(arguments.get("patches")));
            return buildSuccessResult(data);
        } catch (IllegalArgumentException error) {
            return buildErrorResult("Workflow patch format is invalid");
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
        if (!(rawPatches instanceof List<?> patches)) {
            return List.of();
        }
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object rawPatch : patches) {
            if (rawPatch instanceof Map<?, ?> patch) {
                Map<String, Object> sanitized = sanitizePatch(patch);
                if (sanitized != null) accepted.add(sanitized);
            }
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
                || !isOptionalString(data, "skuCode")
                || !isOptionalString(data, "specification")
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
            case "name", "skuId" -> value instanceof String;
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

    private Map<String, Object> linkedMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return result;
    }
}
