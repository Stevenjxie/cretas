package com.cretas.aims.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存量包材(没有采购参考价)必须还能改别的字段。
 *
 * <h2>🔴 2026-08-13 生产实测</h2>
 * {@code updateMaterialType} 原本对所有包材无条件 {@code validateRequiredPricing},
 * 于是**没有参考价的包材连一个字段都改不了** —— 保存任何修改都被
 * 400「含税单价必须大于0」拦下, 包括改名、挂物料分类、调单位。
 *
 * <p>实测规模(prod):
 * <pre>
 *              没参考价 / 启用包材    其中在活 BOM 上被用
 *   F006          34 / 38                    1
 *   LIUSHANMEN    25 / 25                    2
 *   ─────────────────────────────────────────────
 *   合计          59 / 63 (94%)              3
 * </pre>
 *
 * <p>这道闸与下游**冗余**, 而下游更安全:
 * {@code BomRecipeServiceImpl#applyMaterialMasterPricing} 取
 * {@code movingAvgPrice ?: unitPrice}, 两者都空时 {@code itemCost} 为 null →
 * {@code markFamilyCostIncomplete}, 把整个 family 的标准成本标记为不完整
 * —— <b>不是</b>悄悄按 0 元算。成本正确性已经在它真正起作用的位置被守住了,
 * 而 59 条里只有 3 条真的进了活 BOM: 其余 56 条上这道闸什么也没保护。
 *
 * <h2>这条闸守什么</h2>
 * 三件事, 少一件就要么锁死存量、要么放走新数据:
 * <ol>
 *   <li><b>新建仍然要求</b> —— createMaterialType 那处 {@code if (packaging)} 不许动;</li>
 *   <li><b>更新按条件要求</b> —— 只在「本次带价」或「记录本来有价」时才校验;</li>
 *   <li><b>存量值必须在写入前抓</b> —— 抓晚了会读到本次请求刚写进去的值,
 *       条件恒真, 等于没改。</li>
 * </ol>
 *
 * <p>读源码、不连库、毫秒级。
 *
 * <p>⚠️ CI 的 Java selector 目前跑
 * {@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest},
 * <b>不覆盖本用例</b>(本仓 Java 全量套件只在 full_audit 跑)。
 */
class PackagingLegacyPricingUnlockContractTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/cretas/aims/service/impl/RawMaterialTypeServiceImpl.java");

    private String source() throws IOException {
        assertThat(Files.exists(SERVICE))
                .as("找不到 %s —— 文件挪了位置, 这条闸需要跟着改", SERVICE.toAbsolutePath())
                .isTrue();
        return Files.readString(SERVICE);
    }

    /** 剥注释: 注释里引用了旧写法(在讲这个缺陷), 不剥会自己命中自己。 */
    private static String stripComments(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.replaceAll("(?s)/\\*.*?\\*/", "").split("\n", -1)) {
            if (!line.trim().startsWith("//")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("更新时按条件要求包材参考价, 不再无条件拦下存量记录")
    void updateOnlyRequiresPricingWhenSuppliedOrAlreadySet() throws IOException {
        String src = stripComments(source());
        assertThat(src)
                .as("更新路径必须走 packagingPricingRequired 这个条件, 而不是裸 if (packaging)")
                .contains("boolean packagingPricingRequired = packaging");
        assertThat(src).contains("dto.getMaterialReferencePrice() != null");
        assertThat(src).contains("dto.getTaxIncludedUnitPrice() != null");
        assertThat(src)
                .as("「记录本来有价」这一支不能丢 —— 丢了就能把已有的价清空")
                .contains("storedTaxIncludedPrice.compareTo(BigDecimal.ZERO) > 0");
        assertThat(src).contains("if (packagingPricingRequired) {");
    }

    @Test
    @DisplayName("存量参考价在任何写入之前抓")
    void storedPriceIsCapturedBeforeAnyMutation() throws IOException {
        String src = stripComments(source());
        // ⚠️ 必须把范围限定在 update 方法体内。applyReferencePricing( 在 create 里也有一处,
        //    直接 indexOf 会命中更早的那个, 于是「捕获在写入之后」被误判成红 —— 实测踩过。
        int updateAt = src.indexOf("public RawMaterialTypeDTO updateMaterialType(");
        assertThat(updateAt).as("找不到 updateMaterialType").isGreaterThan(-1);
        String updateBody = src.substring(updateAt);

        int captureAt = updateBody.indexOf("BigDecimal storedTaxIncludedPrice = materialType.getTaxIncludedUnitPrice();");
        int applyAt = updateBody.indexOf("applyReferencePricing(materialType,");
        assertThat(captureAt).as("update 里找不到存量价捕获").isGreaterThan(-1);
        assertThat(applyAt).as("update 里找不到价格写入").isGreaterThan(-1);
        assertThat(captureAt)
                .as("抓晚了会读到本次请求刚写进去的值, 条件恒真 —— 等于这次改动没生效")
                .isLessThan(applyAt);
    }

    /**
     * ⚠️ 反向断言: 新建路径**不许**跟着放宽。
     * 少了这条, 把 create 那处也改成条件式照样绿, 而那会让新数据也能没有价。
     */
    @Test
    @DisplayName("新建路径仍然无条件要求包材参考价")
    void createStillRequiresPricingUnconditionally() throws IOException {
        String src = stripComments(source());
        int createAt = src.indexOf("public RawMaterialTypeDTO createMaterialType(");
        int updateAt = src.indexOf("public RawMaterialTypeDTO updateMaterialType(");
        assertThat(createAt).isGreaterThan(-1);
        assertThat(updateAt).isGreaterThan(createAt);
        String createBody = src.substring(createAt, updateAt);
        assertThat(createBody)
                .as("新建仍然要 if (packaging) { validateRequiredPricing(...) }, 保证新数据干净")
                .containsPattern("if \\(packaging\\) \\{\\s*\\n\\s*validateRequiredPricing\\(");
    }
}
