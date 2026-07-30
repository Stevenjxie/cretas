package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryDefaultValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface FactoryDefaultValueRepository extends JpaRepository<FactoryDefaultValue, Long> {
    List<FactoryDefaultValue> findByFactoryIdAndModuleCodeAndFieldCode(String factoryId, String moduleCode, String fieldCode);
    @Query("SELECT d FROM FactoryDefaultValue d WHERE d.factoryId IS NULL AND d.moduleCode = :moduleCode AND d.fieldCode = :fieldCode")
    List<FactoryDefaultValue> findGlobalDefaults(@Param("moduleCode") String moduleCode, @Param("fieldCode") String fieldCode);
    List<FactoryDefaultValue> findByFactoryIdAndModuleCode(String factoryId, String moduleCode);

    @Query("SELECT d FROM FactoryDefaultValue d WHERE d.factoryId = :factoryId OR d.factoryId IS NULL ORDER BY d.moduleCode, d.fieldCode")
    List<FactoryDefaultValue> findAllForFactory(@Param("factoryId") String factoryId);

    @Query("SELECT d FROM FactoryDefaultValue d WHERE (d.factoryId = :factoryId OR d.factoryId IS NULL) AND d.moduleCode = :moduleCode ORDER BY d.fieldCode")
    List<FactoryDefaultValue> findByModuleCodeForFactory(@Param("factoryId") String factoryId, @Param("moduleCode") String moduleCode);
}
