package com.cretas.aims.repository.permission;

import com.cretas.aims.entity.permission.PlatformRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformRolePermissionRepository extends JpaRepository<PlatformRolePermission, Long> {

    List<PlatformRolePermission> findByRoleCodeAndDeletedAtIsNull(String roleCode);

    Optional<PlatformRolePermission> findByRoleCodeAndModuleCodeAndDeletedAtIsNull(
        String roleCode, String moduleCode);

    List<PlatformRolePermission> findByDeletedAtIsNull();
}
