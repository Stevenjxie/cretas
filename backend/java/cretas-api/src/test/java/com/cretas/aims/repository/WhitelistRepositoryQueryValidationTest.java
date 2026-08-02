package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.WhitelistStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup gate for whitelist invitation role and registration queries. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class WhitelistRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired WhitelistRepository whitelistRepository;

    @Test
    void invitedRolePersistsAndRegisteredAccountCountStaysTenantScoped() {
        String factoryId = "F-JPA-WHITELIST";
        persistFactory(factoryId);
        User inviter = persistUser(factoryId, "jpa-whitelist-inviter", null);

        Whitelist whitelist = Whitelist.builder()
                .factoryId(factoryId)
                .phoneNumber("13800138125")
                .name("质检测试用户")
                .invitedRoleCode(FactoryUserRole.quality_inspector)
                .status(WhitelistStatus.ACTIVE)
                .addedBy(inviter.getId())
                .build();
        entityManager.persist(whitelist);
        entityManager.flush();
        entityManager.clear();

        Whitelist reloaded = whitelistRepository
                .findByFactoryIdAndPhoneNumber(factoryId, "13800138125")
                .orElseThrow();
        assertThat(reloaded.getInvitedRoleCode()).isEqualTo(FactoryUserRole.quality_inspector);
        assertThat(whitelistRepository.countRegisteredAccounts(factoryId)).isZero();

        persistUser(factoryId, "13800138125", "13800138125");
        entityManager.flush();
        entityManager.clear();

        assertThat(whitelistRepository.countRegisteredAccounts(factoryId)).isEqualTo(1);
        assertThat(whitelistRepository.countRegisteredAccounts("F-OTHER")).isZero();
    }

    /**
     * 租户边界回归 (2026-08-02)。
     *
     * <p>{@code updateExpiredStatus} 原先是一条<b>没有 factoryId 谓词</b>的全租户 UPDATE,
     * 却挂在 {@code PUT /api/mobile/{factoryId}/whitelist/expired} 这个按工厂分段的路径下。
     * 此前只有 factory_super_admin / permission_admin 够得着所以没暴露; 同日把白名单放开给
     * hr_admin 之后受众扩大, 必须收口。
     *
     * <p>这个用例喂的正是能分辨对错的形状: <b>两个工厂各有一条已过期的 ACTIVE 白名单</b>。
     * 漏了 factoryId 的旧实现会把两条都刷成 EXPIRED (返回 2), 加了谓词的新实现只动自己那条
     * (返回 1) —— 断言同时钉住「自己的改了」和「别人的没被动」, 缺一不可。
     */
    @Test
    void updateExpiredStatusOnlyTouchesTheGivenFactory() {
        String mine = "F-JPA-WL-MINE";
        String theirs = "F-JPA-WL-THEIRS";
        persistFactory(mine);
        persistFactory(theirs);
        User inviter = persistUser(mine, "jpa-wl-expiry-inviter", null);

        LocalDateTime expiredAt = LocalDateTime.now().minusDays(1);
        persistExpiringWhitelist(mine, "13800138201", expiredAt, inviter.getId());
        persistExpiringWhitelist(theirs, "13800138202", expiredAt, inviter.getId());
        entityManager.flush();
        entityManager.clear();

        int updated = whitelistRepository.updateExpiredStatus(mine, LocalDateTime.now());
        entityManager.clear();

        assertThat(updated)
                .as("只应更新本工厂的 1 条; 若为 2 说明 factoryId 谓词丢了, 又变回全租户 UPDATE")
                .isEqualTo(1);
        assertThat(whitelistRepository.findByFactoryIdAndPhoneNumber(mine, "13800138201")
                .orElseThrow().getStatus()).isEqualTo(WhitelistStatus.EXPIRED);
        assertThat(whitelistRepository.findByFactoryIdAndPhoneNumber(theirs, "13800138202")
                .orElseThrow().getStatus())
                .as("别家工厂的白名单不可以被顺手改掉")
                .isEqualTo(WhitelistStatus.ACTIVE);
    }

    private void persistExpiringWhitelist(String factoryId, String phone,
                                          LocalDateTime expiresAt, Long addedBy) {
        entityManager.persist(Whitelist.builder()
                .factoryId(factoryId)
                .phoneNumber(phone)
                .name("过期边界测试")
                .status(WhitelistStatus.ACTIVE)
                .expiresAt(expiresAt)
                .addedBy(addedBy)
                .build());
    }

    private void persistFactory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        // factories.name 有唯一索引 —— 名字必须跟着 id 走, 否则本类里建第二个工厂就撞约束
        // (租户边界那个用例正好需要两个工厂)。
        factory.setName("Whitelist JPA gate " + id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private User persistUser(String factoryId, String username, String phone) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPhone(phone);
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }
}
