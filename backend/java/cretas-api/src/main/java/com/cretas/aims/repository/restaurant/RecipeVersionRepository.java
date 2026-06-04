package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.RecipeVersion;
import com.cretas.aims.entity.restaurant.RecipeVersion.VersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RecipeVersion Repository (#60 Phase 2 配方版本化).
 *
 * <p>Companion to {@link RecipeRepository}. RecipeVersion is independent row-per-approval
 * keyed by {@code (factory_id, product_type_id, version_number)}; the flat {@link
 * com.cretas.aims.entity.restaurant.Recipe} rows have no versioning today.
 *
 * <p>Enum filters bind as {@code @Param} (avoids JPQL nested-enum FQN ambiguity), mirroring
 * {@code bom.BomVersionRepository}.
 *
 * @author Cretas Team / #60 Phase 2
 * @since 2026-06-04
 */
@Repository
public interface RecipeVersionRepository extends JpaRepository<RecipeVersion, String> {

    /** All versions for a dish, newest first. */
    List<RecipeVersion> findByFactoryIdAndProductTypeIdOrderByVersionNumberDesc(
            String factoryId, String productTypeId);

    /** Current effective version: status=APPROVED with effective_to IS NULL. */
    @Query("SELECT rv FROM RecipeVersion rv " +
           "WHERE rv.factoryId = :factoryId " +
           "AND rv.productTypeId = :productTypeId " +
           "AND rv.status = :status " +
           "AND rv.effectiveTo IS NULL")
    Optional<RecipeVersion> findCurrentInStatus(@Param("factoryId") String factoryId,
                                                @Param("productTypeId") String productTypeId,
                                                @Param("status") VersionStatus status);

    /** Max version number for a dish (createDraft assigns max+1). */
    @Query("SELECT COALESCE(MAX(rv.versionNumber), 0) FROM RecipeVersion rv " +
           "WHERE rv.factoryId = :factoryId AND rv.productTypeId = :productTypeId")
    Integer findMaxVersionNumber(@Param("factoryId") String factoryId,
                                 @Param("productTypeId") String productTypeId);

    /** All versions in a given status (for batch monitoring / approval dashboards). */
    List<RecipeVersion> findByFactoryIdAndStatus(String factoryId, VersionStatus status);
}
