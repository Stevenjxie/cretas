package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed parser for the static D1 tool inventory.
 *
 * <p>This loader does not register tools and does not produce runtime authorization policy. It
 * intentionally preserves {@link DescriptorProvenance#LEGACY_INFERRED} and review-required
 * status so that current defaults cannot be mistaken for approved governance.</p>
 */
public final class ToolDescriptorInventoryLoader {

    public static final String DEFAULT_RESOURCE = "ai/tool/gateway/tool-descriptors.yaml";
    public static final int LEGACY_BASELINE_COUNT = 601;
    static final int YAML_CODE_POINT_LIMIT = 1_000_000;

    private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "expectedToolCount", "expectedLegacyCount", "descriptors");
    private static final Set<String> ENTRY_KEYS = Set.of(
            "toolName", "implementationClass", "provenance", "actionType", "riskLevel",
            "supportsPreview", "requiresPermission", "requiredPermissions", "version",
            "domainTags", "overrideFlags", "governanceStatus");
    private static final Set<String> OVERRIDE_KEYS = Set.of(
            "actionType", "riskLevel", "supportsPreview", "requiresPermission",
            "hasPermission", "requiredPermissions", "version", "domainTags");
    public static final Set<String> P0_TOOL_NAMES = Set.of(
            "canvas_set_user_permission",
            "user_role_assign",
            "user_disable",
            "approval_action_execute",
            "finance_invoice_approve",
            "purchase_order_approve",
            "purchase_finance_approve",
            "transfer_approve",
            "return_order_approve",
            "bom_version_approve",
            "ecn_approve",
            "rd_sample_approve",
            "dictionary_batch_import",
            "notify_send",
            "restaurant_performance_rule_manage",
            "restaurant_dish_create",
            "restaurant_dish_delete",
            "restaurant_dish_update",
            "restaurant_procurement_create",
            "restaurant_wastage_record");

    private final ClassLoader classLoader;

    public ToolDescriptorInventoryLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ToolDescriptorInventoryLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public ToolDescriptorInventory loadDefault() {
        return loadResource(DEFAULT_RESOURCE);
    }

    public ToolDescriptorInventory loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("tool descriptor inventory resource not found: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return load(reader);
        } catch (IOException error) {
            throw new IllegalStateException("failed to close tool descriptor inventory resource", error);
        }
    }

    public ToolDescriptorInventory load(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        Object document;
        try {
            document = secureYaml().load(reader);
        } catch (YAMLException error) {
            throw new IllegalArgumentException("invalid tool descriptor inventory YAML", error);
        }
        Map<String, Object> root = asMap(document, "root");
        requireExactKeys(root, ROOT_KEYS, "root");

        int schemaVersion = asInt(root.get("schemaVersion"), "schemaVersion");
        int expectedToolCount = asInt(root.get("expectedToolCount"), "expectedToolCount");
        int expectedLegacyCount = asInt(root.get("expectedLegacyCount"), "expectedLegacyCount");
        List<Object> rawDescriptors = asList(root.get("descriptors"), "descriptors");
        List<ToolDescriptorInventoryEntry> descriptors = new ArrayList<>(rawDescriptors.size());
        for (int index = 0; index < rawDescriptors.size(); index++) {
            descriptors.add(parseEntry(rawDescriptors.get(index), index));
        }

        ToolDescriptorInventory inventory = new ToolDescriptorInventory(
                schemaVersion, expectedToolCount, expectedLegacyCount, descriptors);
        validateDocument(inventory);
        return inventory;
    }

    private Yaml secureYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(YAML_CODE_POINT_LIMIT);
        options.setNestingDepthLimit(16);
        return new Yaml(new SafeConstructor(options));
    }

    private ToolDescriptorInventoryEntry parseEntry(Object raw, int index) {
        String prefix = "descriptors[" + index + "]";
        Map<String, Object> entry = asMap(raw, prefix);
        requireExactKeys(entry, ENTRY_KEYS, prefix);
        String implementationClass = asNonBlankString(
                entry.get("implementationClass"), prefix + ".implementationClass");
        if (!JAVA_CLASS_NAME.matcher(implementationClass).matches()) {
            throw new IllegalArgumentException(prefix + ".implementationClass is not a Java class name");
        }
        return new ToolDescriptorInventoryEntry(
                asNonBlankString(entry.get("toolName"), prefix + ".toolName"),
                implementationClass,
                asEnum(entry.get("provenance"), DescriptorProvenance.class, prefix + ".provenance"),
                asEnum(entry.get("actionType"), ToolExecutor.ActionType.class, prefix + ".actionType"),
                asEnum(entry.get("riskLevel"), ToolExecutor.RiskLevel.class, prefix + ".riskLevel"),
                asBoolean(entry.get("supportsPreview"), prefix + ".supportsPreview"),
                asBoolean(entry.get("requiresPermission"), prefix + ".requiresPermission"),
                asStringSet(entry.get("requiredPermissions"), prefix + ".requiredPermissions"),
                asNonBlankString(entry.get("version"), prefix + ".version"),
                asStringSet(entry.get("domainTags"), prefix + ".domainTags"),
                parseOverrideFlags(entry.get("overrideFlags"), prefix + ".overrideFlags"),
                asEnum(entry.get("governanceStatus"), ToolGovernanceStatus.class,
                        prefix + ".governanceStatus"));
    }

    private ToolDescriptorOverrideFlags parseOverrideFlags(Object raw, String field) {
        Map<String, Object> flags = asMap(raw, field);
        requireExactKeys(flags, OVERRIDE_KEYS, field);
        return new ToolDescriptorOverrideFlags(
                asBoolean(flags.get("actionType"), field + ".actionType"),
                asBoolean(flags.get("riskLevel"), field + ".riskLevel"),
                asBoolean(flags.get("supportsPreview"), field + ".supportsPreview"),
                asBoolean(flags.get("requiresPermission"), field + ".requiresPermission"),
                asBoolean(flags.get("hasPermission"), field + ".hasPermission"),
                asBoolean(flags.get("requiredPermissions"), field + ".requiredPermissions"),
                asBoolean(flags.get("version"), field + ".version"),
                asBoolean(flags.get("domainTags"), field + ".domainTags"));
    }

    private void validateDocument(ToolDescriptorInventory inventory) {
        if (inventory.expectedToolCount() != inventory.descriptors().size()) {
            throw new IllegalArgumentException("expectedToolCount does not match descriptors size");
        }
        if (inventory.expectedLegacyCount() > LEGACY_BASELINE_COUNT) {
            throw new IllegalArgumentException("legacy descriptor count may not exceed baseline "
                    + LEGACY_BASELINE_COUNT);
        }
        long actualLegacyCount = inventory.descriptors().stream()
                .filter(entry -> entry.provenance() == DescriptorProvenance.LEGACY_INFERRED)
                .count();
        if (actualLegacyCount != inventory.expectedLegacyCount()) {
            throw new IllegalArgumentException("expectedLegacyCount does not match legacy descriptors");
        }

        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> implementationClasses = new LinkedHashSet<>();
        for (ToolDescriptorInventoryEntry entry : inventory.descriptors()) {
            if (!toolNames.add(entry.toolName())) {
                throw new IllegalArgumentException("duplicate toolName: " + entry.toolName());
            }
            if (!implementationClasses.add(entry.implementationClass())) {
                throw new IllegalArgumentException(
                        "duplicate implementationClass: " + entry.implementationClass());
            }
            validateGovernance(entry);
        }
    }

    private void validateGovernance(ToolDescriptorInventoryEntry entry) {
        if (P0_TOOL_NAMES.contains(entry.toolName())) {
            if (entry.governanceStatus() == ToolGovernanceStatus.REVIEW_REQUIRED_P0) {
                return;
            }
            if (entry.governanceStatus() == ToolGovernanceStatus.APPROVED) {
                validateExplicitApproval(entry);
                return;
            }
            throw new IllegalArgumentException(
                    "P0 tool must be REVIEW_REQUIRED_P0 or fully explicit APPROVED: "
                            + entry.toolName());
        }
        if (entry.provenance() == DescriptorProvenance.LEGACY_INFERRED
                && entry.governanceStatus() != ToolGovernanceStatus.REVIEW_REQUIRED) {
            throw new IllegalArgumentException(
                    "legacy inferred tool must remain REVIEW_REQUIRED: " + entry.toolName());
        }
        if (entry.governanceStatus() == ToolGovernanceStatus.REVIEW_REQUIRED_P0) {
            throw new IllegalArgumentException(
                    "only the fixed P0 set may use REVIEW_REQUIRED_P0: " + entry.toolName());
        }
        if (entry.governanceStatus() == ToolGovernanceStatus.APPROVED) {
            validateExplicitApproval(entry);
        }
    }

    private void validateExplicitApproval(ToolDescriptorInventoryEntry entry) {
        ToolDescriptorOverrideFlags flags = entry.overrideFlags();
        boolean completeSourceMetadata = flags.actionType()
                && flags.riskLevel()
                && flags.supportsPreview()
                && flags.requiresPermission()
                && flags.hasPermission()
                && flags.requiredPermissions()
                && flags.version()
                && flags.domainTags();
        if (entry.provenance() != DescriptorProvenance.EXPLICIT
                || !completeSourceMetadata
                || !entry.requiresPermission()
                || entry.requiredPermissions().isEmpty()
                || entry.domainTags().isEmpty()) {
            throw new IllegalArgumentException(
                    "APPROVED tool requires complete explicit source metadata and permission codes: "
                            + entry.toolName());
        }
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> allowed, String field) {
        Set<String> actual = value.keySet();
        if (!actual.equals(allowed)) {
            Set<String> unknown = new LinkedHashSet<>(actual);
            unknown.removeAll(allowed);
            Set<String> missing = new LinkedHashSet<>(allowed);
            missing.removeAll(actual);
            throw new IllegalArgumentException(
                    field + " keys do not match schema; unknown=" + unknown + ", missing=" + missing);
        }
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

    private static List<Object> asList(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        return new ArrayList<>(list);
    }

    private static int asInt(Object raw, String field) {
        if (!(raw instanceof Integer value)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value;
    }

    private static boolean asBoolean(Object raw, String field) {
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value;
    }

    private static String asNonBlankString(Object raw, String field) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value;
    }

    private static Set<String> asStringSet(Object raw, String field) {
        List<Object> values = asList(raw, field);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = asNonBlankString(values.get(index), field + "[" + index + "]");
            if (!result.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate value: " + value);
            }
        }
        return result;
    }

    private static <E extends Enum<E>> E asEnum(Object raw, Class<E> type, String field) {
        String value = asNonBlankString(raw, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    field + " has illegal value " + value + "; allowed="
                            + EnumSet.allOf(type), error);
        }
    }
}
