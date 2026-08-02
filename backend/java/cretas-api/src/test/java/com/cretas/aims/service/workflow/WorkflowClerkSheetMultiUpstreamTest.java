package com.cretas.aims.service.workflow;

import com.cretas.aims.service.workflow.impl.WorkflowClerkSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「同一物料可投多批」开关的解析契约 —— 客户张权 2026-08-02 报障。
 *
 * <p>缺陷: {@code WorkflowClerkSheetServiceImpl} 原来写的是
 * {@code .allowMultipleUpstreamSources(upstreamInputCount > 1)} —— **无视用户在 Workflow
 * 画布上配的开关, 当场按端口数重算一遍**。六膳门酱鸭腿三道工序图里 allowMultipleUpstreamSources
 * 全是 true (备份实证), 但每道去掉原料后只有 1 个上游端口, 于是运行时一律回落成 false,
 * 装箱面对 3 批酱制鸭腿只能选 1 批, 客户被迫开 3 行、出 3 个成品批次。
 *
 * <p>「几种物料」(端口数) 与「同种物料几批」(混批) 是两件事, 前者不能替后者做主。
 *
 * <p>本测试直接打解析函数, 不起 Spring —— 它是个纯函数, 起容器只会让判据变慢变脆。
 */
class WorkflowClerkSheetMultiUpstreamTest {

    /** 酱鸭腿「装箱」节点: 图里配了 true, 而它只有 1 个上游端口 (端口数判据会给 false)。 */
    private static final String DUCK_NODES_JSON = """
            [
              {"id":"material:raw:1","kind":"RAW_MATERIAL","data":{"name":"YL-DL-冷冻鸭腿"}},
              {"id":"process:pack:1785650045379","kind":"PROCESS","data":{
                 "processName":"装箱",
                 "allowMultipleUpstreamSources":true,
                 "allowFinishedGoodsSource":false}},
              {"id":"process:cook:1785585001","kind":"PROCESS","data":{
                 "processName":"熟制",
                 "allowMultipleUpstreamSources":false}}
            ]
            """;

    private boolean resolve(String nodesJson, String nodeId, boolean fallback) throws Exception {
        Method m = WorkflowClerkSheetServiceImpl.class.getDeclaredMethod(
                "resolveAllowMultipleUpstreamSources", String.class, String.class, boolean.class);
        m.setAccessible(true);
        // 该方法不碰任何实例字段 —— 用全 null 依赖构造出来的实例即可, 不必起 Spring。
        return (boolean) m.invoke(newInstanceWithNullDeps(), nodesJson, nodeId, fallback);
    }

    private static WorkflowClerkSheetServiceImpl newInstanceWithNullDeps() throws Exception {
        java.lang.reflect.Constructor<?> ctor = WorkflowClerkSheetServiceImpl.class
                .getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        return (WorkflowClerkSheetServiceImpl) ctor.newInstance(args);
    }

    @Test
    @DisplayName("🔴 回归: 图里配了 true 就按 true —— 哪怕只有 1 个上游端口 (端口数判据会说 false)")
    void graphConfigWinsOverPortCount() throws Exception {
        assertThat(resolve(DUCK_NODES_JSON, "process:pack:1785650045379", false))
                .as("装箱图里配了 true; 若为 false 说明又变回按端口数重算, 客户仍只能选一批")
                .isTrue();
    }

    @Test
    @DisplayName("图里配了 false 就按 false —— 不会因为端口多就擅自打开")
    void graphFalseIsRespected() throws Exception {
        assertThat(resolve(DUCK_NODES_JSON, "process:cook:1785585001", true)).isFalse();
    }

    @Test
    @DisplayName("节点没配这个字段 → 回落端口数判据 (老工作流零回归)")
    void missingFlagFallsBackToPortCount() throws Exception {
        String noFlag = """
                [{"id":"process:x","kind":"PROCESS","data":{"processName":"某工序"}}]
                """;
        assertThat(resolve(noFlag, "process:x", true)).isTrue();
        assertThat(resolve(noFlag, "process:x", false)).isFalse();
    }

    @Test
    @DisplayName("找不到该节点 / 空 json / null → 一律回落, 不抛异常")
    void unresolvableInputsFallBack() throws Exception {
        assertThat(resolve(DUCK_NODES_JSON, "process:not-there", true)).isTrue();
        assertThat(resolve(null, "process:pack:1785650045379", true)).isTrue();
        assertThat(resolve("", "process:pack:1785650045379", false)).isFalse();
        assertThat(resolve(DUCK_NODES_JSON, null, true)).isTrue();
    }

    @Test
    @DisplayName("json 坏掉也只回落, 不能让整张报工单打不开")
    void brokenJsonFallsBackInsteadOfThrowing() throws Exception {
        assertThat(resolve("{ 这不是合法 json", "process:pack:1785650045379", true)).isTrue();
        assertThat(resolve("{\"not\":\"an array\"}", "process:pack:1785650045379", false)).isFalse();
    }
}
