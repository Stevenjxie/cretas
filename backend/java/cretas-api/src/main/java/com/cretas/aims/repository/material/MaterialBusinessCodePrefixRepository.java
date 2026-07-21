package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialBusinessCodePrefix;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialBusinessCodePrefixRepository
        extends JpaRepository<MaterialBusinessCodePrefix, Long> {

    Optional<MaterialBusinessCodePrefix> findByFactoryIdAndClassificationSegmentCode(
            String factoryId, String classificationSegmentCode);

    boolean existsByFactoryIdAndCodePrefixIgnoreCase(String factoryId, String codePrefix);

    /** Read-only equivalent of {@link #lockMatchingPrefixes(String, String)} for previews. */
    @Query("SELECT p FROM MaterialBusinessCodePrefix p " +
            "WHERE p.factoryId = :factoryId AND p.isActive = true " +
            "AND :classificationSegmentCode LIKE CONCAT(p.classificationSegmentCode, '%') " +
            "ORDER BY LENGTH(p.classificationSegmentCode) DESC")
    List<MaterialBusinessCodePrefix> findMatchingPrefixes(
            @Param("factoryId") String factoryId,
            @Param("classificationSegmentCode") String classificationSegmentCode);

    /**
     * Locks every configured ancestor and returns the most specific match first. The lock also
     * serializes creation of the first counter row, closing the usual absent-row race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MaterialBusinessCodePrefix p " +
            "WHERE p.factoryId = :factoryId AND p.isActive = true " +
            "AND :classificationSegmentCode LIKE CONCAT(p.classificationSegmentCode, '%') " +
            "ORDER BY LENGTH(p.classificationSegmentCode) DESC")
    List<MaterialBusinessCodePrefix> lockMatchingPrefixes(
            @Param("factoryId") String factoryId,
            @Param("classificationSegmentCode") String classificationSegmentCode);
}
