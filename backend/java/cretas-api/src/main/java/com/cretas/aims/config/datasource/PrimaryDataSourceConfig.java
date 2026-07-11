package com.cretas.aims.config.datasource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Primary DataSource Configuration (MySQL)
 *
 * Handles all existing business data:
 * - User authentication
 * - Factory management
 * - Traceability data
 * - Original SmartBI data (fixed schema)
 *
 * Excludes: repository.smartbi.postgres package (handled by SmartBIPostgresDataSourceConfig)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-25
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = {
        "com.cretas.aims.repository",
        // 一加物流 logistics MVP bounded context (repositories live outside com.cretas.aims.repository)
        "com.cretas.aims.logistics.repository"
    },
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.cretas\\.aims\\.repository\\.smartbi\\.postgres\\..*"
    ),
    entityManagerFactoryRef = "primaryEntityManagerFactory",
    transactionManagerRef = "primaryTransactionManager"
)
public class PrimaryDataSourceConfig {

    @Value("${spring.jpa.properties.hibernate.dialect:org.hibernate.dialect.PostgreSQLDialect}")
    private String hibernateDialect;

    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    @Value("${spring.jpa.show-sql:false}")
    private String showSql;

    /**
     * Primary DataSource Properties
     */
    @Primary
    @Bean(name = "primaryDataSourceProperties")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Primary DataSource
     * Note: auto-commit must be disabled for proper transaction management with PostgreSQL
     */
    @Primary
    @Bean(name = "primaryDataSource")
    public DataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // Disable auto-commit for proper transaction management with PostgreSQL
        ds.setAutoCommit(false);
        return ds;
    }

    /**
     * Primary EntityManagerFactory (MySQL)
     */
    @Primary
    @Bean(name = "primaryEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean primaryEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("primaryDataSource") DataSource dataSource) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", hibernateDialect);
        properties.put("hibernate.hbm2ddl.auto", ddlAuto);
        properties.put("hibernate.show_sql", showSql);
        properties.put("hibernate.format_sql", "false");

        // IMPORTANT: List all entity packages EXCEPT smartbi.postgres
        // The smartbi.postgres entities are handled by SmartBIPostgresDataSourceConfig
        //
        // Solution: Use Class references for SmartBI MySQL entities.
        // When packages() receives Class<?> arguments, it only scans the package
        // containing that class, NOT its subpackages.
        //
        // All other packages have no PostgreSQL subpackages, so we can use string names.
        return builder
            .dataSource(dataSource)
            .packages(
                // Root entity package (entities not in subfolders)
                "com.cretas.aims.entity",
                // Core entity packages (no subpackage conflicts)
                "com.cretas.aims.entity.aps",
                "com.cretas.aims.entity.bom",
                "com.cretas.aims.entity.cache",
                "com.cretas.aims.entity.calibration",
                "com.cretas.aims.entity.common",
                "com.cretas.aims.entity.config",
                "com.cretas.aims.entity.conversation",
                "com.cretas.aims.entity.dahua",
                "com.cretas.aims.entity.decoration",
                "com.cretas.aims.entity.enums",
                "com.cretas.aims.entity.intent",
                "com.cretas.aims.entity.iot",
                "com.cretas.aims.entity.isapi",
                "com.cretas.aims.entity.learning",
                "com.cretas.aims.entity.ml",
                "com.cretas.aims.entity.rules",
                "com.cretas.aims.entity.scale",
                "com.cretas.aims.entity.tool",
                "com.cretas.aims.entity.voice",
                // SmartBI entities - include the whole package including postgres subpackage
                // Since DDL-auto=none, Hibernate won't try to create missing tables.
                // The postgres entities will be scanned but not used (their repository is excluded)
                "com.cretas.aims.entity.smartbi",
                // 一加物流 logistics MVP bounded context (entities live outside com.cretas.aims.entity)
                "com.cretas.aims.logistics.entity"
            )
            .persistenceUnit("primary")
            .properties(properties)
            .build();
    }

    /**
     * Primary TransactionManager
     */
    @Primary
    @Bean(name = "primaryTransactionManager")
    public PlatformTransactionManager primaryTransactionManager(
            @Qualifier("primaryEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
