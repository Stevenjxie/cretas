package com.cretas.aims.repository.canvas;

import com.cretas.aims.entity.canvas.EnumDictionary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link EnumDictionary}.
 *
 * @since Canvas Phase C (2026-05-22)
 */
@Repository
public interface EnumDictionaryRepository extends JpaRepository<EnumDictionary, UUID> {

    /**
     * Per-factory + category lookup (UI dropdown loading), enabled rows first.
     * Backed by {@code idx_enum_dictionary_factory_category}.
     */
    List<EnumDictionary> findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
            String factoryId, String category);

    /**
     * Per-factory + category + code lookup (resolver hot path).
     * Backed by partial unique index.
     */
    Optional<EnumDictionary> findByFactoryIdAndCategoryAndCode(
            String factoryId, String category, String code);

    /**
     * Per-factory list (all categories, used for full export / editor list view).
     */
    List<EnumDictionary> findByFactoryIdOrderByCategoryAscDisplayOrderAscCodeAsc(
            String factoryId);

    /**
     * Per-factory category enum (for tabs / filter dropdown of distinct categories).
     */
    List<EnumDictionary> findByFactoryId(String factoryId);
}
