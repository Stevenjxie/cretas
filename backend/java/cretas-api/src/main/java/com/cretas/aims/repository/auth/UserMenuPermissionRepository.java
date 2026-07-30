package com.cretas.aims.repository.auth;

import com.cretas.aims.entity.auth.UserMenuPermission;
import com.cretas.aims.entity.auth.UserMenuPermission.GrantType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户菜单权限覆盖 Repository
 *
 * @since 2026-04-08 P0-6
 */
@Repository
public interface UserMenuPermissionRepository extends JpaRepository<UserMenuPermission, String> {

    List<UserMenuPermission> findByFactoryIdAndUserId(String factoryId, String userId);

    List<UserMenuPermission> findByFactoryIdAndUserIdAndGrantType(
            String factoryId, String userId, GrantType grantType);

    Optional<UserMenuPermission> findByFactoryIdAndUserIdAndMenuCode(
            String factoryId, String userId, String menuCode);

    void deleteByFactoryIdAndUserIdAndMenuCode(
            String factoryId, String userId, String menuCode);
}
