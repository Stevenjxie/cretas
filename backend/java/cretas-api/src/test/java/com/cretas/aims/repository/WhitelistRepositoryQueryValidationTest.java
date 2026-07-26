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

    private void persistFactory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("Whitelist JPA gate");
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
