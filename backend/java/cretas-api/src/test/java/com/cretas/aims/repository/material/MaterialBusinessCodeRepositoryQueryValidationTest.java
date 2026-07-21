package com.cretas.aims.repository.material;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.material.MaterialBusinessCodeCounter;
import com.cretas.aims.entity.material.MaterialBusinessCodePrefix;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.material.MaterialBusinessCodeService;
import com.cretas.aims.service.material.impl.MaterialBusinessCodeServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Hibernate/JPA startup, search, validation and allocation-concurrency gate. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(MaterialBusinessCodeServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MaterialBusinessCodeRepositoryQueryValidationTest {

    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RawMaterialTypeRepository materialRepository;
    @Autowired MaterialBusinessCodePrefixRepository prefixRepository;
    @Autowired MaterialBusinessCodeCounterRepository counterRepository;
    @Autowired MaterialBusinessCodeService businessCodeService;

    @Test
    void repositoriesBootAndSearchBothBusinessAndLegacyCodes() {
        String factoryId = "F-JPA-MBC-SEARCH";
        createFixture(factoryId, "RMSEA", true);

        assertThat(materialRepository.findByFactoryIdAndBusinessCodeIgnoreCase(factoryId, "rmsea000001"))
                .get()
                .extracting(RawMaterialType::getCode)
                .isEqualTo("0010050003000001");
        assertThat(materialRepository.searchMaterialTypes(
                factoryId, "rmsea", PageRequest.of(0, 20)).getContent())
                .extracting(RawMaterialType::getBusinessCode)
                .containsExactly("RMSEA000001");
        assertThat(materialRepository.searchMaterialTypes(
                factoryId, "0010050003", PageRequest.of(0, 20)).getContent())
                .extracting(RawMaterialType::getBusinessCode)
                .containsExactly("RMSEA000001");
        assertThat(materialRepository.filterBySegmentPrefixAndKeyword(
                factoryId, "001", "RMSEA", PageRequest.of(0, 20)).getContent())
                .extracting(RawMaterialType::getId)
                .containsExactly("R-MBC-" + factoryId);

        RawMaterialTypeDTO historical = RawMaterialTypeDTO.builder()
                .code("0010050003000001")
                .build();
        assertThat(historical.getDisplayCode()).isEqualTo("0010050003000001");
        assertThat(historical.isHistoricalCodeFallback()).isTrue();

        RawMaterialTypeDTO current = RawMaterialTypeDTO.builder()
                .code("0010050003000001")
                .businessCode("RMSEA000001")
                .build();
        assertThat(current.getDisplayCode()).isEqualTo("RMSEA000001");
        assertThat(current.getLegacyClassificationCode()).isEqualTo("0010050003000001");
        assertThat(current.isHistoricalCodeFallback()).isFalse();
    }

    @Test
    void previewUsesConfiguredAncestorWithoutWritingCounterOrPrefix() {
        String factoryId = "F-JPA-MBC-PREVIEW";
        createFixture(factoryId, "RMSEA", false);

        MaterialBusinessCodeService.BusinessCodePreview preview =
                businessCodeService.previewBusinessCode(factoryId, "0010050003");

        assertThat(preview.code()).isEqualTo("RMSEA000001");
        assertThat(preview.codePrefix()).isEqualTo("RMSEA");
        assertThat(preview.prefixSource()).isEqualTo("CONFIGURED");
        assertThat(preview.sourceSegmentCode()).isEqualTo("001005");
        assertThat(counterRepository.findByFactoryIdAndCodePrefix(factoryId, "RMSEA")).isEmpty();
        assertThat(prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                factoryId, "001005")).isPresent();
    }

    @Test
    void missingPrefixUsesStableL3IdentityAndPreviewRemainsReadOnly() {
        String factoryId = "F-JPA-MBC-STABLE";
        createFixture(factoryId, null, false);
        String expectedPrefix = stablePrefix("0010050003");

        MaterialBusinessCodeService.BusinessCodePreview preview =
                businessCodeService.previewBusinessCode(factoryId, "0010050003");

        assertThat(preview.code()).isEqualTo(expectedPrefix + "000001");
        assertThat(preview.prefixSource()).isEqualTo("SYSTEM_STABLE");
        assertThat(prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                factoryId, "0010050003")).isEmpty();
        assertThat(counterRepository.findByFactoryIdAndCodePrefix(factoryId, expectedPrefix)).isEmpty();

        assertThat(businessCodeService.allocateBusinessCode(factoryId, "0010050003"))
                .isEqualTo(preview.code());
        assertThat(prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                factoryId, "0010050003"))
                .get()
                .extracting(MaterialBusinessCodePrefix::getCodePrefix)
                .isEqualTo(expectedPrefix);
        assertThat(counterRepository.findByFactoryIdAndCodePrefix(factoryId, expectedPrefix))
                .get()
                .extracting(MaterialBusinessCodeCounter::getLastAllocated)
                .isEqualTo(1L);
    }

    @Test
    void concurrentFirstAllocationWithoutConfiguredPrefixIsUnique() throws Exception {
        String factoryId = "F-JPA-MBC-CONCURRENT";
        createFixture(factoryId, null, false);
        String expectedPrefix = stablePrefix("0010050003");

        int allocationCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < allocationCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return businessCodeService.allocateBusinessCode(factoryId, "0010050003");
                }));
            }
            start.countDown();

            Set<String> codes = new HashSet<>();
            for (Future<String> future : futures) {
                codes.add(future.get(15, TimeUnit.SECONDS));
            }

            assertThat(codes).hasSize(allocationCount)
                    .allMatch(code -> code.matches("^" + expectedPrefix + "[0-9]{6}$"))
                    .doesNotContain(expectedPrefix + "-000001");
            assertThat(codes).contains(expectedPrefix + "000001", expectedPrefix + "000012");
            assertThat(counterRepository.findByFactoryIdAndCodePrefix(factoryId, expectedPrefix))
                    .get()
                    .extracting(MaterialBusinessCodeCounter::getLastAllocated)
                    .isEqualTo(12L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stableFallbackIsFactoryScoped() {
        String firstFactory = "F-JPA-MBC-TENANT-A";
        String secondFactory = "F-JPA-MBC-TENANT-B";
        createFixture(firstFactory, null, false);
        createFixture(secondFactory, null, false);

        assertThat(businessCodeService.allocateBusinessCode(firstFactory, "0010050003"))
                .isEqualTo(stablePrefix("0010050003") + "000001");
        assertThat(businessCodeService.allocateBusinessCode(secondFactory, "0010050003"))
                .isEqualTo(stablePrefix("0010050003") + "000001");
        assertThat(prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                firstFactory, "0010050003")).isPresent();
        assertThat(prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                secondFactory, "0010050003")).isPresent();
    }

    @Test
    void inactiveExactPrefixIsNotSilentlyReactivatedOrDuplicated() {
        String factoryId = "F-JPA-MBC-INACTIVE";
        createFixture(factoryId, null, false);
        inTransaction(() -> prefixRepository.saveAndFlush(MaterialBusinessCodePrefix.builder()
                .factoryId(factoryId)
                .classificationSegmentCode("0010050003")
                .codePrefix(stablePrefix("0010050003"))
                .sequenceLength(6)
                .isActive(false)
                .build()));

        assertThatThrownBy(() -> businessCodeService.previewBusinessCode(
                factoryId, "0010050003"))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("稳定业务编码前缀与现有配置冲突");
        assertThatThrownBy(() -> businessCodeService.allocateBusinessCode(
                factoryId, "0010050003"))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("稳定业务编码前缀与现有配置冲突");

        assertThat(prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                factoryId, "0010050003"))
                .get()
                .extracting(MaterialBusinessCodePrefix::getIsActive)
                .isEqualTo(false);
        assertThat(counterRepository.findByFactoryIdAndCodePrefix(
                factoryId, stablePrefix("0010050003"))).isEmpty();
    }

    @Test
    void classificationLabelChangeDoesNotChangeIssuedPrefix() {
        String factoryId = "F-JPA-MBC-RENAME";
        createFixture(factoryId, "RMCHK", false);

        assertThat(businessCodeService.allocateBusinessCode(factoryId, "0010050003"))
                .isEqualTo("RMCHK000001");

        inTransaction(() -> {
            MaterialCodeSegment segment = entityManager.createQuery(
                            "SELECT s FROM MaterialCodeSegment s WHERE s.factoryId = :factoryId " +
                                    "AND s.segmentCode = '001005'", MaterialCodeSegment.class)
                    .setParameter("factoryId", factoryId)
                    .getSingleResult();
            segment.setSegmentLabel("重命名后的分类");
        });

        assertThat(businessCodeService.allocateBusinessCode(factoryId, "0010050003"))
                .isEqualTo("RMCHK000002");
    }

    @Test
    void invalidPrefixWithSeparatorIsRejectedBeforeAllocation() {
        String factoryId = "F-JPA-MBC-INVALID";
        createFixture(factoryId, null, false);

        assertThatThrownBy(() -> inTransaction(() -> prefixRepository.saveAndFlush(
                MaterialBusinessCodePrefix.builder()
                        .factoryId(factoryId)
                        .classificationSegmentCode("001005")
                        .codePrefix("RM-SEA")
                        .sequenceLength(6)
                        .build())))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private void createFixture(String factoryId, String codePrefix, boolean withMaterial) {
        inTransaction(() -> {
            Factory factory = new Factory();
            factory.setId(factoryId);
            factory.setName(factoryId);
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            entityManager.persist(factory);

            User user = new User();
            user.setFactoryId(factoryId);
            user.setUsername("mbc-" + factoryId);
            user.setPasswordHash("test-only");
            user.setIsActive(true);
            entityManager.persist(user);
            entityManager.flush();

            persistSegment(factoryId, (short) 1, "001", "原料", null);
            persistSegment(factoryId, (short) 2, "001005", "水产原料", "001");
            persistSegment(factoryId, (short) 3, "0010050003", "河鲀", "001005");

            if (codePrefix != null) {
                entityManager.persist(MaterialBusinessCodePrefix.builder()
                        .factoryId(factoryId)
                        .classificationSegmentCode("001005")
                        .codePrefix(codePrefix)
                        .sequenceLength(6)
                        .build());
            }

            if (withMaterial) {
                RawMaterialType material = new RawMaterialType();
                material.setId("R-MBC-" + factoryId);
                material.setFactoryId(factoryId);
                material.setCode("0010050003000001");
                material.setBusinessCode("RMSEA000001");
                material.setName("测试水产原料");
                material.setCategory("原料");
                material.setUnit("kg");
                material.setIsActive(true);
                material.setCreatedBy(user.getId());
                entityManager.persist(material);
            }
        });
    }

    private void persistSegment(String factoryId, short level, String code, String label, String parentCode) {
        entityManager.persist(MaterialCodeSegment.builder()
                .factoryId(factoryId)
                .level(level)
                .segmentCode(code)
                .segmentLabel(label)
                .parentCode(parentCode)
                .sortOrder((int) level)
                .isActive(true)
                .build());
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private String stablePrefix(String classificationSegmentCode) {
        return "M" + Long.toString(Long.parseLong(classificationSegmentCode), 36)
                .toUpperCase(java.util.Locale.ROOT);
    }
}
