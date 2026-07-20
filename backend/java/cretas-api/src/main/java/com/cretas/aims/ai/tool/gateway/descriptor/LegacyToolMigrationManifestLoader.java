package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.entity.enums.FactoryType;
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

/** Fail-closed parser for the independent, intentionally tiny legacy migration manifest. */
public final class LegacyToolMigrationManifestLoader {

    public static final String DEFAULT_RESOURCE =
            "ai/tool/gateway/legacy-intent-dispatch-migration.yaml";
    static final int YAML_CODE_POINT_LIMIT = 32_000;
    static final Set<String> INITIAL_ALLOWED_TOOLS = Set.of(
            "restaurant_dish_list",
            "restaurant_ingredient_stock",
            "restaurant_order_statistics");

    private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "expectedToolCount", "tools");
    private static final Set<String> TOOL_KEYS = Set.of(
            "implementationClass", "toolName", "actionType", "riskLevel",
            "supportsPreview", "requiresPermission", "requiredPermissions", "allowedRoles",
            "allowedBusinessTypes", "version", "domainTags", "dataClassification");

    private final ClassLoader classLoader;

    public LegacyToolMigrationManifestLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public LegacyToolMigrationManifestLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public LegacyToolMigrationManifest loadDefault() {
        return loadResource(DEFAULT_RESOURCE);
    }

    public LegacyToolMigrationManifest loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException(
                    "legacy migration manifest resource not found: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return load(reader);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "failed to close legacy migration manifest resource", error);
        }
    }

    public LegacyToolMigrationManifest load(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        Object document;
        try {
            document = secureYaml().load(reader);
        } catch (YAMLException error) {
            throw new IllegalArgumentException("invalid legacy migration manifest YAML", error);
        }
        Map<String, Object> root = asMap(document, "root");
        requireExactKeys(root, ROOT_KEYS, "root");
        int schemaVersion = asInt(root.get("schemaVersion"), "schemaVersion");
        int expectedToolCount = asInt(root.get("expectedToolCount"), "expectedToolCount");
        List<Object> rawTools = asList(root.get("tools"), "tools");
        List<LegacyToolMigrationEntry> tools = new ArrayList<>(rawTools.size());
        for (int index = 0; index < rawTools.size(); index++) {
            tools.add(parseTool(rawTools.get(index), index));
        }
        LegacyToolMigrationManifest manifest = new LegacyToolMigrationManifest(
                schemaVersion, expectedToolCount, tools);
        validateBindings(manifest);
        return manifest;
    }

    private Yaml secureYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(YAML_CODE_POINT_LIMIT);
        options.setNestingDepthLimit(10);
        return new Yaml(new SafeConstructor(options));
    }

    private LegacyToolMigrationEntry parseTool(Object raw, int index) {
        String prefix = "tools[" + index + "]";
        Map<String, Object> tool = asMap(raw, prefix);
        requireExactKeys(tool, TOOL_KEYS, prefix);
        String implementationClass = asNonBlankString(
                tool.get("implementationClass"), prefix + ".implementationClass");
        if (!JAVA_CLASS_NAME.matcher(implementationClass).matches()) {
            throw new IllegalArgumentException(
                    prefix + ".implementationClass is not a Java class name");
        }
        return new LegacyToolMigrationEntry(
                implementationClass,
                asNonBlankString(tool.get("toolName"), prefix + ".toolName"),
                asEnum(tool.get("actionType"), ToolExecutor.ActionType.class,
                        prefix + ".actionType"),
                asEnum(tool.get("riskLevel"), ToolExecutor.RiskLevel.class,
                        prefix + ".riskLevel"),
                asBoolean(tool.get("supportsPreview"), prefix + ".supportsPreview"),
                asBoolean(tool.get("requiresPermission"), prefix + ".requiresPermission"),
                asStringSet(tool.get("requiredPermissions"),
                        prefix + ".requiredPermissions"),
                asStringSet(tool.get("allowedRoles"), prefix + ".allowedRoles"),
                asEnumSet(tool.get("allowedBusinessTypes"), FactoryType.class,
                        prefix + ".allowedBusinessTypes"),
                asNonBlankString(tool.get("version"), prefix + ".version"),
                asStringSet(tool.get("domainTags"), prefix + ".domainTags"),
                asEnum(tool.get("dataClassification"), DataClassification.class,
                        prefix + ".dataClassification"));
    }

    private static void validateBindings(LegacyToolMigrationManifest manifest) {
        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> implementationClasses = new LinkedHashSet<>();
        for (LegacyToolMigrationEntry entry : manifest.tools()) {
            if (!INITIAL_ALLOWED_TOOLS.contains(entry.toolName())) {
                throw new IllegalArgumentException(
                        "tool is outside the initial migration allowlist: " + entry.toolName());
            }
            if (!toolNames.add(entry.toolName())) {
                throw new IllegalArgumentException("duplicate toolName: " + entry.toolName());
            }
            if (!implementationClasses.add(entry.implementationClass())) {
                throw new IllegalArgumentException(
                        "duplicate implementationClass: " + entry.implementationClass());
            }
        }
    }

    private static void requireExactKeys(
            Map<String, Object> value, Set<String> allowed, String field) {
        if (!value.keySet().equals(allowed)) {
            Set<String> unknown = new LinkedHashSet<>(value.keySet());
            unknown.removeAll(allowed);
            Set<String> missing = new LinkedHashSet<>(allowed);
            missing.removeAll(value.keySet());
            throw new IllegalArgumentException(
                    field + " keys do not match schema; unknown=" + unknown
                            + ", missing=" + missing);
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
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> Set<E> asEnumSet(
            Object raw, Class<E> type, String field) {
        List<Object> values = asList(raw, field);
        Set<E> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            E value = asEnum(values.get(index), type, field + "[" + index + "]");
            if (!result.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate value: " + value);
            }
        }
        return Set.copyOf(result);
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
