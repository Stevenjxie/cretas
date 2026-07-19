package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Injection-only configuration repository. */
@Repository
public interface BomProcessInjectionConfigRepository extends JpaRepository<BomProcessInjectionConfig, Long> {

    List<BomProcessInjectionConfig> findByRecipeIdAndDeletedAtIsNull(String recipeId);

    Optional<BomProcessInjectionConfig> findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(
            String recipeId, String workProcessId);

    boolean existsByWorkProcessId(String workProcessId);
}
