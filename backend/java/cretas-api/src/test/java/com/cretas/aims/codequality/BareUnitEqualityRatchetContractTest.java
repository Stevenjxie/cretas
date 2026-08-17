package com.cretas.aims.codequality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 棘轮闸 —— <b>单位同一性禁止用裸字符串比较</b>。只禁新增, 冻结存量。
 *
 * <h2>为什么要这道闸</h2>
 * 「两个单位是不是同一个」用 {@code equals} 判, 在本仓已经连续咬到用户三次, 每次只补被咬的那一处:
 * <ul>
 *   <li>2026-07-30 LIUSHANMEN: 调拨把「只」存成 {@code pcs}, 报工按字面比较看不见整批 501 只;</li>
 *   <li>2026-08-14: 后端把「件/个/只」拆成三个独立单位而前端仍折成一个, 两厂 50 个包材加不进 BOM;</li>
 *   <li>2026-08-17 (#2775): 以销定产 {@code case -> 箱} 判不等, 订单换算齐全却建不出生产计划;
 *       同一个方法里隔几行的另一半 {@code transactionUnit.equals(baseUnit)} 当时漏改,
 *       导致按基本单位调拨的行被推进「必须选包装规格」的死路。</li>
 * </ul>
 * 这不是三个 bug, 是<b>一个形状的 N 个调用点</b>。正解是问单位契约
 * ({@code UnitContractService#areEquivalent} / {@code #storageUnit} / 各服务自己的
 * {@code sameXxxUnit} 包装), 而不是比字符串 —— 同一个单位在库里/客户端/导入文件里有中英两种写法。
 *
 * <h2>口径 (必须写出来, 否则数字没有意义)</h2>
 * <ul>
 *   <li>范围: {@code backend/java/cretas-api/src/main/java/**}{@code /*.java}; 剥注释后逐行扫;</li>
 *   <li>检出: {@code A.equals(B)} / {@code A.equalsIgnoreCase(B)} / {@code A.contentEquals(B)} /
 *       {@code Objects.equals(A,B)} / {@code StringUtils.equals*(A,B)}, 且<b>两侧都是单位表达式</b>;</li>
 *   <li>单位表达式: 标识符按 camelCase 词段切开后含 {@code unit/units/uom/uoms} 的那一段
 *       —— ⚠️ 不能用 {@code \w*unit\w*} 之类的子串匹配, {@code Opportunity} 里就含 "unit",
 *       实测因此把 {@code SalesOpportunityService} 的 enum 比较误报过 3 条;</li>
 *   <li><b>不管</b>单位与字面量比 ({@code "kg".equals(unit)}) —— 那是按量纲分类, 是另一类问题。
 *       宁可窄而可信: 一道天天误报的闸最终会被加 {@code @Disabled} 关掉, 那时它覆盖率归零;</li>
 *   <li><b>不管</b>两侧都是 {@code .code()} 的比较 —— 那已经过契约归一, 是正解不是违例;</li>
 *   <li>{@code unitPrice / unitCost / gramsPerUnit / unitReviewRequired} 这类<b>以单位为定语</b>的名字
 *       按词段黑名单排除。</li>
 * </ul>
 *
 * <h2>这是代理判据, 已标出来</h2>
 * 静态扫描判不出「这个 equals 到底在不在判同一性」。本闸用的代理是「两侧都是单位表达式」。
 * 它<b>看不见</b>: 变量名不含 unit 的单位 (如 {@code String from / String to})、
 * 反射/字符串拼出来的比较、以及跨行折行的表达式。
 * ⛔ 不要靠不断加启发式去逼近 —— 那会做出一个更复杂、误报更多、仍然漏的东西。
 * 要判得更准只有一条路: 把单位比较收敛到一处 helper, 闸改扫「有没有绕过那个 helper」。
 *
 * @since 2026-08-17
 */
class BareUnitEqualityRatchetContractTest {

    /**
     * 冻结存量。每行 {@code <相对 src/main/java 的路径>|<左表达式>|<右表达式>[|<条数, 省略即 1>]}。
     * 空白已剥除。
     *
     * <p>⛔ <b>只许变短, 不许变长</b>。改好一处就把它从这里删掉; 新增一处会让本测试变红。
     * 失败信息里会打出可直接粘贴的新基线。
     */
    private static final String[] FROZEN_BASELINE = {
            "com/cretas/aims/entity/ProductionBatch.java|plannedUnit|unit",
            "com/cretas/aims/service/bom/BomWorkflowRevisionService.java|canonicalUnit(left)|canonicalUnit(right)",
            "com/cretas/aims/service/bom/impl/BomItemSubstituteServiceImpl.java|parentUnit|substituteUnit",
            "com/cretas/aims/service/factory/impl/FactoryMaterialRequisitionServiceImpl.java|bomUnit.trim()|stockUnit.trim()",
            "com/cretas/aims/service/impl/ProductTypeServiceImpl.java|previousLevel1Unit|productType.getLevel1Unit()|2",
            "com/cretas/aims/service/impl/ProductTypeServiceImpl.java|previousUnit|productType.getUnit()|2",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|authoritativeUnit|requestedUnit",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|b.getPlannedUnit()|b.getUnit()",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|bomUnit.trim()|stockUnit.trim()|2",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|canonicalQuantityUnit|canonicalBatchUnit",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|canonicalReceiptUnit(unit)|canonicalReceiptUnit(batch.getUnit())",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|requestedUnit|terminalUnit",
            "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java|unit|expectedUnit",
            "com/cretas/aims/service/impl/RawMaterialTypeServiceImpl.java|previousUnit|materialType.getUnit()",
            "com/cretas/aims/service/impl/ScaleProtocolAdapterServiceImpl.java|testCase.getExpectedUnit()|parseResult.getUnit()",
            "com/cretas/aims/service/inventory/CustomerMaterialArrivalNoticeService.java|materialUnit|requestUnit",
            "com/cretas/aims/service/inventory/FgQuantityUnitConverter.java|actualUnit|packagingUnit",
            "com/cretas/aims/service/inventory/FgQuantityUnitConverter.java|fromUnit|toUnit",
            "com/cretas/aims/service/inventory/SalesOrderSuppliedMaterialRequirementService.java|canonicalUnit|materialUnit",
            "com/cretas/aims/service/inventory/SalesOrderSuppliedMaterialRequirementService.java|materialUnit|requirement.getUnit()",
            "com/cretas/aims/service/inventory/SalesOrderSuppliedMaterialRequirementService.java|persisted.getUnit()|candidate.getUnit()",
            "com/cretas/aims/service/inventory/SalesOrderSuppliedMaterialRequirementService.java|requirement.getUnit()|requestUnit",
            "com/cretas/aims/service/inventory/impl/FinishedGoodsFeedServiceImpl.java|fgUnit.trim()|feedUnit.trim()",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|acc.unit|sourceUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|fromUnit|toUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|orderBaseUnit|selection.baseUnit()",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|orderUnit|receiveUnit|2",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|purchaseUnit|canonicalUnit(factoryId,request.getPriceUnit(),<str>)",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|requestedUnit|orderPackageUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|sourceUnit|specBaseUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|sourceUnit|targetUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|spec.getPurchasePackageUnit()|canonicalUnit(factoryId,request.getPriceUnit(),<str>)",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|spec.getPurchasePackageUnit()|requestUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|targetPurchaseUnit|explicitUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|targetUnit|specPackageUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|unit|baseUnit",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|unit|canonicalUnitOrRaw(factoryId,spec.getPackageUnit())",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java|unit|specUnit",
            "com/cretas/aims/service/inventory/impl/SalesServiceImpl.java|sourceOrderItem.getUnit()|itemDTO.getUnit()",
            "com/cretas/aims/service/inventory/impl/TransferServiceImpl.java|canonicalUnit|canonicalTransferUnit(factoryId,batch.getUnit())",
            "com/cretas/aims/service/inventory/impl/TransferServiceImpl.java|rawInventoryUnit|canonicalTransferUnit(factoryId,batch.getQuantityUnit())",
            "com/cretas/aims/service/inventory/impl/TransferServiceImpl.java|transactionUnit|baseUnit",
            "com/cretas/aims/service/inventory/impl/TransferServiceImpl.java|unit|trimToNull(row.getUnit())",
            "com/cretas/aims/service/orchestration/BomExpansionService.java|bomUnit.trim()|stockUnit.trim()",
            "com/cretas/aims/service/orchestration/InventoryMatchingService.java|item.getUnit()|item.getPackagingUnit()",
            "com/cretas/aims/service/orchestration/ProductionWorkflowOrchestrator.java|sourceUnit|targetUnit",
            "com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java|currentUnit|firstUnit",
            "com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java|expectUnit|actualUnit.trim()",
            "com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java|inputUnit|outputUnit",
            "com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java|plan.getPlannedUnit()|outputUnit",
            "com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java|suppliedUnit.trim()|configuredUnit.trim()",
            "com/cretas/aims/service/processentry/impl/ProductionStockAllocationServiceImpl.java|declaredUnit|batchUnit",
            "com/cretas/aims/service/processentry/impl/ProductionStockAllocationServiceImpl.java|inputUnit|batchUnit",
            "com/cretas/aims/service/processentry/impl/ProductionStockAllocationServiceImpl.java|requiredUnit|batchUnit",
            "com/cretas/aims/service/product/impl/ProductPackagingSpecServiceImpl.java|baseUnit|packageUnit",
            "com/cretas/aims/service/productimport/SkuImportServiceImpl.java|normalizeUnit(left)|normalizeUnit(right)",
            "com/cretas/aims/service/restaurant/RestaurantAgentActionProposalMapper.java|unit|persisted.unit()",
            "com/cretas/aims/service/restock/RestockBoardService.java|demand.getMinUnit()|demand.getMaxUnit()|3",
            "com/cretas/aims/service/supplier/SupplierMaterialPurchaseSpecServiceImpl.java|baseUnit|relationUnit",
            "com/cretas/aims/service/supplier/SupplierMaterialPurchaseSpecServiceImpl.java|materialUnit|inventoryUnit",
            "com/cretas/aims/service/supplier/SupplierMaterialPurchaseSpecServiceImpl.java|relationUnit|packageUnit",
            "com/cretas/aims/service/supplier/SupplierMaterialServiceImpl.java|target.getPurchaseUnit()|normalizedUnit.code()",
            "com/cretas/aims/service/unit/impl/UnitContractServiceImpl.java|edge.toUnit()|toUnit",
            "com/cretas/aims/service/validation/ProductProcessWorkflowUnitValidator.java|materialUnit|expectedUnit",
            "com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java|wipUnit|inputUnit",
            "com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompiler.java|declaredPrimaryUnit|declaredOutputUnit",
            "com/cretas/aims/service/workflow/impl/ProductWorkflowResolutionServiceImpl.java|inputUnit|outputUnit",
            "com/cretas/aims/service/yield/CostReconcileService.java|firstInputUnit|lastOutputUnit",
            "com/cretas/aims/service/yield/CostReconcileService.java|unit|baseUnit",
            "com/cretas/aims/service/yield/OrderCostBreakdownService.java|normalizeUnit(left)|normalizeUnit(right)",
            "com/cretas/aims/service/yield/impl/InterimSettleServiceImpl.java|agg.unit|unit",
            "com/cretas/aims/service/yield/impl/YieldAnalysisServiceImpl.java|inUnit|outUnit",
            "com/cretas/aims/service/yield/impl/YieldCalculationServiceImpl.java|first.getInputUnit()|last.getOutputUnit()",
            "com/cretas/aims/service/yield/impl/YieldCalculationServiceImpl.java|firstInputUnit|stepOutUnit",
            "com/cretas/aims/service/yield/impl/YieldCalculationServiceImpl.java|inUnit|outUnit",
            "com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java|effInputUnit|effOutputUnit",
            "com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java|inUnit|outUnit|2",
            "com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java|unit|normalizeUnit(conversion.countUnit())",
    };

    /** 词段命中这些就是单位。 */
    private static final Set<String> UNIT_SEGMENTS = Set.of("unit", "units", "uom", "uoms");

    /** {@code unit} 后面跟着这些词段 ⇒ 不是单位, 是以单位为定语的别的东西。 */
    private static final Set<String> NOT_A_UNIT_AFTER = Set.of(
            "price", "cost", "amount", "value", "weight", "qty", "quantity",
            "review", "code", "codes", "name", "names", "id", "ids", "type",
            "label", "text", "conversion", "conversions", "contract", "governance",
            "service", "converter", "validator", "resolver", "map", "list", "set");

    /** {@code unit} 前面跟着这些词段 ⇒ 同理 ({@code gramsPerUnit} / {@code weightPerUnit})。 */
    private static final Set<String> NOT_A_UNIT_BEFORE = Set.of(
            "per", "grams", "gram", "weight", "price", "cost");

    private static final List<String> INSTANCE_METHODS = List.of("equals", "equalsIgnoreCase", "contentEquals");
    private static final List<String> STATIC_HOLDERS = List.of("Objects", "StringUtils", "ObjectUtils");
    private static final List<String> STATIC_METHODS = List.of("equals", "equalsIgnoreCase", "nullSafeEquals");

    // ────────────────────────────── 阳性 / 阴性自检 ──────────────────────────────

    /**
     * 🔴 <b>阳性对照</b>: 扫描器必须在一段已知含违例的源码上真的报出来。
     *
     * <p>没有这一条, 扫描器被改坏 (正则失配 / 路径找不到 / 注释剥错) 时读数会是「0 违例」——
     * 那和「全仓已经修干净了」<b>长得一模一样</b>。本仓已经因为这个形状栽过两次。
     */
    @Test
    @DisplayName("阳性对照: 已知违例形状必须被检出")
    void scannerDetectsKnownViolationShapes() {
        assertTrue(scanSource("A.java", "if (!inUnit.equals(outUnit)) { }").size() == 1,
                "实例 equals 两侧单位没被检出");
        assertTrue(scanSource("A.java", "if (!inUnit.equalsIgnoreCase(outUnit)) { }").size() == 1,
                "equalsIgnoreCase 没被检出");
        assertTrue(scanSource("A.java", "boolean b = Objects.equals(declaredUnit, batchUnit);").size() == 1,
                "Objects.equals 两侧单位没被检出");
        assertTrue(scanSource("A.java", "if (bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) { }").size() == 1,
                "带链式调用的两侧单位没被检出");
    }

    /**
     * 🔴 <b>阴性对照</b>: 这些不该报。少了它, 闸会变成天天误报然后被关掉的那一种。
     */
    @Test
    @DisplayName("阴性对照: 分类/定语/契约码/注释 都不该报")
    void scannerIgnoresNonIdentityShapes() {
        assertTrue(scanSource("A.java", "if (\"kg\".equals(unit)) { }").isEmpty(),
                "与字面量比是按量纲分类, 本闸不管");
        assertTrue(scanSource("A.java", "if (Boolean.TRUE.equals(workflow.getUnitReviewRequired())) { }").isEmpty(),
                "unitReviewRequired 不是单位");
        assertTrue(scanSource("A.java", "if (purchaseUnit.code().equals(materialUnit.code())) { }").isEmpty(),
                "两侧都是契约码, 是正解");
        assertTrue(scanSource("A.java", "if (Objects.equals(unitPrice, otherUnitPrice)) { }").isEmpty(),
                "unitPrice 不是单位");
        assertTrue(scanSource("A.java", "if (stage == OpportunityStage.CLOSED_WON) { }").isEmpty(),
                "Opportunity 里的 'unit' 不是词段");
        assertTrue(scanSource("A.java", "// if (inUnit.equals(outUnit)) { }").isEmpty(),
                "注释里的不算");
        assertTrue(scanSource("A.java", "/* inUnit.equals(outUnit) */ int x = 1;").isEmpty(),
                "块注释里的不算");
    }

    // ────────────────────────────── 棘轮本体 ──────────────────────────────

    @Test
    @DisplayName("棘轮: src/main 里的裸单位同一性比较只许变少")
    void bareUnitEqualityOnlyShrinks() throws IOException {
        Path root = resolveMainJavaRoot();
        Map<String, Integer> current = scanTree(root);
        Map<String, Integer> baseline = parseBaseline();

        List<String> grew = new ArrayList<>();
        for (Map.Entry<String, Integer> e : current.entrySet()) {
            int allowed = baseline.getOrDefault(e.getKey(), 0);
            if (e.getValue() > allowed) {
                grew.add(String.format("  + %s   (基线 %d 处 → 现在 %d 处)", e.getKey(), allowed, e.getValue()));
            }
        }

        if (!grew.isEmpty()) {
            fail("""
                    新增了裸单位字符串比较 —— 单位同一性必须问单位契约, 不能比字符串。

                    %s

                    正解 (三选一, 看你在哪一层):
                      · 有 UnitContractService: unitContractService.areEquivalent(factoryId, a, b)
                      · 需要保留「只/个/件 各自为单位」的语义: 走 storageUnit 比较
                        (见 TransferServiceImpl#sameTransferUnit)
                      · 已经拿到 CanonicalUnit: 比 .code(), 那已过契约, 本闸放行

                    ⛔ 不要把新增项加进 FROZEN_BASELINE 来让闸变绿 —— 那是棘轮反着转。
                    存量已冻结 %d 处 / %d 个键; 改好一处就从基线里删掉一行。
                    """.formatted(String.join("\n", grew),
                    baseline.values().stream().mapToInt(Integer::intValue).sum(), baseline.size()));
        }

        // 基线里已经不存在的条目 —— 说明有人修好了。不算失败, 但要提示收紧, 否则棘轮会松。
        Set<String> stale = new LinkedHashSet<>(baseline.keySet());
        stale.removeAll(current.keySet());
        assertTrue(stale.size() <= baseline.size(), "unreachable");
        if (!stale.isEmpty()) {
            System.out.println("[unit-ratchet] 基线里有 " + stale.size()
                    + " 条已不存在(修好了或代码删了), 下次改动时请一并从 FROZEN_BASELINE 删除:\n  "
                    + String.join("\n  ", stale));
        }
    }

    /**
     * 🔴 扫描器<b>必须真的在扫到东西</b>。存量 69 个键 / 74 处; 若读数掉到极低,
     * 先怀疑仪器 (路径没找到 / 剥注释剥过头), 而不是「大家都修好了」。
     * 修完存量后请把这个下界一起调低 —— 它是「仪器还活着」的证据, 不是覆盖率目标。
     */
    @Test
    @DisplayName("仪器活着: 全仓扫描必须扫到 src/main 且读数量级合理")
    void scannerActuallyReadsTheTree() throws IOException {
        Path root = resolveMainJavaRoot();
        assertTrue(Files.isDirectory(root), "没找到 src/main/java: " + root);
        long javaFiles;
        try (Stream<Path> s = Files.walk(root)) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertTrue(javaFiles > 3000, "只扫到 " + javaFiles + " 个 java 文件, 仪器可能没扫到真正的 src/main");
        Map<String, Integer> current = scanTree(root);
        int total = current.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(total >= 30,
                "只扫出 " + total + " 处裸单位比较 —— 存量曾是 74 处。先查仪器(路径/正则/剥注释), 别先信「修干净了」");
    }

    // ────────────────────────────── 扫描实现 ──────────────────────────────

    private static Map<String, Integer> parseBaseline() {
        Map<String, Integer> m = new TreeMap<>();
        for (String raw : FROZEN_BASELINE) {
            if (raw.isBlank() || raw.startsWith("PLACEHOLDER")) continue;
            String[] parts = raw.split("\\|");
            if (parts.length < 3) continue;
            String key = parts[0] + "|" + parts[1] + "|" + parts[2];
            int n = parts.length >= 4 ? Integer.parseInt(parts[3].trim()) : 1;
            m.merge(key, n, Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> scanTree(Path root) throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            for (String key : scanSource(rel, src)) {
                out.merge(key, 1, Integer::sum);
            }
        }
        return out;
    }

    /** 返回该文件里的违例键 (可重复), 键形如 {@code path|lhs|rhs}, 空白已剥。 */
    static List<String> scanSource(String relativePath, String source) {
        String code = stripComments(source);
        List<String> hits = new ArrayList<>();
        for (String line : code.split("\n", -1)) {
            String low = line.toLowerCase(Locale.ROOT);
            if (!low.contains("unit") && !low.contains("uom")) continue;
            collectInstanceForm(relativePath, line, hits);
            collectStaticForm(relativePath, line, hits);
        }
        return hits;
    }

    private static void collectInstanceForm(String rel, String line, List<String> hits) {
        for (String method : INSTANCE_METHODS) {
            String needle = "." + method + "(";
            int from = 0;
            while (true) {
                int idx = line.indexOf(needle, from);
                if (idx < 0) break;
                from = idx + needle.length();
                // 方法名后面必须紧跟 '(' (已在 needle 里), 前面的 '.' 之前是接收者
                String receiver = scanReceiverBackwards(line, idx);
                int argStart = idx + needle.length();
                int argEnd = matchClosingParen(line, argStart - 1);
                if (argEnd < 0 || receiver == null) continue;
                String arg = line.substring(argStart, argEnd);
                if (splitTopLevel(arg).size() != 1) continue;
                record(rel, receiver, arg, hits);
            }
        }
    }

    private static void collectStaticForm(String rel, String line, List<String> hits) {
        for (String holder : STATIC_HOLDERS) {
            for (String method : STATIC_METHODS) {
                String needle = holder + "." + method + "(";
                int from = 0;
                while (true) {
                    int idx = line.indexOf(needle, from);
                    if (idx < 0) break;
                    from = idx + needle.length();
                    int argStart = idx + needle.length();
                    int argEnd = matchClosingParen(line, argStart - 1);
                    if (argEnd < 0) continue;
                    List<String> args = splitTopLevel(line.substring(argStart, argEnd));
                    if (args.size() != 2) continue;
                    record(rel, args.get(0), args.get(1), hits);
                }
            }
        }
    }

    private static void record(String rel, String lhsRaw, String rhsRaw, List<String> hits) {
        String lhs = normalize(lhsRaw);
        String rhs = normalize(rhsRaw);
        if (!isUnitExpression(lhs) || !isUnitExpression(rhs)) return;
        if (endsWithCodeCall(lhs) && endsWithCodeCall(rhs)) return;   // 契约码比较, 正解
        hits.add(rel + "|" + lhs + "|" + rhs);
    }

    /**
     * 键的规范化。⚠️ 顺带把<b>字符串字面量掏空</b> ({@code "采购计价单位"} → {@code ""}):
     * 参数里的中文提示语进了键之后, 基线在不同控制台编码下会变成乱码
     * (实测 Maven 在 Windows 走 GBK 输出, 复制回源码就对不上了),
     * 而这些字面量对「这两个表达式是不是同一处比较」没有信息量。
     */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").replaceAll("\"[^\"]*\"", "<str>");
    }

    private static boolean endsWithCodeCall(String e) {
        return e.endsWith(".code()");
    }

    /** 从 {@code .method(} 的那个点往前扒出接收者表达式; 扒不出返回 null。 */
    private static String scanReceiverBackwards(String line, int dotIdx) {
        int i = dotIdx - 1;
        StringBuilder sb = new StringBuilder();
        while (i >= 0) {
            char c = line.charAt(i);
            if (c == ')') {
                int open = matchOpeningParen(line, i);
                if (open < 0) return null;
                sb.append(new StringBuilder(line.substring(open, i + 1)).reverse());
                i = open - 1;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                sb.append(c);
                i--;
                continue;
            }
            break;
        }
        String receiver = sb.reverse().toString();
        return receiver.isEmpty() ? null : receiver;
    }

    /** {@code openIdx} 指向 '(' 时返回匹配 ')' 的下标; 否则 -1。 */
    private static int matchClosingParen(String line, int openIdx) {
        if (openIdx < 0 || openIdx >= line.length() || line.charAt(openIdx) != '(') return -1;
        int depth = 0;
        for (int i = openIdx; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** {@code closeIdx} 指向 ')' 时返回匹配 '(' 的下标; 否则 -1。 */
    private static int matchOpeningParen(String line, int closeIdx) {
        int depth = 0;
        for (int i = closeIdx; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * 表达式是不是「一个单位」。⚠️ 字面量一律不是 —— 那是分类, 本闸不管。
     */
    static boolean isUnitExpression(String expr) {
        String e = expr.trim();
        while (e.startsWith("(") && e.endsWith(")")) e = e.substring(1, e.length() - 1).trim();
        if (e.isEmpty() || e.startsWith("\"")) return false;
        if (e.startsWith("Boolean.") || e.startsWith("Integer.") || e.startsWith("Long.")
                || e.startsWith("BigDecimal.")) return false;
        for (String ident : splitIdentifiers(e)) {
            if (identifierIsUnit(ident)) return true;
        }
        return false;
    }

    private static List<String> splitIdentifiers(String e) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') cur.append(c);
            else {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static boolean identifierIsUnit(String ident) {
        List<String> segs = camelSegments(ident);
        for (int i = 0; i < segs.size(); i++) {
            if (!UNIT_SEGMENTS.contains(segs.get(i))) continue;
            String next = i + 1 < segs.size() ? segs.get(i + 1) : null;
            String prev = i > 0 ? segs.get(i - 1) : null;
            if (next != null && NOT_A_UNIT_AFTER.contains(next)) continue;
            if (prev != null && NOT_A_UNIT_BEFORE.contains(prev)) continue;
            return true;
        }
        return false;
    }

    /** {@code getQuantityUnit} → [get, quantity, unit]; {@code UNIT_CODE} → [unit, code]。 */
    static List<String> camelSegments(String ident) {
        List<String> out = new ArrayList<>();
        for (String chunk : ident.split("_")) {
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                boolean boundary = Character.isUpperCase(c)
                        && cur.length() > 0
                        && (!Character.isUpperCase(cur.charAt(cur.length() - 1))
                            || (i + 1 < chunk.length() && Character.isLowerCase(chunk.charAt(i + 1))));
                if (boundary) {
                    out.add(cur.toString().toLowerCase(Locale.ROOT));
                    cur.setLength(0);
                }
                cur.append(c);
            }
            if (cur.length() > 0) out.add(cur.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 剥注释, 保留字符串字面量, 行数不变。 */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, n = text.length();
        int state = 0;   // 0 code, 1 line, 2 block, 3 string, 4 char
        while (i < n) {
            char c = text.charAt(i);
            char nx = i + 1 < n ? text.charAt(i + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (c == '/' && nx == '/') { state = 1; out.append("  "); i += 2; }
                    else if (c == '/' && nx == '*') { state = 2; out.append("  "); i += 2; }
                    else if (c == '"') { state = 3; out.append(c); i++; }
                    else if (c == '\'') { state = 4; out.append(c); i++; }
                    else { out.append(c); i++; }
                }
                case 1 -> {
                    if (c == '\n') { state = 0; out.append('\n'); }
                    else out.append(' ');
                    i++;
                }
                case 2 -> {
                    if (c == '*' && nx == '/') { state = 0; out.append("  "); i += 2; }
                    else { out.append(c == '\n' ? '\n' : ' '); i++; }
                }
                case 3 -> {
                    if (c == '\\') { out.append("  "); i += 2; }
                    else { if (c == '"') state = 0; out.append(c); i++; }
                }
                default -> {
                    if (c == '\\') { out.append("  "); i += 2; }
                    else { if (c == '\'') state = 0; out.append(c); i++; }
                }
            }
        }
        return out.toString();
    }

    private static Path resolveMainJavaRoot() {
        Path candidate = Paths.get("src/main/java");
        if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().normalize();
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path probe = dir.resolve("src/main/java");
            if (Files.isDirectory(probe)) return probe.normalize();
            Path nested = dir.resolve("backend/java/cretas-api/src/main/java");
            if (Files.isDirectory(nested)) return nested.normalize();
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 src/main/java");
    }

    /** 供人工重建基线用: {@code mvn test -Dtest=BareUnitEqualityRatchetContractTest#printBaseline}。 */
    @Test
    @DisplayName("辅助: 打印当前基线 (改好存量后用它重建 FROZEN_BASELINE)")
    void printBaseline() throws IOException {
        Map<String, Integer> current = scanTree(resolveMainJavaRoot());
        StringBuilder sb = new StringBuilder("\n===== BEGIN BASELINE =====\n");
        for (Map.Entry<String, Integer> e : current.entrySet()) {
            sb.append("            \"").append(e.getKey());
            if (e.getValue() > 1) sb.append('|').append(e.getValue());
            sb.append("\",\n");
        }
        sb.append("===== END BASELINE ===== total=")
          .append(current.values().stream().mapToInt(Integer::intValue).sum())
          .append(" keys=").append(current.size()).append('\n');
        // ⚠️ 落 UTF-8 文件再看 —— Maven 在 Windows 走 GBK 输出, 走 stdout 的中文会变乱码,
        //    复制回源码就永远对不上。
        Path dump = Paths.get("target", "unit-ratchet-baseline.txt");
        Files.createDirectories(dump.getParent());
        Files.write(dump, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[unit-ratchet] baseline written to " + dump.toAbsolutePath());
        System.out.println(sb);
        assertFalse(current.isEmpty(), "扫不到任何东西 —— 先查仪器");
    }

    // 让 IDE 不告警未使用的 import
    @SuppressWarnings("unused")
    private static final List<String> UNUSED = Arrays.asList();
    @SuppressWarnings("unused")
    private static final Map<String, String> UNUSED2 = new LinkedHashMap<>();
    @SuppressWarnings("unused")
    private static void unusedAssert() {
        assertEquals(1, 1);
    }
}
