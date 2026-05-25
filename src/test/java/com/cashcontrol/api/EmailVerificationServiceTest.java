package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.EmailVerificationToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.EmailService;
import com.cashcontrol.api.service.EmailVerificationServiceImpl;
import com.cashcontrol.api.util.TokenHasher;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

    @InjectMocks private EmailVerificationServiceImpl service;

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private LookupCache lookupCache;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private AppProperties appProperties;
    @Mock private DataMasker dataMasker;

    @BeforeEach
    void setUp() {
        AppProperties.Security sec = new AppProperties.Security();
        when(appProperties.getSecurity()).thenReturn(sec);
        when(dataMasker.maskEmail(anyString())).thenReturn("m***@x.com");

        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
    }

    @Test
    void verifyEmail_validToken_activatesAccountAndConsumesToken() {
        String rawToken = UUID.randomUUID().toString();
        String hash = TokenHasher.sha256(rawToken);

        User user = buildUser(UserSlugConstants.STATUS_PENDING_VERIFICATION);
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash))
                .thenReturn(Optional.of(token));

        service.verifyEmail(rawToken);

        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(token.getConsumedAt()).isNotNull();
        verify(userRepository).save(user);
        verify(auditService).record(eq(AuditEventSlug.EMAIL_VERIFIED), eq(AuditOutcomeSlug.SUCCESS), any(), any(), any());
    }

    @Test
    void verifyEmail_expiredToken_throwsTokenExpiredException() {
        String rawToken = UUID.randomUUID().toString();
        String hash = TokenHasher.sha256(rawToken);

        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().minusSeconds(1));
        token.setUser(buildUser(UserSlugConstants.STATUS_PENDING_VERIFICATION));

        when(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verifyEmail(rawToken))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void verifyEmail_unknownToken_throwsTokenExpiredException() {
        when(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail("unknown"))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void resendVerification_pendingUser_invalidatesOldTokenAndSendsNew() {
        User user = buildUser(UserSlugConstants.STATUS_PENDING_VERIFICATION);
        when(userRepository.findByEmailAndDeletedAtIsNull("u@x.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(any()))
                .thenReturn(List.of());

        service.resendVerification("u@x.com");

        verify(tokenRepository).invalidateActiveTokensForUser(any());
        verify(tokenRepository).save(any());
        verify(emailService).sendEmailVerification(anyString(), anyString(), any());
    }

    @Test
    void resendVerification_unknownEmail_silentlyReturns() {
        when(userRepository.findByEmailAndDeletedAtIsNull("missing@x.com")).thenReturn(Optional.empty());

        service.resendVerification("missing@x.com");

        verify(tokenRepository, never()).save(any());
    }

    @Test
    void resendVerification_alreadyVerifiedUser_silentlyReturns() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        when(userRepository.findByEmailAndDeletedAtIsNull("u@x.com")).thenReturn(Optional.of(user));

        service.resendVerification("u@x.com");

        verify(tokenRepository, never()).save(any());
    }

    private User buildUser(String statusSlug) {
        User user = new User();
        user.setEmail("u@x.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(statusSlug));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }
}