package com.exquy.webhook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.Properties;

/**
 * Configuration class for database connectivity and JPA settings.
 * Configures the data source, entity manager, transaction manager,
 * and additional database-related features like auditing.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.company.transactionrecovery.domain.repository")
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class DatabaseConfig {

    @Value("${spring.datasource.url}")
    private String databaseUrl;

    @Value("${spring.datasource.username}")
    private String databaseUsername;

    @Value("${spring.datasource.password}")
    private String databasePassword;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    @Value("${spring.jpa.hibernate.ddl-auto:none}")
    private String hibernateDdlAuto;

    @Value("${spring.jpa.properties.hibernate.dialect}")
    private String hibernateDialect;

    @Value("${spring.jpa.show-sql:false}")
    private boolean showSql;

    /**
     * Configures the data source for connecting to the database.
     */
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(databaseUrl);
        dataSource.setUsername(databaseUsername);
        dataSource.setPassword(databasePassword);
        return dataSource;
    }

    /**
     * Configures the entity manager factory, which is used to create EntityManager instances.
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource());
        em.setPackagesToScan("com.company.transactionrecovery.domain.model");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", hibernateDdlAuto);
        properties.setProperty("hibernate.dialect", hibernateDialect);
        properties.setProperty("hibernate.show_sql", String.valueOf(showSql));
        properties.setProperty("hibernate.format_sql", String.valueOf(showSql));
        
        // Enable batch processing for better performance
        properties.setProperty("hibernate.jdbc.batch_size", "50");
        properties.setProperty("hibernate.order_inserts", "true");
        properties.setProperty("hibernate.order_updates", "true");
        
        // Connection pool settings
        properties.setProperty("hibernate.connection.provider_class", 
                "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
        properties.setProperty("hibernate.hikari.minimumIdle", "5");
        properties.setProperty("hibernate.hikari.maximumPoolSize", "20");
        properties.setProperty("hibernate.hikari.idleTimeout", "30000");
        
        // Settings for handling large result sets
        properties.setProperty("hibernate.jdbc.fetch_size", "100");
        
        // Enable statistics for monitoring
        properties.setProperty("hibernate.generate_statistics", "true");

        em.setJpaProperties(properties);
        return em;
    }

    /**
     * Configures the transaction manager for managing database transactions.
     */
    @Bean
    public PlatformTransactionManager transactionManager() {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory().getObject());
        return transactionManager;
    }

    /**
     * Provides auditing information for entity creation and modification.
     * This records which user or system created or modified each record.
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        // In a real application, this would typically get the username from security context
        // For this system, we'll use a simple implementation that returns a fixed value 
        // or the value from a request-scoped bean that captures the authenticated user
        return () -> Optional.of("SYSTEM");
    }

    /**
     * Bean for monitoring database performance metrics.
     */
    @Bean
    public DatabaseMetricsCollector databaseMetricsCollector() {
        return new DatabaseMetricsCollector(entityManagerFactory().getObject());
    }

    /**
     * Utility class for collecting database performance metrics.
     * This would be implemented in a real application to monitor query performance,
     * connection pool usage, etc.
     */
    public static class DatabaseMetricsCollector {
        private final Object entityManagerFactory;

        public DatabaseMetricsCollector(Object entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        // Methods to collect various metrics would be implemented here
        public long getConnectionPoolActiveConnections() {
            // Implementation would extract connection pool statistics
            return 0;
        }
    }
}
