package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialBusinessCodeCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MaterialBusinessCodeCounterRepository
        extends JpaRepository<MaterialBusinessCodeCounter, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MaterialBusinessCodeCounter c " +
            "WHERE c.factoryId = :factoryId AND c.codePrefix = :codePrefix")
    Optional<MaterialBusinessCodeCounter> lockByFactoryIdAndCodePrefix(
            @Param("factoryId") String factoryId,
            @Param("codePrefix") String codePrefix);

    Optional<MaterialBusinessCodeCounter> findByFactoryIdAndCodePrefix(
            String factoryId, String codePrefix);
}
