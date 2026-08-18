package com.cretas.aims.service.inventory;

import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 闸 —— 销售侧落库的单位<b>不许是英文码</b>。
 *
 * <h2>🔴 源头定位 (prod 实测 2026-08-18)</h2>
 * {@code sales_order_items.unit} 里躺着 {@code box}×2 / {@code case}×1，
 * {@code sales_delivery_items.unit} 里还有 {@code box}×2。
 *
 * <p>关键证据是<b>它不是从档案带出来的</b>：那三行指向的商品
 * 「SOP-20260817-01-黄油鸡-成品800g」在 {@code product_types.unit} 里是<b>中文「盒」</b>，
 * 它唯一的包装规格 {@code product_packaging_specs} 也是 {@code package_unit=箱 / base_unit=盒}。
 * 中文进、英文出 ⇒ 中间有人翻译过。翻译的地方是 web-admin
 * {@code src/utils/unitPricing.ts} 的 {@code UNIT_ALIASES}（{@code '盒': 'box'}，
 * {@code '箱': 'case'}），经 {@code views/sales/orders/salesOrderUnitContract.ts:63}
 * 的 {@code canonicalSalesOrderItemPayload} 进入请求体 —— 该文件自己的注释就写着
 * 「{@code canonicalUnitCode} …还参与<b>构造 API 请求体</b>」。
 *
 * <p><b>前端的规范码方向与后端相反</b>：前端 盒→{@code box}，后端
 * {@code UnitContractServiceImpl.systemAliases()} 是 {@code alias("盒","盒","box")}
 * —— 码是「盒」，{@code box} 只是别名。本仓形态 D：同一个东西两份口径，方向还是反的。
 *
 * <h2>为什么修在后端而不是只修前端</h2>
 * {@link UnitContractService#storageUnit} 的 javadoc 写着「全系统唯一口径，
 * <b>任何写入路径都必须走这里</b>」，而全仓 5 个调用点里<b>一个都不在销售侧</b>
 * （{@code ProductTypeServiceImpl} / {@code RawMaterialTypeServiceImpl} /
 * {@code PurchaseServiceImpl} / {@code TransferServiceImpl}×2）——
 * 典型的形态 B「机制在、只是没接上」。只修前端挡不住 RN、AI 工具（
 * {@code SalesNeedCreateTool} 的 unit 直接来自 LLM 参数）和脚本。
 *
 * <p>⚠️ 读侧出口翻译已由 PR #2837 上线，本闸只管<b>写入源头</b>，不重复翻一遍。
 */
class SalesOrderUnitStorageContractTest {

    private static final String F = "F006";

    private static final Path SALES_SERVICE = Paths.get(
            "src/main/java/com/cretas/aims/service/inventory/impl/SalesServiceImpl.java");

    /**
     * 硬约束 8 的登记：改之前数过，销售侧 {@code setUnit(} 的<b>真实调用点</b>是 5 处
     * （476 createSalesOrder / 2154 updateSalesOrder / 2403 copySalesOrder /
     * 2656 createDeliveryRecord / 2828 createDeliveryShipment）。
     * 第 6 处 {@code alloc.setUnit(item.getUnit())} 只出现在<b>注释</b>里，
     * 所以下面扫描前必须先剥注释 —— 否则它会被数进来（本仓形态 A⁗ 第 5 例：
     * 文本 grep 把 docstring 里提到函数名的行也数成调用点）。
     */
    private static final int EXPECTED_SET_UNIT_CALL_SITES = 5;

    private static UnitContractService realContract() {
        // 用真实契约实现（内置单位表 + 别名表就在里面），只把 4 个仓储 repo mock 掉 ——
        // 测的是「真的契约怎么归一」，不是我编的一份假别名表。
        return new UnitContractServiceImpl(
                mock(com.cretas.aims.repository.config.UnitOfMeasurementRepository.class),
                mock(com.cretas.aims.repository.unit.ProductUnitConversionRepository.class),
                mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class),
                mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class));
    }

    /**
     * 走 SalesServiceImpl 自己的私有归一函数（注入真实契约）—— 这是产品真正调用的那一支。
     *
     * <p>用 {@code CALLS_REAL_METHODS} 的 mock 而不是 {@code new}：本类有两个不同 arity 的
     * 构造器，写死参数个数会在别人加依赖时无谓地红一次（与 {@code SalesOrderSellableGuardTest} 同法）。
     */
    private static SalesServiceImpl serviceWith(UnitContractService contract) {
        SalesServiceImpl service = mock(SalesServiceImpl.class,
                org.mockito.Mockito.withSettings()
                        .defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(service, "unitContractService", contract);
        return service;
    }

    private static String salesStorageUnit(String raw) {
        return ReflectionTestUtils.invokeMethod(serviceWith(realContract()), "storageUnit", F, raw);
    }

    // ───────────────────────── 行为：权威表到底怎么归一 ─────────────────────────

    @Test
    @DisplayName("🔴 主断言: 英文包装码落库必须变中文 —— box→盒, case→箱, carton→箱")
    void englishPackagingCodesBecomeChinese() {
        assertEquals("盒", salesStorageUnit("box"), "prod 上 sales_order_items 有 2 行 box");
        assertEquals("箱", salesStorageUnit("case"), "prod 上 sales_order_items 有 1 行 case");
        assertEquals("箱", salesStorageUnit("carton"));
        assertEquals("袋", salesStorageUnit("bag"));
        assertEquals("包", salesStorageUnit("pack"));
        assertEquals("片", salesStorageUnit("slice"));
    }

    @Test
    @DisplayName("阳性对照: 已经是中文的原样通过 —— 否则上面的断言可能只是因为它把一切都变成了中文")
    void chineseStaysChinese() {
        assertEquals("盒", salesStorageUnit("盒"));
        assertEquals("箱", salesStorageUnit("箱"));
        assertEquals("袋", salesStorageUnit("袋"));
    }

    @Test
    @DisplayName("🔴 阴性对照: 质量/体积/长度的【国际符号】不许被中文化 —— 判据是「不许英文码」不是「一律中文」")
    void scientificSymbolsAreNotTranslated() {
        // 秤上/单据上/国标上都写 kg。UnitDisplayNames 的 javadoc 明确「刻意不翻科学计量单位」。
        assertEquals("kg", salesStorageUnit("kg"), "kg 被中文化 = 改坏了, 不是修好了");
        assertEquals("g", salesStorageUnit("g"));
        assertEquals("t", salesStorageUnit("t"));
        assertEquals("ml", salesStorageUnit("ml"));
        // jin 是拼音不是符号, 权威表的码就是 jin —— 落库仍写 jin, 展示层才翻「斤」
        assertEquals("jin", salesStorageUnit("jin"));
    }

    @Test
    @DisplayName("🔴 只/个/件 是三个不同的单位, 落库必须保用户字面 (#1976 / LIUSHANMEN 2026-07-30 事故)")
    void countingLabelsAreNotCollapsed() {
        assertEquals("只", salesStorageUnit("只"), "一只鸡不是一件包材");
        assertEquals("个", salesStorageUnit("个"));
        assertEquals("件", salesStorageUnit("件"));
    }

    @Test
    @DisplayName("权威表认不出的自由文本原样返回 —— 归一不能把没登记的单位吃成空")
    void unknownFreeTextIsPreserved() {
        assertEquals("半只", salesStorageUnit("半只"));
        assertEquals("unitless", salesStorageUnit("unitless"), "哨兵值不是单位, 不许被改写");
    }

    @Test
    @DisplayName("空值/未注入契约时退回原样 —— 归一失败不能把单位吃掉")
    void nullSafety() {
        assertEquals(null, salesStorageUnit(null));
        assertEquals("", salesStorageUnit(""));
        SalesServiceImpl noContract = serviceWith(null);
        assertEquals("box", ReflectionTestUtils.invokeMethod(noContract, "storageUnit", F, "box"),
                "未注入契约时应退回原样(= 今天的行为), 不能返回 null/空");
    }

    @Test
    @DisplayName("幂等: 归一两次与归一一次结果相同 —— 复制订单/发运复制才敢接这支函数")
    void isIdempotent() {
        for (String raw : List.of("box", "case", "kg", "只", "盒", "半只")) {
            String once = salesStorageUnit(raw);
            assertEquals(once, salesStorageUnit(once), "不幂等: " + raw);
        }
    }

    // ───────────────────────── 接线：写入点真的走了那支函数 ─────────────────────────

    /**
     * 🔴 接线断言 —— 只测 helper 是「零件对了、线没接上」，本仓今天已经踩过。
     *
     * <p>做法是<b>结构提取</b>而不是整文件 {@code contains}：先剥掉注释（否则
     * javadoc 里的 {@code alloc.setUnit(item.getUnit())} 会被数成第 6 个调用点），
     * 再逐个取出每个 {@code setUnit(} 的<b>实参</b>，断言它是 {@code storageUnit(} 调用。
     * 把某一处改回 {@code itemDTO.getUnit()} 这条断言就红 —— 变异对照见 PR 描述。
     */
    @Test
    @DisplayName("🔴 接线: SalesServiceImpl 里每一处 setUnit( 的实参都必须是 storageUnit( 的结果")
    void everySetUnitCallSiteRoutesThroughStorageUnit() throws IOException {
        assertTrue(Files.exists(SALES_SERVICE),
                "找不到被扫描的源文件, 本闸会变成空转: " + SALES_SERVICE.toAbsolutePath());
        String source = stripComments(Files.readString(SALES_SERVICE, StandardCharsets.UTF_8));

        List<String> arguments = new ArrayList<>();
        Matcher m = Pattern.compile("\\.setUnit\\(").matcher(source);
        while (m.find()) {
            arguments.add(readArgument(source, m.end()));
        }

        // 阳性对照: 扫不到调用点 = 仪器坏了, 而不是「全都合规」
        assertEquals(EXPECTED_SET_UNIT_CALL_SITES, arguments.size(),
                "setUnit 调用点数量变了 (硬约束 8: 改共享结构前先数, 改完再数). 实际取到: " + arguments);

        List<String> offenders = arguments.stream()
                .filter(arg -> !arg.startsWith("storageUnit("))
                .toList();
        assertTrue(offenders.isEmpty(),
                "有写入点绕过了单位归一, 会把英文码写进库: " + offenders);
    }

    @Test
    @DisplayName("阳性对照: 剥注释这一步真的在起作用 (否则上面的计数可能是碰巧对上的)")
    void stripCommentsActuallyRemovesTheJavadocMention() throws IOException {
        String raw = Files.readString(SALES_SERVICE, StandardCharsets.UTF_8);
        String stripped = stripComments(raw);
        assertTrue(count(raw, ".setUnit(") > count(stripped, ".setUnit("),
                "剥注释前后 setUnit 出现次数相同 —— 说明注释里那处 alloc.setUnit 没被剥掉, "
                        + "或者剥注释根本没生效");
        assertNotNull(stripped);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** 从 {@code setUnit(} 的右括号后读到配对的右括号, 得到实参源码。 */
    private static String readArgument(String source, int from) {
        int depth = 1;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 剥掉 block/line 注释与字符串字面量, 让扫描只看代码结构。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (c == '"') {
                out.append(' ');
                i++;
                while (i < source.length() && source.charAt(i) != '"') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
