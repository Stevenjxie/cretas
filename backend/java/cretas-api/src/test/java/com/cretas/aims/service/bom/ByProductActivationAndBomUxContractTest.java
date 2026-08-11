package com.cretas.aims.service.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三条 F006 最小闭环遗留问题的源码级契约 (P4 / P7 / P8)。
 *
 * <p>都发生在<b>深层私有方法 + 需要完整 Spring/JPA 上下文</b>的路径上, 语义用例覆盖成本极高
 * (相关的三个既有测试类在 main 上本来就是红的)。这里钉住的是「关键结构还在不在」——
 * 与 {@code ApInvoiceUniquePerReceiptMigrationContractTest} 同一手法。
 */
@DisplayName("契约 — 副产品可激活 / 包材必填一次收齐 / Workflow 物料节点缺 SKU 不静默")
class ByProductActivationAndBomUxContractTest {

    private static final Path BOM = Path.of("src", "main", "java", "com", "cretas", "aims",
            "service", "bom", "impl", "BomRecipeServiceImpl.java");
    private static final Path VALIDATOR = Path.of("src", "main", "java", "com", "cretas", "aims",
            "service", "validation", "ProductProcessWorkflowUnitValidator.java");

    private String bom() throws Exception {
        return Files.readString(BOM);
    }

    /** 取 activateRecipe 里那段逐成员校验循环。 */
    private String activationLoop(String src) {
        int start = src.indexOf("validateByProductCreditRules(family);");
        assertThat(start).as("应能定位到激活流程").isGreaterThan(0);
        int end = src.indexOf("Archive competing versions", start);
        assertThat(end).as("应能定位到激活流程结尾").isGreaterThan(start);
        return src.substring(start, end);
    }

    /**
     * 🔴 2026-08-09 真机事故: 「生效该草稿」激活了属于旧工艺版本线的草稿, 之后
     * 建计划 409 / 发布按钮禁用 / 另存报错 —— 三条路全堵死且页面无告警, 只能改库救回。
     *
     * <p>⚠️ 这是**结构契约**不是行为测试(同本类其余用例: activateRecipe 在深层私有路径上,
     * 语义用例需要完整 Spring/JPA 且相关测试类在 main 上本就是红的)。它能保证闸还在、
     * 位置还在校验之前、判据仍是 activeWorkflowId; 它**不能**证明运行期真的拦得住。
     */
    @Test
    @DisplayName("🔴 激活前必须校验草稿钉的工艺 = 当前启用的工艺(否则激活出一个建不了计划的状态)")
    void activationMustRejectStaleWorkflowLineage() throws Exception {
        String src = bom();

        assertThat(src)
                .as("activateRecipe 必须调用版本线校验")
                .contains("requireDraftMatchesEnabledWorkflow(factoryId, family, publishingWorkflowId);");
        assertThat(src)
                .as("判据必须是「当前启用的工艺记录」activeWorkflowId, 不是别的")
                .contains("getActiveWorkflowId()");
        assertThat(src)
                .as("必须给出可执行的出口(回画布重新发布), 而不是只报错")
                .contains("BOM_DRAFT_STALE_WORKFLOW_LINEAGE");

        // ⛔ 必须把范围限定在 activateRecipe 方法体内再比位置:
        //    validateFamilyContracts 在本文件出现 3 次, 裸 indexOf 会命中**更靠前**的那个,
        //    于是「闸在校验之前」这条断言恒假 —— 我第一版正是这么写的, 当场红在自己的锚点上。
        // 2026-08-11: 对外的 activateRecipe 现在只是一层 delegate(补 publishingWorkflowId=null),
        // 真正的流程在私有重载里 —— 锚点跟着搬, 否则量的是那三行 delegate。
        int methodStart = src.indexOf("private BomRecipe activateRecipe(");
        assertThat(methodStart).as("应能定位到 activateRecipe 私有重载").isGreaterThan(0);
        String method = src.substring(methodStart);
        int guardAt = method.indexOf("requireDraftMatchesEnabledWorkflow(factoryId, family, publishingWorkflowId);");
        int validateAt = method.indexOf("validateFamilyContracts(factoryId, family);");
        assertThat(guardAt).as("应能在 activateRecipe 内定位到版本线闸").isGreaterThan(0);
        assertThat(validateAt).as("应能在 activateRecipe 内定位到 family 校验").isGreaterThan(0);
        assertThat(guardAt)
                .as("版本线闸必须在校验/落库之前 —— 放后面就等于先把坏状态写进去再说")
                .isLessThan(validateAt);
    }

    @Test
    @DisplayName("🔴 P4: 副产品成员必须跳过<b>两道</b>闸 —— 只豁免一道仍然激活不了")
    void byProductSkipsBothActivationGates() throws Exception {
        String loop = activationLoop(bom());

        assertThat(loop)
                .as("副产品按设计没有自己的原料行(分摊 0% + 走 NRV 抵扣), 必须豁免")
                .contains("BomRecipe.OutputRole.BY_PRODUCT");

        // ⚠️ 不能用 loop.indexOf("BY_PRODUCT") —— 上面那段注释里就有好几次 "BY_PRODUCT",
        // 命中的是<b>我自己写的注释</b>而不是代码, 位置恒定不变, 变异怎么改都抓不到。
        // (今晚第三次踩同一个坑: 断言要落在可执行构造上。)
        int skip = loop.indexOf("member.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT");
        assertThat(skip).as("应能定位到豁免判断这行代码(不是注释)").isGreaterThan(0);
        // 同理: 用<b>带实参的调用形式</b>定位, 否则又命中注释里提到的方法名
        int items = loop.indexOf("validateActivatableItems(member);");
        int readiness = loop.indexOf("readinessService.requireBomCompleteForActivation(factoryId, member);");
        assertThat(items).as("validateActivatableItems 应在循环内").isGreaterThan(0);
        assertThat(readiness).as("requireBomCompleteForActivation 应在循环内").isGreaterThan(0);
        assertThat(skip)
                .as("豁免判断必须排在<b>两道闸之前</b> —— 排在中间只挡住一道, 另一道照样报「BOM 还没有任何原辅料」")
                .isLessThan(items)
                .isLessThan(readiness);
    }

    @Test
    @DisplayName("🔴 P7: 包材三条必填一次收齐 —— 不再三个连续 if-throw")
    void packagingFieldsReportedTogether() throws Exception {
        String src = bom();
        assertThat(src)
                .as("应先把缺失项收集起来再一次抛")
                .contains("packagingIssues")
                .contains("BOM_PACKAGING_FIELDS_REQUIRED");

        // 三条各自的 code 仍保留(单条缺失时不改变既有客户端行为), 但不再各自 throw
        for (String code : new String[]{"BOM_PACKAGING_ROLE_REQUIRED",
                "BOM_PACKAGING_UNIT_MISMATCH", "BOM_PACKAGING_QUANTITY_REQUIRED"}) {
            assertThat(src).as("%s 应仍然存在(单条缺失时用它)", code).contains(code);
        }
        assertThat(src.split("throw bomError\\(400, \"包材", -1).length - 1)
                .as("这三条不该再有各自的 throw —— 有几个就说明退回逐条抛了")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("🔴 P8: 物料节点缺 skuId 必须报出来, 不能裸 continue")
    void materialNodeWithoutSkuIsReported() throws Exception {
        String src = Files.readString(VALIDATOR);
        assertThat(src)
                .as("裸 continue 会让「字段名写错」保存成功且只在别处冒出 currentUnit=null 的告警, 看不出真因")
                .doesNotContain("if (blank(skuId)) continue;");
        assertThat(src)
                .as("应报出专门的问题码, 并提示物料节点该用 skuId / baseUnit")
                .contains("WORKFLOW_MATERIAL_SKU_MISSING")
                .contains("baseUnit");
    }
}
