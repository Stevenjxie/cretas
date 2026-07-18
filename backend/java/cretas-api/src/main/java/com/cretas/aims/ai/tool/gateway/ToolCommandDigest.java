package com.cretas.aims.ai.tool.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stable SHA-256 digests for confirmation-bound tool commands.
 *
 * <p>Object keys are sorted recursively while array order and Jackson scalar node types are
 * preserved. The command envelope includes every trusted execution binding; parameters are never
 * allowed to provide or override those values.</p>
 */
public final class ToolCommandDigest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolCommandDigest() {
    }

    public static String parametersHash(JsonNode parameters) {
        requireObject(parameters);
        return sha256Hex(canonicalBytes(parameters));
    }

    public static String commandDigest(String factoryId,
                                       Long userId,
                                       String toolName,
                                       String descriptorVersion,
                                       ToolExecutionMode mode,
                                       JsonNode parameters) {
        requireNonBlank(factoryId, "factoryId");
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        requireNonBlank(toolName, "toolName");
        requireNonBlank(descriptorVersion, "descriptorVersion");
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        requireObject(parameters);

        ObjectNode envelope = OBJECT_MAPPER.createObjectNode();
        envelope.put("factoryId", factoryId);
        envelope.put("userId", userId);
        envelope.put("toolName", toolName);
        envelope.put("descriptorVersion", descriptorVersion);
        envelope.put("mode", mode.name());
        envelope.set("parameters", parameters.deepCopy());
        return sha256Hex(canonicalBytes(envelope));
    }

    /** A non-secret identifier suitable for logs; never log the bearer token itself. */
    public static String tokenFingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "missing";
        }
        return sha256Hex(token.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    static byte[] canonicalBytes(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(canonicalize(node));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to canonicalize JSON", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = OBJECT_MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = OBJECT_MAPPER.createArrayNode();
            node.forEach(child -> ordered.add(canonicalize(child)));
            return ordered;
        }
        return node.deepCopy();
    }

    private static void requireObject(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            throw new IllegalArgumentException("parameters must be a JSON object");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
