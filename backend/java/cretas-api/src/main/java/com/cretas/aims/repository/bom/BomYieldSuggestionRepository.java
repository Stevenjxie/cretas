package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomYieldSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BomYieldSuggestionRepository extends JpaRepository<BomYieldSuggestion, Long> {

    boolean existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
            String factoryId, String productTypeId, String sourceEventType, String sourceEventId);

    List<BomYieldSuggestion> findByFactoryIdAndStatusAndDeletedAtIsNullOrderByGeneratedAtDesc(
            String factoryId, BomYieldSuggestion.Status status);

    List<BomYieldSuggestion> findByFactoryIdAndDeletedAtIsNullOrderByGeneratedAtDesc(String factoryId);

    Optional<BomYieldSuggestion> findFirstByFactoryIdAndProductTypeIdAndStatusAndDeletedAtIsNullOrderByAppliedAtDescGeneratedAtDesc(
            String factoryId, String productTypeId, BomYieldSuggestion.Status status);
}
