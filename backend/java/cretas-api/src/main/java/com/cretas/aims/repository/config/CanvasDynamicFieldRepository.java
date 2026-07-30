package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.CanvasDynamicField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CanvasDynamicFieldRepository extends JpaRepository<CanvasDynamicField, String> {

    List<CanvasDynamicField> findByFactoryIdAndModuleCode(String factoryId, String moduleCode);

    @Query("SELECT f FROM CanvasDynamicField f " +
           "WHERE (f.factoryId = :factoryId OR f.factoryId IS NULL) " +
           "AND f.moduleCode = :moduleCode " +
           "AND f.status != 'DISABLED' " +
           "ORDER BY f.sortOrder")
    List<CanvasDynamicField> findByModuleCodeForFactory(
            @Param("factoryId") String factoryId,
            @Param("moduleCode") String moduleCode);

    List<CanvasDynamicField> findByFactoryIdAndStatus(String factoryId, String status);

    List<CanvasDynamicField> findByFactoryIdAndStatusIn(String factoryId, List<String> statuses);

    @Query("SELECT f FROM CanvasDynamicField f " +
           "WHERE (f.factoryId = :factoryId OR f.factoryId IS NULL) " +
           "AND f.moduleCode = :moduleCode " +
           "AND f.status = 'ACTIVE' " +
           "ORDER BY f.sortOrder")
    List<CanvasDynamicField> findActiveByModuleCodeForFactory(
            @Param("factoryId") String factoryId,
            @Param("moduleCode") String moduleCode);

    Optional<CanvasDynamicField> findByFactoryIdAndModuleCodeAndFieldCode(
            String factoryId, String moduleCode, String fieldCode);
}
