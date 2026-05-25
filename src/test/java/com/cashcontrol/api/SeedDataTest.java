package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class SeedDataTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void accountStatusesContainsAllRequiredSlugs() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM account_statuses", String.class);
        assertThat(slugs).containsExactlyInAnyOrder("ACTIVE", "INACTIVE", "LOCKED", "PENDING_VERIFICATION");
    }

    @Test
    void authOriginsContainsAllRequiredSlugs() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM auth_origins", String.class);
        assertThat(slugs).containsExactlyInAnyOrder("LOCAL", "GOOGLE", "MIXED");
    }

    @Test
    void oauthProvidersContainsGoogleSlug() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM oauth_providers", String.class);
        assertThat(slugs).contains("GOOGLE");
    }

    @Test
    void lockoutTypesContainsAllRequiredSlugs() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM lockout_types", String.class);
        assertThat(slugs).containsExactlyInAnyOrder("AUTOMATIC", "MANUAL");
    }

    @Test
    void permissionCategoriesContainsAllRequiredSlugs() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM permission_categories", String.class);
        assertThat(slugs).containsExactlyInAnyOrder(
                "USER_MANAGEMENT", "ROLE_MANAGEMENT", "PERMISSION_MANAGEMENT", "AUDIT", "AUTH_MANAGEMENT");
    }

    @Test
    void authenticationMethodsContainsAllRequiredSlugs() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM authentication_methods", String.class);
        assertThat(slugs).containsExactlyInAnyOrder("PASSWORD", "GOOGLE_OAUTH2", "MFA_TOTP");
    }

    @Test
    void auditOutcomesContainsSuccessAndFailure() {
        List<String> slugs = jdbcTemplate.queryForList("SELECT slug FROM audit_outcomes", String.class);
        assertThat(slugs).containsExactlyInAnyOrder("SUCCESS", "FAILURE");
    }

    @Test
    void allSeedRowsHaveIsActiveTrue() {
        for (String table : List.of(
                "account_statuses", "auth_origins", "oauth_providers",
                "lockout_types", "permission_categories", "authentication_methods",
                "audit_event_types", "audit_outcomes")) {
            Integer inactive = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE is_active = FALSE", Integer.class);
            assertThat(inactive)
                    .as("All seed rows in '%s' should have is_active = true", table)
                    .isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "USER_REGISTERED,AUTHENTICATION,NORMAL",
        "USER_REGISTERED_GOOGLE,AUTHENTICATION,NORMAL",
        "ACCOUNT_LINKED_GOOGLE,AUTHENTICATION,NORMAL",
        "AUTH_SUCCESS,AUTHENTICATION,NORMAL",
        "AUTH_FAILURE,AUTHENTICATION,HIGH",
        "AUTH_LOGOUT,AUTHENTICATION,NORMAL",
        "EMAIL_VERIFIED,AUTHENTICATION,NORMAL",
        "ACCOUNT_LOCKED,ACCOUNT,HIGH",
        "ACCOUNT_UNLOCKED,ACCOUNT,NORMAL",
        "USER_CREATED,ACCOUNT,NORMAL",
        "USER_DISABLED,ACCOUNT,HIGH",
        "USER_ACTIVATED,ACCOUNT,NORMAL",
        "USER_DELETED,ACCOUNT,HIGH",
        "PASSWORD_CHANGED,ACCOUNT,HIGH",
        "PASSWORD_RESET_REQUESTED,ACCOUNT,NORMAL",
        "PASSWORD_RESET_COMPLETED,ACCOUNT,HIGH",
        "CONSENT_ACCEPTED,ACCOUNT,NORMAL",
        "PROVIDER_UNLINKED,ACCOUNT,NORMAL",
        "CREDENTIALS_INVALIDATED,TOKEN,CRITICAL",
        "ROLE_ASSIGNED,AUTHORIZATION,HIGH",
        "ROLE_REMOVED,AUTHORIZATION,HIGH",
        "ROLE_CREATED,AUTHORIZATION,NORMAL",
        "PERMISSION_GRANTED,AUTHORIZATION,HIGH",
        "PERMISSION_REVOKED,AUTHORIZATION,HIGH"
    })
    void auditEventTypeHasCorrectCategoryAndSeverity(String slug, String category, String severity) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event_types WHERE slug = ? AND category = ? AND severity = ?",
                Integer.class, slug, category, severity);
        assertThat(count)
                .as("audit_event_types slug='%s' must have category='%s' and severity='%s'", slug, category, severity)
                .isEqualTo(1);
    }
}