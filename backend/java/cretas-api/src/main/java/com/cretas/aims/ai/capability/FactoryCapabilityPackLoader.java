package com.cretas.aims.ai.capability;

import com.cretas.aims.ai.capability.FactoryCapabilityPack.EvalCase;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.EvalOutcome;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.FewShot;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.OutputField;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.OutputFieldType;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.OutputSchema;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.PackStatus;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.ResponseMode;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.WorkflowReference;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.WorkflowReferenceType;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventory;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryEntry;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict SafeConstructor loader. It validates configuration and never executes a tool. */
public final class FactoryCapabilityPackLoader {
    static final int YAML_CODE_POINT_LIMIT = 65_536;
    private static final int MAX_LIST = 24;
    private static final int MAX_TEXT = 4_096;
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,7}$");
    private static final Pattern FIELD = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern CASE_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern YAML_ALIAS_OR_ANCHOR = Pattern.compile(
            "(?m)(?:^|[\\s\\[{,])(?:&|\\*)[A-Za-z0-9_-]+");
    private static final Pattern MUTATION_LIKE_TOOL = Pattern.compile(
            "(?:^|_)(?:create|update|delete|execute|submit|start|complete|pause|resume|cancel|"
                    + "assign|approve|reject|acknowledge|resolve|adjust|reserve|release|use|consume|"
                    + "outbound|inbound|import|toggle|mark)(?:_|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "packId", "version", "status", "businessTypes", "roles",
            "instructions", "readToolAllowlist", "workflowReferences", "outputSchema",
            "rules", "forbiddenActions", "matchTerms", "fewShots", "evalCases");
    private static final Set<String> WORKFLOW_KEYS = Set.of(
            "referenceId", "type", "mutation", "approvalRequired");
    private static final Set<String> OUTPUT_SCHEMA_KEYS = Set.of("schemaId", "fields");
    private static final Set<String> OUTPUT_FIELD_KEYS = Set.of(
            "name", "type", "required", "description");
    private static final Set<String> FEW_SHOT_KEYS = Set.of(
            "userQuery", "expectedMode", "expectedReadTools",
            "expectedWorkflowReference", "assistantResponse");
    private static final Set<String> EVAL_KEYS = Set.of(
            "caseId", "query", "expectedMode", "expectedReadTools",
            "expectedWorkflowReference", "expectedOutcome");
    private static final Set<FactoryType> ALLOWED_BUSINESS_TYPES =
            Set.of(FactoryType.FACTORY, FactoryType.CENTRAL_KITCHEN);

    private final ClassLoader classLoader;

    public FactoryCapabilityPackLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    FactoryCapabilityPackLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public FactoryCapabilityPack loadResource(
            String resourcePath, ToolDescriptorInventory inventory) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("capability pack resource not found: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return load(reader, resourcePath, inventory);
        } catch (IOException error) {
            throw new IllegalStateException("failed to close capability pack resource", error);
        }
    }

    public FactoryCapabilityPack load(
            Reader reader, String resourcePath, ToolDescriptorInventory inventory) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(inventory, "inventory");
        String source = readBounded(reader);
        String normalizedSource = source.replace("\r\n", "\n").replace('\r', '\n');
        if (YAML_ALIAS_OR_ANCHOR.matcher(normalizedSource).find()) {
            throw new IllegalArgumentException("YAML aliases and anchors are forbidden");
        }

        Object document;
        try {
            document = secureYaml().load(normalizedSource);
        } catch (YAMLException error) {
            throw new IllegalArgumentException("invalid capability pack YAML", error);
        }
        Map<String, Object> root = asMap(document, "root");
        requireExactKeys(root, ROOT_KEYS, "root");

        int schemaVersion = asInt(root.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
        String packId = identifier(root.get("packId"), "packId", ID);
        String version = text(root.get("version"), "version", 32);
        if (!SEMVER.matcher(version).matches()) {
            throw new IllegalArgumentException("version must be stable semantic version");
        }
        PackStatus status = enumValue(root.get("status"), PackStatus.class, "status");
        Set<FactoryType> businessTypes = enumSet(
                root.get("businessTypes"), FactoryType.class, "businessTypes");
        if (businessTypes.isEmpty() || !ALLOWED_BUSINESS_TYPES.containsAll(businessTypes)) {
            throw new IllegalArgumentException("businessTypes must contain only FACTORY/CENTRAL_KITCHEN");
        }
        Set<FactoryUserRole> roles = enumSet(root.get("roles"), FactoryUserRole.class, "roles");
        if (roles.isEmpty() || roles.contains(FactoryUserRole.unactivated)) {
            throw new IllegalArgumentException("roles must contain active repository roles");
        }
        String instructions = text(root.get("instructions"), "instructions", MAX_TEXT);
        Set<String> tools = stringSet(root.get("readToolAllowlist"), "readToolAllowlist");
        validateTools(tools, inventory);
        List<WorkflowReference> workflows = parseWorkflows(root.get("workflowReferences"));
        OutputSchema outputSchema = parseOutputSchema(root.get("outputSchema"));
        List<String> rules = nonEmptyStrings(root.get("rules"), "rules", 512);
        List<String> forbidden = nonEmptyStrings(
                root.get("forbiddenActions"), "forbiddenActions", 512);
        List<String> terms = nonEmptyStrings(root.get("matchTerms"), "matchTerms", 64);
        List<FewShot> fewShots = parseFewShots(root.get("fewShots"), tools, workflows);
        List<EvalCase> evalCases = parseEvalCases(root.get("evalCases"), tools, workflows);

        return new FactoryCapabilityPack(
                schemaVersion, packId, version, status, businessTypes, roles, instructions,
                tools, workflows, outputSchema, rules, forbidden, terms, fewShots, evalCases,
                sha256(normalizedSource), resourcePath);
    }

    private Yaml secureYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(YAML_CODE_POINT_LIMIT);
        options.setNestingDepthLimit(16);
        return new Yaml(new SafeConstructor(options));
    }

    private static List<WorkflowReference> parseWorkflows(Object raw) {
        List<Object> values = list(raw, "workflowReferences");
        requireNonEmptyBounded(values, "workflowReferences");
        List<WorkflowReference> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String prefix = "workflowReferences[" + index + "]";
            Map<String, Object> item = asMap(values.get(index), prefix);
            requireExactKeys(item, WORKFLOW_KEYS, prefix);
            String referenceId = text(item.get("referenceId"), prefix + ".referenceId", 96);
            WorkflowReferenceType type = enumValue(
                    item.get("type"), WorkflowReferenceType.class, prefix + ".type");
            validateReferenceId(referenceId, type, prefix);
            boolean mutation = bool(item.get("mutation"), prefix + ".mutation");
            boolean approval = bool(
                    item.get("approvalRequired"), prefix + ".approvalRequired");
            if (type == WorkflowReferenceType.NAVIGATION && (mutation || approval)) {
                throw new IllegalArgumentException(prefix + " navigation cannot mutate or approve");
            }
            if (approval && !mutation) {
                throw new IllegalArgumentException(prefix + " approval requires mutation guidance");
            }
            if (!ids.add(referenceId)) {
                throw new IllegalArgumentException("duplicate workflow reference " + referenceId);
            }
            result.add(new WorkflowReference(referenceId, type, mutation, approval));
        }
        return List.copyOf(result);
    }

    private static OutputSchema parseOutputSchema(Object raw) {
        Map<String, Object> schema = asMap(raw, "outputSchema");
        requireExactKeys(schema, OUTPUT_SCHEMA_KEYS, "outputSchema");
        String schemaId = identifier(schema.get("schemaId"), "outputSchema.schemaId", ID);
        List<Object> rawFields = list(schema.get("fields"), "outputSchema.fields");
        requireNonEmptyBounded(rawFields, "outputSchema.fields");
        List<OutputField> fields = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < rawFields.size(); index++) {
            String prefix = "outputSchema.fields[" + index + "]";
            Map<String, Object> field = asMap(rawFields.get(index), prefix);
            requireExactKeys(field, OUTPUT_FIELD_KEYS, prefix);
            String name = identifier(field.get("name"), prefix + ".name", FIELD);
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate output field " + name);
            }
            fields.add(new OutputField(
                    name,
                    enumValue(field.get("type"), OutputFieldType.class, prefix + ".type"),
                    bool(field.get("required"), prefix + ".required"),
                    text(field.get("description"), prefix + ".description", 256)));
        }
        return new OutputSchema(schemaId, fields);
    }

    private static List<FewShot> parseFewShots(
            Object raw, Set<String> tools, List<WorkflowReference> workflows) {
        List<Object> values = list(raw, "fewShots");
        requireNonEmptyBounded(values, "fewShots");
        List<FewShot> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String prefix = "fewShots[" + index + "]";
            Map<String, Object> item = asMap(values.get(index), prefix);
            requireExactKeys(item, FEW_SHOT_KEYS, prefix);
            List<String> expectedTools = stringList(
                    item.get("expectedReadTools"), prefix + ".expectedReadTools", 128);
            validateExpectedTools(expectedTools, tools, prefix);
            String expectedReference = text(
                    item.get("expectedWorkflowReference"),
                    prefix + ".expectedWorkflowReference", 96);
            validateExpectedReference(expectedReference, workflows, prefix);
            result.add(new FewShot(
                    text(item.get("userQuery"), prefix + ".userQuery", 256),
                    enumValue(item.get("expectedMode"), ResponseMode.class,
                            prefix + ".expectedMode"),
                    expectedTools,
                    expectedReference,
                    text(item.get("assistantResponse"), prefix + ".assistantResponse", 1024)));
        }
        return List.copyOf(result);
    }

    private static List<EvalCase> parseEvalCases(
            Object raw, Set<String> tools, List<WorkflowReference> workflows) {
        List<Object> values = list(raw, "evalCases");
        requireNonEmptyBounded(values, "evalCases");
        List<EvalCase> result = new ArrayList<>();
        Set<String> caseIds = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String prefix = "evalCases[" + index + "]";
            Map<String, Object> item = asMap(values.get(index), prefix);
            requireExactKeys(item, EVAL_KEYS, prefix);
            String caseId = identifier(item.get("caseId"), prefix + ".caseId", CASE_ID);
            if (!caseIds.add(caseId)) {
                throw new IllegalArgumentException("duplicate eval case " + caseId);
            }
            List<String> expectedTools = stringList(
                    item.get("expectedReadTools"), prefix + ".expectedReadTools", 128);
            validateExpectedTools(expectedTools, tools, prefix);
            String expectedReference = text(
                    item.get("expectedWorkflowReference"),
                    prefix + ".expectedWorkflowReference", 96);
            validateExpectedReference(expectedReference, workflows, prefix);
            result.add(new EvalCase(
                    caseId,
                    text(item.get("query"), prefix + ".query", 256),
                    enumValue(item.get("expectedMode"), ResponseMode.class,
                            prefix + ".expectedMode"),
                    expectedTools,
                    expectedReference,
                    enumValue(item.get("expectedOutcome"), EvalOutcome.class,
                            prefix + ".expectedOutcome")));
        }
        return List.copyOf(result);
    }

    private static void validateTools(Set<String> tools, ToolDescriptorInventory inventory) {
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("readToolAllowlist must not be empty");
        }
        Map<String, ToolDescriptorInventoryEntry> byName = new HashMap<>();
        for (ToolDescriptorInventoryEntry entry : inventory.descriptors()) {
            byName.put(entry.toolName(), entry);
        }
        for (String tool : tools) {
            if (MUTATION_LIKE_TOOL.matcher(tool).find()) {
                throw new IllegalArgumentException("write-like tool forbidden from pack: " + tool);
            }
            ToolDescriptorInventoryEntry entry = byName.get(tool);
            if (entry == null) {
                throw new IllegalArgumentException("tool absent from D1 inventory: " + tool);
            }
            if (!"READ".equals(entry.actionType().name())
                    || !"LOW".equals(entry.riskLevel().name())) {
                throw new IllegalArgumentException("tool must be effective READ+LOW: " + tool);
            }
        }
    }

    private static void validateExpectedTools(
            List<String> expected, Set<String> allowlist, String prefix) {
        if (!allowlist.containsAll(expected)) {
            throw new IllegalArgumentException(prefix + " expected tool is outside allowlist");
        }
    }

    private static void validateExpectedReference(
            String reference, List<WorkflowReference> workflows, String prefix) {
        if ("NONE".equals(reference)) {
            return;
        }
        boolean found = workflows.stream().anyMatch(item -> item.referenceId().equals(reference));
        if (!found) {
            throw new IllegalArgumentException(prefix + " references unknown workflow " + reference);
        }
    }

    private static void validateReferenceId(
            String referenceId, WorkflowReferenceType type, String prefix) {
        String expectedPrefix = type.name() + ":";
        if (!referenceId.startsWith(expectedPrefix)
                || !referenceId.substring(expectedPrefix.length()).matches("[A-Z0-9_.-]{2,80}")) {
            throw new IllegalArgumentException(prefix + " has invalid deterministic referenceId");
        }
    }

    private static List<String> nonEmptyStrings(Object raw, String field, int maxLength) {
        List<String> result = stringList(raw, field, maxLength);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return result;
    }

    private static List<String> stringList(Object raw, String field, int maxLength) {
        List<Object> values = list(raw, field);
        if (values.size() > MAX_LIST) {
            throw new IllegalArgumentException(field + " exceeds maximum entries");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = text(values.get(index), field + "[" + index + "]", maxLength);
            if (!unique.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate value");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static Set<String> stringSet(Object raw, String field) {
        return Set.copyOf(stringList(raw, field, 128));
    }

    private static <E extends Enum<E>> Set<E> enumSet(Object raw, Class<E> type, String field) {
        List<Object> values = list(raw, field);
        if (values.size() > MAX_LIST) {
            throw new IllegalArgumentException(field + " exceeds maximum entries");
        }
        Set<E> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            E value = enumValue(values.get(index), type, field + "[" + index + "]");
            if (!result.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate value");
            }
        }
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(Object raw, Class<E> type, String field) {
        String value = text(raw, field, 64);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    field + " has illegal value " + value + "; allowed="
                            + EnumSet.allOf(type), error);
        }
    }

    private static String identifier(Object raw, String field, Pattern pattern) {
        String value = text(raw, field, 128);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has invalid format");
        }
        return value;
    }

    private static String text(Object raw, String field, int maximum) {
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum
                || CONTROL_CHARACTER.matcher(normalized).find()) {
            throw new IllegalArgumentException(field + " is blank, oversized or unsafe");
        }
        return normalized;
    }

    private static boolean bool(Object raw, String field) {
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value;
    }

    private static int asInt(Object raw, String field) {
        if (!(raw instanceof Integer value)) {
            throw new IllegalArgumentException(field + " must be integer");
        }
        return value;
    }

    private static List<Object> list(Object raw, String field) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        return new ArrayList<>(values);
    }

    private static Map<String, Object> asMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireExactKeys(
            Map<String, Object> value, Set<String> expected, String field) {
        if (!value.keySet().equals(expected)) {
            Set<String> unknown = new LinkedHashSet<>(value.keySet());
            unknown.removeAll(expected);
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(value.keySet());
            throw new IllegalArgumentException(
                    field + " keys do not match schema; unknown=" + unknown + ", missing=" + missing);
        }
    }

    private static void requireNonEmptyBounded(List<?> values, String field) {
        if (values.isEmpty() || values.size() > MAX_LIST) {
            throw new IllegalArgumentException(field + " must contain 1.." + MAX_LIST + " entries");
        }
    }

    private static String readBounded(Reader reader) {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4_096];
        try {
            int count;
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
                if (result.length() > YAML_CODE_POINT_LIMIT) {
                    throw new IllegalArgumentException("capability pack YAML exceeds size limit");
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("failed to read capability pack YAML", error);
        }
        return result.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte one : digest) {
                result.append(String.format(Locale.ROOT, "%02x", one));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
