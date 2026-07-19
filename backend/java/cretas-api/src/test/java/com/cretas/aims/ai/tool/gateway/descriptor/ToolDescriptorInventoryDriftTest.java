package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-metadata drift gate. It never constructs or starts any of the 593 Spring tool beans. */
class ToolDescriptorInventoryDriftTest {

    private static final Pattern COMPONENT = Pattern.compile("@Component(?:\\s*\\([^)]*\\))?");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([^;]+);");
    private static final Pattern CLASS = Pattern.compile(
            "(?s)\\bpublic\\s+((?:(?:abstract|final|sealed|non-sealed)\\s+)*)"
                    + "class\\s+(\\w+)\\s*([^\\{]*)\\{");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");
    private static final Set<String> BUSINESS_TOOL_BASES = Set.of(
            "AbstractBusinessTool",
            "AbstractRestaurantDiagnosticTool",
            "AbstractReviewGoldTool",
            "GoldBackedRestaurantTool");
    @Test
    void sourceToolBeansAndInventoryStayInOneToOneMetadataAlignment() throws IOException {
        Map<String, SourceTool> sourceTools = discoverSourceTools();
        ToolDescriptorInventory inventory = new ToolDescriptorInventoryLoader().loadDefault();
        Map<String, ToolDescriptorInventoryEntry> inventoryByClass = inventory.descriptors().stream()
                .collect(Collectors.toMap(
                        ToolDescriptorInventoryEntry::implementationClass,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("duplicate inventory class "
                                    + left.implementationClass());
                        },
                        LinkedHashMap::new));

        assertThat(sourceTools).hasSize(593);
        assertThat(inventory.expectedToolCount()).isEqualTo(593);
        assertThat(inventoryByClass.keySet()).isEqualTo(sourceTools.keySet());
        assertThat(sourceTools.values())
                .extracting(SourceTool::toolName)
                .doesNotHaveDuplicates();
        assertThat(inventory.descriptors())
                .extracting(ToolDescriptorInventoryEntry::toolName)
                .doesNotHaveDuplicates();

        for (SourceTool source : sourceTools.values()) {
            ToolDescriptorInventoryEntry entry = inventoryByClass.get(source.implementationClass());
            assertThat(entry).as(source.sourcePath().toString()).isNotNull();
            assertThat(entry.toolName()).isEqualTo(source.toolName());
            assertThat(entry.actionType()).isEqualTo(source.actionType());
            assertThat(entry.riskLevel()).isEqualTo(source.riskLevel());
            assertThat(entry.supportsPreview()).isEqualTo(source.supportsPreview());
            assertThat(entry.requiresPermission()).isEqualTo(source.requiresPermission());
            assertThat(entry.requiredPermissions()).isEqualTo(source.requiredPermissions());
            assertThat(entry.version()).isEqualTo(source.version());
            assertThat(entry.domainTags()).isEqualTo(source.domainTags());
            assertThat(entry.overrideFlags()).isEqualTo(source.overrideFlags());
        }
    }

    @Test
    void freezesLegacyMembershipWhileAllowingOnlyExplicitFutureGrowth() throws IOException {
        Map<String, SourceTool> sourceTools = discoverSourceTools();
        ToolDescriptorInventory inventory = new ToolDescriptorInventoryLoader().loadDefault();
        Set<String> legacyClasses = inventory.descriptors().stream()
                .filter(entry -> entry.provenance() == DescriptorProvenance.LEGACY_INFERRED)
                .map(ToolDescriptorInventoryEntry::implementationClass)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(LegacyToolDescriptorBaseline.IMPLEMENTATION_CLASSES)
                .hasSize(LegacyToolDescriptorBaseline.COUNT);
        assertThat(inventory.expectedLegacyCount()).isEqualTo(legacyClasses.size());
        assertThat(LegacyToolDescriptorBaseline.IMPLEMENTATION_CLASSES).containsAll(legacyClasses);
        assertThat(sourceTools.keySet()).containsAll(legacyClasses);

        // Future migrations may remove classes from legacy membership. Any newly discovered class
        // must be represented in YAML and must not be admitted as LEGACY_INFERRED.
        assertThat(inventory.expectedLegacyCount())
                .isLessThanOrEqualTo(LegacyToolDescriptorBaseline.COUNT);
    }

    @Test
    void discoversFinalToolsBehindFutureBaseTypes() {
        String source = """
                package com.example.future;

                @Component
                public final class FutureWriteTool extends FutureToolBase {
                    public String getToolName() {
                        return "future_write";
                    }

                    public ToolExecutor.ActionType getActionType() {
                        return ToolExecutor.ActionType.WRITE;
                    }
                }
                """;

        SourceTool tool = SourceTool.from(Path.of("FutureWriteTool.java"), source);

        assertThat(tool).isNotNull();
        assertThat(tool.implementationClass()).isEqualTo("com.example.future.FutureWriteTool");
        assertThat(tool.toolName()).isEqualTo("future_write");
        assertThat(tool.actionType()).isEqualTo(ToolExecutor.ActionType.WRITE);
    }

    @Test
    void capturesLiteralConstantExceptionAndAllOverrideFlagBaselines() throws IOException {
        List<SourceTool> tools = new ArrayList<>(discoverSourceTools().values());
        List<SourceTool> constantNames = tools.stream()
                .filter(tool -> !tool.literalToolName())
                .toList();

        assertThat(tools.stream().filter(SourceTool::literalToolName)).hasSize(592);
        assertThat(constantNames).singleElement().satisfies(tool -> {
            assertThat(tool.implementationClass()).isEqualTo(
                    "com.cretas.aims.ai.tool.impl.workprocess.ProductProcessWorkflowConfigTool");
            assertThat(tool.toolName()).isEqualTo("canvas_product_process_workflow_config");
        });
        assertThat(countOverride(tools, flags -> flags.actionType())).isEqualTo(51);
        assertThat(countOverride(tools, flags -> flags.riskLevel())).isEqualTo(34);
        assertThat(countOverride(tools, flags -> flags.supportsPreview())).isEqualTo(42);
        assertThat(countOverride(tools, flags -> flags.requiresPermission())).isEqualTo(45);
        assertThat(countOverride(tools, flags -> flags.hasPermission())).isEqualTo(34);
        assertThat(countOverride(tools, flags -> flags.requiredPermissions())).isEqualTo(5);
        assertThat(countOverride(tools, flags -> flags.version())).isEqualTo(5);
        assertThat(countOverride(tools, flags -> flags.domainTags())).isEqualTo(19);

        assertThat(countActions(tools)).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.ActionType.READ, 450L,
                ToolExecutor.ActionType.WRITE, 64L,
                ToolExecutor.ActionType.UPDATE, 27L,
                ToolExecutor.ActionType.DELETE, 11L,
                ToolExecutor.ActionType.ANALYZE, 19L,
                ToolExecutor.ActionType.GENERATE, 15L,
                ToolExecutor.ActionType.NOTIFY, 7L));
        assertThat(countRisks(tools)).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.RiskLevel.LOW, 515L,
                ToolExecutor.RiskLevel.MEDIUM, 73L,
                ToolExecutor.RiskLevel.HIGH, 5L,
                ToolExecutor.RiskLevel.CRITICAL, 0L));
    }

    private Map<String, SourceTool> discoverSourceTools() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java").toAbsolutePath().normalize();
        assertThat(sourceRoot).isDirectory();
        Map<String, SourceTool> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                SourceTool tool = SourceTool.from(path, source);
                if (tool == null) {
                    continue;
                }
                SourceTool previous = result.putIfAbsent(tool.implementationClass(), tool);
                if (previous != null) {
                    throw new IllegalStateException(
                            "duplicate source class " + tool.implementationClass());
                }
            }
        }
        return result;
    }

    private long countOverride(
            List<SourceTool> tools,
            java.util.function.Predicate<ToolDescriptorOverrideFlags> selector) {
        return tools.stream().map(SourceTool::overrideFlags).filter(selector).count();
    }

    private Map<ToolExecutor.ActionType, Long> countActions(List<SourceTool> tools) {
        EnumMap<ToolExecutor.ActionType, Long> counts = zeroed(ToolExecutor.ActionType.class);
        tools.forEach(tool -> counts.compute(tool.actionType(), (ignored, count) -> count + 1));
        return counts;
    }

    private Map<ToolExecutor.RiskLevel, Long> countRisks(List<SourceTool> tools) {
        EnumMap<ToolExecutor.RiskLevel, Long> counts = zeroed(ToolExecutor.RiskLevel.class);
        tools.forEach(tool -> counts.compute(tool.riskLevel(), (ignored, count) -> count + 1));
        return counts;
    }

    private <E extends Enum<E>> EnumMap<E, Long> zeroed(Class<E> type) {
        EnumMap<E, Long> counts = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counts.put(value, 0L);
        }
        return counts;
    }

    private record SourceTool(
            Path sourcePath,
            String implementationClass,
            String toolName,
            boolean literalToolName,
            ToolExecutor.ActionType actionType,
            ToolExecutor.RiskLevel riskLevel,
            boolean supportsPreview,
            boolean requiresPermission,
            Set<String> requiredPermissions,
            String version,
            Set<String> domainTags,
            ToolDescriptorOverrideFlags overrideFlags) {

        private static SourceTool from(Path path, String source) {
            if (!COMPONENT.matcher(source).find()) {
                return null;
            }
            Matcher classMatcher = CLASS.matcher(source);
            if (!classMatcher.find() || classMatcher.group(1).contains("abstract")) {
                return null;
            }
            String declarationTail = classMatcher.group(3);
            String toolNameBody = methodBody(source, "String", "getToolName");
            if (toolNameBody == null) {
                return null;
            }
            Matcher packageMatcher = PACKAGE.matcher(source);
            if (!packageMatcher.find()) {
                throw new IllegalStateException("missing package in " + path);
            }
            String implementationClass = packageMatcher.group(1) + "." + classMatcher.group(2);
            ToolName toolName = parseToolName(source, toolNameBody, path);
            boolean businessTool = BUSINESS_TOOL_BASES.stream().anyMatch(declarationTail::contains);

            String actionBody = methodBody(source, "(?:ToolExecutor\\.)?ActionType", "getActionType");
            String riskBody = methodBody(source, "(?:ToolExecutor\\.)?RiskLevel", "getRiskLevel");
            String previewBody = methodBody(source, "boolean", "supportsPreview");
            String permissionBody = methodBody(source, "boolean", "requiresPermission");
            String hasPermissionBody = methodBody(source, "boolean", "hasPermission");
            String requiredPermissionsBody = methodBody(
                    source, "Set\\s*<\\s*String\\s*>", "getRequiredPermissions");
            String versionBody = methodBody(source, "String", "getVersion");
            String domainTagsBody = methodBody(
                    source, "Set\\s*<\\s*String\\s*>", "getDomainTags");

            ToolExecutor.ActionType actionType = actionBody == null
                    ? inferAction(toolName.value(), businessTool)
                    : parseEnum(actionBody, "ActionType", ToolExecutor.ActionType.class, path);
            ToolExecutor.RiskLevel riskLevel = riskBody == null
                    ? inferRisk(actionType, businessTool)
                    : parseEnum(riskBody, "RiskLevel", ToolExecutor.RiskLevel.class, path);
            boolean supportsPreview = previewBody != null && parseBoolean(previewBody, path);
            boolean requiresPermission = permissionBody != null && parseBoolean(permissionBody, path);
            Set<String> requiredPermissions = requiredPermissionsBody == null
                    ? Set.of()
                    : parseStringSet(requiredPermissionsBody, path);
            String version = versionBody == null
                    ? "1.0.0"
                    : parseReturnedString(versionBody, path);
            Set<String> domainTags = domainTagsBody == null
                    ? inferDomainTags(toolName.value(), businessTool)
                    : parseStringSet(domainTagsBody, path);
            ToolDescriptorOverrideFlags flags = new ToolDescriptorOverrideFlags(
                    actionBody != null,
                    riskBody != null,
                    previewBody != null,
                    permissionBody != null,
                    hasPermissionBody != null,
                    requiredPermissionsBody != null,
                    versionBody != null,
                    domainTagsBody != null);
            return new SourceTool(
                    path,
                    implementationClass,
                    toolName.value(),
                    toolName.literal(),
                    actionType,
                    riskLevel,
                    supportsPreview,
                    requiresPermission,
                    requiredPermissions,
                    version,
                    domainTags,
                    flags);
        }

        private static ToolName parseToolName(String source, String body, Path path) {
            Matcher literal = Pattern.compile("return\\s+\"([^\"]+)\"\\s*;").matcher(body);
            if (literal.find()) {
                return new ToolName(literal.group(1), true);
            }
            Matcher constantReturn = Pattern.compile("return\\s+(\\w+)\\s*;").matcher(body);
            if (!constantReturn.find()) {
                throw new IllegalStateException("cannot parse getToolName in " + path);
            }
            Pattern constant = Pattern.compile(
                    "(?m)(?:public|private|protected)?\\s*static\\s+final\\s+String\\s+"
                            + Pattern.quote(constantReturn.group(1))
                            + "\\s*=\\s*\"([^\"]+)\"");
            Matcher constantMatcher = constant.matcher(source);
            if (!constantMatcher.find()) {
                throw new IllegalStateException("cannot resolve tool name constant in " + path);
            }
            return new ToolName(constantMatcher.group(1), false);
        }

        private static ToolExecutor.ActionType inferAction(String toolName, boolean businessTool) {
            if (!businessTool) {
                return ToolExecutor.ActionType.READ;
            }
            if (toolName.endsWith("_create") || toolName.contains("_create_")) {
                return ToolExecutor.ActionType.WRITE;
            }
            if (toolName.endsWith("_delete") || toolName.contains("_delete_")) {
                return ToolExecutor.ActionType.DELETE;
            }
            if (toolName.endsWith("_update") || toolName.contains("_update_")) {
                return ToolExecutor.ActionType.UPDATE;
            }
            if (toolName.contains("_analyze") || toolName.contains("_analysis")) {
                return ToolExecutor.ActionType.ANALYZE;
            }
            if (toolName.contains("_notify") || toolName.contains("_alert")) {
                return ToolExecutor.ActionType.NOTIFY;
            }
            if (toolName.contains("_generate")) {
                return ToolExecutor.ActionType.GENERATE;
            }
            return ToolExecutor.ActionType.READ;
        }

        private static ToolExecutor.RiskLevel inferRisk(
                ToolExecutor.ActionType actionType, boolean businessTool) {
            if (businessTool
                    && (actionType == ToolExecutor.ActionType.DELETE
                    || actionType == ToolExecutor.ActionType.WRITE)) {
                return ToolExecutor.RiskLevel.MEDIUM;
            }
            return ToolExecutor.RiskLevel.LOW;
        }

        private static Set<String> inferDomainTags(String toolName, boolean businessTool) {
            int separator = toolName.indexOf('_');
            if (!businessTool || separator < 0) {
                return Set.of();
            }
            return Set.of(toolName.substring(0, separator));
        }

        private static <E extends Enum<E>> E parseEnum(
                String body, String enumName, Class<E> type, Path path) {
            Matcher matcher = Pattern.compile(enumName + "\\.(\\w+)").matcher(body);
            if (!matcher.find()) {
                throw new IllegalStateException("cannot parse " + enumName + " in " + path);
            }
            return Enum.valueOf(type, matcher.group(1));
        }

        private static boolean parseBoolean(String body, Path path) {
            Matcher matcher = Pattern.compile("return\\s+(true|false)\\s*;").matcher(body);
            if (!matcher.find()) {
                throw new IllegalStateException("cannot parse boolean metadata in " + path);
            }
            return Boolean.parseBoolean(matcher.group(1));
        }

        private static String parseReturnedString(String body, Path path) {
            Matcher matcher = Pattern.compile("return\\s+\"([^\"]+)\"\\s*;").matcher(body);
            if (!matcher.find()) {
                throw new IllegalStateException("cannot parse returned string in " + path);
            }
            return matcher.group(1);
        }

        private static Set<String> parseStringSet(String body, Path path) {
            Matcher matcher = STRING_LITERAL.matcher(body);
            Set<String> values = new LinkedHashSet<>();
            while (matcher.find()) {
                values.add(matcher.group(1));
            }
            if (!body.contains("Set.of(") && !body.contains("Collections.emptySet(")) {
                throw new IllegalStateException("cannot parse string set metadata in " + path);
            }
            return Set.copyOf(values);
        }

        private static String methodBody(String source, String returnType, String methodName) {
            Pattern signature = Pattern.compile(
                    "(?m)\\bpublic\\s+" + returnType + "\\s+" + Pattern.quote(methodName)
                            + "\\s*\\([^)]*\\)(?:\\s*throws\\s+[^\\{]+)?\\s*\\{");
            Matcher matcher = signature.matcher(source);
            if (!matcher.find()) {
                return null;
            }
            int open = source.indexOf('{', matcher.start());
            int depth = 0;
            for (int index = open; index < source.length(); index++) {
                char character = source.charAt(index);
                if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                    if (depth == 0) {
                        return source.substring(open + 1, index);
                    }
                }
            }
            throw new IllegalStateException("unbalanced method " + methodName);
        }
    }

    private record ToolName(String value, boolean literal) {
    }
}
