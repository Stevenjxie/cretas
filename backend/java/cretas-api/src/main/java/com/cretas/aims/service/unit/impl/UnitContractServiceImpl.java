package com.cretas.aims.service.unit.impl;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class UnitContractServiceImpl implements UnitContractService {

    private static final Map<String, CanonicalUnit> SYSTEM_UNITS = systemUnits();
    private static final Map<String, String> SYSTEM_ALIASES = systemAliases();

    private final UnitOfMeasurementRepository unitRepository;

    public UnitContractServiceImpl(UnitOfMeasurementRepository unitRepository) {
        this.unitRepository = unitRepository;
    }

    @Override
    public UnitNormalizationResult normalize(String factoryId, String rawUnit) {
        String key = key(rawUnit);
        if (key == null) {
            return new UnitNormalizationResult(rawUnit, null, null);
        }

        Map<String, CanonicalUnit> catalog = factoryCatalog(factoryId);
        CanonicalUnit catalogUnit = catalog.get(key);
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
            return result(UnitConversionStatus.UNKNOWN_UNIT, quantity, null, null, List.of(), "单位换算上下文不能为空");
        }

        UnitNormalizationResult from = normalize(context.factoryId(), context.fromUnit());
        UnitNormalizationResult to = normalize(context.factoryId(), context.toUnit());
        if (!from.recognized() || !to.recognized()) {
            return result(UnitConversionStatus.UNKNOWN_UNIT, quantity, from.code(), to.code(), List.of(), "存在未知单位");
        }
        if (from.code().equals(to.code())) {
            return result(UnitConversionStatus.IDENTITY, quantity, from.code(), to.code(), List.of(from.code()), null);
        }
        if (!isIntrinsicConvertible(from.unit(), to.unit())) {
            return result(
                    UnitConversionStatus.PRODUCT_CONVERSION_MISSING,
                    quantity,
                    from.code(),
                    to.code(),
                    List.of(from.code(), to.code()),
                    "仅支持系统固有质量和体积换算；产品或包装换算尚未配置"
            );
        }

        BigDecimal converted = quantity == null ? null
                : quantity.multiply(from.unit().factorToBase()).divide(to.unit().factorToBase());
        return result(UnitConversionStatus.CONVERTED, converted, from.code(), to.code(), List.of(from.code(), to.code()), null);
    }

    private UnitConversionResult result(
            UnitConversionStatus status,
            BigDecimal quantity,
            String fromUnit,
            String toUnit,
            List<String> path,
            String message) {
        return new UnitConversionResult(status, quantity, fromUnit, toUnit, path, null, null, message);
    }

    private boolean isIntrinsicConvertible(CanonicalUnit from, CanonicalUnit to) {
        return from.dimension() == to.dimension()
                && (from.dimension() == UnitDimension.MASS || from.dimension() == UnitDimension.VOLUME)
                && from.factorToBase() != null
                && to.factorToBase() != null;
    }

    private Map<String, CanonicalUnit> factoryCatalog(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return Map.of();
        }

        Map<String, CanonicalUnit> catalog = new LinkedHashMap<>();
        for (UnitOfMeasurement unit : unitRepository.findAllByFactoryId(factoryId)) {
            CanonicalUnit canonical = canonicalize(unit);
            if (canonical == null) {
                continue;
            }
            catalog.putIfAbsent(key(unit.getUnitCode()), canonical);
            catalog.putIfAbsent(key(unit.getUnitName()), canonical);
            catalog.putIfAbsent(key(unit.getUnitSymbol()), canonical);
            if (unit.getAliasesJson() != null) {
                unit.getAliasesJson().forEach(alias -> catalog.putIfAbsent(key(alias), canonical));
            }
        }
        return catalog;
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
}
