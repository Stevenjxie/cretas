package com.cretas.aims.service.intent;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * spec §8.2 消费点①: mode=READ 时写工具绑定的意图不得出现在候选集里。
 *
 * <p>这是"洞②"的回归测试 —— {@code PythonIntentMatchRequest.mode/userPermissions} 两个字段
 * 从 Phase 2B-α 起就带着 javadoc 存在, 却从未被赋过值, 目录过滤在两侧都是死的。
 */
class IntentAccessModeFilterTest {

    private static final String FACTORY = "F001";

    private IntentConfigManagementService configService;
    private ToolRegistry toolRegistry;
    private IntentAccessModeFilter filter;

    /** 名字里没有任何写动词后缀的写工具 —— 正是旧启发式漏判的那一类。 */
    private static ToolExecutor tool(String name, AccessMode mode) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public AccessMode getAccessMode() { return mode; }
        };
    }

    private static AIIntentConfig intent(String code, String toolName, String sensitivity) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code);
        cfg.setIntentName(code);
        cfg.setToolName(toolName);
        cfg.setSensitivityLevel(sensitivity);
        return cfg;
    }

    private static IntentMatchResult.CandidateIntent candidate(String code) {
        return IntentMatchResult.CandidateIntent.builder()
                .intentCode(code).intentName(code).confidence(0.8).build();
    }

    @BeforeEach
    void setUp() {
        configService = mock(IntentConfigManagementService.class);
        toolRegistry = mock(ToolRegistry.class);
        filter = new IntentAccessModeFilter(configService, new WriteGuardService(), toolRegistry);

        // canvas_add_field 真的会 repository.save(), 但名字里没有 _CREATE/_UPDATE/... 后缀,
        // 意图 sensitivity 也是 LOW —— 旧启发式判它是读。只有声明能揭穿它。
        Map<String, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put("dish_sales_query", tool("dish_sales_query", AccessMode.READ));
        tools.put("canvas_add_field", tool("canvas_add_field", AccessMode.WRITE));
        tools.put("store_revenue_query", tool("store_revenue_query", AccessMode.READ));
        when(toolRegistry.getExecutor(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tools.get(inv.getArgument(0, String.class))));

        Map<String, AIIntentConfig> intents = new LinkedHashMap<>();
        intents.put("DISH_SALES_QUERY", intent("DISH_SALES_QUERY", "dish_sales_query", "LOW"));
        intents.put("CANVAS_ADD_FIELD", intent("CANVAS_ADD_FIELD", "canvas_add_field", "LOW"));
        intents.put("STORE_REVENUE_QUERY", intent("STORE_REVENUE_QUERY", "store_revenue_query", "LOW"));
        when(configService.getIntentByCode(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(intents.get(inv.getArgument(1, String.class))));
        when(configService.getIntentByCode(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(intents.get(inv.getArgument(0, String.class))));
    }

    private static List<String> codes(IntentMatchResult r) {
        List<String> out = new ArrayList<>();
        if (r.getTopCandidates() != null) {
            r.getTopCandidates().forEach(c -> out.add(c.getIntentCode()));
        }
        return out;
    }

    @Test
    @DisplayName("旧启发式判不出 canvas_add_field 是写, 声明可以")
    void declarationCatchesWhatHeuristicMisses() {
        WriteGuardService heuristic = new WriteGuardService();
        AIIntentConfig writeIntent = intent("CANVAS_ADD_FIELD", "canvas_add_field", "LOW");

        assertThat(heuristic.isWriteIntent(writeIntent))
                .as("这正是洞①: 名字与敏感度都看不出写意味").isFalse();
        assertThat(filter.isWriteIntent(writeIntent))
                .as("绑定 Tool 的 getAccessMode() 声明揭穿了它").isTrue();
    }

    @Test
    @DisplayName("mode=READ: WRITE 工具绑定的意图不出现在候选集里")
    void readModeDropsWriteBoundCandidatesFromTheCandidateSet() {
        IntentMatchResult result = IntentMatchResult.builder()
                .topCandidates(new ArrayList<>(List.of(
                        candidate("DISH_SALES_QUERY"),
                        candidate("CANVAS_ADD_FIELD"),
                        candidate("STORE_REVENUE_QUERY"))))
                .additionalIntents(new ArrayList<>(List.of(
                        IntentMatchResult.IntentMatch.builder()
                                .intentCode("CANVAS_ADD_FIELD").confidence(0.7).build(),
                        IntentMatchResult.IntentMatch.builder()
                                .intentCode("DISH_SALES_QUERY").confidence(0.9).build())))
                .isMultiIntent(Boolean.TRUE)
                .build();

        IntentMatchResult filtered = filter.filterForMode(result, FACTORY, "READ", null);

        assertThat(codes(filtered))
                .as("写意图必须被剔除, 读意图必须保留")
                .containsExactly("DISH_SALES_QUERY", "STORE_REVENUE_QUERY")
                .doesNotContain("CANVAS_ADD_FIELD");

        // 多意图执行路径同样不能留下写意图 —— additionalIntents 是真会被执行的
        assertThat(filtered.getAdditionalIntents()).extracting(IntentMatchResult.IntentMatch::getIntentCode)
                .containsExactly("DISH_SALES_QUERY");

        // 原对象不能被就地修改 (它可能正躺在 IntentResultCache 里, 缓存键不含 mode)
        assertThat(codes(result)).hasSize(3).contains("CANVAS_ADD_FIELD");
    }

    @Test
    @DisplayName("mode=OPERATE: 有权限的写意图保留, 无权限的剔除")
    void operateModeFiltersByPermission() {
        AIIntentConfig gated = intent("CANVAS_ADD_FIELD", "canvas_add_field", "LOW");
        gated.setRequiredPermission("canvas:write");
        when(configService.getIntentByCode(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(1, String.class);
                    if ("CANVAS_ADD_FIELD".equals(code)) return Optional.of(gated);
                    if ("DISH_SALES_QUERY".equals(code)) {
                        return Optional.of(intent("DISH_SALES_QUERY", "dish_sales_query", "LOW"));
                    }
                    return Optional.empty();
                });

        IntentMatchResult result = IntentMatchResult.builder()
                .topCandidates(new ArrayList<>(List.of(
                        candidate("DISH_SALES_QUERY"), candidate("CANVAS_ADD_FIELD"))))
                .build();

        assertThat(codes(filter.filterForMode(result, FACTORY, "OPERATE", Set.of("canvas:write"))))
                .as("有权限 → 保留").containsExactly("DISH_SALES_QUERY", "CANVAS_ADD_FIELD");

        assertThat(codes(filter.filterForMode(result, FACTORY, "OPERATE", Set.of("sales:read"))))
                .as("无权限 → 剔除").containsExactly("DISH_SALES_QUERY");

        assertThat(codes(filter.filterForMode(result, FACTORY, "OPERATE", null)))
                .as("权限集解析不出来时不做权限过滤, 交给下游真正的鉴权门")
                .containsExactly("DISH_SALES_QUERY", "CANVAS_ADD_FIELD");
    }

    @Test
    @DisplayName("mode 为空 / 未知值时不过滤 (旧调用方行为不变)")
    void noModeMeansNoFiltering() {
        IntentMatchResult result = IntentMatchResult.builder()
                .topCandidates(new ArrayList<>(List.of(
                        candidate("DISH_SALES_QUERY"), candidate("CANVAS_ADD_FIELD"))))
                .build();

        assertThat(filter.filterForMode(result, FACTORY, null, null)).isSameAs(result);
        assertThat(filter.filterForMode(result, FACTORY, "", null)).isSameAs(result);
        assertThat(filter.filterForMode(result, FACTORY, "SOMETHING_ELSE", null)).isSameAs(result);
    }

    @Test
    @DisplayName("fail-closed: 查不到配置 / 绑定了未注册工具的意图, 一律不进只读候选集")
    void unresolvableIntentsAreTreatedAsWrite() {
        assertThat(filter.isWriteIntent(FACTORY, "NO_SUCH_INTENT")).isTrue();
        assertThat(filter.isWriteIntent(FACTORY, null)).isTrue();
        assertThat(filter.isWriteIntent(intent("GHOST", "ghost_tool_not_registered", "LOW"))).isTrue();

        IntentMatchResult result = IntentMatchResult.builder()
                .topCandidates(new ArrayList<>(List.of(
                        candidate("DISH_SALES_QUERY"), candidate("NO_SUCH_INTENT"))))
                .build();
        assertThat(codes(filter.filterForMode(result, FACTORY, "READ", null)))
                .containsExactly("DISH_SALES_QUERY");
    }
}
