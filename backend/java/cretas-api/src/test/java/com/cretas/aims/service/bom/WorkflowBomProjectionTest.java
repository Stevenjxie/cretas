package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 3-3：删掉 {@code WORKFLOW_ACTIVE_BOM_REQUIRED} 前置，改为从画布定义投影出 BOM。
 *
 * <h2>这条闸盯的是什么</h2>
 * 投影最容易出的两种错，恰好方向相反：
 * <ol>
 *   <li><b>投多了</b> —— 编一个主料用量出来。那正是它要消灭的问题的翻版：
 *       2026-08-05 那份「主料用量为空的 ACTIVE BOM」之所以有害，不是因为空，
 *       <b>是因为它是编的</b>。空用量在这条口径下是合法且诚实的表达。</li>
 *   <li><b>投少了/投错了</b> —— 把没绑 SKU 的节点也投出去，落库直接违反
 *       {@code material_type_id → raw_material_types(id)} 的硬外键。</li>
 * </ol>
 *
 * <h2>为什么可以只投影 RAW</h2>
 * 激活 BOM 的两道闸实际判的是（读代码，不是读提示语）：
 * <ul>
 *   <li>{@code ProductConfigurationReadinessService:241} —— 只数 {@code rawCount > 0}（<b>行数</b>）</li>
 *   <li>{@code BomRecipeServiceImpl#validateActivatableItems} —— 注释写着
 *       「原料与工序辅料的 BOM 行表达资格/关系，<b>固定用量可留空</b>；包材必须有正数用量」</li>
 * </ul>
 * 所以「有几条 RAW 行」才是判据，用量不是。
 */
class WorkflowBomProjectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BomWorkflowRevisionService serviceWith(String nodesJson) throws Exception {
        BomWorkflowRevisionService service = new BomWorkflowRevisionService(
                null, null, null, null, null, null, null, null);
        // 只需要 revisionSnapshotService 能把 nodesJson 解析成 definition；其余依赖这条路径用不到。
        WorkflowRevisionSnapshotService snapshot =
                new WorkflowRevisionSnapshotService(null, null, null, objectMapper);
        inject(service, snapshot);
        return service;
    }

    private void inject(BomWorkflowRevisionService target, WorkflowRevisionSnapshotService value)
            throws Exception {
        for (Field field : BomWorkflowRevisionService.class.getDeclaredFields()) {
            if (field.getType() == WorkflowRevisionSnapshotService.class) {
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
        }
        throw new IllegalStateException("BomWorkflowRevisionService 没有 WorkflowRevisionSnapshotService 依赖了?");
    }

    private ProductProcessWorkflowRevision revision(String nodesJson) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setFactoryId("F006");
        revision.setProductTypeId("PT-1");
        revision.setDefinitionVersion(1);
        revision.setSchemaVersion(1);
        revision.setNodesJson(nodesJson);
        revision.setEdgesJson("[]");
        revision.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        return revision;
    }

    private static final String TWO_RAW_ONE_UNBOUND = """
            [
              {"id":"raw:1","kind":"RAW_MATERIAL","position":{"x":0,"y":0},
               "data":{"name":"整鸡","skuId":"RMT-1","baseUnit":"kg","bound":true}},
              {"id":"raw:2","kind":"RAW_MATERIAL","position":{"x":0,"y":160},
               "data":{"name":"冰水","skuId":"RMT-2","baseUnit":"kg","bound":true}},
              {"id":"raw:3","kind":"RAW_MATERIAL","position":{"x":0,"y":320},
               "data":{"name":"待绑定","skuId":"","bound":false}},
              {"id":"process:1","kind":"PROCESS","position":{"x":260,"y":0},
               "data":{"processName":"卤制","ports":[]}},
              {"id":"fin:1","kind":"FINISHED_GOOD","position":{"x":520,"y":0},
               "data":{"name":"成品","skuId":"SKU-1","baseUnit":"袋"}}
            ]
            """;

    @Test
    void projectsOneRawItemPerBoundRawMaterialNode() throws Exception {
        List<CreateBomRecipeRequest.BomRecipeItemDTO> items =
                serviceWith(TWO_RAW_ONE_UNBOUND).projectRawMaterialItems(revision(TWO_RAW_ONE_UNBOUND));

        assertEquals(2, items.size(), "只投影绑定了 SKU 的原料节点");
        assertEquals("RMT-1", items.get(0).getMaterialTypeId());
        assertEquals("RMT-2", items.get(1).getMaterialTypeId());
        assertTrue(items.stream().allMatch(item -> "RAW".equals(item.getMaterialCategory())));
    }

    @Test
    void neverInventsAMainMaterialQuantity() {
        // ⛔ 这是本文件最重要的一条。编一个用量出来 = 重演 2026-08-05 那份假 BOM。
        //    空用量是合法的：主料按报工实际重量走。
        List<CreateBomRecipeRequest.BomRecipeItemDTO> items;
        try {
            items = serviceWith(TWO_RAW_ONE_UNBOUND).projectRawMaterialItems(revision(TWO_RAW_ONE_UNBOUND));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        items.forEach(item -> assertNull(item.getStandardQuantity(),
                "主料用量必须留空 —— 编一个出来会被下游当成用户的真实意图"));
    }

    @Test
    void skipsNodesWithoutASkuBinding() throws Exception {
        // 没绑 SKU 的节点投出去会违反 material_type_id → raw_material_types(id) 的硬外键。
        List<CreateBomRecipeRequest.BomRecipeItemDTO> items =
                serviceWith(TWO_RAW_ONE_UNBOUND).projectRawMaterialItems(revision(TWO_RAW_ONE_UNBOUND));
        assertTrue(items.stream().noneMatch(item ->
                        item.getMaterialTypeId() == null || item.getMaterialTypeId().isBlank()),
                "不许投出没有物料 id 的行");
    }

    @Test
    void onlyRawMaterialNodesAreProjected() throws Exception {
        // 工序/成品节点不是 BOM 的 RAW 行。把它们投出去会造出指向 product_types 的假物料行。
        List<CreateBomRecipeRequest.BomRecipeItemDTO> items =
                serviceWith(TWO_RAW_ONE_UNBOUND).projectRawMaterialItems(revision(TWO_RAW_ONE_UNBOUND));
        assertTrue(items.stream().noneMatch(item -> "SKU-1".equals(item.getMaterialTypeId())),
                "成品 SKU 不能被当成原料投影出去");
    }

    @Test
    void emptyCanvasProjectsNothing() throws Exception {
        String empty = "[]";
        assertTrue(serviceWith(empty).projectRawMaterialItems(revision(empty)).isEmpty(),
                "空画布投影出空列表 —— 调用方据此明确报错, 而不是造一份空 BOM");
    }

    @Test
    void carriesTheBaseUnitWhenTheCanvasHasOne() throws Exception {
        List<CreateBomRecipeRequest.BomRecipeItemDTO> items =
                serviceWith(TWO_RAW_ONE_UNBOUND).projectRawMaterialItems(revision(TWO_RAW_ONE_UNBOUND));
        assertEquals("kg", items.get(0).getUnit());
    }

    @Test
    void definitionParsingIsTheOnlyInputSoNoDbAccessIsNeeded() {
        // 这条不是覆盖率填充: 它证明投影是**纯函数式**的 —— 只看 revision 的 nodesJson。
        // 若将来有人让它去查库(比如"顺手补上物料的默认用量"), 这个不带任何 repository 的
        // 构造就会当场炸 —— 那正是我们想立刻知道的事。
        ProductProcessWorkflowDTO parsed = new WorkflowRevisionSnapshotService(null, null, null, objectMapper)
                .definition(revision(TWO_RAW_ONE_UNBOUND));
        assertEquals(5, parsed.getNodes().size());
    }
}
