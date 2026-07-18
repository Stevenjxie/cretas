package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.EgressMode;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.ToolEgressPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
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

/** Fail-closed SafeConstructor loader for the explicit runtime policy manifest. */
public final class RuntimeToolPolicyLoader {

    public static final String DEFAULT_RESOURCE =
            "ai/tool/gateway/runtime-tool-policies.yaml";
    static final int YAML_CODE_POINT_LIMIT = 64_000;

    private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "expectedPolicyCount", "policies");
    private static final Set<String> POLICY_KEYS = Set.of(
            "implementationClass", "toolName", "actionType", "riskLevel",
            "requiredPermissions", "allowedRoles", "allowedBusinessTypes",
            "domainTags", "version", "supportsPreview",
            "confirmationPolicy", "approvalPolicy", "idempotencyPolicy",
            "dataClassification", "allowedSources", "egressPolicy", "provenance");
    private static final Set<String> EGRESS_KEYS = Set.of("mode", "allowedDestinations");

    private final ClassLoader classLoader;

    public RuntimeToolPolicyLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public RuntimeToolPolicyLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public RuntimeToolPolicyManifest loadDefault() {
        return loadResource(DEFAULT_RESOURCE);
    }

    public RuntimeToolPolicyManifest loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("runtime tool policy resource not found: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return load(reader);
        } catch (IOException error) {
            throw new IllegalStateException("failed to close runtime tool policy resource", error);
        }
    }

    public RuntimeToolPolicyManifest load(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        Object document;
        try {
            document = secureYaml().load(reader);
        } catch (YAMLException error) {
            throw new IllegalArgumentException("invalid runtime tool policy YAML", error);
        }

        Map<String, Object> root = asMap(document, "root");
        requireExactKeys(root, ROOT_KEYS, "root");
        int schemaVersion = asInt(root.get("schemaVersion"), "schemaVersion");
        int expectedPolicyCount = asInt(
                root.get("expectedPolicyCount"), "expectedPolicyCount");
        List<Object> rawPolicies = asList(root.get("policies"), "policies");
        List<RuntimeToolPolicyEntry> policies = new ArrayList<>(rawPolicies.size());
        for (int index = 0; index < rawPolicies.size(); index++) {
            policies.add(parsePolicy(rawPolicies.get(index), index));
        }
        RuntimeToolPolicyManifest manifest = new RuntimeToolPolicyManifest(
                schemaVersion, expectedPolicyCount, policies);
        validateUniqueBindings(manifest);
        return manifest;
    }

    private Yaml secureYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(YAML_CODE_POINT_LIMIT);
        options.setNestingDepthLimit(12);
        return new Yaml(new SafeConstructor(options));
    }

    private RuntimeToolPolicyEntry parsePolicy(Object raw, int index) {
        String prefix = "policies[" + index + "]";
        Map<String, Object> policy = asMap(raw, prefix);
        requireExactKeys(policy, POLICY_KEYS, prefix);
        String implementationClass = asNonBlankString(
                policy.get("implementationClass"), prefix + ".implementationClass");
        if (!JAVA_CLASS_NAME.matcher(implementationClass).matches()) {
            throw new IllegalArgumentException(
                    prefix + ".implementationClass is not a Java class name");
        }
        return new RuntimeToolPolicyEntry(
                implementationClass,
                asNonBlankString(policy.get("toolName"), prefix + ".toolName"),
                asEnum(policy.get("actionType"), ToolExecutor.ActionType.class,
                        prefix + ".actionType"),
                asEnum(policy.get("riskLevel"), ToolExecutor.RiskLevel.class,
                        prefix + ".riskLevel"),
                asStringSet(policy.get("requiredPermissions"),
                        prefix + ".requiredPermissions"),
                asStringSet(policy.get("allowedRoles"), prefix + ".allowedRoles"),
                asEnumSet(policy.get("allowedBusinessTypes"), FactoryType.class,
                        prefix + ".allowedBusinessTypes"),
                asStringSet(policy.get("domainTags"), prefix + ".domainTags"),
                asNonBlankString(policy.get("version"), prefix + ".version"),
                asBoolean(policy.get("supportsPreview"), prefix + ".supportsPreview"),
                asEnum(policy.get("confirmationPolicy"), ConfirmationPolicy.class,
                        prefix + ".confirmationPolicy"),
                asEnum(policy.get("approvalPolicy"), ApprovalPolicy.class,
                        prefix + ".approvalPolicy"),
                asEnum(policy.get("idempotencyPolicy"), IdempotencyPolicy.class,
                        prefix + ".idempotencyPolicy"),
                asEnum(policy.get("dataClassification"), DataClassification.class,
                        prefix + ".dataClassification"),
                asEnumSet(policy.get("allowedSources"), ToolExecutionSource.class,
                        prefix + ".allowedSources"),
                parseEgress(policy.get("egressPolicy"), prefix + ".egressPolicy"),
                asEnum(policy.get("provenance"), DescriptorProvenance.class,
                        prefix + ".provenance"));
    }

    private ToolEgressPolicy parseEgress(Object raw, String field) {
        Map<String, Object> egress = asMap(raw, field);
        requireExactKeys(egress, EGRESS_KEYS, field);
        return new ToolEgressPolicy(
                asEnum(egress.get("mode"), EgressMode.class, field + ".mode"),
                asStringSet(egress.get("allowedDestinations"),
                        field + ".allowedDestinations"));
    }

    private void validateUniqueBindings(RuntimeToolPolicyManifest manifest) {
        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> implementationClasses = new LinkedHashSet<>();
        for (RuntimeToolPolicyEntry policy : manifest.policies()) {
            if (!toolNames.add(policy.toolName())) {
                throw new IllegalArgumentException("duplicate toolName: " + policy.toolName());
            }
            if (!implementationClasses.add(policy.implementationClass())) {
                throw new IllegalArgumentException(
                        "duplicate implementationClass: " + policy.implementationClass());
            }
        }
    }

    private static void requireExactKeys(
            Map<String, Object> value, Set<String> allowed, String field) {
        Set<String> actual = value.keySet();
        if (!actual.equals(allowed)) {
            Set<String> unknown = new LinkedHashSet<>(actual);
            unknown.removeAll(allowed);
            Set<String> missing = new LinkedHashSet<>(allowed);
            missing.removeAll(actual);
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
