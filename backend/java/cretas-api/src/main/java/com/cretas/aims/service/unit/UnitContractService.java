package com.cretas.aims.service.unit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UnitContractService {

    UnitNormalizationResult normalize(String factoryId, String rawUnit);

    Optional<CanonicalUnit> describe(String factoryId, String rawUnit);

    boolean areEquivalent(String factoryId, String leftUnit, String rightUnit);

    UnitConversionResult convert(UnitConversionContext context);

    UnitConversionResult convert(BigDecimal quantity, UnitConversionContext context);

    List<String> validateConversionGraph(
            String factoryId,
            String productTypeId,
            LocalDateTime at);
}
