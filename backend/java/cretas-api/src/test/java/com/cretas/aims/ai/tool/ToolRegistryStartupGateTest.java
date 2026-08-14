package com.cretas.aims.ai.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.service.governance.ToolSimilarityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动期相似度闸必须**真的产出判决**, 而不是被 catch 吞成一行 WARN。
 *
 * <h3>为什么要有这个文件(它拦的是我自己漏掉的那一步)</h3>
 *
 * 生产实证 2026-08-14, 这道闸修了两轮:
 *
 * <ol>
 *   <li>原始(构造注入 ToolRegistry): 报
 *       {@code Error creating bean 'toolSimilarityService' ... 构造参数 0 ... 'toolRegistry':
 *       Requested bean is currently in creation}</li>
 *   <li>第一轮修(#2613, 改 {@code ObjectProvider}): **依然失败**, 只是报错变短成
 *       {@code Error creating bean 'toolRegistry': Requested bean is currently in creation}。
 *       因为 {@code getObject()} 仍然在 registry 自己的 {@code @PostConstruct} 里执行。</li>
 *   <li>第二轮修(本次): 启动期不再问容器要 registry, 由 registry 把手里的 executors 传进去。</li>
 * </ol>
 *
 * ⚠️ 第一轮为什么没被拦住 —— 当时那条断言验的是「**本服务能否构造**」(能), 而失效发生在
 * 「**执行时去取 registry**」那一步。断言和缺陷差了一格, 于是它绿得毫无异样。
 * ⇒ 本测试改为**驱动真实入口 {@code init()}**, 并且断言直接打在生产症状(那行 WARN)上。
 *
 * ⚠️ 这里用「取一次就抛」的 provider 模拟 registry in-creation 那一刻 ——
 * 只要启动闸还试图回头找 registry 这个 bean, 无论用哪种注入形式, 本测试都会红。
 */
@DisplayName("ToolRegistry 启动期相似度闸")
class ToolRegistryStartupGateTest {

    private static final String SKIPPED = "Similarity gate-check skipped due to error";

    private ListAppender<ILoggingEvent> appender;
    private Logger registryLogger;

    @BeforeEach
    void attachAppender() {
        registryLogger = (Logger) LoggerFactory.getLogger(ToolRegistry.class);
        appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        registryLogger.detachAppender(appender);
    }

    private String loggedText() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** registry 尚在创建中 —— 任何 {@code getObject()} 都会抛, 正如生产上那一刻。 */
    private static ObjectProvider<ToolRegistry> stillInCreation() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolRegistry> p = mock(ObjectProvider.class);
        when(p.getObject()).thenThrow(new IllegalStateException(
                "Error creating bean with name 'toolRegistry': Requested bean is currently in creation"));
        return p;
    }

    private static ToolExecutor tool(String name, String description) {
        ToolExecutor t = mock(ToolExecutor.class);
        when(t.getToolName()).thenReturn(name);
        when(t.getDescription()).thenReturn(description);
        // ⚠️ 这三个桩缺一不可: isEnabled 默认 false 会让工具根本注册不进 toolMap;
        // getActionType/getRiskLevel 返 null 会让 indexTool 的 computeIfAbsent 直接 NPE。
        when(t.isEnabled()).thenReturn(true);
        when(t.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(t.getRiskLevel()).thenReturn(ToolExecutor.RiskLevel.LOW);
        when(t.getDomainTags()).thenReturn(Set.of("test"));
        when(t.getParametersSchema()).thenReturn(Map.of(
                "properties", Map.of("factoryId", Map.of(), "productTypeId", Map.of())));
        return t;
    }

    private ToolRegistry registryWith(List<ToolExecutor> tools) {
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "toolExecutors", tools);
        ReflectionTestUtils.setField(registry, "toolSimilarityService",
                new ToolSimilarityService(stillInCreation()));
        return registry;
    }

    @Test
    @DisplayName("🔒 registry 仍在创建中时, 启动闸照样跑出判决 —— 这正是它两轮都没跑成的那一步")
    void startupGateProducesAVerdictWhileTheRegistryIsStillInCreation() {
        registryWith(List.of(
                tool("material_batch_query", "查询原材料批次库存"),
                tool("restaurant_dish_delete", "软删除餐厅菜品"))).init();

        String logged = loggedText();
        assertTrue(logged.contains("Similarity gate-check"),
                "启动闸完全没输出 —— 它没跑。实际日志:\n" + logged);
        assertTrue(!logged.contains(SKIPPED),
                "启动闸又被异常吞了(这就是生产上那行 WARN)。实际日志:\n" + logged);
    }

    @Test
    @DisplayName("闸不是空跑: 两个描述相同的工具必须被它报出来")
    void startupGateActuallyReportsSimilarPairs() {
        String same = "查询原材料批次库存数量与到期日, 支持按仓库过滤";
        registryWith(List.of(tool("batch_query_a", same), tool("batch_query_b", same))).init();

        String logged = loggedText();
        assertTrue(!logged.contains(SKIPPED), "闸被异常吞了, 谈不上报不报。实际日志:\n" + logged);
        assertTrue(logged.contains("found 1 similar tool pairs"),
                "两个描述完全相同的工具没被报出来 —— 闸在空跑。实际日志:\n" + logged);
    }

    @Test
    @DisplayName("反向: 毫不相干的两个工具不该被报 —— 否则闸在所有输入上都响, 等于没有")
    void startupGateDoesNotFireOnUnrelatedTools() {
        registryWith(List.of(
                tool("material_batch_query", "查询原材料批次库存数量与到期日"),
                tool("restaurant_dish_delete", "软删除餐厅菜品, 需要高风险确认"))).init();

        String logged = loggedText();
        assertTrue(logged.contains("no highly similar tool pairs found"),
                "不相干的工具被判成相似对了。实际日志:\n" + logged);
    }

    @Test
    @DisplayName("闸看到的工具数 == 真正注册进 toolMap 的数量(不是注入进来的数量)")
    void gateSeesTheRegisteredToolsNotTheInjectedOnes() {
        ToolExecutor disabled = tool("disabled_tool", "被禁用的工具");
        when(disabled.isEnabled()).thenReturn(false);

        ToolRegistry registry = registryWith(List.of(
                tool("a_tool", "查询原材料批次库存"), tool("b_tool", "软删除餐厅菜品"), disabled));
        registry.init();

        // 3 个注入, 1 个禁用 → 闸应当只看到 2 个。
        assertEquals(2, registry.getAllExecutors().size());
        assertTrue(!loggedText().contains(SKIPPED));
    }
}
