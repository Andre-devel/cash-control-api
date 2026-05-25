package com.cashcontrol.api.config;

import com.cashcontrol.api.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest extends BaseIntegrationTest {

    @Autowired
    private AppProperties appProperties;

    @Test
    void jwtPropertiesAreBound() {
        assertThat(appProperties.getJwt().getSecret()).isNotBlank();
        assertThat(appProperties.getJwt().getExpirationMinutes()).isPositive();
    }

    @Test
    void securityPropertiesAreBound() {
        assertThat(appProperties.getSecurity().getMaxFailedAttempts()).isPositive();
        assertThat(appProperties.getSecurity().getLockoutDurationMinutes()).isPositive();
        assertThat(appProperties.getSecurity().getPasswordResetExpiryMinutes()).isPositive();
        assertThat(appProperties.getSecurity().getEmailVerificationExpiryHours()).isPositive();
    }

    @Test
    void attachmentPropertiesAreBound() {
        assertThat(appProperties.getAttachments().getMaxFileSizeMb()).isPositive();
        assertThat(appProperties.getAttachments().getMaxPerTransaction()).isPositive();
        assertThat(appProperties.getAttachments().getAllowedTypes()).isNotBlank();
    }

    @Test
    void dashboardPropertiesAreBound() {
        assertThat(appProperties.getDashboard().getUpcomingBillsDays()).isPositive();
        assertThat(appProperties.getDashboard().getUpcomingBillsMaxResults()).isPositive();
    }
}