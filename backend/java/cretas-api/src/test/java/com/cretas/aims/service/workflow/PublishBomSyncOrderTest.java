package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 2026-08-08：BOM 投影必须跑在「要求 ACTIVE BOM」那道闸的**前面**。
 *
 * <h2>这条闸是被一次真机验证逼出来的</h2>
 * 3-3 删掉了 {@code WORKFLOW_ACTIVE_BOM_REQUIRED}（同步路径 + preflight 两处），部署上 prod
 * 之后真机一发布，仍然被拦下 —— 报的却是**另一个错误码**：
 * 「成品产出 Cell … 尚未配置并激活新版 BOM 配方」。
 *
 * 那是同一条规则的**第三个承载点**：{@code ProductProcessWorkflowCatalogValidator
 * #validateFinishedOutputBoms}，由 {@code validateForPublish} 带进来。而它在
 * {@code publishAndActivate} 里跑在 {@code synchronizeForPublish} 的**前面** ——
 * 闸在投影前面，投影就永远跑不到，3-3 整个是空转的。
 *
 * <h2>判据</h2>
 * 「改了一条规则」要问的不是「我改了几处」，而是「**发布这条链路上还有谁在判同一件事**」。
 * 这里用源码顺序把它钉死：源码位置本身就是语义。
 */
class PublishBomSyncOrderTest {

    private String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImpl.java"));
    }

    @Test
    void bomSyncRunsBeforeTheActiveBomGate() throws Exception {
        String src = source();
        int syncAt = src.indexOf("workflowBomSynchronizationService.synchronizeForPublish(");
        assertTrue(syncAt > 0, "同步调用应存在");

        // 同步之前那一段里，不允许出现要求 ACTIVE BOM 的 validateForPublish。
        String beforeSync = src.substring(0, syncAt);
        int lastCatalogPublish = beforeSync.lastIndexOf("catalogValidator.validateForPublish(");
        int guard = beforeSync.lastIndexOf("if (synchronizeBom)");
        assertTrue(lastCatalogPublish < 0 || lastCatalogPublish < guard,
                "同步之前不许无条件调 catalogValidator.validateForPublish —— 它含 ACTIVE BOM 闸, "
                        + "会让投影永远跑不到(2026-08-08 真机实证)");
    }

    @Test
    void synchronizeBranchUsesTheBomConfigurationVariant() throws Exception {
        assertTrue(source().contains("catalogValidator.validateForBomConfiguration(factoryId, productTypeId, definition)"),
                "synchronizeBom 分支应改用不要求 ACTIVE BOM 的那个变体");
    }

    @Test
    void aFullValidationStillRunsAfterTheSync() throws Exception {
        String src = source();
        int syncAt = src.indexOf("workflowBomSynchronizationService.synchronizeForPublish(");
        String afterSync = src.substring(syncAt);
        // ⛔ 兜底不能省: 少了它, 投影失败会被静默放过 —— 发布出一个没有 BOM 的 Workflow,
        //    报工时无料可扣, 而且没有任何报错。
        assertTrue(afterSync.contains("catalogValidator.validateForPublish("),
                "同步之后必须再跑一次完整校验兜底");
    }

    @Test
    void theProjectionReusesEnsureDraftSoCoProductsAreCovered() throws Exception {
        String impl = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java"));
        int at = impl.indexOf("private BomRecipe projectActiveBomFromRevision");
        assertTrue(at > 0, "投影方法应存在");
        String body = impl.substring(at, at + 2200);
        // 自己 createRecipe 只能建主产出那一份, 联产的第二个终端产出仍然过不了
        // validateFinishedOutputBoms —— 必须走建整个家族的 ensureDraft。
        assertTrue(body.contains("ensureDraft(factoryId, productTypeId, targetRevision.getId())"),
                "投影必须复用 ensureDraft(按家族建草稿 + 钉在目标 revision 上)");
        assertTrue(body.contains("activateRecipe("), "建完要激活");
    }

    @Test
    void theDuplicateRawSeedingIsGone() throws Exception {
        String svc = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/bom/BomWorkflowRevisionService.java"));
        // ensureDraft 本来就从画布播 RAW 行; 我原来另写了一份, 同一件事两处实现必然漂移。
        assertTrue(!svc.contains("List<CreateBomRecipeRequest.BomRecipeItemDTO> projectRawMaterialItems"),
                "重复的 RAW 播种实现应已删除, 统一走 ensureDraft");
    }
}
