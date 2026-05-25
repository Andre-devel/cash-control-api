package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuditEventType;
import com.cashcontrol.api.domain.entity.AuditOutcome;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.AuthenticationMethod;
import com.cashcontrol.api.domain.entity.BaseLookupEntity;
import com.cashcontrol.api.domain.entity.LockoutType;
import com.cashcontrol.api.domain.entity.OauthProvider;
import com.cashcontrol.api.domain.entity.PermissionCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LookupCache implements ApplicationRunner {

    private final AccountStatusRepository accountStatusRepository;
    private final AuthOriginRepository authOriginRepository;
    private final OauthProviderRepository oauthProviderRepository;
    private final LockoutTypeRepository lockoutTypeRepository;
    private final PermissionCategoryRepository permissionCategoryRepository;
    private final AuthenticationMethodRepository authenticationMethodRepository;
    private final AuditEventTypeRepository auditEventTypeRepository;
    private final AuditOutcomeRepository auditOutcomeRepository;

    private Map<String, AccountStatus> accountStatuses;
    private Map<String, AuthOrigin> authOrigins;
    private Map<String, OauthProvider> oauthProviders;
    private Map<String, LockoutType> lockoutTypes;
    private Map<String, PermissionCategory> permissionCategories;
    private Map<String, AuthenticationMethod> authenticationMethods;
    private Map<String, AuditEventType> auditEventTypes;
    private Map<String, AuditOutcome> auditOutcomes;

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        accountStatuses = toSlugMap(accountStatusRepository.findAll());
        authOrigins = toSlugMap(authOriginRepository.findAll());
        oauthProviders = toSlugMap(oauthProviderRepository.findAll());
        lockoutTypes = toSlugMap(lockoutTypeRepository.findAll());
        permissionCategories = toSlugMap(permissionCategoryRepository.findAll());
        authenticationMethods = toSlugMap(authenticationMethodRepository.findAll());
        auditEventTypes = toSlugMap(auditEventTypeRepository.findAll());
        auditOutcomes = toSlugMap(auditOutcomeRepository.findAll());
    }

    private <T extends BaseLookupEntity> Map<String, T> toSlugMap(List<T> entities) {
        return entities.stream().collect(Collectors.toUnmodifiableMap(BaseLookupEntity::getSlug, e -> e));
    }

    public AccountStatus requireAccountStatus(String slug) {
        return require(accountStatuses, slug, "AccountStatus");
    }

    public AuthOrigin requireAuthOrigin(String slug) {
        return require(authOrigins, slug, "AuthOrigin");
    }

    public OauthProvider requireOauthProvider(String slug) {
        return require(oauthProviders, slug, "OauthProvider");
    }

    public LockoutType requireLockoutType(String slug) {
        return require(lockoutTypes, slug, "LockoutType");
    }

    public PermissionCategory requirePermissionCategory(String slug) {
        return require(permissionCategories, slug, "PermissionCategory");
    }

    public AuthenticationMethod requireAuthenticationMethod(String slug) {
        return require(authenticationMethods, slug, "AuthenticationMethod");
    }

    public AuditEventType requireAuditEventType(String slug) {
        return require(auditEventTypes, slug, "AuditEventType");
    }

    public AuditOutcome requireAuditOutcome(String slug) {
        return require(auditOutcomes, slug, "AuditOutcome");
    }

    private <T> T require(Map<String, T> map, String slug, String typeName) {
        T value = map.get(slug);
        if (value == null) {
            throw new IllegalStateException(typeName + " not found in cache: " + slug);
        }
        return value;
    }
}