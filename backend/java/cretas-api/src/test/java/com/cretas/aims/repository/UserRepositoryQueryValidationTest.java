package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate/JPA startup and tenant-isolation gate for user object lookups. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class UserRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired UserRepository repository;

    @Test
    void findByIdAndFactoryIdReturnsOnlyTheOwningTenantUser() {
        persistFactory("F-JPA-USER-A", "JPA user query gate A");
        persistFactory("F-JPA-USER-B", "JPA user query gate B");
        User tenantAUser = persistUser("F-JPA-USER-A", "jpa-user-query-a");
        User tenantBUser = persistUser("F-JPA-USER-B", "jpa-user-query-b");
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdAndFactoryId(tenantAUser.getId(), "F-JPA-USER-A"))
                .get()
                .extracting(User::getUsername)
                .isEqualTo("jpa-user-query-a");
        assertThat(repository.findByIdAndFactoryId(tenantAUser.getId(), "F-JPA-USER-B"))
                .isEmpty();
        assertThat(repository.findByIdAndFactoryId(tenantBUser.getId(), "F-JPA-USER-A"))
                .isEmpty();
        assertThat(repository.findByIdAndFactoryId(Long.MAX_VALUE, "F-JPA-USER-A"))
                .isEmpty();
    }

    private void persistFactory(String id, String name) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(name);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private User persistUser(String factoryId, String username) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        return user;
    }
}
