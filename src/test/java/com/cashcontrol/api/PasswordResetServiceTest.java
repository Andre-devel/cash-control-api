package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.PasswordResetToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.PasswordResetTokenRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.util.TokenHasher;
import com.cashcontrol.api.service.EmailService;
import com.cashcontrol.api.service.PasswordResetServiceImpl;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    @InjectMocks private PasswordResetServiceImpl service;

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AppProperties appProperties;
    @Mock private DataMasker dataMasker;

    @BeforeEach
    void setUp() {
        AppProperties.Security sec = new AppProperties.Security();
        when(appProperties.getSecurity()).thenReturn(sec);
        when(dataMasker.maskEmail(anyString())).thenReturn("m***@x.com");
        when(dataMasker.maskIp(any())).thenReturn("0.0.0.0");
    }

    @Test
    void initiateReset_existingActiveUser_sendsEmailAndSavesToken() {
        User user = buildUser(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        when(userRepository.findByEmailAndDeletedAtIsNull("u@x.com")).thenReturn(Optional.of(user));

        service.initiateReset("u@x.com");

        verify(tokenRepository).save(any());
        verify(emailService).sendPasswordResetEmail(anyString(), anyString(), any());
        verify(auditService).record(eq(AuditEventSlug.PASSWORD_RESET_REQUESTED), eq(AuditOutcomeSlug.SUCCESS),
                any(), any(), any());
    }

    @Test
    void initiateReset_nonExistingEmail_silentlyReturns() {
        when(userRepository.findByEmailAndDeletedAtIsNull("missing@x.com")).thenReturn(Optional.empty());

        service.initiateReset("missing@x.com");

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any());
    }

    @Test
    void completeReset_validToken_updatesPasswordAndCredentialsUpdatedAt() {
        String rawToken = UUID.randomUUID().toString();
        String hash = TokenHasher.sha256(rawToken);

        User user = buildUser(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));

        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.encode("New!Pass123")).thenReturn("$new$hash");

        service.completeReset(rawToken, "New!Pass123");

        verify(userRepository).save(user);
        verify(tokenRepository).save(token);
        verify(auditService).record(eq(AuditEventSlug.PASSWORD_RESET_COMPLETED), any(), any(), any());
        verify(auditService).record(eq(AuditEventSlug.CREDENTIALS_INVALIDATED), any(), any(), any(), any());
    }

    @Test
    void completeReset_expiredToken_throwsTokenExpiredException() {
        String rawToken = UUID.randomUUID().toString();
        String hash = TokenHasher.sha256(rawToken);

        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().minusSeconds(1)); // expired
        token.setUser(buildUser(new AccountStatus()));

        when(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.completeReset(rawToken, "New!Pass123"))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void completeReset_unknownToken_throwsTokenExpiredException() {
        String rawToken = UUID.randomUUID().toString();
        String hash = TokenHasher.sha256(rawToken);
        when(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeReset(rawToken, "New!Pass123"))
                .isInstanceOf(TokenExpiredException.class);
    }

    private User buildUser(AccountStatus status) {
        User user = new User();
        user.setEmail("u@x.com");
        user.setAccountStatus(status);
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }
}