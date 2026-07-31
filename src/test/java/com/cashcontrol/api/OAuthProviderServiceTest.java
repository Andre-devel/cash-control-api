package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.OauthAccount;
import com.cashcontrol.api.domain.entity.OauthProvider;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.OauthAccountRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.OAuthProviderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthProviderServiceTest {

    @InjectMocks
    private OAuthProviderServiceImpl oAuthProviderService;

    @Mock private UserRepository userRepository;
    @Mock private OauthAccountRepository oauthAccountRepository;
    @Mock private LookupCache lookupCache;
    @Mock private AuditService auditService;

    private UUID userId;
    private OauthProvider googleProvider;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        googleProvider = new OauthProvider();
        ReflectionTestUtils.setField(googleProvider, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(googleProvider, "slug", "GOOGLE");

        when(lookupCache.requireOauthProvider("GOOGLE")).thenReturn(googleProvider);
        when(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL))
                .thenReturn(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
    }

    @Test
    void googleOnlyUserWithNoPassword_unlinkThrowsConflictException() {
        User googleOnlyUser = buildUser(UserSlugConstants.ORIGIN_GOOGLE);
        googleOnlyUser.setPasswordHash(null);

        OauthAccount oauthAccount = buildOauthAccount(googleOnlyUser);

        when(oauthAccountRepository.findByUserIdAndProviderIdAndUnlinkedAtIsNull(userId, googleProvider.getId()))
                .thenReturn(Optional.of(oauthAccount));
        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(googleOnlyUser));

        assertThatThrownBy(() -> oAuthProviderService.unlinkProvider(userId, "GOOGLE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Não é possível desvincular o único método de login");
    }

    @Test
    void mixedUser_unlinkSucceeds_setsOriginToLocal() {
        User mixedUser = buildUser(UserSlugConstants.ORIGIN_MIXED);
        mixedUser.setPasswordHash("$argon2id$hash");

        OauthAccount oauthAccount = buildOauthAccount(mixedUser);

        when(oauthAccountRepository.findByUserIdAndProviderIdAndUnlinkedAtIsNull(userId, googleProvider.getId()))
                .thenReturn(Optional.of(oauthAccount));
        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(mixedUser));
        when(oauthAccountRepository.save(any())).thenReturn(oauthAccount);
        when(userRepository.save(any())).thenReturn(mixedUser);

        oAuthProviderService.unlinkProvider(userId, "GOOGLE");

        assertThat(oauthAccount.getUnlinkedAt()).isNotNull();
        assertThat(mixedUser.getAuthOrigin().getSlug()).isEqualTo(UserSlugConstants.ORIGIN_LOCAL);
        verify(userRepository).save(mixedUser);
    }

    @Test
    void unlinkProvider_recordsProviderUnlinkedAuditEvent() {
        User mixedUser = buildUser(UserSlugConstants.ORIGIN_MIXED);
        mixedUser.setPasswordHash("$argon2id$hash");

        OauthAccount oauthAccount = buildOauthAccount(mixedUser);

        when(oauthAccountRepository.findByUserIdAndProviderIdAndUnlinkedAtIsNull(userId, googleProvider.getId()))
                .thenReturn(Optional.of(oauthAccount));
        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(mixedUser));
        when(oauthAccountRepository.save(any())).thenReturn(oauthAccount);
        when(userRepository.save(any())).thenReturn(mixedUser);

        oAuthProviderService.unlinkProvider(userId, "GOOGLE");

        verify(auditService).record(
                eq(AuditEventSlug.PROVIDER_UNLINKED),
                eq(AuditOutcomeSlug.SUCCESS),
                eq(userId),
                eq(userId),
                eq(Map.of("provider", "GOOGLE")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User buildUser(String originSlug) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("user@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(originSlug));
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }

    private OauthAccount buildOauthAccount(User user) {
        OauthAccount account = new OauthAccount();
        account.setUser(user);
        account.setProvider(googleProvider);
        account.setProviderUserId("google-sub-123");
        account.setProviderEmail("user@example.com");
        account.setLinkedAt(Instant.now());
        return account;
    }
}
