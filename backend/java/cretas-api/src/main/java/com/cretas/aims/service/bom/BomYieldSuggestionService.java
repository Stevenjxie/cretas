package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomYieldSuggestion;

import java.util.Optional;

public interface BomYieldSuggestionService {

    Optional<BomYieldSuggestion> generateForProduct(
            String factoryId, String productTypeId, String sourceEventType, String sourceEventId);
}
