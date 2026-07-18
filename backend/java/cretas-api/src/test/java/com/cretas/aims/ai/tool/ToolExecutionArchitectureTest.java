package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration gate that freezes direct ToolExecutor calls while callers move behind the gateway.
 *
 * <p>This is intentionally a JDK/JUnit source scan, not a final type-aware ArchUnit rule. It
 * prevents the known bypass surface from growing without adding a Maven dependency. Once every
 * caller uses ToolExecutionGateway, replace this temporary baseline with a zero-bypass rule.</p>
 */
class ToolExecutionArchitectureTest {

    private static final String BASELINE_RESOURCE =
            "/architecture/tool-execution-direct-call-baseline.tsv";
    private static final Pattern TOOL_EXECUTOR_IDENTIFIER = Pattern.compile(
            "(?:\\bToolExecutor\\b|\\bOptional\\s*<\\s*ToolExecutor\\s*>)"
                    + "\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern REGISTRY_CHAIN_CALL = Pattern.compile(
            "\\b[A-Za-z_$][A-Za-z0-9_$]*(?:Registry|registry)[A-Za-z0-9_$]*\\b"
                    + "(?:\\s*\\.\\s*(?:getTool|getExecutor|findTool)\\s*\\([^;{}]*?\\))"
                    + "(?:\\s*\\.\\s*(?:get|orElseThrow)\\s*\\([^;{}]*?\\))*"
                    + "\\s*\\.\\s*(execute|preview)\\s*\\(");

    @Test
    void directToolExecutionPathsDoNotGrowBeyondMigrationBaseline() throws IOException {
        Baseline baseline = loadBaseline();
        Path sourceRoot = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        ScanResult actual = scan(sourceRoot);

        assertThat(baseline.files()).hasSize(14);
        assertThat(baseline.totalLines()).isEqualTo(18);
        assertThat(baseline.totalExpressions()).isEqualTo(19);
        assertThat(baseline.totalExecute()).isEqualTo(14);
        assertThat(baseline.totalPreview()).isEqualTo(5);

        assertNoGrowth(baseline, actual);
        if (actual.totalExpressions() < baseline.totalExpressions()
                || actual.files().size() < baseline.files().size()) {
            System.out.printf(
                    "Tool execution bypasses decreased to %d files/%d lines/%d expressions; "
                            + "update %s after the migration diff is reviewed.%n",
                    actual.files().size(), actual.totalLines(), actual.totalExpressions(),
                    BASELINE_RESOURCE);
        }
    }

    @Test
    void migrationGateDetectsSyntheticNewBypass(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("com/example/NewBypass.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                class NewBypass {
                    void run() throws Exception {
                        ToolExecutor executor;
                        executor.execute(call, context);
                    }
                }
                """, StandardCharsets.UTF_8);

        ScanResult actual = scan(tempDir);
        Baseline emptyBaseline = new Baseline(Map.of());

        assertThat(actual.totalExecute()).isEqualTo(1);
        assertThatThrownBy(() -> assertNoGrowth(emptyBaseline, actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("new direct-call file")
                .hasMessageContaining("com/example/NewBypass.java");
    }

    @Test
    void migrationGateDetectsSyntheticRegistryChain(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("com/example/ChainedBypass.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                class ChainedBypass {
                    void run() throws Exception {
                        toolRegistry.getExecutor("dangerous_write").orElseThrow()
                                .preview(call, context);
                    }
                }
                """, StandardCharsets.UTF_8);

        ScanResult actual = scan(tempDir);

        assertThat(actual.totalPreview()).isEqualTo(1);
        assertThatThrownBy(() -> assertNoGrowth(new Baseline(Map.of()), actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("new direct-call file")
                .hasMessageContaining("com/example/ChainedBypass.java");
    }

    private static Baseline loadBaseline() throws IOException {
        InputStream input = ToolExecutionArchitectureTest.class.getResourceAsStream(
                BASELINE_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Missing baseline resource " + BASELINE_RESOURCE);
        }

        Map<String, Counts> files = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\t");
                if (fields.length != 4) {
                    throw new IllegalStateException("Invalid baseline row: " + line);
                }
                files.put(fields[0], new Counts(
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3])));
            }
        }
        return new Baseline(files);
    }

    static ScanResult scan(Path sourceRoot) throws IOException {
        Map<String, Counts> files = new LinkedHashMap<>();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException("Source root does not exist: " + sourceRoot);
        }

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                String sanitized = stripCommentsAndLiterals(source);
                Set<String> receiverNames = toolExecutorIdentifiers(sanitized);
                Map<Integer, String> invocations = new LinkedHashMap<>();
                for (String receiverName : receiverNames) {
                    Pattern receiverCall = Pattern.compile(
                            "\\b" + Pattern.quote(receiverName) + "\\b"
                                    + "(?:\\s*\\.\\s*(?:get|orElseThrow)"
                                    + "\\s*\\([^;{}]*?\\))*"
                                    + "\\s*\\.\\s*(execute|preview)\\s*\\(");
                    collectInvocations(receiverCall.matcher(sanitized), invocations);
                }
                collectInvocations(REGISTRY_CHAIN_CALL.matcher(sanitized), invocations);

                int execute = 0;
                int preview = 0;
                Set<Integer> codeLines = new HashSet<>();
                for (Map.Entry<Integer, String> invocation : invocations.entrySet()) {
                    if ("execute".equals(invocation.getValue())) {
                        execute++;
                    } else {
                        preview++;
                    }
                    codeLines.add(lineNumberAt(sanitized, invocation.getKey()));
                }
                if (execute + preview > 0) {
                    String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                    files.put(relative, new Counts(execute, preview, codeLines.size()));
                }
            }
        }
        return new ScanResult(files);
    }

    private static Set<String> toolExecutorIdentifiers(String source) {
        Set<String> identifiers = new HashSet<>();
        Matcher matcher = TOOL_EXECUTOR_IDENTIFIER.matcher(source);
        while (matcher.find()) {
            identifiers.add(matcher.group(1));
        }
        return identifiers;
    }

    private static void collectInvocations(
            Matcher matcher,
            Map<Integer, String> invocations) {
        while (matcher.find()) {
            invocations.putIfAbsent(matcher.start(1), matcher.group(1));
        }
    }

    static void assertNoGrowth(Baseline baseline, ScanResult actual) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Counts> entry : actual.files().entrySet()) {
            Counts expected = baseline.files().get(entry.getKey());
            Counts found = entry.getValue();
            if (expected == null) {
                violations.add("new direct-call file: " + entry.getKey());
                continue;
            }
            if (found.execute() > expected.execute()) {
                violations.add(entry.getKey() + " execute expressions grew from "
                        + expected.execute() + " to " + found.execute());
            }
            if (found.preview() > expected.preview()) {
                violations.add(entry.getKey() + " preview expressions grew from "
                        + expected.preview() + " to " + found.preview());
            }
            if (found.lines() > expected.lines()) {
                violations.add(entry.getKey() + " direct-call lines grew from "
                        + expected.lines() + " to " + found.lines());
            }
        }
        if (actual.totalExpressions() > baseline.totalExpressions()) {
            violations.add("total expressions grew from " + baseline.totalExpressions()
                    + " to " + actual.totalExpressions());
        }
        if (actual.totalLines() > baseline.totalLines()) {
            violations.add("total code lines grew from " + baseline.totalLines()
                    + " to " + actual.totalLines());
        }
        if (actual.files().size() > baseline.files().size()) {
            violations.add("total files grew from " + baseline.files().size()
                    + " to " + actual.files().size());
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("Direct ToolExecutor migration baseline exceeded:\n- "
                    + String.join("\n- ", violations));
        }
    }

    private static int lineNumberAt(String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Replaces comments and literals with spaces while preserving newlines and offsets. */
    private static String stripCommentsAndLiterals(String source) {
        StringBuilder result = new StringBuilder(source.length());
        LexState state = LexState.CODE;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (state == LexState.CODE && current == '/' && next == '/') {
                result.append("  ");
                index++;
                state = LexState.LINE_COMMENT;
            } else if (state == LexState.CODE && current == '/' && next == '*') {
                result.append("  ");
                index++;
                state = LexState.BLOCK_COMMENT;
            } else if (state == LexState.CODE && current == '"') {
                result.append(' ');
                state = LexState.STRING;
            } else if (state == LexState.CODE && current == '\'') {
                result.append(' ');
                state = LexState.CHARACTER;
            } else if (state == LexState.LINE_COMMENT) {
                result.append(current == '\n' ? '\n' : ' ');
                if (current == '\n') {
                    state = LexState.CODE;
                }
            } else if (state == LexState.BLOCK_COMMENT) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    index++;
                    state = LexState.CODE;
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                }
            } else if (state == LexState.STRING || state == LexState.CHARACTER) {
                char terminator = state == LexState.STRING ? '"' : '\'';
                if (current == '\\' && next != '\0') {
                    result.append("  ");
                    index++;
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                    if (current == terminator) {
                        state = LexState.CODE;
                    }
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private enum LexState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }

    record Counts(int execute, int preview, int lines) {
        int expressions() {
            return execute + preview;
        }
    }

    record Baseline(Map<String, Counts> files) {
        Baseline {
            files = Map.copyOf(files);
        }

        int totalExecute() {
            return files.values().stream().mapToInt(Counts::execute).sum();
        }

        int totalPreview() {
            return files.values().stream().mapToInt(Counts::preview).sum();
        }

        int totalExpressions() {
            return files.values().stream().mapToInt(Counts::expressions).sum();
        }

        int totalLines() {
            return files.values().stream().mapToInt(Counts::lines).sum();
        }
    }

    record ScanResult(Map<String, Counts> files) {
        ScanResult {
            files = Map.copyOf(files);
        }

        int totalExecute() {
            return files.values().stream().mapToInt(Counts::execute).sum();
        }

        int totalPreview() {
            return files.values().stream().mapToInt(Counts::preview).sum();
        }

        int totalExpressions() {
            return files.values().stream().mapToInt(Counts::expressions).sum();
        }

        int totalLines() {
            return files.values().stream().mapToInt(Counts::lines).sum();
        }
    }
}
