package com.cretas.aims.repository.unit;

import com.cretas.aims.entity.unit.ProductUnitConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductUnitConversionRepository extends JpaRepository<ProductUnitConversion, String> {

    List<ProductUnitConversion> findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc(
            String factoryId, String productTypeId);

    List<ProductUnitConversion> findByFactoryIdOrderByProductTypeIdAscCreatedAtAsc(String factoryId);

    Optional<ProductUnitConversion> findByIdAndFactoryIdAndProductTypeId(
            String id, String factoryId, String productTypeId);

    @Query("""
            SELECT p FROM ProductUnitConversion p
            WHERE p.factoryId = :factoryId
              AND p.productTypeId = :productTypeId
              AND p.effectiveFrom <= :at
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
              AND p.deletedAt IS NULL
            """)
    List<ProductUnitConversion> findEffectiveByFactoryIdAndProductTypeIdAt(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("at") LocalDateTime at);
}
