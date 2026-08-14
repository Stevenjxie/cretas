package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调拨的单位比较必须「中英写法互认, 但不合并 只/个/件」。
 *
 * <h3>为什么有这个文件(2026-08-14 生产实测)</h3>
 *
 * 在清空后的 F006 上走真实流程: 建两批冻猪蹄 → 调拨 15kg, 被
 * {@code 409 调拨包装规格与原料基本单位不一致} 挡死。抓到的真实 payload:
 *
 * <pre>{"quantity":1.5,"unit":"case","materialPackagingSpecId":"745b1332-…"}</pre>
 *
 * 而库里该规格 {@code package_unit = 箱}。前端 {@code toTransferItemPayload} 会把
 * {@code row.unit} 过一遍 {@code canonicalUnitCode}(箱 → case)再提交, 后端
 * {@code canonicalTransferUnit} 当时**只归一 MASS/VOLUME**, 计数单位原样落回,
 * 于是 {@code "case" != "箱"} → 409。
 *
 * <p>库里两种写法是同时存在的(实测活跃原料包装规格: {@code case} 29 条 / {@code 箱} 7 条,
 * 后者全是默认包装), 所以这不是脏数据, 是**两套词汇表**。
 *
 * <p>⚠️ 这不是新问题: {@code web-admin/src/utils/unitPricing.ts} 注释写着
 * 「2026-07-31 客户就是这么被拦住的」—— 当时只在前端补了 {@code sameUnit()},
 * 后端的字面比较原样留着。同一个洞从另一个方向又咬了一次。
 *
 * <h3>为什么用 storageUnit 而不是别的</h3>
 *
 * 第二条断言是这里最容易写漏的: 只要图省事改成 {@code describe().code()} 或
 * {@code areEquivalent()}, 只/个/件 就会全归一到 {@code pcs} 从而互相可替换 ——
 * 而产品规则明确「一只鸡不是一件包材」。{@code storageUnit} 的规则 2 正是为此存在的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("调拨单位词汇表")
class TransferUnitVocabularyTest {

    private static final String FACTORY = "F006";

    @Mock private com.cretas.aims.repository.config.UnitOfMeasurementRepository unitRepository;
    @Mock private com.cretas.aims.repository.unit.ProductUnitConversionRepository conversionRepository;
    @Mock private com.cretas.aims.repository.MaterialPackagingHierarchyRepository hierarchyRepository;
    @Mock private com.cretas.aims.repository.material.MaterialPackagingSpecRepository specRepository;
    @Mock private com.cretas.aims.repository.RawMaterialTypeRepository materialTypeRepository;

    private TransferServiceImpl service;

    /** 比较语义(本次改动新增) —— 认中英同义。 */
    private boolean same(String a, String b) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(service, "sameTransferUnit", FACTORY, a, b));
    }

    /** 落库语义(本次改动【不得】变动) —— 计数/包装单位必须保持字面。 */
    private String stored(String unit) {
        return ReflectionTestUtils.invokeMethod(service, "canonicalTransferUnit", FACTORY, unit);
    }

    @BeforeEach
    void setUp() {
        UnitContractService unitContractService = new UnitContractServiceImpl(
                unitRepository, conversionRepository, hierarchyRepository, specRepository);
        service = new TransferServiceImpl(null, null, null, null, null, null, materialTypeRepository);
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
    }

    @Test
    @DisplayName("🔒 箱 与 case 必须判为同一个单位 —— 这正是 409 的成因")
    void chineseAndEnglishSpellingsOfTheSameUnitMustMatch() {
        assertThat(same("箱", "case"))
                .as("库里存「箱」而前端送 case, 比较必须认同一个单位, 否则调拨被 409 挡死")
                .isTrue();

        // 同族的其余包装单位, 库里实测都出现过英文写法
        assertThat(same("袋", "bag")).isTrue();
        assertThat(same("盒", "box")).isTrue();
        assertThat(same("吨", "t")).isTrue();
    }

    @Test
    @DisplayName("✅ 曾经的残留缺口已关闭: 框/筐 各自有码, 与 crate 互认")
    void unitsWithMultipleChineseSpellingsNowResolveConsistently() {
        // #2625 当时留了个已知缺口: crate 被 {框, 筐} 两个中文写法共用, 命中 storageUnit 规则 2
        // 各自保留字面, 于是「库里存 框 + 前端送 crate」仍会 409。
        // 本次把非科学单位的码改成中文字本身之后, 框 和 筐 各自成为独立单位,
        // crate 降级为「框」的英文别名 —— 一码多中文的根因消失, 缺口随之关闭。
        assertThat(same("框", "crate")).as("crate 现在是「框」的别名").isTrue();
        // 而 框 与 筐 仍然是两个不同的单位(它们本来就是不同的东西)
        assertThat(same("框", "筐")).as("框和筐是两个单位, 不能因为拼音/英文相同就合并").isFalse();
    }

    @Test
    @DisplayName("🔒 反向: 只 / 个 / 件 仍然互不相等 —— 一只鸡不是一件包材")
    void distinctChineseCountLabelsMustNotBeMerged() {
        assertThat(same("只", "件"))
                .as("只/个/件 共用内置码 pcs; 判为同一个单位会让报工按字面比较时看不见整批货 "
                        + "(LIUSHANMEN 2026-07-30 事故)")
                .isFalse();
        assertThat(same("个", "件")).isFalse();
        assertThat(same("只", "个")).isFalse();
    }

    @Test
    @DisplayName("反向: 真正不同的单位仍然不等 —— 判据不是恒真")
    void genuinelyDifferentUnitsStayDifferent() {
        assertThat(same("箱", "kg")).isFalse();
        assertThat(same("袋", "箱")).isFalse();
    }


    /**
     * 🔴 跑在**真实调用点** {@code normalizeRawTransferPackaging} 上。
     *
     * <p>为什么必须有这一条: 本文件其余断言是用反射直接调 {@code sameTransferUnit} 的 ——
     * 把**调用点**退回 {@code transactionUnit.equals(specUnit)} 时, helper 本身仍然正确,
     * 那些断言纹丝不动(实测变异 M1: 0 failures)。断言与缺陷差一格 = 等于没有守卫。
     *
     * <p>本条复现生产上那次 409 的完整形状: 库里规格存「箱」, 客户端送 {@code case}。
     * 同时钉住落库规则 —— 快照必须是**主数据的写法**「箱」, 而不是客户端送来的 {@code case}
     * (否则就是 LIUSHANMEN 2026-07-30 事故: 快照与主档写法不一致, 报工按字面比较看不见货)。
     */
    @Test
    @DisplayName("🔒 真实调用点: 库存「箱」+ 客户端送 case → 不再 409, 且快照落「箱」")
    void realCallSiteAcceptsChineseSpecWithEnglishClientUnit() {
        String materialTypeId = "RMT_TEST";
        String specId = "SPEC_TEST";

        com.cretas.aims.entity.RawMaterialType material = new com.cretas.aims.entity.RawMaterialType();
        material.setId(materialTypeId);
        material.setFactoryId(FACTORY);
        material.setUnit("kg");
        org.mockito.Mockito.when(materialTypeRepository.findById(materialTypeId))
                .thenReturn(java.util.Optional.of(material));

        com.cretas.aims.entity.material.MaterialPackagingSpec spec =
                new com.cretas.aims.entity.material.MaterialPackagingSpec();
        spec.setId(specId);
        spec.setFactoryId(FACTORY);
        spec.setMaterialTypeId(materialTypeId);
        spec.setPackageUnit("箱");          // ← 库里是中文
        spec.setBaseUnit("kg");
        spec.setConversionFactor(new java.math.BigDecimal("10"));
        org.mockito.Mockito.when(specRepository
                        .findByIdAndFactoryIdAndMaterialTypeIdAndActiveTrue(specId, FACTORY, materialTypeId))
                .thenReturn(java.util.Optional.of(spec));
        ReflectionTestUtils.setField(service, "materialPackagingSpecRepository", specRepository);

        com.cretas.aims.dto.inventory.CreateTransferRequest.TransferItemDTO item =
                new com.cretas.aims.dto.inventory.CreateTransferRequest.TransferItemDTO();
        item.setItemType("RAW_MATERIAL");
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialPackagingSpecId(specId);
        item.setUnit("case");               // ← 前端 canonicalUnitCode 转出来的英文码
        item.setQuantity(new java.math.BigDecimal("1.5"));

        ReflectionTestUtils.invokeMethod(
                service, "normalizeRawTransferPackaging", FACTORY, item, materialTypeId);

        assertThat(item.getPackageUnitSnapshot())
                .as("落库必须采用主数据写法「箱」, 不能存客户端送来的 case")
                .isEqualTo("箱");
        assertThat(item.getPackageToBaseFactorSnapshot())
                .isEqualByComparingTo(new java.math.BigDecimal("10"));
    }
    @Test
    @DisplayName("🔒 落库语义不得变: 计数/包装单位仍保持字面 (LIUSHANMEN 2026-07-30 事故的防线)")
    void storageSemanticsMustStayLiteral() {
        // canonicalTransferUnit 的结果会写进 packageUnitSnapshot 与目标批次单位。
        // 一旦它开始归一, 用户选的「只」就会被存成 pcs, 报工按字面比较跳过整批货。
        assertThat(stored("只")).isEqualTo("只");
        assertThat(stored("箱")).isEqualTo("箱");
        assertThat(stored("件")).isEqualTo("件");
    }

    @Test
    @DisplayName("权威表认不出的自由文本原样保留, 不被吞成空")
    void unknownFreeTextIsPreserved() {
        assertThat(stored("自定义托盘X")).isNotBlank();
        assertThat(stored(null)).isEmpty();
        assertThat(stored("   ")).isEmpty();
    }
}
