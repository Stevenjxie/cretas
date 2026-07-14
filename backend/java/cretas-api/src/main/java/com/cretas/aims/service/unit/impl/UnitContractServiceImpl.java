package com.cretas.aims.service.unit.impl;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.unit.UnitConversionStep;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final MathContext FACTOR_CONTEXT = MathContext.DECIMAL128;
    private static final int MAX_GRAPH_HOPS = 16;
    private static final int MAX_GRAPH_STATES = 10_000;
    private static final int MAX_GRAPH_RELATIONS = 1_000;

    private final UnitOfMeasurementRepository unitRepository;
    private final ProductUnitConversionRepository conversionRepository;

    public UnitContractServiceImpl(
            UnitOfMeasurementRepository unitRepository,
            ProductUnitConversionRepository conversionRepository) {
        this.unitRepository = unitRepository;
        this.conversionRepository = conversionRepository;
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
    public boolean areEquivalent(String factoryId, String leftUnit, String rightUnit) {
        String leftCode = normalize(factoryId, leftUnit).code();
        String rightCode = normalize(factoryId, rightUnit).code();
        return leftCode != null && leftCode.equals(rightCode);
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
                && (from.dimension() == UnitDimension.MASS || from.dimension() == UnitDimension.VOLUME)
                && from.factorToBase() != null
                && to.factorToBase() != null;
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
            registerCatalogAlias(catalog, conflicts, unit.getUnitCode(), canonical);
            registerCatalogAlias(catalog, conflicts, unit.getUnitName(), canonical);
            registerCatalogAlias(catalog, conflicts, unit.getUnitSymbol(), canonical);
            if (unit.getAliasesJson() != null) {
                unit.getAliasesJson().forEach(alias -> registerCatalogAlias(catalog, conflicts, alias, canonical));
            }
        }
        return new Catalog(Map.copyOf(catalog), Set.copyOf(conflicts));
    }

    private static void registerCatalogAlias(
            Map<String, CanonicalUnit> catalog,
            Set<String> conflicts,
            String rawAlias,
            CanonicalUnit unit) {
        String alias = key(rawAlias);
        if (alias == null || conflicts.contains(alias)) {
            return;
        }

        CanonicalUnit systemUnit = systemUnitFor(alias);
        if (systemUnit != null && !sameUnit(systemUnit, unit)) {
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
                    unit.getDecimalPlaces() == null ? systemUnit.displayScale() : unit.getDecimalPlaces()
            );
        }
        return new CanonicalUnit(
                code,
                dimension(unit.getCategory()),
                code,
                null,
                unit.getUnitName(),
                unit.getDecimalPlaces() == null ? 0 : unit.getDecimalPlaces()
        );
    }

    private static UnitDimension dimension(String category) {
        if (category == null) {
            return UnitDimension.UNKNOWN;
        }
        return switch (category.trim().toUpperCase(Locale.ROOT)) {
            case "MASS", "WEIGHT" -> UnitDimension.MASS;
            case "VOLUME" -> UnitDimension.VOLUME;
            case "COUNT" -> UnitDimension.COUNT;
            case "PACKAGE" -> UnitDimension.PACKAGE;
            default -> UnitDimension.UNKNOWN;
        };
    }

    private static String key(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            return null;
        }
        return rawUnit.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, CanonicalUnit> systemUnits() {
        Map<String, CanonicalUnit> units = new LinkedHashMap<>();
        add(units, "mg", UnitDimension.MASS, "g", "0.001", "毫克", 3);
        add(units, "g", UnitDimension.MASS, "g", "1", "克", 3);
        add(units, "kg", UnitDimension.MASS, "g", "1000", "公斤", 3);
        add(units, "t", UnitDimension.MASS, "g", "1000000", "吨", 6);
        add(units, "ml", UnitDimension.VOLUME, "ml", "1", "毫升", 3);
        add(units, "l", UnitDimension.VOLUME, "ml", "1000", "升", 3);
        add(units, "pcs", UnitDimension.COUNT, "pcs", null, "件", 0);
        add(units, "portion", UnitDimension.COUNT, "portion", null, "份", 0);
        add(units, "box", UnitDimension.PACKAGE, "box", null, "盒", 0);
        add(units, "case", UnitDimension.PACKAGE, "case", null, "箱", 0);
        add(units, "bag", UnitDimension.PACKAGE, "bag", null, "袋", 0);
        add(units, "bottle", UnitDimension.PACKAGE, "bottle", null, "瓶", 0);
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
                displayScale
        ));
    }

    private static Map<String, String> systemAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        alias(aliases, "mg", "mg", "毫克");
        alias(aliases, "g", "g", "克");
        alias(aliases, "kg", "kg", "公斤", "千克");
        alias(aliases, "t", "t", "吨");
        alias(aliases, "ml", "ml", "毫升");
        alias(aliases, "l", "l", "升");
        alias(aliases, "pcs", "pcs", "件", "个", "只");
        alias(aliases, "portion", "portion", "份");
        alias(aliases, "box", "box", "盒");
        alias(aliases, "case", "case", "箱");
        alias(aliases, "bag", "bag", "袋");
        alias(aliases, "bottle", "bottle", "瓶");
        return Map.copyOf(aliases);
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
