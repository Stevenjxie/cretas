package com.cretas.aims.service.unit.impl;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.unit.UnitConversionStep;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;

@Service
public class UnitContractServiceImpl implements UnitContractService {

    private static final Map<String, CanonicalUnit> SYSTEM_UNITS = systemUnits();
    private static final Map<String, String> SYSTEM_ALIASES = systemAliases();
    /** 被多个中文写法共用的内置码 (实测只有 pcs: 件/个/只) —— 归一到它等于断定这几个是同一个东西。 */
    private static final Set<String> AMBIGUOUS_CHINESE_CODES = ambiguousChineseCodes();
    private static final MathContext FACTOR_CONTEXT = MathContext.DECIMAL128;
    private static final int MAX_GRAPH_HOPS = 16;
    private static final int MAX_GRAPH_STATES = 10_000;
    private static final int MAX_GRAPH_RELATIONS = 1_000;

    private final UnitOfMeasurementRepository unitRepository;
    private final ProductUnitConversionRepository conversionRepository;
    private final MaterialPackagingHierarchyRepository materialPackagingRepository;
    private final MaterialPackagingSpecRepository materialPackagingSpecRepository;

    public UnitContractServiceImpl(
            UnitOfMeasurementRepository unitRepository,
            ProductUnitConversionRepository conversionRepository,
            MaterialPackagingHierarchyRepository materialPackagingRepository,
            MaterialPackagingSpecRepository materialPackagingSpecRepository) {
        this.unitRepository = unitRepository;
        this.conversionRepository = conversionRepository;
        this.materialPackagingRepository = materialPackagingRepository;
        this.materialPackagingSpecRepository = materialPackagingSpecRepository;
    }

    @Override
    public List<CanonicalUnit> catalog(String factoryId) {
        Map<String, CanonicalUnit> byCode = new LinkedHashMap<>(SYSTEM_UNITS);
        factoryCatalog(factoryId).units().values().forEach(unit -> byCode.put(unit.code(), unit));
        return byCode.values().stream()
                .sorted(Comparator.comparing(CanonicalUnit::code))
                .toList();
    }

    @Override
    public List<CanonicalUnit> catalog(String factoryId, UnitUsageScope usageScope) {
        if (usageScope == null) {
            return catalog(factoryId);
        }
        return catalog(factoryId).stream()
                .filter(CanonicalUnit::active)
                .filter(unit -> unit.usageScopes().contains(usageScope))
                .toList();
    }

    @Override
    public UnitNormalizationResult normalize(String factoryId, String rawUnit) {
        return normalize(rawUnit, factoryCatalog(factoryId));
    }

    private UnitNormalizationResult normalize(String rawUnit, Catalog catalog) {
        String key = key(rawUnit);
        if (key == null) {
            return new UnitNormalizationResult(rawUnit, null, null);
        }

        if (catalog.conflicts().contains(key)) {
            return new UnitNormalizationResult(rawUnit, null, null);
        }
        CanonicalUnit catalogUnit = catalog.units().get(key);
        if (catalogUnit != null) {
            return new UnitNormalizationResult(rawUnit, catalogUnit.code(), catalogUnit);
        }

        String code = SYSTEM_ALIASES.get(key);
        CanonicalUnit unit = code == null ? null : SYSTEM_UNITS.get(code);
        return new UnitNormalizationResult(rawUnit, code, unit);
    }

    @Override
    public Optional<CanonicalUnit> describe(String factoryId, String rawUnit) {
        return Optional.ofNullable(normalize(factoryId, rawUnit).unit());
    }

    @Override
    public String storageUnit(String factoryId, String rawUnit) {
        String value = rawUnit == null ? null : rawUnit.trim();
        if (value == null || value.isEmpty()) {
            return "";
        }
        CanonicalUnit unit = normalize(factoryId, value).unit();
        if (unit == null) {
            return value;           // 规则 1: 权威表认不出的自由文本, 原样保留
        }
        if (unit.factorToBase() == null && ambiguousChineseCode(unit.code())) {
            return value;           // 规则 2: 无普适换算 + 多个中文写法 → 保用户字面, 不塌陷
        }
        if (!SYSTEM_UNITS.containsKey(unit.code())) {
            String displayName = unit.displayName();
            // 规则 3: 工厂自定义单位存中文名; 名字缺失时退回码, 不写空
            return displayName == null || displayName.isBlank() ? unit.code() : displayName;
        }
        return unit.code();         // 规则 4: 内置单位存英文码 (与 2400 行存量一致)
    }

    /**
     * 该内置码是否被<b>多个中文写法</b>共用 —— 共用即意味着归一会替工厂断定「这几个是同一个东西」。
     *
     * <p>{@code alias("pcs","pcs","件","个","只")} 一个码挂了三个中文写法, 归一到任何一个都是塌陷;
     * {@code alias("box","box","盒")} 只有一个中文写法, 「盒」与 {@code box} 是同一个单位的两种写法,
     * 归一是纯翻译, 安全。
     *
     * <p>只在 {@code factorToBase == null}(没有普适换算) 时才用这个判据 —— 「公斤/千克」同样是
     * 一个码两个中文写法, 但它们之间有恒定换算, 本来就是同一个单位, 归一有物理意义。
     */
    private static boolean ambiguousChineseCode(String code) {
        return AMBIGUOUS_CHINESE_CODES.contains(code);
    }

    private static Set<String> ambiguousChineseCodes() {
        Map<String, Integer> chineseAliasCount = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : SYSTEM_ALIASES.entrySet()) {
            if (!containsHan(entry.getKey())) {
                continue;
            }
            chineseAliasCount.merge(entry.getValue(), 1, Integer::sum);
        }
        return chineseAliasCount.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    @Override
    public boolean areEquivalent(String factoryId, String leftUnit, String rightUnit) {
        String leftCode = normalize(factoryId, leftUnit).code();
        String rightCode = normalize(factoryId, rightUnit).code();
        return leftCode != null && leftCode.equals(rightCode);
    }

    @Override
    public boolean supportsUsage(String factoryId, String rawUnit, UnitUsageScope usageScope) {
        if (usageScope == null) {
            return false;
        }
        return describe(factoryId, rawUnit)
                .filter(CanonicalUnit::active)
                .map(unit -> unit.usageScopes().contains(usageScope))
                .orElse(false);
    }

    @Override
    public UnitConversionResult convert(UnitConversionContext context) {
        return convert(null, context);
    }

    @Override
    public UnitConversionResult convert(BigDecimal quantity, UnitConversionContext context) {
        if (context == null) {
            return result(
                    UnitConversionStatus.UNKNOWN_UNIT,
                    quantity,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "单位换算上下文不能为空");
        }

        Catalog catalog = factoryCatalog(context.factoryId());
        UnitNormalizationResult from = normalize(context.fromUnit(), catalog);
        UnitNormalizationResult to = normalize(context.toUnit(), catalog);
        if (!from.recognized() || !to.recognized()) {
            return result(
                    UnitConversionStatus.UNKNOWN_UNIT,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(),
                    List.of(),
                    "存在未知单位");
        }
        if (from.code().equals(to.code())) {
            return result(
                    UnitConversionStatus.IDENTITY,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code()),
                    List.of(),
                    null);
        }

        if (isIntrinsicConvertible(from.unit(), to.unit())) {
            BigDecimal factor = from.unit().factorToBase()
                    .divide(to.unit().factorToBase(), FACTOR_CONTEXT);
            BigDecimal converted = quantity == null ? null : quantity.multiply(factor, FACTOR_CONTEXT);
            return result(
                    UnitConversionStatus.CONVERTED,
                    converted,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    List.of(new UnitConversionStep(from.code(), to.code(), factor, null, null)),
                    null
            );
        }

        if (isScientificDimension(from.unit().dimension())
                && isScientificDimension(to.unit().dimension())
                && from.unit().dimension() != to.unit().dimension()) {
            return result(
                    UnitConversionStatus.INCOMPATIBLE_DIMENSION,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    List.of(),
                    "不同物理维度的科学单位不能直接换算");
        }

        if (!hasText(context.factoryId()) || !hasText(context.productTypeId()) || context.at() == null) {
            return result(
                    UnitConversionStatus.PRODUCT_CONVERSION_MISSING,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    List.of(),
                    "产品专属换算需要 factoryId、productTypeId 和业务时间"
            );
        }

        List<ProductUnitConversion> conversions = conversionRepository
                .findEffectiveByFactoryIdAndProductTypeIdAt(
                        context.factoryId(), context.productTypeId(), context.at());
        ConversionGraph graph = buildGraph(conversions, catalog);
        PathSearchResult search = findShortestPaths(graph, from.code(), to.code());
        if (search.limitExceeded()) {
            return result(
                    UnitConversionStatus.AMBIGUOUS_CONVERSION,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    List.of(),
                    "产品换算图搜索超过安全上限"
            );
        }
        if (search.paths().isEmpty()) {
            Optional<UnitConversionResult> hierarchyResult = convertViaMaterialPackagingHierarchy(
                    quantity, context, catalog, from, to);
            if (hierarchyResult.isPresent()) {
                return hierarchyResult.get();
            }
            return result(
                    UnitConversionStatus.PRODUCT_CONVERSION_MISSING,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    List.of(),
                    "未找到产品有效换算关系"
            );
        }

        Set<FactorRatio> products = new HashSet<>();
        search.paths().forEach(path -> products.add(path.factor()));
        if (products.size() > 1) {
            return result(
                    UnitConversionStatus.AMBIGUOUS_CONVERSION,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    List.of(),
                    "多个最短路径得到不同换算乘积"
            );
        }

        ConversionPath selected = search.paths().get(0);
        BigDecimal converted = quantity == null ? null : selected.factor().apply(quantity);
        return result(
                UnitConversionStatus.CONVERTED,
                converted,
                from.code(),
                to.code(),
                selected.units(),
                selected.steps(),
                null
        );
    }

    /**
     * Raw-material master data supplies direct packaging rules such as {@code 1 case = 10 kg};
     * the legacy hierarchy remains a read fallback. Explicit effective product conversions keep
     * precedence. Purchase orders snapshot the selected factor, so later master-data changes do not
     * rewrite historical receipts.
     */
    private Optional<UnitConversionResult> convertViaMaterialPackagingHierarchy(
            BigDecimal quantity,
            UnitConversionContext context,
            Catalog catalog,
            UnitNormalizationResult from,
            UnitNormalizationResult to) {
        if (materialPackagingSpecRepository != null) {
            List<MaterialPackagingSpec> specs = materialPackagingSpecRepository
                    .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                            context.factoryId(), context.productTypeId());
            for (MaterialPackagingSpec spec : specs) {
                UnitNormalizationResult packageUnit = normalize(spec.getPackageUnit(), catalog);
                UnitNormalizationResult baseUnit = normalize(spec.getBaseUnit(), catalog);
                BigDecimal factor = positive(spec.getConversionFactor());
                if (!packageUnit.recognized() || !baseUnit.recognized() || factor == null) {
                    continue;
                }
                if (from.code().equals(packageUnit.code()) && to.code().equals(baseUnit.code())) {
                    return Optional.of(packagingResult(
                            quantity, from.code(), to.code(), List.of(from.code(), to.code()),
                            List.of(packagingStep(from.code(), to.code(), factor, spec.getId()))));
                }
                if (from.code().equals(baseUnit.code()) && to.code().equals(packageUnit.code())) {
                    BigDecimal inverse = BigDecimal.ONE.divide(factor, FACTOR_CONTEXT);
                    return Optional.of(packagingResult(
                            quantity, from.code(), to.code(), List.of(from.code(), to.code()),
                            List.of(packagingStep(from.code(), to.code(), inverse, spec.getId()))));
                }
            }
        }
        if (materialPackagingRepository == null) {
            return Optional.empty();
        }
        MaterialPackagingHierarchy hierarchy = materialPackagingRepository
                .findByMaterialTypeId(context.productTypeId())
                .filter(value -> context.factoryId().equals(value.getFactoryId()))
                .orElse(null);
        if (hierarchy == null) {
            return Optional.empty();
        }

        UnitNormalizationResult level1 = normalize(hierarchy.getLevel1Unit(), catalog);
        UnitNormalizationResult level2 = normalize(hierarchy.getLevel2Unit(), catalog);
        UnitNormalizationResult level3 = normalize(hierarchy.getLevel3Unit(), catalog);
        if (!level1.recognized()) {
            return Optional.empty();
        }

        BigDecimal level1PerLevel2 = positive(hierarchy.getLevel1PerLevel2());
        BigDecimal level2PerLevel3 = positive(hierarchy.getLevel2PerLevel3());
        String conversionRef = hierarchy.getId();

        if (level2.recognized() && level1PerLevel2 != null) {
            if (from.code().equals(level2.code()) && to.code().equals(level1.code())) {
                return Optional.of(packagingResult(
                        quantity, from.code(), to.code(), List.of(from.code(), to.code()),
                        List.of(packagingStep(from.code(), to.code(), level1PerLevel2, conversionRef))));
            }
            if (from.code().equals(level1.code()) && to.code().equals(level2.code())) {
                BigDecimal inverse = BigDecimal.ONE.divide(level1PerLevel2, FACTOR_CONTEXT);
                return Optional.of(packagingResult(
                        quantity, from.code(), to.code(), List.of(from.code(), to.code()),
                        List.of(packagingStep(from.code(), to.code(), inverse, conversionRef))));
            }
        }

        if (level2.recognized() && level3.recognized() && level2PerLevel3 != null) {
            if (from.code().equals(level3.code()) && to.code().equals(level2.code())) {
                return Optional.of(packagingResult(
                        quantity, from.code(), to.code(), List.of(from.code(), to.code()),
                        List.of(packagingStep(from.code(), to.code(), level2PerLevel3, conversionRef))));
            }
            if (from.code().equals(level2.code()) && to.code().equals(level3.code())) {
                BigDecimal inverse = BigDecimal.ONE.divide(level2PerLevel3, FACTOR_CONTEXT);
                return Optional.of(packagingResult(
                        quantity, from.code(), to.code(), List.of(from.code(), to.code()),
                        List.of(packagingStep(from.code(), to.code(), inverse, conversionRef))));
            }
        }

        if (level2.recognized() && level3.recognized()
                && level1PerLevel2 != null && level2PerLevel3 != null) {
            if (from.code().equals(level3.code()) && to.code().equals(level1.code())) {
                return Optional.of(packagingResult(
                        quantity, from.code(), to.code(), List.of(from.code(), level2.code(), to.code()),
                        List.of(
                                packagingStep(from.code(), level2.code(), level2PerLevel3, conversionRef),
                                packagingStep(level2.code(), to.code(), level1PerLevel2, conversionRef))));
            }
            if (from.code().equals(level1.code()) && to.code().equals(level3.code())) {
                BigDecimal inverseLevel1 = BigDecimal.ONE.divide(level1PerLevel2, FACTOR_CONTEXT);
                BigDecimal inverseLevel2 = BigDecimal.ONE.divide(level2PerLevel3, FACTOR_CONTEXT);
                return Optional.of(packagingResult(
                        quantity, from.code(), to.code(), List.of(from.code(), level2.code(), to.code()),
                        List.of(
                                packagingStep(from.code(), level2.code(), inverseLevel1, conversionRef),
                                packagingStep(level2.code(), to.code(), inverseLevel2, conversionRef))));
            }
        }
        return Optional.empty();
    }

    private UnitConversionResult packagingResult(
            BigDecimal quantity,
            String fromUnit,
            String toUnit,
            List<String> path,
            List<UnitConversionStep> steps) {
        BigDecimal converted = quantity;
        if (converted != null) {
            for (UnitConversionStep step : steps) {
                converted = converted.multiply(step.factor(), FACTOR_CONTEXT);
            }
        }
        return result(UnitConversionStatus.CONVERTED, converted, fromUnit, toUnit, path, steps, null);
    }

    private UnitConversionStep packagingStep(
            String fromUnit, String toUnit, BigDecimal factor, String conversionRef) {
        return new UnitConversionStep(fromUnit, toUnit, factor, conversionRef, null);
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }

    @Override
    public List<String> validateConversionGraph(
            String factoryId,
            String productTypeId,
            LocalDateTime at) {
        if (!hasText(factoryId) || !hasText(productTypeId) || at == null) {
            return List.of("换算图校验需要 factoryId、productTypeId 和业务时间");
        }

        List<ProductUnitConversion> conversions = conversionRepository
                .findEffectiveByFactoryIdAndProductTypeIdAt(factoryId, productTypeId, at);
        return buildGraph(conversions, factoryCatalog(factoryId)).errors();
    }

    private UnitConversionResult result(
            UnitConversionStatus status,
            BigDecimal quantity,
            String fromUnit,
            String toUnit,
            List<String> path,
            List<UnitConversionStep> steps,
            String message) {
        UnitConversionStep direct = steps.size() == 1 && steps.get(0).conversionRefId() != null
                ? steps.get(0)
                : null;
        return new UnitConversionResult(
                status,
                quantity,
                fromUnit,
                toUnit,
                path,
                direct == null ? null : direct.conversionRefId(),
                direct == null ? null : direct.conversionVersion(),
                message,
                steps);
    }

    private boolean isIntrinsicConvertible(CanonicalUnit from, CanonicalUnit to) {
        return from.dimension() == to.dimension()
                && (from.dimension() == UnitDimension.MASS
                || from.dimension() == UnitDimension.VOLUME
                || from.dimension() == UnitDimension.LENGTH)
                && from.factorToBase() != null
                && to.factorToBase() != null;
    }

    private boolean isScientificDimension(UnitDimension dimension) {
        return dimension == UnitDimension.MASS
                || dimension == UnitDimension.VOLUME
                || dimension == UnitDimension.LENGTH;
    }

    private ConversionGraph buildGraph(
            List<ProductUnitConversion> conversions,
            Catalog catalog) {
        List<ProductUnitConversion> effective = conversions == null ? List.of() : conversions;
        Set<String> errors = new LinkedHashSet<>();
        if (effective.size() > MAX_GRAPH_RELATIONS) {
            errors.add("有效产品换算关系超过安全上限 " + MAX_GRAPH_RELATIONS);
            return new ConversionGraph(Map.of(), List.copyOf(errors));
        }

        Map<String, List<GraphEdge>> adjacency = new TreeMap<>();
        Map<LogicalUnitPair, String> logicalRelations = new HashMap<>();
        List<String> relationIds = new ArrayList<>();

        for (ProductUnitConversion conversion : effective) {
            if (conversion == null) {
                errors.add("产品换算关系不能为空");
                continue;
            }

            String relationId = hasText(conversion.getId()) ? conversion.getId() : "<unknown>";
            relationIds.add(relationId);
            UnitNormalizationResult from = normalize(conversion.getFromUnitCode(), catalog);
            UnitNormalizationResult to = normalize(conversion.getToUnitCode(), catalog);
            if (!from.recognized() || !to.recognized()) {
                errors.add("关系 " + relationId + " 包含未知单位");
                continue;
            }
            if (conversion.getFactor() == null || conversion.getFactor().signum() <= 0) {
                errors.add("关系 " + relationId + " 的换算因子必须大于 0");
                continue;
            }

            FactorRatio factor = FactorRatio.of(conversion.getFactor());
            if (from.code().equals(to.code())) {
                if (!factor.equals(FactorRatio.ONE)) {
                    errors.add("关系 " + relationId + " 构成乘积不为 1 的自闭环");
                }
                continue;
            }

            LogicalUnitPair pair = LogicalUnitPair.of(from.code(), to.code());
            String previousId = logicalRelations.putIfAbsent(pair, relationId);
            if (previousId != null) {
                errors.add("重复产品换算关系 " + previousId + " 与 " + relationId
                        + " 连接 " + pair.left() + " / " + pair.right());
            }

            addEdge(adjacency, new GraphEdge(
                    to.code(),
                    factor,
                    new UnitConversionStep(
                            from.code(),
                            to.code(),
                            conversion.getFactor(),
                            conversion.getId(),
                            conversion.getVersion())));
            FactorRatio inverse = factor.inverse();
            addEdge(adjacency, new GraphEdge(
                    from.code(),
                    inverse,
                    new UnitConversionStep(
                            to.code(),
                            from.code(),
                            inverse.toBigDecimal(),
                            conversion.getId(),
                            conversion.getVersion())));
        }

        Comparator<GraphEdge> edgeOrder = Comparator
                .comparing(GraphEdge::toUnit)
                .thenComparing(edge -> String.valueOf(edge.step().conversionRefId()))
                .thenComparing(edge -> edge.factor().numerator())
                .thenComparing(edge -> edge.factor().denominator());
        Map<String, List<GraphEdge>> orderedAdjacency = new LinkedHashMap<>();
        adjacency.forEach((unit, edges) -> {
            edges.sort(edgeOrder);
            orderedAdjacency.put(unit, List.copyOf(edges));
        });

        relationIds.sort(String::compareTo);
        errors.addAll(findInconsistentCycles(orderedAdjacency, relationIds));
        return new ConversionGraph(
                Map.copyOf(orderedAdjacency),
                List.copyOf(errors));
    }

    private static void addEdge(Map<String, List<GraphEdge>> adjacency, GraphEdge edge) {
        adjacency.computeIfAbsent(edge.step().fromUnit(), ignored -> new ArrayList<>()).add(edge);
        adjacency.computeIfAbsent(edge.toUnit(), ignored -> new ArrayList<>());
    }

    private static List<String> findInconsistentCycles(
            Map<String, List<GraphEdge>> adjacency,
            List<String> relationIds) {
        Set<String> errors = new LinkedHashSet<>();
        Map<String, FactorRatio> factorsFromRoot = new HashMap<>();

        for (String root : adjacency.keySet()) {
            if (factorsFromRoot.containsKey(root)) {
                continue;
            }
            factorsFromRoot.put(root, FactorRatio.ONE);
            Queue<String> queue = new ArrayDeque<>();
            queue.add(root);

            while (!queue.isEmpty()) {
                String current = queue.remove();
                FactorRatio currentFactor = factorsFromRoot.get(current);
                for (GraphEdge edge : adjacency.getOrDefault(current, List.of())) {
                    FactorRatio expected = currentFactor.multiply(edge.factor());
                    FactorRatio existing = factorsFromRoot.putIfAbsent(edge.toUnit(), expected);
                    if (existing == null) {
                        queue.add(edge.toUnit());
                    } else if (!existing.equals(expected)) {
                        errors.add("检测到乘积不为 1 的换算闭环，涉及关系: "
                                + String.join(", ", relationIds));
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    private static PathSearchResult findShortestPaths(
            ConversionGraph graph,
            String fromUnit,
            String toUnit) {
        Queue<SearchState> queue = new ArrayDeque<>();
        queue.add(new SearchState(
                fromUnit,
                FactorRatio.ONE,
                List.of(fromUnit),
                List.of(),
                Set.of(fromUnit)));

        List<ConversionPath> matches = new ArrayList<>();
        int targetDepth = Integer.MAX_VALUE;
        int generatedStates = 1;
        boolean hopLimitReached = false;

        while (!queue.isEmpty()) {
            SearchState current = queue.remove();
            int currentDepth = current.steps().size();
            if (currentDepth >= targetDepth) {
                continue;
            }
            if (currentDepth >= MAX_GRAPH_HOPS) {
                hopLimitReached = true;
                continue;
            }

            for (GraphEdge edge : graph.adjacency().getOrDefault(current.unit(), List.of())) {
                if (current.visitedUnits().contains(edge.toUnit())) {
                    continue;
                }
                if (++generatedStates > MAX_GRAPH_STATES) {
                    return new PathSearchResult(List.of(), true);
                }

                List<String> units = appended(current.units(), edge.toUnit());
                List<UnitConversionStep> steps = appended(current.steps(), edge.step());
                FactorRatio factor = current.factor().multiply(edge.factor());
                int depth = steps.size();
                if (edge.toUnit().equals(toUnit)) {
                    if (depth < targetDepth) {
                        matches.clear();
                        targetDepth = depth;
                    }
                    if (depth == targetDepth) {
                        matches.add(new ConversionPath(factor, units, steps));
                    }
                    continue;
                }

                Set<String> visited = new LinkedHashSet<>(current.visitedUnits());
                visited.add(edge.toUnit());
                queue.add(new SearchState(
                        edge.toUnit(),
                        factor,
                        units,
                        steps,
                        Set.copyOf(visited)));
            }
        }

        return new PathSearchResult(List.copyOf(matches), matches.isEmpty() && hopLimitReached);
    }

    private static <T> List<T> appended(List<T> source, T value) {
        List<T> copy = new ArrayList<>(source.size() + 1);
        copy.addAll(source);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Catalog factoryCatalog(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return new Catalog(Map.of(), Set.of());
        }

        Map<String, CanonicalUnit> catalog = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (UnitOfMeasurement unit : unitRepository.findAllByFactoryId(factoryId)) {
            CanonicalUnit canonical = canonicalize(unit);
            if (canonical == null) {
                continue;
            }
            boolean legacyGlobalSystemUnit = "*".equals(unit.getFactoryId())
                    && Boolean.TRUE.equals(unit.getIsSystem());
            registerCatalogAlias(catalog, conflicts, unit.getUnitCode(), canonical, legacyGlobalSystemUnit);
            registerCatalogAlias(catalog, conflicts, unit.getUnitName(), canonical, legacyGlobalSystemUnit);
            registerCatalogAlias(catalog, conflicts, unit.getUnitSymbol(), canonical, legacyGlobalSystemUnit);
            if (unit.getAliasesJson() != null) {
                unit.getAliasesJson().forEach(alias -> registerCatalogAlias(
                        catalog, conflicts, alias, canonical, legacyGlobalSystemUnit));
            }
        }
        return new Catalog(Map.copyOf(catalog), Set.copyOf(conflicts));
    }

    private static void registerCatalogAlias(
            Map<String, CanonicalUnit> catalog,
            Set<String> conflicts,
            String rawAlias,
            CanonicalUnit unit,
            boolean legacyGlobalSystemUnit) {
        String alias = key(rawAlias);
        if (alias == null || conflicts.contains(alias)) {
            return;
        }

        CanonicalUnit systemUnit = systemUnitFor(alias);
        if (systemUnit != null && !sameUnit(systemUnit, unit)) {
            // The pre-unit-contract global dictionary used a few overloaded codes
            // (notably box=箱), while the canonical contract distinguishes 盒/箱.
            // Keep the built-in canonical alias authoritative, but continue to
            // fail closed for tenant-defined aliases that masquerade as a system unit.
            if (legacyGlobalSystemUnit) {
                return;
            }
            catalog.remove(alias);
            conflicts.add(alias);
            return;
        }

        CanonicalUnit existing = catalog.putIfAbsent(alias, unit);
        if (existing != null && !sameUnit(existing, unit)) {
            catalog.remove(alias);
            conflicts.add(alias);
        }
    }

    private static CanonicalUnit systemUnitFor(String alias) {
        String code = SYSTEM_ALIASES.get(alias);
        return code == null ? null : SYSTEM_UNITS.get(code);
    }

    private static boolean sameUnit(CanonicalUnit left, CanonicalUnit right) {
        return left.code().equals(right.code()) && left.dimension() == right.dimension();
    }

    private CanonicalUnit canonicalize(UnitOfMeasurement unit) {
        String code = key(unit.getUnitCode());
        if (code == null) {
            return null;
        }
        CanonicalUnit systemUnit = SYSTEM_UNITS.get(code);
        if (systemUnit != null) {
            return new CanonicalUnit(
                    systemUnit.code(),
                    systemUnit.dimension(),
                    systemUnit.baseCode(),
                    systemUnit.factorToBase(),
                    unit.getUnitName(),
                    unit.getDecimalPlaces() == null ? systemUnit.displayScale() : unit.getDecimalPlaces(),
                    usageScopes(unit, systemUnit.dimension()),
                    hasText(unit.getConversionFamily()) ? unit.getConversionFamily() : systemUnit.conversionFamily(),
                    !Boolean.FALSE.equals(unit.getIsActive())
            );
        }
        UnitDimension unitDimension = dimension(unit.getCategory());
        return new CanonicalUnit(
                code,
                unitDimension,
                code,
                null,
                unit.getUnitName(),
                unit.getDecimalPlaces() == null ? 0 : unit.getDecimalPlaces(),
                usageScopes(unit, unitDimension),
                hasText(unit.getConversionFamily()) ? unit.getConversionFamily() : unitDimension.name(),
                !Boolean.FALSE.equals(unit.getIsActive())
        );
    }

    private static Set<UnitUsageScope> usageScopes(UnitOfMeasurement unit, UnitDimension dimension) {
        List<String> configured = unit.getUsageScopesJson();
        if (configured == null) {
            return defaultUsageScopes(dimension);
        }
        EnumSet<UnitUsageScope> scopes = EnumSet.noneOf(UnitUsageScope.class);
        for (String value : configured) {
            if (!hasText(value)) {
                continue;
            }
            try {
                scopes.add(UnitUsageScope.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown future scopes are fail-closed until this runtime knows them.
            }
        }
        return Set.copyOf(scopes);
    }

    private static Set<UnitUsageScope> defaultUsageScopes(UnitDimension dimension) {
        return switch (dimension) {
            case MASS, VOLUME, COUNT, PACKAGE -> Set.of(
                    UnitUsageScope.INVENTORY_QUANTITY,
                    UnitUsageScope.PURCHASE_QUANTITY,
                    UnitUsageScope.BOM_QUANTITY,
                    UnitUsageScope.SPECIFICATION);
            case LENGTH, AREA -> Set.of(UnitUsageScope.SPECIFICATION);
            case TIME -> Set.of(UnitUsageScope.PROCESS_DURATION);
            case TEMPERATURE -> Set.of(UnitUsageScope.STORAGE_TEMPERATURE);
            case RATIO -> Set.of(UnitUsageScope.YIELD_RATE);
            case UNKNOWN -> Set.of();
        };
    }

    private static UnitDimension dimension(String category) {
        if (category == null) {
            return UnitDimension.UNKNOWN;
        }
        return switch (category.trim().toUpperCase(Locale.ROOT)) {
            case "MASS", "WEIGHT" -> UnitDimension.MASS;
            case "VOLUME" -> UnitDimension.VOLUME;
            case "LENGTH" -> UnitDimension.LENGTH;
            case "AREA" -> UnitDimension.AREA;
            case "COUNT" -> UnitDimension.COUNT;
            case "PACKAGE", "PACKAGING" -> UnitDimension.PACKAGE;
            case "TIME" -> UnitDimension.TIME;
            case "TEMPERATURE" -> UnitDimension.TEMPERATURE;
            case "RATIO" -> UnitDimension.RATIO;
            default -> UnitDimension.UNKNOWN;
        };
    }

    private static String key(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            return null;
        }
        return Normalizer.normalize(rawUnit, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static Map<String, CanonicalUnit> systemUnits() {
        Map<String, CanonicalUnit> units = new LinkedHashMap<>();
        add(units, "mg", UnitDimension.MASS, "g", "0.001", "毫克", 3);
        add(units, "g", UnitDimension.MASS, "g", "1", "克", 3);
        add(units, "kg", UnitDimension.MASS, "g", "1000", "公斤", 3);
        add(units, "jin", UnitDimension.MASS, "g", "500", "斤", 3);
        add(units, "t", UnitDimension.MASS, "g", "1000000", "吨", 6);
        add(units, "ml", UnitDimension.VOLUME, "ml", "1", "毫升", 3);
        add(units, "l", UnitDimension.VOLUME, "ml", "1000", "升", 3);
        add(units, "mm", UnitDimension.LENGTH, "m", "0.001", "毫米", 3);
        add(units, "cm", UnitDimension.LENGTH, "m", "0.01", "厘米", 3);
        add(units, "m", UnitDimension.LENGTH, "m", "1", "米", 3);
        add(units, "km", UnitDimension.LENGTH, "m", "1000", "千米", 6);
        add(units, "pcs", UnitDimension.COUNT, "pcs", null, "件", 0);
        add(units, "portion", UnitDimension.COUNT, "portion", null, "份", 0);
        add(units, "box", UnitDimension.PACKAGE, "box", null, "盒", 0);
        add(units, "case", UnitDimension.PACKAGE, "case", null, "箱", 0);
        add(units, "bag", UnitDimension.PACKAGE, "bag", null, "袋", 0);
        add(units, "pack", UnitDimension.PACKAGE, "pack", null, "包", 0);
        add(units, "bottle", UnitDimension.PACKAGE, "bottle", null, "瓶", 0);
        add(units, "can", UnitDimension.PACKAGE, "can", null, "罐", 0);
        add(units, "crate", UnitDimension.PACKAGE, "crate", null, "框", 0);
        add(units, "pail", UnitDimension.PACKAGE, "pail", null, "桶", 0);
        add(units, "roll", UnitDimension.PACKAGE, "roll", null, "卷", 0);
        add(units, "slice", UnitDimension.COUNT, "slice", null, "片", 0);
        add(units, "item", UnitDimension.COUNT, "item", null, "项", 0);
        // 🔴 这三个此前只在 systemAliases() 里有别名, 却从没在这里定义 ——
        // systemUnitFor() 查到别名 sheet 再查定义得到 null, 整体返回 null。
        // 后果静默且双向: crossLanguageCode("张") 原样返回「张」(中英写法被当成两个单位,
        // 有库存也匹配不上), isBuiltInCountingUnit("sheet") 返回 false
        // (「按件计数的成品必须填标准克重」对它失效)。
        // prod 实测 raw_material_types 里「张」6 行在用。量纲取值与名录
        // unit_of_measurements 的同族单位一致 (托盘/板是容器 → PACKAGE)。
        // 契约: UnitAuthorityConsistencyTest 扫源码钉住「别名承诺的码必须有定义」。
        add(units, "sheet", UnitDimension.COUNT, "sheet", null, "张", 0);
        add(units, "tray", UnitDimension.PACKAGE, "tray", null, "托盘", 0);
        add(units, "plate", UnitDimension.PACKAGE, "plate", null, "板", 0);
        return Map.copyOf(units);
    }

    private static void add(
            Map<String, CanonicalUnit> units,
            String code,
            UnitDimension dimension,
            String baseCode,
            String factorToBase,
            String displayName,
            int displayScale) {
        units.put(code, new CanonicalUnit(
                code,
                dimension,
                baseCode,
                factorToBase == null ? null : new BigDecimal(factorToBase),
                displayName,
                displayScale,
                defaultUsageScopes(dimension),
                baseCode,
                true
        ));
    }

    private static Map<String, String> systemAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        alias(aliases, "mg", "mg", "毫克");
        alias(aliases, "g", "g", "克", "gram", "grams");
        alias(aliases, "kg", "kg", "公斤", "千克", "kgs", "kilogram", "kilograms");
        alias(aliases, "jin", "jin", "斤");
        alias(aliases, "t", "t", "吨");
        alias(aliases, "ml", "ml", "毫升");
        alias(aliases, "l", "l", "升");
        alias(aliases, "mm", "mm", "毫米", "公厘", "millimeter", "millimeters");
        alias(aliases, "cm", "cm", "厘米", "公分", "centimeter", "centimeters");
        alias(aliases, "m", "m", "米", "公尺", "meter", "meters", "metre", "metres");
        alias(aliases, "km", "km", "千米", "公里", "kilometer", "kilometers", "kilometre", "kilometres");
        // pc / piece / pieces / carton 来自 SkuImportServiceImpl 那张私有别名表 ——
        // 收敛时把知识并进权威表, 而不是随表一起删掉。
        alias(aliases, "pcs", "pcs", "件", "个", "只", "pc", "piece", "pieces");
        alias(aliases, "portion", "portion", "份");
        alias(aliases, "box", "box", "盒");
        alias(aliases, "case", "case", "箱", "carton");
        alias(aliases, "bag", "bag", "袋");
        alias(aliases, "pack", "pack", "包");
        alias(aliases, "bottle", "bottle", "瓶");
        alias(aliases, "can", "can", "罐");
        alias(aliases, "crate", "crate", "框", "筐");
        alias(aliases, "pail", "pail", "桶");
        alias(aliases, "roll", "roll", "卷");
        alias(aliases, "slice", "slice", "片");
        alias(aliases, "sheet", "sheet", "张");
        alias(aliases, "tray", "tray", "托盘");
        alias(aliases, "plate", "plate", "板");
        alias(aliases, "item", "item", "项");
        return Map.copyOf(aliases);
    }

    /** Built-in canonical lookup used by the unit creation duplicate gate. */
    public static Optional<CanonicalUnit> describeBuiltIn(String rawUnit) {
        String normalized = key(rawUnit);
        return Optional.ofNullable(normalized == null ? null : systemUnitFor(normalized));
    }

    /** Same normalization used by runtime unit lookup and create-time duplicate checks. */
    public static String normalizeLookupKey(String rawUnit) {
        return key(rawUnit);
    }

    /**
     * 归一到内置规范码; 表里没有的原样返回 (trim + 小写)。
     *
     * <p>🔴 <b>存在的理由</b>: 2026-07-31 之前, 至少五个地方各自手写了一张单位别名 switch
     * (报工 BOM 校验 / 结单实收 / Workflow 快照比对 / 辅料工作台 / 移动端实收), 覆盖 2~21 组不等,
     * 而这张权威表有 24 组。后果两个方向都有 —— 表里没有的单位原样返回, 于是「袋」≠「bag」被误拦
     * (客户现场); 有的抄本又把 个/片 折成同一个, 让本该拦的混过去。</p>
     *
     * <p>所以它们现在<b>全部调这一个函数</b>。要加单位改 {@link #systemAliases()} 一处即可,
     * 不需要再去找那五张表 —— <b>找不全正是上一轮的失败方式</b>。</p>
     *
     * <p>⚠️ 只覆盖<b>内置</b> 24 组; <b>租户自定义别名</b>需要 factoryId, 走实例方法
     * {@link #normalize(String, String)} / {@link #areEquivalent(String, String, String)}。
     * 静态调用点拿不到 factoryId, 这是已知边界, 不是新增缺口。</p>
     */
    public static String canonicalCodeOrRaw(String rawUnit) {
        if (rawUnit == null) {
            return null;
        }
        String trimmed = rawUnit.trim();
        CanonicalUnit builtIn = systemUnitFor(key(trimmed));
        return builtIn != null ? builtIn.code() : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * <b>跨语言</b>归一 —— 只把「同一个单位的英文码与中文名」折成一个,
     * <b>绝不合并不同的中文计量单位</b>。
     *
     * <p>🔴 与 {@link #canonicalCodeOrRaw} 的区别, 以及为什么必须有这个区别:</p>
     *
     * <p>权威别名表里 {@code alias("pcs", "pcs", "件", "个", "只")} —— 件/个/只 都归 pcs。
     * 但 <b>#1976 明确规定「一只 ≠ 一件」</b>: 计数单位按字面区分, 一只鸡不是一件包材。
     * 既有做法见 {@code ProductionStockAllocationServiceImpl#canonicalNativeUnit} ——
     * 它只对 MASS/VOLUME 折别名, 其余一律回落字面比较, 正是为了守住这条。</p>
     *
     * <p>而客户 2026-07-31 撞到的是<b>另一件事</b>: 「袋」与「bag」是<b>同一个单位的两种写法</b>
     * (中文名 vs 英文码), 被判成不同 → 误拦。这两件必须分开处理:</p>
     *
     * <ul>
     *   <li><b>可换算维度</b> (MASS / VOLUME / LENGTH): 全部别名折成规范码 ——
     *       千克/公斤/kg 本就是一个单位, 且真的能换算。</li>
     *   <li><b>计数 / 包装维度</b> (COUNT / PACKAGE): <b>只</b>折「规范码 ↔ 该单位的中文名」
     *       ({@code CanonicalUnit#displayName}), 其余别名保持字面。
     *       于是 袋≡bag、盒≡box、件≡pcs, 而 <b>只≠件、个≠件</b> —— #1976 得以保住。</li>
     * </ul>
     *
     * <p>凡是拿结果做<b>相等判定 / 去重</b>的地方都该用这个, 而不是
     * {@link #canonicalCodeOrRaw} (后者会把 只/个/件 并成一个)。</p>
     */
    public static String crossLanguageCode(String rawUnit) {
        if (rawUnit == null) {
            return null;
        }
        String trimmed = rawUnit.trim();
        if (DISTINCT_COUNT_LABELS.contains(trimmed)) {
            return trimmed;
        }
        CanonicalUnit builtIn = systemUnitFor(key(trimmed));
        return builtIn != null ? builtIn.code() : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * <b>#1976 的例外名单</b>: 权威表把 件/个/只 都并进 {@code pcs} (为了换算与展示),
     * 但业务上<b>一只 ≠ 一件</b> —— 一只鸡不是一件包材, 拿「只」去顶「件」过闸是错的。
     *
     * <p>写成<b>显式名单</b>而不是靠某条通用规则的副作用: 早先试过「只折 码↔中文名」,
     * 结果把「框」和「筐」也拆开了 —— 那俩只是同一个词的两种写法, 拆开正是本次要修的那类误拦。
     * 真正需要区分的就这两个字, 列出来最清楚, 也最不容易误伤别的单位。</p>
     *
     * <p>「件」<b>不在</b>名单里 —— 它就是 {@code pcs} 的中文名, 件≡pcs 是对的;
     * 名单只挡住 只/个 各自独立。</p>
     */
    private static final Set<String> DISTINCT_COUNT_LABELS = Set.of("只", "个");

    /**
     * 是不是「按个数论」的单位 (量纲 {@link UnitDimension#COUNT} 或 {@link UnitDimension#PACKAGE})。
     *
     * <p>用于投料折算判定: 计数单位与 kg 口径不同, 必须经 gramsPerUnit 折算, 不能直接当 kg 用。
     * 内置表不认识的单位返回 {@code false} —— 调用方自行决定要不要再退回自己的模糊匹配。</p>
     */
    public static boolean isBuiltInCountingUnit(String rawUnit) {
        CanonicalUnit builtIn = rawUnit == null ? null : systemUnitFor(key(rawUnit.trim()));
        return builtIn != null
                && (builtIn.dimension() == UnitDimension.COUNT
                        || builtIn.dimension() == UnitDimension.PACKAGE);
    }

    private static void alias(Map<String, String> aliases, String code, String... values) {
        for (String value : values) {
            aliases.put(key(value), code);
        }
    }

    private record GraphEdge(
            String toUnit,
            FactorRatio factor,
            UnitConversionStep step) {
    }

    private record ConversionGraph(
            Map<String, List<GraphEdge>> adjacency,
            List<String> errors) {
    }

    private record SearchState(
            String unit,
            FactorRatio factor,
            List<String> units,
            List<UnitConversionStep> steps,
            Set<String> visitedUnits) {
    }

    private record ConversionPath(
            FactorRatio factor,
            List<String> units,
            List<UnitConversionStep> steps) {
    }

    private record PathSearchResult(List<ConversionPath> paths, boolean limitExceeded) {
    }

    private record LogicalUnitPair(String left, String right) {

        private static LogicalUnitPair of(String first, String second) {
            return first.compareTo(second) <= 0
                    ? new LogicalUnitPair(first, second)
                    : new LogicalUnitPair(second, first);
        }
    }

    private record FactorRatio(BigInteger numerator, BigInteger denominator) {

        private static final FactorRatio ONE = new FactorRatio(BigInteger.ONE, BigInteger.ONE);

        private FactorRatio {
            if (denominator.signum() == 0) {
                throw new IllegalArgumentException("denominator cannot be zero");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }

        private static FactorRatio of(BigDecimal value) {
            BigDecimal normalized = value.stripTrailingZeros();
            BigInteger numerator = normalized.unscaledValue();
            int scale = normalized.scale();
            if (scale < 0) {
                return new FactorRatio(
                        numerator.multiply(BigInteger.TEN.pow(-scale)),
                        BigInteger.ONE);
            }
            return new FactorRatio(numerator, BigInteger.TEN.pow(scale));
        }

        private FactorRatio multiply(FactorRatio other) {
            return new FactorRatio(
                    numerator.multiply(other.numerator),
                    denominator.multiply(other.denominator));
        }

        private FactorRatio inverse() {
            return new FactorRatio(denominator, numerator);
        }

        private BigDecimal apply(BigDecimal quantity) {
            return quantity.multiply(new BigDecimal(numerator), FACTOR_CONTEXT)
                    .divide(new BigDecimal(denominator), FACTOR_CONTEXT);
        }

        private BigDecimal toBigDecimal() {
            return new BigDecimal(numerator).divide(new BigDecimal(denominator), FACTOR_CONTEXT);
        }
    }

    private record Catalog(Map<String, CanonicalUnit> units, Set<String> conflicts) {
    }
}
