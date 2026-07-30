package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactoryTemplateRepository extends JpaRepository<FactoryTemplate, Long> {
    Optional<FactoryTemplate> findByTemplateCode(String templateCode);

    /** Case-insensitive lookup — accepts food_processing or FOOD_PROCESSING */
    @Query("SELECT t FROM FactoryTemplate t WHERE UPPER(t.templateCode) = UPPER(:templateCode)")
    Optional<FactoryTemplate> findByTemplateCodeIgnoreCase(@Param("templateCode") String templateCode);

    List<FactoryTemplate> findByIsActiveTrue();
}
