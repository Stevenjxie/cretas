package com.cretas.aims.service.governance;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 相似度治理闸必须**真的跑起来**。
 *
 * 背景(2026-08-14 生产实证): 这道闸自引入起**一次都没执行过** —— 日志里
 * 354 条全是同一句
 * `Similarity gate-check skipped due to error: ... Requested bean is currently in creation`,
 * 而成功那行(`no highly similar tool pairs found` / `found N similar tool pairs`)
 * 一次都没出现。
 *
 * 成因: {@link ToolRegistry} 在自己的 {@code @PostConstruct} 里调
 * {@code runSimilarityGateCheck()}, 而 {@link ToolSimilarityService} 当时是**构造注入**
 * ToolRegistry —— 此刻 registry 还在创建中。registry 那侧虽然写了 {@code @Autowired @Lazy},
 * 但 {@code @Lazy} 只延后代理创建, 挡不住「代理方法被调用时对端仍在构造」。
 *
 * ⚠️ 异常被 catch 吞成一行 WARN, 所以「闸没跑」和「闸跑了且没发现问题」在日志上
 * 长得几乎一样(都不报错) —— 这是这类失效难以自查的根源。
 *
 * <h3>🔴 本文件不足以守住那个缺陷(2026-08-14 复盘)</h3>
 *
 * 本文件里的 {@code constructionDoesNotRequireTheRegistryToBeReady} 只验了
 * 「**本服务能否被构造**」。而真正的失效发生在下一步 ——
 * 「**执行时回头去取 registry**」。差这一格, 于是 #2613 上线后闸**依然一次没跑成**,
 * 生产报错只是从长变短, 而本文件全绿。
 *
 * ⇒ 真正的守卫在 {@code ToolRegistryStartupGateTest}: 它驱动**真实入口 init()**,
 * 断言直接打在生产症状(那行 {@code Similarity gate-check skipped due to error})上。
 * 实测把调用改回无参重载: 那个文件 4/4 全红, **本文件 0 失败**。
 *
 * 范围澄清: 从未执行的是**启动闸**这条路; {@code ToolHealthMonitor} 的定时扫描一直是通的。
 */
@DisplayName("Tool 相似度治理闸")
class ToolSimilarityGateRunsContractTest {

    /** 构造期 registry 还没就绪 —— 用「取一次就抛」的 provider 模拟这个时刻。 */
    private static ObjectProvider<ToolRegistry> notReadyYet(AtomicInteger calls) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolRegistry> p = mock(ObjectProvider.class);
        when(p.getObject()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("Requested bean is currently in creation");
        });
        return p;
    }

    private static ObjectProvider<ToolRegistry> ready(ToolRegistry registry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolRegistry> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(registry);
        return p;
    }

    private static ToolExecutor tool(String name, String description) {
        ToolExecutor t = mock(ToolExecutor.class);
        when(t.getToolName()).thenReturn(name);
        when(t.getDescription()).thenReturn(description);
        when(t.getParametersSchema()).thenReturn(Map.of(
                "properties", Map.of("factoryId", Map.of(), "productTypeId", Map.of())));
        return t;
    }

    @Test
    @DisplayName("registry 尚未就绪时【构造本服务】不能抛（必要但**不充分** —— 执行那步见 ToolRegistryStartupGateTest）")
    void constructionDoesNotRequireTheRegistryToBeReady() {
        AtomicInteger calls = new AtomicInteger();

        // 构造期一次都不许去取 registry。构造注入的老写法在这一步就会失败。
        ToolSimilarityService service = new ToolSimilarityService(notReadyYet(calls));

        assertEquals(0, calls.get(),
                "构造期不能去取 ToolRegistry —— 取了就会在 registry 的 @PostConstruct 里炸");
        assertTrue(service != null);
    }

    @Test
    @DisplayName("registry 就绪后能真的扫出相似对（证明闸不是空跑）")
    void detectsSimilarPairsOnceTheRegistryIsReady() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String same = "查询原材料批次库存数量与到期日, 支持按仓库过滤";
        // ⚠️ 先把 mock 建好再 stub —— 在 when(...) 参数里嵌套调用会 stub 的工厂方法,
        // Mockito 会判成 UnfinishedStubbing。
        ToolExecutor a = tool("material_batch_query_a", same);
        ToolExecutor b = tool("material_batch_query_b", same);
        when(registry.getAllExecutors()).thenReturn(List.of(a, b));

        List<ToolSimilarityService.SimilarToolPair> pairs =
                new ToolSimilarityService(ready(registry)).detectSimilarTools();

        assertFalse(pairs.isEmpty(), "两个描述完全相同的工具必须被判为相似对");
        assertEquals(1.0, pairs.get(0).getDescriptionSimilarity(), 0.001);
    }

    @Test
    @DisplayName("反向: 描述与参数都不同的工具不算相似 —— 闸不是恒真")
    void doesNotFlagUnrelatedTools() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor a = tool("material_batch_query", "查询原材料批次库存数量与到期日");
        ToolExecutor b = mock(ToolExecutor.class);
        when(b.getToolName()).thenReturn("restaurant_dish_delete");
        when(b.getDescription()).thenReturn("软删除餐厅菜品, 需要高风险确认");
        when(b.getParametersSchema()).thenReturn(Map.of(
                "properties", Map.of("dishId", Map.of())));
        when(registry.getAllExecutors()).thenReturn(List.of(a, b));

        assertTrue(new ToolSimilarityService(ready(registry)).detectSimilarTools().isEmpty(),
                "毫不相干的两个工具不该被判为相似 —— 否则这道闸在所有输入上都响, 等于没有");
    }

    @Test
    @DisplayName("registry 那侧仍然要把失败吞成 WARN 而不是让启动失败（不改这个语义）")
    void registryStillSwallowsTheFailureRatherThanBreakingStartup() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/ai/tool/ToolRegistry.java"), StandardCharsets.UTF_8);
        int at = src.indexOf("runSimilarityGateCheck()");
        assertTrue(at > 0, "找不到 runSimilarityGateCheck 锚点");
        // 治理闸挂了不该拖垮整个应用启动 —— 这条语义保持不变, 本 PR 只让它别再挂。
        assertTrue(src.contains("Similarity gate-check skipped due to error"),
                "失败仍应降级成 WARN, 不要改成抛出让启动失败");
    }
}
