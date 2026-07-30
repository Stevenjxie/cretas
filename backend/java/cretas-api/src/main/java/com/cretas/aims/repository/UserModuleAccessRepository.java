package com.cretas.aims.repository;

import com.cretas.aims.entity.UserModuleAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserModuleAccessRepository extends JpaRepository<UserModuleAccess, String> {

    @Query(value = """
            SELECT *
            FROM user_module_access
            WHERE factory_id = :factoryId
              AND user_id = :userId
              AND module_code = :moduleCode
              AND deleted_at IS NULL
            """, nativeQuery = true)
    Optional<UserModuleAccess> findActive(
            @Param("factoryId") String factoryId,
            @Param("userId") String userId,
            @Param("moduleCode") String moduleCode);

    @Query(value = """
            SELECT *
            FROM user_module_access
            WHERE factory_id = :factoryId
              AND user_id = :userId
              AND deleted_at IS NULL
            ORDER BY module_code
            """, nativeQuery = true)
    List<UserModuleAccess> findActiveByFactoryAndUser(
            @Param("factoryId") String factoryId,
            @Param("userId") String userId);

    @Query(value = """
            SELECT *
            FROM user_module_access
            WHERE factory_id = :factoryId
              AND user_id = :userId
              AND module_code = :moduleCode
            """, nativeQuery = true)
    Optional<UserModuleAccess> findAny(
            @Param("factoryId") String factoryId,
            @Param("userId") String userId,
            @Param("moduleCode") String moduleCode);
}
