package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 阶段 3（版本合一）的**机制证明**：改克数 → 换 revisionHash → 新工艺版本。
 *
 * <h2>为什么这条要单独钉</h2>
 * 方案 B 的全部收益都建立在一句话上：「改画布 = 造新工艺版本，旧版本原样留给已排产批次」。
 * 而「改克数也算改画布」这件事，靠的是 {@code materialBindings} 落进 {@code nodesJson}、
 * 而 {@link WorkflowRevisionSnapshotService#hash} 算的就是整个 nodesJson。
 *
 * 这是一条**隐式**的因果链：中间任何一环变了（序列化加了字段白名单、hash 换成只算
 * 节点 id/kind、data 被后端过滤成已知键），克数就会改了却**不产生新版本** ——
 * 而且不会有任何报错：用户改完保存，版本号不动，已排产批次却跟着变了。
 * 那正是方案 B 想避免的事，所以这里把因果链两端都钉死。
 *
 * <h2>顺带证明的另一半</h2>
 * 「既有 revision 的 hash 不会因为本次改动而变」—— 因为它们的 nodesJson 一个字没动。
 * 下面 {@code sameNodesJsonKeepsSameHash} 就是这条：同样的 nodesJson 必须得到同样的 hash。
 * 真机侧的对应证据：prod 上 6 个被生产计划钉住的 revision，md5(nodes_json) 与
 * 改前逐条相同（见 HANDOFF）。
 */
class MaterialBindingsInRevisionHashTest {

    private final WorkflowRevisionSnapshotService service =
            new WorkflowRevisionSnapshotService(null, null, null, new ObjectMapper());

    /** 一道熟制工序，带一味调料。dosage 由参数给，其余逐字相同。 */
    private String nodesJsonWithDosage(String dosagePerKgG) {
        return """
                [{"id":"process:1","kind":"PROCESS","position":{"x":0,"y":0},
                  "data":{"workProcessId":"WP-1","processName":"卤制","processCategory":"熟制",
                          "inputUnit":"kg","outputUnit":"kg","reportingRequired":true,
                          "ports":[],"conversionRule":{"mode":"ACTUAL_WEIGHT"},
                          "materialBindings":[{"materialTypeId":"RMT-1","materialName":"八角",
                                               "dosagePerKgG":%s}]}}]
                """.formatted(dosagePerKgG);
    }

    private ProductProcessWorkflow workflowWith(String nodesJson) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("PT-1");
        workflow.setDefinitionVersion(1);
        workflow.setSchemaVersion(1);
        workflow.setNodesJson(nodesJson);
        workflow.setEdgesJson("[]");
        workflow.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        return workflow;
    }

    @Test
    void changingOnlyTheDosageChangesTheRevisionHash() {
        // 这就是「改克数产生新版本」的全部机制。两份定义只差一个数字。
        String before = service.hash(workflowWith(nodesJsonWithDosage("12.5")));
        String after = service.hash(workflowWith(nodesJsonWithDosage("13.5")));

        assertNotEquals(before, after,
                "改克数必须换 hash —— 否则改了配方却不产生新工艺版本, 已排产批次会被静默改掉");
    }

    @Test
    void changingOnlyThePotRatioChangesTheRevisionHash() {
        String withoutRatio = service.hash(workflowWith(nodesJsonWithDosage("12.5")));
        String withRatio = service.hash(workflowWith("""
                [{"id":"process:1","kind":"PROCESS","position":{"x":0,"y":0},
                  "data":{"workProcessId":"WP-1","processName":"卤制","processCategory":"熟制",
                          "inputUnit":"kg","outputUnit":"kg","reportingRequired":true,
                          "ports":[],"conversionRule":{"mode":"ACTUAL_WEIGHT"},
                          "materialBindings":[{"materialTypeId":"RMT-1","materialName":"八角",
                                               "dosagePerKgG":12.5,"subsequentPotRatio":60}]}}]
                """));

        assertNotEquals(withoutRatio, withRatio, "加锅序比例同样是改配方, 同样要换版本");
    }

    @Test
    void sameNodesJsonKeepsSameHash() {
        // 另一半: 没改的东西不许换 hash。既有 revision 的 nodesJson 一个字没动,
        // 它们的 hash 就必须原样 —— 生产计划钉的 selected_workflow_revision_hash 才不会失配。
        assertEquals(
                service.hash(workflowWith(nodesJsonWithDosage("12.5"))),
                service.hash(workflowWith(nodesJsonWithDosage("12.5"))),
                "同样的定义必须得到同样的 hash");
    }

    @Test
    void hashCoversTheWholeNodesJsonNotJustIdentity() {
        // ⛔ 阴性对照: 如果 hash 只算节点 id/kind(而不是整份 data), 上面两条"改了要换 hash"
        //    会全部变绿而机制其实是坏的 —— 因为两份定义的 id/kind 本来就相同。
        //    这条用一个**只改 data 里无关字段**的例子, 再证一次 hash 确实覆盖整个 data。
        String a = service.hash(workflowWith(nodesJsonWithDosage("12.5")));
        String b = service.hash(workflowWith("""
                [{"id":"process:1","kind":"PROCESS","position":{"x":0,"y":0},
                  "data":{"workProcessId":"WP-1","processName":"卤制(改名)","processCategory":"熟制",
                          "inputUnit":"kg","outputUnit":"kg","reportingRequired":true,
                          "ports":[],"conversionRule":{"mode":"ACTUAL_WEIGHT"},
                          "materialBindings":[{"materialTypeId":"RMT-1","materialName":"八角",
                                               "dosagePerKgG":12.5}]}}]
                """));
        assertNotEquals(a, b, "hash 必须覆盖整个节点 data, 不只是 id/kind");
    }
}
