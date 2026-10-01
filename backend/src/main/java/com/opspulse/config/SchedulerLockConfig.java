package com.opspulse.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Without this, every backend replica runs the escalation sweeper, and the
 * same un-acknowledged incident pages the same engineer N times. That is the
 * single bug real on-call responders hate most, so it is worth the one bean.
 */
@Configuration
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()   // clock skew between replicas stops mattering
                        .build());
    }
}
