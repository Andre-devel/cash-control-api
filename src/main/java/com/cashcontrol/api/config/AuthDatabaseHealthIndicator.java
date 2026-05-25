package com.cashcontrol.api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component("authDatabase")
@RequiredArgsConstructor
public class AuthDatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(3)) {
                return Health.up().build();
            }
            return Health.down()
                    .withDetail("error", "Database connection validation timed out")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", "Database connectivity check failed")
                    .build();
        }
    }
}
