package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.AuditEventTypeRepository;
import com.cashcontrol.api.repository.AuditOutcomeRepository;
import com.cashcontrol.api.repository.AuthenticationMethodRepository;
import com.cashcontrol.api.repository.LockoutTypeRepository;
import com.cashcontrol.api.repository.OauthProviderRepository;
import com.cashcontrol.api.repository.PermissionCategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class LookupEntityRepositoryTest {

    @Autowired
    private AccountStatusRepository accountStatusRepository;
    @Autowired
    private AuthOriginRepository authOriginRepository;
    @Autowired
    private OauthProviderRepository oauthProviderRepository;
    @Autowired
    private LockoutTypeRepository lockoutTypeRepository;
    @Autowired
    private PermissionCategoryRepository permissionCategoryRepository;
    @Autowired
    private AuthenticationMethodRepository authenticationMethodRepository;
    @Autowired
    private AuditEventTypeRepository auditEventTypeRepository;
    @Autowired
    private AuditOutcomeRepository auditOutcomeRepository;

    @Test
    void accountStatusFindBySlugReturnsSeededValues() {
        assertThat(accountStatusRepository.findBySlug("ACTIVE")).isPresent();
        assertThat(accountStatusRepository.findBySlug("INACTIVE")).isPresent();
        assertThat(accountStatusRepository.findBySlug("LOCKED")).isPresent();
        assertThat(accountStatusRepository.findBySlug("PENDING_VERIFICATION")).isPresent();
    }

    @Test
    void authOriginFindBySlugReturnsSeededValues() {
        assertThat(authOriginRepository.findBySlug("LOCAL")).isPresent();
        assertThat(authOriginRepository.findBySlug("GOOGLE")).isPresent();
        assertThat(authOriginRepository.findBySlug("MIXED")).isPresent();
    }

    @Test
    void oauthProviderFindBySlugReturnsGoogle() {
        assertThat(oauthProviderRepository.findBySlug("GOOGLE")).isPresent();
    }

    @Test
    void lockoutTypeFindBySlugReturnsSeededValues() {
        assertThat(lockoutTypeRepository.findBySlug("AUTOMATIC")).isPresent();
        assertThat(lockoutTypeRepository.findBySlug("MANUAL")).isPresent();
    }

    @Test
    void permissionCategoryFindBySlugReturnsSeededValues() {
        assertThat(permissionCategoryRepository.findBySlug("USER_MANAGEMENT")).isPresent();
        assertThat(permissionCategoryRepository.findBySlug("ROLE_MANAGEMENT")).isPresent();
        assertThat(permissionCategoryRepository.findBySlug("PERMISSION_MANAGEMENT")).isPresent();
        assertThat(permissionCategoryRepository.findBySlug("AUDIT")).isPresent();
        assertThat(permissionCategoryRepository.findBySlug("AUTH_MANAGEMENT")).isPresent();
    }

    @Test
    void authenticationMethodFindBySlugReturnsSeededValues() {
        assertThat(authenticationMethodRepository.findBySlug("PASSWORD")).isPresent();
        assertThat(authenticationMethodRepository.findBySlug("GOOGLE_OAUTH2")).isPresent();
        assertThat(authenticationMethodRepository.findBySlug("MFA_TOTP")).isPresent();
    }

    @Test
    void auditEventTypeFindBySlugReturnsSeededValues() {
        assertThat(auditEventTypeRepository.findBySlug("AUTH_SUCCESS")).isPresent();
        assertThat(auditEventTypeRepository.findBySlug("AUTH_FAILURE")).isPresent();
        assertThat(auditEventTypeRepository.findBySlug("CREDENTIALS_INVALIDATED")).isPresent();
    }

    @Test
    void auditEventTypeHasCategoryAndSeverity() {
        var credInvalidated = auditEventTypeRepository.findBySlug("CREDENTIALS_INVALIDATED");
        assertThat(credInvalidated).isPresent();
        assertThat(credInvalidated.get().getCategory()).isEqualTo("TOKEN");
        assertThat(credInvalidated.get().getSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    void auditOutcomeFindBySlugReturnsSeededValues() {
        assertThat(auditOutcomeRepository.findBySlug("SUCCESS")).isPresent();
        assertThat(auditOutcomeRepository.findBySlug("FAILURE")).isPresent();
    }

    @Test
    void allLookupEntitiesHaveNonNullIds() {
        accountStatusRepository.findAll().forEach(e -> assertThat(e.getId()).isNotNull());
        authOriginRepository.findAll().forEach(e -> assertThat(e.getId()).isNotNull());
        auditEventTypeRepository.findAll().forEach(e -> assertThat(e.getId()).isNotNull());
    }
}