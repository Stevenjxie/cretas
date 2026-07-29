package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 工厂总监自审批例外。
 *
 * 背景: 职责分离(发起人 != 审批人)是默认铁律, 但小工厂常常只有一个 factory_super_admin。
 * 审批节点又只认这个角色时, 他发起的调拨永远批不掉 —— 实测某工厂因此一个多月没走通过
 * 一次调拨审批, 调拨单全部卡在草稿。例外只对 factory_super_admin 开, 其它角色不变。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferSuperAdminSelfApprovalTest {

    private static final String FACTORY_ID = "LIUSHANMEN";
    private static final String SUPER_ADMIN = "factory_super_admin";
    private static final String WAREHOUSE_MANAGER = "warehouse_manager";
    private static final Long ADMIN_ID = 1638L;
    private static final Long OTHER_ID = 2001L;

    @Mock private UserRepository userRepository;

    private TransferServiceImpl service;

    private static User user(Long id, boolean active) {
        User user = new User();
        user.setId(id);
        user.setIsActive(active);
        return user;
    }

    @BeforeEach
    void setUp() {
        // 被测的两个判据只依赖 userRepository(字段注入); 构造器依赖与审批人解析无关
        service = new TransferServiceImpl(null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
    }

    private boolean allowsSelfApproval(List<String> roles, Long initiatorId) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "allowsSuperAdminSelfApproval", FACTORY_ID, roles, initiatorId));
    }

    private boolean isSuperAdmin(Long userId, String actorRole) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "isFactorySuperAdmin", FACTORY_ID, userId, actorRole));
    }

    @Test
    void soleSuperAdminMayApproveOwnTransfer() {
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY_ID, SUPER_ADMIN))
                .thenReturn(List.of(user(ADMIN_ID, true)));

        assertTrue(allowsSelfApproval(List.of(SUPER_ADMIN), ADMIN_ID),
                "全厂唯一的工厂总监发起的调拨必须能自己批, 否则永久卡在草稿");
    }

    @Test
    void superAdminCannotBypassNodeMeantForAnotherRole() {
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY_ID, WAREHOUSE_MANAGER))
                .thenReturn(List.of());

        // 节点指定仓库主管审批时, 超管自审批就不是"解死锁"而是"绕过审批"
        assertFalse(allowsSelfApproval(List.of(WAREHOUSE_MANAGER), ADMIN_ID),
                "approverRoles 不含 factory_super_admin 时不得放行自审批");
    }

    @Test
    void nonSuperAdminInitiatorStillNeedsSecondPairOfEyes() {
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY_ID, SUPER_ADMIN))
                .thenReturn(List.of(user(ADMIN_ID, true)));

        assertFalse(allowsSelfApproval(List.of(SUPER_ADMIN), OTHER_ID),
                "发起人不是工厂总监时, 职责分离照旧生效");
    }

    @Test
    void deactivatedSuperAdminDoesNotUnlockSelfApproval() {
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY_ID, SUPER_ADMIN))
                .thenReturn(List.of(user(ADMIN_ID, false)));

        assertFalse(allowsSelfApproval(List.of(SUPER_ADMIN), ADMIN_ID),
                "停用账号不该解锁任何审批路径");
    }

    @Test
    void approvalSideFallsBackToStoredRoleWhenActorRoleMissing() {
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY_ID, SUPER_ADMIN))
                .thenReturn(List.of(user(ADMIN_ID, true)));

        // actorRole 由调用方传入, 可能为空 —— 不能让缺失的入参把例外吞掉
        assertTrue(isSuperAdmin(ADMIN_ID, null));
        assertTrue(isSuperAdmin(ADMIN_ID, SUPER_ADMIN));
        assertFalse(isSuperAdmin(OTHER_ID, null));
    }

    @Test
    void anonymousActorIsNeverTreatedAsSuperAdmin() {
        assertFalse(isSuperAdmin(null, SUPER_ADMIN));
        assertFalse(allowsSelfApproval(List.of(SUPER_ADMIN), null));
    }
}
