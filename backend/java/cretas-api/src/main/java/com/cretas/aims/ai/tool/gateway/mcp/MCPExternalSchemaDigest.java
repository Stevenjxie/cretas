package com.cretas.aims.ai.tool.gateway.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Canonical SHA-256 for untrusted MCP input schemas. */
public final class MCPExternalSchemaDigest {

    private MCPExternalSchemaDigest() {
    }

    public static String sha256(Map<String, Object> schema, ObjectMapper objectMapper) {
        if (schema == null) {
            throw new IllegalArgumentException("inputSchema must be a JSON object");
        }
        JsonNode source = objectMapper.valueToTree(schema);
        if (!source.isObject()) {
            throw new IllegalArgumentException("inputSchema must be a JSON object");
        }
        JsonNode canonical = canonicalize(source, objectMapper);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to calculate MCP schema digest", exception);
        }
    }

    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null
                || !expectedHex.matches("[0-9a-f]{64}")
                || !actualHex.matches("[0-9a-f]{64}")) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                actualHex.getBytes(StandardCharsets.US_ASCII));
    }

    private static JsonNode canonicalize(JsonNode node, ObjectMapper objectMapper) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            fields.forEach(entry -> result.set(
                    entry.getKey(), canonicalize(entry.getValue(), objectMapper)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(element -> result.add(canonicalize(element, objectMapper)));
            return result;
        }
        return node.deepCopy();
    }
}
